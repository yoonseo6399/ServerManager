package local.servermanager

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Non-executing audit, NOT a shell interpreter. Everything outside this small literal grammar stays UNKNOWN. */
internal object PiBashAudit {
    data class Result(val scope:String,val readOnly:Boolean,val targets:List<String>,val fingerprints:List<String>,val reason:String)
    fun inspect(home:Path,command:String):Result?=runCatching {
        val root=home.toRealPath();var cwd=root;var readOnly=true;var uncertain=false
        val paths=linkedSetOf<Path>();val words=mutableListOf<String>();val commands=mutableListOf<List<String>>();val word=StringBuilder();var quoted:Char?=null;var has=false;var i=0
        require(command.length in 1..12000)
        fun endWord(){if(has){words.add(word.toString());word.setLength(0);has=false}}
        fun endCommand(){endWord();require(words.isNotEmpty());commands.add(words.toList());words.clear();require(commands.size<=12)}
        while(i<command.length){val c=command[i]
            // Reject expansion/redirection even inside quotes; this is deliberately narrower than Bash.
            require(c !in "\n\r\\$`<>|;(){}*?~#!" && c!='\u0000')
            when {quoted!=null->{if(c==quoted)quoted=null else word.append(c);has=true}
                c=='\'' || c=='"'->{quoted=c;has=true}
                c=='&'->{require(command.getOrNull(i+1)=='&');endCommand();i++}
                c==' ' || c=='\t'->endWord()
                else->{word.append(c);has=true}}
            i++
        }
        require(quoted==null);endCommand()
        fun path(raw:String):Path {
            require(raw.isNotBlank() && raw!='-'.toString())
            val p=cwd.resolve(raw).toAbsolutePath();if(p.any {it.toString()==".."})uncertain=true
            var ancestor=p;val tail=mutableListOf<String>()
            while(!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS)){tail.add(ancestor.fileName.toString());ancestor=ancestor.parent ?: error("No parent")}
            var walk=ancestor.root
            for(part in ancestor){walk=walk.resolve(part);if(Files.isSymbolicLink(walk))uncertain=true}
            val real=tail.asReversed().fold(ancestor.toRealPath()){a,b->a.resolve(b)}.normalize()
            if(real.any {ProjectAgentShell.isSecret(it.toString()) || it.toString() in setOf(".release-keys","private.der")})uncertain=true
            if(Files.isRegularFile(ancestor) && runCatching {(Files.getAttribute(ancestor,"unix:nlink") as Number).toInt()>1}.getOrDefault(false))uncertain=true
            if(Files.getFileStore(ancestor)!=Files.getFileStore(root))uncertain=true
            paths.add(real);return real
        }
        for(argv in commands){val args=argv.drop(1);when(argv[0]){
            "pwd"->{require(args.isEmpty());paths.add(cwd)}
            "echo"->{require(args.firstOrNull()?.startsWith('-')!=true)}
            "cd"->{require(args.size==1);cwd=path(args.single());require(Files.isDirectory(cwd))}
            "ls"->{val files=args.filterNot {it.matches(Regex("-[laAFh1d]+"))};require(files.none {it.startsWith('-')});if(files.isEmpty())paths.add(cwd)else files.forEach {path(it)}}
            "cat","wc"->{val flags=if(argv[0]=="cat")Regex("-[nbs]+")else Regex("-[lcwm]+")
                val files=args.filterNot {it.matches(flags)};require(files.isNotEmpty() && files.none {it.startsWith('-')});files.forEach {require(Files.isRegularFile(path(it)))}}
            "head","tail"->{var a=args;if(a.firstOrNull()=="-n"){require(a.size>=3 && a[1].toIntOrNull() in 0..4000);a=a.drop(2)};require(a.isNotEmpty() && a.none {it.startsWith('-')});a.forEach {require(Files.isRegularFile(path(it)))}}
            "rm"->{readOnly=false;var files=args;if(files.firstOrNull()=="-f")files=files.drop(1);if(files.firstOrNull()=="--")files=files.drop(1)
                require(files.size in 1..10 && files.none {it.startsWith('-')});files.forEach {val p=path(it);require(!Files.exists(p) || Files.isRegularFile(p))}}
            else->error("Unproven command, script, Git or external effect")
        }}
        val targets=paths.map {it.toString()}
        val fingerprint=paths.map {p->var existing=p;while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.parent
            val a=Files.readAttributes(existing,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS);"$p|$existing|${a.fileKey()}|${a.lastModifiedTime()}|${a.size()}"}
        val scope=if(paths.any {!it.startsWith(root)})"OUTSIDE"else if(uncertain)"UNKNOWN"else "INSIDE"
        Result(scope,readOnly,targets,fingerprint,"전체 literal 명령 ${commands.size}개 · 경로 $scope; 복합 스크립트/확장/Git 동작은 자동 허용하지 않음")
    }.getOrNull()
}
