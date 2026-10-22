package local.servermanager

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Non-executing audit, NOT a shell interpreter. Everything outside this small literal grammar stays UNKNOWN. */
internal object PiBashAudit {
    data class Result(val scope:String,val readOnly:Boolean,val targets:List<String>,val fingerprints:List<String>,val reason:String,val requiresIntent:Boolean=false)
    fun inspect(home:Path,command:String):Result?=runCatching {
        val command=PiReadShell.normalize(command)
        val root=home.toRealPath();var cwd=root;var readOnly=true;var uncertain=false;var requiresIntent=false
        val proofPaths=linkedSetOf<Path>();val paths=linkedSetOf<Path>();val words=mutableListOf<String>();val commands=mutableListOf<List<String>>();val word=StringBuilder();var quoted:Char?=null;var has=false;var i=0
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
            "echo"->Unit
            "cd"->{require(args.size==1);cwd=path(args.single());require(Files.isDirectory(cwd))}
            "ls"->{val files=args.filterNot {it.matches(Regex("-[laAFh1d]+"))};require(files.none {it.startsWith('-')});if(files.isEmpty())paths.add(cwd)else files.forEach {path(it)}}
            "cat","wc"->{val flags=if(argv[0]=="cat")Regex("-[nbs]+")else Regex("-[lcwm]+")
                val files=args.filterNot {it.matches(flags)};require(files.isNotEmpty() && files.none {it.startsWith('-')});files.forEach {require(Files.isRegularFile(path(it)))}}
            "head","tail"->{var a=args;if(a.firstOrNull()=="-n"){require(a.size>=2 && a[1].toIntOrNull() in 0..4000);a=a.drop(2)}else if(a.firstOrNull()?.matches(Regex("-\\d{1,4}"))==true){require(a.first().drop(1).toInt()<=4000);a=a.drop(1)};require(a.none {it.startsWith('-')});a.forEach {require(Files.isRegularFile(path(it)))}}
            "rm"->{readOnly=false;var files=args;if(files.firstOrNull()=="-f")files=files.drop(1);if(files.firstOrNull()=="--")files=files.drop(1)
                require(files.size in 1..10 && files.none {it.startsWith('-')});files.forEach {val p=path(it);require(!Files.exists(p) || Files.isRegularFile(p))}}
            "git"->{
                var a=args;val repo=if(a.firstOrNull()=="-C"){require(a.size>=3);path(a[1]).also {a=a.drop(2)}}else path(".")
                require(repo.startsWith(root) && Files.isDirectory(repo))
                val allowed=setOf(listOf("status"),listOf("status","--short"),listOf("remote","-v"),listOf("diff"),listOf("diff","--stat"),listOf("log","--oneline","-5"),listOf("rev-parse","--short","HEAD"),listOf("fetch"),listOf("fetch","origin"),listOf("pull","--ff-only"))
                val log=a.firstOrNull()=="log" && a.drop(1).all {it.matches(Regex("-\\d{1,3}|--oneline|--date=(iso|short)|--format=[^\\r\\n]{1,200}"))}
                require(a in allowed || log || a==listOf("status","-sb") || a==listOf("fetch","--dry-run"))
                if(a.first() in setOf("pull","fetch")){if("--dry-run" !in a)readOnly=false;requiresIntent=true}
                // Linked worktrees, external configs/filters/hooks and unknown options stay UNKNOWN.
                var ancestor:Path?=repo;var discovered:Path?=null
                while(ancestor!=null) {
                    val candidate=ancestor.resolve(".git")
                    if(Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)){require(ancestor.startsWith(root));discovered=candidate;break}
                    proofPaths.add(candidate);ancestor=ancestor.parent
                }
                // With no repository at all, a read-only Git query can only fail; it cannot find an outside parent repo.
                if(discovered==null){require(a.first() !in setOf("pull","fetch"));continue}
                val git=discovered;require(Files.isDirectory(git,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(git))
                require(!Files.isSymbolicLink(git.resolve("config")))
                val config=path(git.resolve("config").toString());require(Files.isRegularFile(config) && Files.size(config)<=65536)
                require(safeGitConfig(Files.readString(config),a.first() in setOf("pull","fetch")))
                val hooks=path(git.resolve("hooks").toString())
                if(Files.exists(hooks))Files.list(hooks).use {s->require(s.allMatch {it.fileName.toString().endsWith(".sample") && Files.isRegularFile(it,LinkOption.NOFOLLOW_LINKS)})}
                for(extra in listOf("config.worktree","info/attributes","info/grafts","commondir","objects/info/alternates"))require(!Files.exists(git.resolve(extra)))
            }
            else->error("Unproven command, script, Git or external effect")
        }}
        val targets=paths.map {it.toString()}
        val fingerprint=(paths+proofPaths).map {p->var existing=p;while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.parent
            val a=Files.readAttributes(existing,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            val hash=if(a.isRegularFile && a.size()<=65536)java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(existing)).joinToString(""){"%02x".format(it)}else ""
            "$p|$existing|${a.fileKey()}|${a.lastModifiedTime()}|${a.size()}|$hash"}
        val scope=if(paths.any {!it.startsWith(root)})"OUTSIDE"else if(uncertain)"UNKNOWN"else "INSIDE"
        Result(scope,readOnly,targets,fingerprint,"전체 literal 명령 ${commands.size}개 · 경로 $scope; Git은 제한된 조회/fetch/ff-only pull 및 저장소 설정·hook 검사, 다른 스크립트/확장/Git 동작은 미검증",requiresIntent)
    }.getOrNull()

    private fun safeGitConfig(text:String,requiresRemote:Boolean):Boolean=runCatching {
        var section="";var remote=false;var remoteSection=""
        for(raw in text.lineSequence()) {
            val line=raw.trim();if(line.isEmpty() || line.startsWith('#') || line.startsWith(';'))continue
            if(line.startsWith('[')){require(line.matches(Regex("\\[(core|user|remote|branch|pull|merge)( \\\"[^\\\"\\\\]+\\\")?]",RegexOption.IGNORE_CASE)));section=line.substringAfter('[').substringBefore(' ').substringBefore(']').lowercase();remoteSection=line.substringAfter('"',"").substringBefore('"');continue}
            if(section=="remote")require(remoteSection=="origin")
            val key=line.substringBefore('=').trim().lowercase();val value=line.substringAfter('=',"").trim();require(!value.contains('\\'))
            when(section) {
                "core"->require(key in setOf("repositoryformatversion","filemode","bare","logallrefupdates","ignorecase","precomposeunicode","symlinks","quotepath","autocrlf","eol","safecrlf"))
                "user"->require(key in setOf("name","email"))
                "branch"->{require(key in setOf("remote","merge","rebase"));if(key=="remote")require(value=="origin")}
                "pull"->require(key in setOf("ff","rebase"))
                "merge"->require(key=="ff")
                "remote"->{require(key in setOf("url","fetch"));if(key=="url"){val u=java.net.URI(value);require(!u.host.isNullOrBlank() && u.userInfo==null && u.query==null && u.fragment==null && (u.scheme=="https" || u.scheme=="http" && u.host in setOf("127.0.0.1","localhost","::1")));remote=true}}
                else->error("Unknown Git config")
            }
        }
        require(!requiresRemote || remote);true
    }.getOrDefault(false)
}
