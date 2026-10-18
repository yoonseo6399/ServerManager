package local.servermanager

import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.io.path.*

/**
 * Read-only command line for the model. No OS shell is involved: the line is parsed here, every command is implemented
 * in-process (git excepted, with a flag allowlist) and every path is resolved inside the project home.
 * Supported operators: `&&` `||` `;` `|`. Redirection, substitution, variables and background jobs are rejected.
 */
class ProjectAgentShell(home:Path) {
    data class Result(val output:String,val status:Int)
    private data class Word(val text:String,val glob:Boolean=false)
    private data class Step(val op:String,val pipeline:List<List<Word>>)
    private class Out(val text:String,val status:Int)
    private class Bounded(private val source:CharSequence,private val deadline:Long):CharSequence {
        override val length get()=source.length
        override fun get(index:Int):Char {if(System.nanoTime()>deadline)throw IllegalStateException("검색 시간 제한을 넘었습니다. 더 단순한 패턴을 쓰세요.");return source[index]}
        override fun subSequence(startIndex:Int,endIndex:Int):CharSequence=Bounded(source.subSequence(startIndex,endIndex),deadline)
        override fun toString()=source.toString()
    }

    private val root=home.toRealPath()
    private var deadline=0L

    companion object {
        val names=setOf("pwd","ls","cat","head","tail","wc","grep","rg","find","sed","sort","uniq","echo","git")
        const val LIMIT=48000
        private const val SECONDS=20L
        private val noise=setOf(".git","node_modules","build",".gradle",".idea","target","dist","__pycache__",".next",".venv")
        private val secret=Regex("(?i)(\\.env(\\..*)?|.*\\.(pem|key|p12|pfx|jks|keystore|kdbx)|id_(rsa|ed25519|ecdsa|dsa).*|.*credential.*|.*secret.*|\\.netrc|\\.npmrc|token\\.key)")
        fun isSecret(name:String)=secret.matches(name)
        private val gitCommands=setOf("status","diff","log","show","branch","ls-files","rev-parse","shortlog")
        private val gitFlag=Regex("--?(n|N|p|s|b|a|r|w|v|l)|-\\d+|-U\\d+|-[SG][^\\n]{1,100}|--(oneline|stat|shortstat|numstat|name-only|name-status|cached|staged|patch|graph|decorate|short|porcelain|no-merges|merges|all|follow|no-color|abbrev-commit|ignore-all-space|ignore-space-change|summary|first-parent|reverse|date-order|show-current|list|branches|untracked-files(=\\w+)?|max-count=\\d+|since=[\\w .:-]+|until=[\\w .:-]+|after=[\\w .:-]+|before=[\\w .:-]+|author=[^\\n]{1,100}|grep=[^\\n]{1,100}|format=[^\\n]{1,200}|pretty=[\\w:%-]{1,200}|diff-filter=[A-Za-z]+|abbrev=\\d+|unified=\\d+|date=[\\w:-]+)")
        private val escapes=Regex("(^|/)\\.\\.(/|$)")
    }

    // ---- Parsing ------------------------------------------------------------------------------------------------
    private fun parse(line:String):List<Step> {
        require(line.isNotBlank() && line.length<=4000 && !line.contains('\u0000')) {"명령은 1~4000자의 한 줄이어야 합니다."}
        val steps=mutableListOf<Step>();var pipeline=mutableListOf<List<Word>>();var words=mutableListOf<Word>()
        val sb=StringBuilder();var has=false;var glob=false;var op=";";var commands=0
        fun endWord() {if(has){words.add(Word(sb.toString(),glob));sb.setLength(0);has=false;glob=false}}
        fun endCommand() {endWord();require(words.isNotEmpty()) {"연산자 앞뒤에 명령이 있어야 합니다."};pipeline.add(words.toList());words=mutableListOf();require(++commands<=12) {"한 줄에 명령은 12개까지입니다."}}
        fun endPipeline(next:String) {endCommand();steps.add(Step(op,pipeline.toList()));pipeline=mutableListOf();op=next}
        var i=0
        while(i<line.length) {
            val c=line[i]
            when {
                c=='\'' -> {val end=line.indexOf('\'',i+1);require(end>0) {"작은따옴표가 닫히지 않았습니다."};sb.append(line,i+1,end);has=true;i=end+1}
                c=='"' -> {
                    var j=i+1
                    while(true) {
                        require(j<line.length) {"큰따옴표가 닫히지 않았습니다."}
                        val d=line[j]
                        if(d=='"')break
                        if(d=='\\' && j+1<line.length && line[j+1] in "\"\\") {sb.append(line[j+1]);j+=2;continue}
                        require(d!='`' && !(d=='$' && j+1<line.length && (line[j+1].isLetter() || line[j+1] in "_({"))) {"변수·명령 치환은 지원하지 않습니다. 패턴은 작은따옴표 '...'로 감싸세요."}
                        sb.append(d);j++
                    }
                    has=true;i=j+1
                }
                c=='\\' -> {require(i+1<line.length) {"끝에 남은 역슬래시가 있습니다."};sb.append(line[i+1]);has=true;i+=2}
                c==' ' || c=='\t' || c=='\r' -> {endWord();i++}
                c=='&' -> {require(line.getOrNull(i+1)=='&') {"백그라운드 &는 지원하지 않습니다. && 를 사용하세요."};endPipeline("&&");i+=2}
                c=='|' -> if(line.getOrNull(i+1)=='|'){endPipeline("||");i+=2} else {endCommand();i++}
                c==';' || c=='\n' -> {if(words.isEmpty() && pipeline.isEmpty() && has.not())i++ else {endPipeline(";");i++}}
                c in "<>`$(){}" -> throw IllegalArgumentException("'$c' 는 지원하지 않습니다. 리다이렉션·치환·변수·서브셸은 쓸 수 없습니다. 수정은 edit_file을 사용하세요.")
                c=='*' || c=='?' -> {sb.append(c);has=true;glob=true;i++}
                else -> {sb.append(c);has=true;i++}
            }
        }
        endWord()
        if(words.isNotEmpty() || pipeline.isNotEmpty())endPipeline(";")
        require(steps.isNotEmpty()) {"실행할 명령이 없습니다."}
        require(op==";") {"연산자 뒤에 명령이 없습니다."}
        return steps
    }
    private fun argv(command:List<Word>):List<String> = command.take(1).map {it.text}+command.drop(1).flatMap {expand(it)}
    private fun expand(word:Word):List<String> {
        if(!word.glob)return listOf(word.text)
        val absolute=word.text.startsWith("/")
        var current=listOf(if(absolute)root.root else root)
        for(part in word.text.split('/').filter {it.isNotEmpty()}) {
            val next=mutableListOf<Path>()
            for(base in current) {
                if(part.any {it=='*' || it=='?'}) {
                    val matcher=FileSystems.getDefault().getPathMatcher("glob:$part")
                    runCatching {Files.newDirectoryStream(base).use {entries->for(entry in entries)if(!entry.fileName.toString().startsWith(".") && matcher.matches(entry.fileName))next.add(entry)}}
                } else {val entry=base.resolve(part);if(Files.exists(entry,LinkOption.NOFOLLOW_LINKS))next.add(entry)}
            }
            current=next.sortedBy {it.toString()}.take(200)
        }
        return current.map {if(absolute)it.toString() else display(it)}.ifEmpty {listOf(word.text)}
    }

    /** Syntax and policy check without executing, so an invalid line is refused before any approval step. */
    fun validate(line:String) {
        parse(line).forEach {step->step.pipeline.forEach {command->
            val name=command.first().text
            require(name in names) {"$name: 지원하지 않는 명령입니다. 사용 가능: ${names.sorted().joinToString()}"}
            if(name=="git")gitArgv(command.drop(1).map {it.text})
        }}
    }
    /** Runs one already-split command (no line parsing), for backend-built calls such as the search tool. */
    fun runArgv(argv:List<String>,active:()->Unit={}):Result {
        require(argv.isNotEmpty() && argv.size<=64 && argv[0] in names) {"지원하지 않는 명령입니다."}
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(SECONDS)
        val out=exec(argv,null,active)
        return Result(cap(out.text.trimEnd('\n')),out.status)
    }
    fun run(line:String,active:()->Unit={}):Result {
        val steps=parse(line);deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(SECONDS)
        val out=StringBuilder();var status=0
        for(step in steps) {
            if(step.op=="&&" && status!=0)continue
            if(step.op=="||" && status==0)continue
            active();check(System.nanoTime()<deadline) {"명령 실행 시간 제한 ${SECONDS}초를 넘었습니다."}
            var stdin:String?=null;var last=Out("",0)
            for(command in step.pipeline) {
                last=exec(argv(command),stdin,active);stdin=last.text
                if(last.status==2 && command!==step.pipeline.last())break
            }
            status=last.status
            if(last.text.isNotEmpty())out.append(last.text.removeSuffix("\n")).append('\n')
            if(out.length>LIMIT*2)break
        }
        return Result(cap(out.toString().trimEnd('\n')),status)
    }

    // ---- Path handling ------------------------------------------------------------------------------------------
    private fun display(path:Path)=root.relativize(path).toString().replace('\\','/').ifEmpty {"."}
    private fun cap(text:String)=if(text.length<=LIMIT)text else text.take(LIMIT)+"\n[출력 일부 생략: 전체 ${text.length}자. grep -n, head, sed -n 'A,Bp'로 범위를 좁히세요]"
    private fun jail(arg:String):Path {
        require(arg.length<=1000 && !arg.contains('\u0000')) {"경로가 올바르지 않습니다."}
        val given=Path.of(arg.ifBlank {"."});val absolute=(if(given.isAbsolute)given else root.resolve(given)).normalize()
        require(Files.exists(absolute,LinkOption.NOFOLLOW_LINKS)) {"$arg: 없는 경로입니다."}
        val real=absolute.toRealPath()
        require(real.startsWith(root)) {"$arg: 프로젝트 폴더 밖의 경로입니다."}
        return real
    }
    private fun binary(bytes:ByteArray)=bytes.take(4096).any {it==0.toByte()}
    private fun readFile(arg:String):String {
        val path=jail(arg);require(path.isRegularFile()) {"$arg: 일반 파일이 아닙니다."}
        require(!isSecret(path.fileName.toString())) {"$arg: 비밀 값이 있을 수 있는 파일이라 읽지 않습니다."}
        require(path.fileSize()<=1_048_576) {"$arg: 1MB를 넘습니다. grep -n 또는 sed -n 'A,Bp'를 쓰세요."}
        val bytes=path.readBytes();require(!binary(bytes)) {"$arg: 바이너리 파일입니다."}
        return String(bytes,Charsets.UTF_8)
    }
    private fun lines(text:String)=if(text.isEmpty())emptyList() else text.removeSuffix("\n").split('\n')
    private fun number(value:String):Int = value.toIntOrNull()?.takeIf {it>=0} ?: throw IllegalArgumentException("$value: 숫자가 아닙니다.")
    /** Input text for commands that read a file or, without file operands, the previous pipeline stage. */
    private fun inputs(files:List<String>,stdin:String?):List<Pair<String?,String>> =
        if(files.isEmpty()) listOf(null to (stdin ?: throw IllegalArgumentException("파일 인자가 필요합니다."))) else files.map {it to readFile(it)}

    private fun exec(argv:List<String>,stdin:String?,active:()->Unit):Out {
        val name=argv[0];val args=argv.drop(1)
        return try {
            when(name) {
                "pwd" -> Out(root.toString(),0)
                "echo" -> Out(args.joinToString(" "),0)
                "ls" -> ls(args)
                "cat" -> cat(args,stdin)
                "head","tail" -> headTail(name=="head",args,stdin)
                "wc" -> wc(args,stdin)
                "grep" -> grep(args,stdin,active)
                "rg" -> rg(args,stdin,active)
                "find" -> find(args,active)
                "sed" -> sed(args,stdin)
                "sort" -> sort(args,stdin)
                "uniq" -> uniq(args,stdin)
                "git" -> git(args,active)
                else -> throw IllegalArgumentException("지원하지 않는 명령입니다. 사용 가능: ${names.sorted().joinToString()}")
            }
        } catch(e:IllegalArgumentException) {Out("$name: ${e.message}",2)}
    }

    // ---- Commands -----------------------------------------------------------------------------------------------
    private fun ls(args:List<String>):Out {
        val flags=args.filter {it.startsWith("-")}.joinToString("");val paths=args.filter {!it.startsWith("-")}.ifEmpty {listOf(".")}
        require(flags.none {it=='R'}) {"-R은 지원하지 않습니다. find를 사용하세요."}
        val long='l' in flags;val all='a' in flags;val out=StringBuilder()
        for(arg in paths) {
            val path=jail(arg)
            if(path.isDirectory()) {
                if(paths.size>1)out.append(arg).append(":\n")
                Files.list(path).use {entries->entries.sorted().filter {all || !it.fileName.toString().startsWith(".")}.limit(500).forEach {entry->
                    val directory=entry.isDirectory(LinkOption.NOFOLLOW_LINKS)
                    out.append(if(long)"${if(directory)"-" else entry.fileSize()}\t" else "").append(entry.fileName).append(if(directory)"/" else "").append('\n')
                }}
            } else out.append(display(path)).append('\n')
        }
        return Out(out.toString(),0)
    }
    private fun cat(args:List<String>,stdin:String?):Out {
        val numbered="-n" in args;val files=args.filter {it!="-n"}
        require(files.none {it.startsWith("-") && it!="-"}) {"-n 외의 옵션은 지원하지 않습니다."}
        val all=inputs(files.filter {it!="-"},stdin);val out=StringBuilder()
        for((name,text) in all) {
            if(all.size>1)out.append("==> ").append(name ?: "stdin").append(" <==\n")
            if(numbered)lines(text).forEachIndexed {i,line->out.append("${i+1}\t").append(line).append('\n')} else out.append(text).append(if(text.endsWith("\n")) "" else "\n")
        }
        return Out(out.toString(),0)
    }
    private fun headTail(head:Boolean,args:List<String>,stdin:String?):Out {
        var count=10;val files=mutableListOf<String>();var i=0
        while(i<args.size) {
            val a=args[i]
            when {
                a=="-n" -> count=number(args.getOrNull(++i) ?: throw IllegalArgumentException("-n 값이 필요합니다."))
                a.startsWith("-n") -> count=number(a.substring(2))
                a.matches(Regex("-\\d+")) -> count=number(a.substring(1))
                a.startsWith("-") && a!="-" -> throw IllegalArgumentException("$a: 지원하지 않는 옵션입니다.")
                else -> files.add(a)
            }
            i++
        }
        val all=inputs(files,stdin);val out=StringBuilder()
        for((name,text) in all) {
            if(all.size>1)out.append("==> ").append(name ?: "stdin").append(" <==\n")
            val rows=lines(text);(if(head)rows.take(count) else rows.takeLast(count)).forEach {out.append(it).append('\n')}
        }
        return Out(out.toString(),0)
    }
    private fun wc(args:List<String>,stdin:String?):Out {
        val flags=args.filter {it.startsWith("-")}.joinToString("") {it.drop(1)};val files=args.filter {!it.startsWith("-")}
        require(flags.all {it in "lwc"}) {"-l, -w, -c 만 지원합니다."}
        val out=StringBuilder()
        for((name,text) in inputs(files,stdin)) {
            val values=listOf('l' to lines(text).size,'w' to text.split(Regex("\\s+")).count {it.isNotEmpty()},'c' to text.toByteArray().size)
            val shown=if(flags.isEmpty())values else values.filter {it.first in flags}
            out.append(shown.joinToString(" ") {it.second.toString()}).append(if(name!=null)" $name" else "").append('\n')
        }
        return Out(out.toString(),0)
    }
    private fun sed(args:List<String>,stdin:String?):Out {
        val rest=args.filter {it!="-n"}
        require("-n" in args && rest.isNotEmpty()) {"지원 형식: sed -n 'A,Bp' 파일 (A,B는 줄 번호 또는 \$). 수정은 edit_file을 사용하세요."}
        val m=Regex("^(\\d+|\\$)(?:,(\\d+|\\$))?p$").matchEntire(rest[0]) ?: throw IllegalArgumentException("지원 형식: sed -n 'A,Bp' 파일 (A,B는 줄 번호 또는 \$). 수정은 edit_file을 사용하세요.")
        val out=StringBuilder()
        for((_,text) in inputs(rest.drop(1),stdin)) {
            val rows=lines(text)
            fun at(value:String)=if(value=="$")rows.size else value.toInt()
            val from=at(m.groupValues[1]);val to=if(m.groupValues[2].isEmpty())from else at(m.groupValues[2])
            require(from>=1) {"줄 번호는 1부터입니다."}
            rows.drop(from-1).take(maxOf(0,to-from+1)).forEach {out.append(it).append('\n')}
        }
        return Out(out.toString(),0)
    }
    private fun sort(args:List<String>,stdin:String?):Out {
        val flags=args.filter {it.startsWith("-")}.joinToString("") {it.drop(1)};val files=args.filter {!it.startsWith("-")}
        require(flags.all {it in "rnu"}) {"-r, -n, -u 만 지원합니다."}
        var rows=inputs(files,stdin).flatMap {lines(it.second)}
        rows=if('n' in flags)rows.sortedBy {it.trim().takeWhile {c->c.isDigit() || c=='-' || c=='.'}.toDoubleOrNull() ?: 0.0} else rows.sorted()
        if('r' in flags)rows=rows.reversed();if('u' in flags)rows=rows.distinct()
        return Out(rows.joinToString("\n",postfix="\n"),0)
    }
    private fun uniq(args:List<String>,stdin:String?):Out {
        require(args.all {it=="-c" || !it.startsWith("-")}) {"-c 만 지원합니다."}
        val rows=inputs(args.filter {!it.startsWith("-")},stdin).flatMap {lines(it.second)};val out=StringBuilder();var i=0
        while(i<rows.size) {var j=i;while(j<rows.size && rows[j]==rows[i])j++;out.append(if("-c" in args)"${j-i} " else "").append(rows[i]).append('\n');i=j}
        return Out(out.toString(),0)
    }

    private fun walk(start:Path,include:List<PathMatcher>,active:()->Unit,visit:(Path)->Boolean) {
        var scanned=0
        Files.walkFileTree(start,object:SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir:Path,attrs:BasicFileAttributes)=if(dir!=start && dir.fileName.toString() in noise)FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            override fun visitFile(file:Path,attrs:BasicFileAttributes):FileVisitResult {
                active();if(++scanned>10000)return FileVisitResult.TERMINATE
                if(!attrs.isRegularFile || attrs.isSymbolicLink)return FileVisitResult.CONTINUE
                if(include.isNotEmpty() && include.none {it.matches(file.fileName)})return FileVisitResult.CONTINUE
                return if(visit(file))FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
            }
            override fun visitFileFailed(file:Path,exc:IOException)=FileVisitResult.CONTINUE
        })
    }
    /**
     * ripgrep-style search translated onto the in-process grep: recursive and numbered by default, `-g GLOB`, `-t TYPE`,
     * `-e`, `-C/-A/-B`, `-m`, `-i -l -c -v -F -w` and `--files`. Nothing is executed outside this class; ignore files,
     * hidden-file and negated-glob switches are refused instead of being half supported.
     */
    private fun rg(args:List<String>,stdin:String?,active:()->Unit):Out {
        val types=mapOf("kotlin" to "*.kt","kt" to "*.kt","kts" to "*.kts","java" to "*.java","ts" to "*.ts","tsx" to "*.tsx","js" to "*.js","py" to "*.py","md" to "*.md",
            "txt" to "*.txt","text" to "*.txt","json" to "*.json","css" to "*.css","rust" to "*.rs","go" to "*.go","yaml" to "*.yml","html" to "*.html","sh" to "*.sh")
        val flags=mutableListOf<String>();val globs=mutableListOf<String>();val explicit=mutableListOf<String>();val positional=mutableListOf<String>()
        var files=false;var i=0
        fun value(name:String)=args.getOrNull(++i) ?: throw IllegalArgumentException("$name 값이 필요합니다.")
        while(i<args.size) {
            val a=args[i]
            when {
                a=="--files" -> files=true
                a in setOf("-i","-l","-c","-v","-F","-w","-s") -> if(a!="-s")flags.add(a)
                a in setOf("-n","--line-number","--no-heading","--color=never","--with-filename","-H") -> {}
                a=="-g" || a=="--glob" -> globs.add(value(a).also {require(!it.startsWith("!")) {"제외 glob(!)은 지원하지 않습니다."}})
                a.startsWith("--glob=") -> globs.add(a.substringAfter('=').also {require(!it.startsWith("!")) {"제외 glob(!)은 지원하지 않습니다."}})
                a=="-t" || a=="--type" -> globs.add(types[value(a)] ?: throw IllegalArgumentException("지원하는 파일 종류: ${types.keys.sorted().joinToString()}"))
                a=="-e" || a=="--regexp" -> explicit.add(value(a))
                a=="-A" || a=="-B" || a=="-C" -> {flags.add(a);flags.add(number(value(a)).coerceAtMost(10).toString())}
                a=="-m" || a=="--max-count" -> {flags.add("-m");flags.add(number(value(a)).toString())}
                a=="--" -> {positional.addAll(args.drop(i+1));i=args.size}
                a.startsWith("-") && a!="-" -> throw IllegalArgumentException("$a: 지원하지 않는 옵션입니다. 지원: --files -i -l -c -v -F -w -g -t -e -A -B -C -m")
                else -> positional.add(a)
            }
            i++
        }
        if(files) {
            val findArgs=mutableListOf<String>()
            findArgs.addAll(positional.ifEmpty {listOf(".")});findArgs.addAll(listOf("-type","f"))
            require(globs.size<=1) {"--files에는 glob을 하나만 쓸 수 있습니다."}
            globs.firstOrNull()?.let {findArgs.addAll(listOf("-name",it))}
            return find(findArgs,active)
        }
        val patterns=explicit.toMutableList();val paths=positional.toMutableList()
        if(patterns.isEmpty()) {require(paths.isNotEmpty()) {"검색 패턴이 필요합니다."};patterns.add(paths.removeAt(0))}
        val argv=mutableListOf<String>()
        val readsStdin=paths.isEmpty() && stdin!=null
        argv.add(if(readsStdin)"-n" else "-rn");argv.addAll(flags)
        globs.forEach {argv.add("--include=$it")}
        patterns.forEach {argv.add("-e");argv.add(it)}
        if(!readsStdin)argv.addAll(paths.ifEmpty {listOf(".")}) else argv.addAll(paths)
        return grep(argv,stdin,active)
    }
    private fun grep(args:List<String>,stdin:String?,active:()->Unit):Out {
        var recursive=false;var numbered=false;var ignore=false;var invert=false;var count=false;var listOnly=false;var fixed=false;var word=false;var noName=false;var forceName=false
        var after=0;var before=0;var max=Int.MAX_VALUE;val patterns=mutableListOf<String>();val positional=mutableListOf<String>();val include=mutableListOf<PathMatcher>()
        var i=0;var endOptions=false
        while(i<args.size) {
            val a=args[i]
            if(endOptions || !a.startsWith("-") || a=="-") {positional.add(a);i++;continue}
            if(a=="--") {endOptions=true;i++;continue}
            if(a.startsWith("--")) {
                when {
                    a=="--recursive" -> recursive=true
                    a=="--line-number" -> numbered=true
                    a=="--ignore-case" -> ignore=true
                    a=="--invert-match" -> invert=true
                    a=="--count" -> count=true
                    a=="--files-with-matches" -> listOnly=true
                    a=="--fixed-strings" -> fixed=true
                    a=="--word-regexp" -> word=true
                    a=="--no-filename" -> noName=true
                    a.startsWith("--include=") -> include.add(FileSystems.getDefault().getPathMatcher("glob:"+a.substringAfter('=')))
                    a.startsWith("--max-count=") -> max=number(a.substringAfter('='))
                    a.startsWith("--regexp=") -> patterns.add(a.substringAfter('='))
                    else -> throw IllegalArgumentException("$a: 지원하지 않는 옵션입니다.")
                }
                i++;continue
            }
            var j=1
            while(j<a.length) {
                val flag=a[j]
                when(flag) {
                    'r','R' -> recursive=true;'n' -> numbered=true;'i' -> ignore=true;'v' -> invert=true;'c' -> count=true;'l' -> listOnly=true
                    'F' -> fixed=true;'E','I','s' -> {};'w' -> word=true;'h' -> noName=true;'H' -> forceName=true
                    'e','m','A','B','C' -> {
                        val value=if(j+1<a.length)a.substring(j+1) else (args.getOrNull(++i) ?: throw IllegalArgumentException("-$flag 값이 필요합니다."))
                        when(flag) {'e' -> patterns.add(value);'m' -> max=number(value);'A' -> after=number(value);'B' -> before=number(value);else -> {after=number(value);before=number(value)}}
                        j=a.length
                    }
                    else -> throw IllegalArgumentException("-$flag: 지원하지 않는 옵션입니다.")
                }
                j++
            }
            i++
        }
        if(patterns.isEmpty())patterns.add(positional.removeFirstOrNull() ?: throw IllegalArgumentException("패턴이 필요합니다."))
        val body=patterns.joinToString("|") {if(fixed)Pattern.quote(it) else it}
        val regex=try {Pattern.compile(if(word)"\\b(?:$body)\\b" else body,if(ignore)Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)} catch(e:PatternSyntaxException) {throw IllegalArgumentException("정규식 오류: ${e.description}. 글자 그대로 찾으려면 -F를 쓰세요.")}
        data class Source(val name:String?,val text:String)
        val sources=mutableListOf<Source>();var skipped=0
        if(positional.isEmpty()) {
            if(stdin!=null)sources.add(Source(null,stdin)) else {require(recursive) {"파일 또는 -r 이 필요합니다."};positional.add(".")}
        }
        var directories=false
        for(arg in positional) {
            val path=jail(arg)
            if(path.isDirectory()) {
                require(recursive) {"$arg: 디렉터리입니다. -r 을 사용하세요."};directories=true
                walk(path,include,active) {file->
                    if(isSecret(file.fileName.toString()) || file.fileSize()>1_048_576) {skipped++;return@walk true}
                    val bytes=file.readBytes();if(binary(bytes)){skipped++;return@walk true}
                    sources.add(Source(display(file),String(bytes,Charsets.UTF_8)));sources.size<10000
                }
            } else sources.add(Source(display(path),readFile(arg)))
        }
        val showName=!noName && (forceName || directories || sources.size>1)
        val out=StringBuilder();var matched=false
        for(source in sources) {
            active();val rows=lines(source.text);val hits=ArrayList<Int>()
            for(index in rows.indices) {if(hits.size>=max)break;if(regex.matcher(Bounded(rows[index],deadline)).find()!=invert)hits.add(index)}
            if(hits.isEmpty())continue
            matched=true
            val prefix=if(showName)"${source.name ?: "(stdin)"}" else ""
            if(listOnly) {out.append(source.name ?: "(stdin)").append('\n');continue}
            if(count) {out.append(if(showName)"$prefix:" else "").append(hits.size).append('\n');continue}
            val wanted=sortedSetOf<Int>();val isHit=hits.toHashSet()
            for(h in hits)for(k in maxOf(0,h-before)..minOf(rows.size-1,h+after))wanted.add(k)
            var previous=-2
            for(k in wanted) {
                if((after>0 || before>0) && previous>=0 && k>previous+1)out.append("--\n")
                val sep=if(k in isHit)':' else '-'
                out.append(if(showName)"$prefix$sep" else "").append(if(numbered)"${k+1}$sep" else "").append(rows[k].take(500)).append('\n')
                previous=k
            }
            if(out.length>LIMIT){out.append("[결과 일부 생략: 출력 한도]\n");break}
        }
        if(skipped>0)out.append("[건너뜀: 비밀·바이너리·1MB 초과 파일 ${skipped}개]\n")
        return Out(out.toString(),if(matched)0 else 1)
    }
    private fun find(args:List<String>,active:()->Unit):Out {
        val paths=args.takeWhile {!it.startsWith("-")}.ifEmpty {listOf(".")};var name:PathMatcher?=null;var type:String?=null;var depth=Int.MAX_VALUE;var i=paths.size
        while(i<args.size) {
            when(args[i]) {
                "-name","-iname" -> {name=FileSystems.getDefault().getPathMatcher("glob:"+(args.getOrNull(i+1) ?: throw IllegalArgumentException("${args[i]} 값이 필요합니다.")).let {if(args[i]=="-iname")it.lowercase() else it});i++}
                "-type" -> {type=args.getOrNull(i+1);require(type=="f" || type=="d") {"-type 은 f 또는 d 입니다."};i++}
                "-maxdepth" -> {depth=number(args.getOrNull(i+1) ?: throw IllegalArgumentException("-maxdepth 값이 필요합니다."));i++}
                else -> throw IllegalArgumentException("${args[i]}: 지원하지 않는 옵션입니다. 지원: -name -iname -type -maxdepth")
            }
            i++
        }
        val insensitive="-iname" in args;val out=StringBuilder();var hits=0
        for(arg in paths) {
            val start=jail(arg)
            Files.walkFileTree(start,emptySet(),minOf(depth,40),object:SimpleFileVisitor<Path>() {
                private fun accept(path:Path,directory:Boolean):FileVisitResult {
                    active()
                    if((path!=start || !directory) && (type==null || (type=="d")==directory)) {
                        val file=path.fileName.toString()
                        if((name==null || name!!.matches(Path.of(if(insensitive)file.lowercase() else file))) && hits<500){out.append(display(path)).append(if(directory)"/" else "").append('\n');hits++}
                    }
                    return if(hits>=500)FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
                override fun preVisitDirectory(dir:Path,attrs:BasicFileAttributes):FileVisitResult {
                    if(dir!=start && dir.fileName.toString() in noise)return FileVisitResult.SKIP_SUBTREE
                    val result=accept(dir,true);return if(result==FileVisitResult.CONTINUE)FileVisitResult.CONTINUE else result
                }
                override fun visitFile(file:Path,attrs:BasicFileAttributes)=if(attrs.isRegularFile && !attrs.isSymbolicLink)accept(file,false) else FileVisitResult.CONTINUE
                override fun visitFileFailed(file:Path,exc:IOException)=FileVisitResult.CONTINUE
            })
        }
        if(hits>=500)out.append("[결과 500개에서 중단했습니다. -name 이나 경로로 좁히세요]\n")
        return Out(out.toString(),0)
    }
    private fun gitArgv(args:List<String>):List<String> {
        val sub=args.firstOrNull() ?: throw IllegalArgumentException("git 하위 명령이 필요합니다. 지원: ${gitCommands.sorted().joinToString()}")
        require(sub in gitCommands) {"git $sub: 지원하지 않습니다. 지원: ${gitCommands.sorted().joinToString()}"}
        val rest=args.drop(1);var afterDashes=false;val positional=mutableListOf<String>();var list=false
        for(a in rest) {
            if(!afterDashes && a=="--"){afterDashes=true;continue}
            if(!afterDashes && a.startsWith("-")) {
                require(gitFlag.matches(a)) {"git $sub: 옵션 $a 는 허용되지 않습니다."}
                if(a=="--list" || a=="-l")list=true
            } else {
                require(!a.startsWith("/") && !a.startsWith("~") && !a.startsWith(":") && !escapes.containsMatchIn(a.replace('\\','/'))) {"git: 프로젝트 밖을 가리키는 인자는 쓸 수 없습니다."}
                val objectPath=a.substringAfter(':',a)
                require(objectPath.split('/','\\').none {isSecret(it)}) {"git: 비밀 파일의 작업 트리·이력·객체 내용은 읽을 수 없습니다."}
                positional.add(a)
            }
        }
        require(sub!="branch" || positional.isEmpty() || list) {"git branch 는 목록 조회만 가능합니다. 브랜치를 만들거나 바꿀 수 없습니다."}
        val objectRead=sub=="show" && positional.any {':' in it}
        if(objectRead)require(positional.size==1) {"git show 객체 조회는 검증 가능한 경로 하나만 지정하세요."}
        if(sub=="show" && !objectRead) {
            // A raw blob hash has no auditable filename. Only commit objects may produce filtered diffs.
            positional.takeWhile {it !in rest.dropWhile {v->v!="--"}.drop(1)}.forEach {revision->
                val check=ProcessBuilder("git","rev-parse","--verify","$revision^{commit}").directory(root.toFile()).redirectErrorStream(true).start()
                try {require(check.waitFor(2,TimeUnit.SECONDS) && check.exitValue()==0) {"git show: 파일 경로 없는 blob/tree 객체는 읽지 않습니다. REF:일반파일 경로를 쓰세요."}}
                finally {check.destroyForcibly()}
            }
        }
        val excluded=if(sub in setOf("show","diff","log") && !objectRead) {
            val patterns=listOf(".env*","*.pem","*.key","*.p12","*.pfx","*.jks","*.keystore","*.kdbx","id_rsa*","id_ed25519*","id_ecdsa*","id_dsa*","*credential*","*secret*",".netrc",".npmrc")
            (if("--" in rest)emptyList()else listOf("--","."))+patterns.map {":(glob,exclude,icase)**/$it"}
        }else emptyList()
        val guard=if(sub in setOf("diff","show","log"))listOf("--no-ext-diff","--no-textconv") else emptyList()
        return listOf("git","--no-pager","--no-optional-locks","-c","core.fsmonitor=false","-c","core.pager=cat","-c","diff.external=",sub)+guard+rest+excluded
    }
    private fun git(args:List<String>,active:()->Unit):Out {
        val command=gitArgv(args)
        val builder=ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
        builder.environment().keys.removeIf {it.startsWith("GIT_") && it !in setOf("GIT_SSL_CAINFO")}
        builder.environment()["GIT_TERMINAL_PROMPT"]="0";builder.environment()["GIT_PAGER"]="cat";builder.environment()["GIT_OPTIONAL_LOCKS"]="0"
        val process=try {builder.start()} catch(e:IOException) {return Out("git을 실행하지 못했습니다.",2)}
        val capture=java.util.concurrent.CompletableFuture.supplyAsync {process.inputStream.use {it.readNBytes(LIMIT*4+1)}}
        try {
            while(process.isAlive) {active();check(System.nanoTime()<deadline) {"명령 실행 시간 제한 ${SECONDS}초를 넘었습니다."};process.waitFor(100,TimeUnit.MILLISECONDS)}
            return Out(String(capture.get(2,TimeUnit.SECONDS),Charsets.UTF_8),if(process.exitValue()==0)0 else 1)
        } finally {process.descendants().forEach {it.destroyForcibly()};process.destroyForcibly();capture.cancel(true)}
    }
}
