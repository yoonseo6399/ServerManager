package local.servermanager

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

object CodePaths {
    private val blocked=setOf("build","node_modules","dist","config","security","deleted-instances")
    fun buildFile(name:String)=name.substringAfterLast('/') in setOf("build.gradle.kts","settings.gradle.kts","build.gradle","settings.gradle","gradle.properties","package.json","package-lock.json")
    fun safe(name:String):Boolean {
        val path=runCatching {Path.of(name)}.getOrNull() ?: return false
        return !path.isAbsolute && path.none {it.toString().startsWith('.') || it.toString() in blocked} && name.isNotBlank()
    }
    fun allowed(name:String)=safe(name) && (name.endsWith(".gradle") || name.substringAfterLast('.') in setOf("kt","java","ts","tsx","js","jsx","css","md","json","yaml","yml","toml","properties","html","txt","py","sh"))
    fun files(root:Path,snapshot:Boolean=false):List<String> {
        val result=mutableListOf<String>();var bytes=0L
        Files.walkFileTree(root,object:SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir:Path,attrs:BasicFileAttributes):FileVisitResult {
                if(dir!=root && !safe(root.relativize(dir).toString()))return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file:Path,attrs:BasicFileAttributes):FileVisitResult {
                val name=root.relativize(file).toString()
                val special=name=="gradlew" || name=="VERSION" || name.substringAfterLast('/')=="LICENSE" || name=="gradle/wrapper/gradle-wrapper.jar" || name=="src/main/resources/update-public-key.der"
                if(!Files.isSymbolicLink(file) && attrs.isRegularFile && (allowed(name) || (snapshot && special))) {
                    bytes+=attrs.size();require(bytes<=50_000_000 && result.size<5000) { "작업 스냅샷 범위를 초과했습니다." };result.add(name)
                }
                return FileVisitResult.CONTINUE
            }
        });return result.sorted()
    }
    fun resolve(root:Path,name:String):Path {
        require(allowed(name)) { "허용되지 않은 코드 경로입니다: $name" }
        val path=root.resolve(name).normalize();require(path.startsWith(root))
        var cursor=root
        Path.of(name).forEach {cursor=cursor.resolve(it);require(!Files.isSymbolicLink(cursor)) { "심볼릭 링크 경로는 사용할 수 없습니다." }}
        return path
    }
}

data class GitOutput(val code:Int,val output:String)
object AgentGit {
    fun run(root:Path,vararg args:String):GitOutput {
        val log=Files.createTempFile("agent-git-",".log")
        val builder=ProcessBuilder(listOf("git","-c","core.hooksPath=/dev/null","-c","user.name=Luonnotar","-c","user.email=agent@localhost")+args)
            .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
        builder.environment().apply {put("GIT_CONFIG_NOSYSTEM","1");put("GIT_CONFIG_GLOBAL","/dev/null");remove("GIT_DIR");remove("GIT_WORK_TREE");remove("GIT_INDEX_FILE")}
        var process:Process?=null
        try {
            process=builder.start();check(process.waitFor(30,TimeUnit.SECONDS)) { "Git 작업 시간 초과" }
            val bytes=Files.newInputStream(log).use {it.readNBytes(2_000_001)};check(bytes.size<=2_000_000) { "Git 출력 한도 초과" }
            return GitOutput(process.exitValue(),bytes.toString(Charsets.UTF_8))
        } finally {if(process?.isAlive==true)process.destroyForcibly();Files.deleteIfExists(log)}
    }
    fun checked(root:Path,vararg args:String)=run(root,*args).also {check(it.code==0) { "Git ${args.firstOrNull()}: ${it.output.takeLast(1200)}" }}.output.trim()
    fun commit(root:Path,message:String):String {
        checked(root,"add","--all");checked(root,"commit","--allow-empty","-m",message)
        return checked(root,"rev-parse","HEAD")
    }
}

/** Private repositories include uncommitted source changes but never the original .git or private data. */
class IsolatedAgentProject(val source:Path,val home:Path) {
    val integration=home.resolve("integration")
    private val baseline:Map<String,ByteArray>
    private val baseCommit:String
    private val merged=linkedSetOf<String>()
    init {
        require(!home.exists()) { "이미 존재하는 작업 폴더입니다." }
        integration.createDirectories()
        val files=CodePaths.files(source,true)
        baseline=files.associateWith {name ->
            val bytes=source.resolve(name).readBytes();val target=integration.resolve(name)
            target.parent.createDirectories();target.writeBytes(bytes);bytes
        }
        integration.resolve(".gitignore").writeText("build/\n.gradle/\n.gradle-user/\nnode_modules/\nfrontend/node_modules/\n")
        AgentGit.checked(integration,"init","-b","main");baseCommit=AgentGit.commit(integration,"Source snapshot")
    }
    @Synchronized fun branch(id:String):Path {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,60}")))
        checkpoint()
        val path=home.resolve(id)
        AgentGit.checked(home,"clone","--no-hardlinks","--",integration.toString(),path.toString())
        return path
    }
    @Synchronized fun checkpoint():String=AgentGit.commit(integration,"Coordinator checkpoint")
    @Synchronized fun merge(id:String,branch:Path):String {
        require(id !in merged) { "이미 병합한 작업입니다." }
        checkpoint();AgentGit.commit(branch,"Worker result")
        val ref="refs/agent-tasks/$id"
        AgentGit.checked(integration,"fetch","--no-tags","--",branch.toString(),"HEAD:$ref")
        val result=AgentGit.run(integration,"merge","--no-ff","--no-edit",ref)
        if(result.code!=0) {
            val conflicts=AgentGit.run(integration,"diff","--name-only","--diff-filter=U").output.trim()
            AgentGit.checked(integration,"merge","--abort")
            return "병합 충돌 · 원본과 통합 체크아웃은 보존됨\n$conflicts\n서브 작업 체크아웃: $branch"
        }
        merged.add(id);return "병합 완료: $id\n"+changes().joinToString("\n")
    }
    @Synchronized fun changes():List<String> {
        checkpoint()
        return AgentGit.checked(integration,"diff","--name-only",baseCommit,"HEAD").lineSequence().filter {it.isNotBlank()}.toList()
    }
    @Synchronized fun publish(allowed:Set<String>?=null):List<String> {
        val changes=changes()
        require(changes.all {CodePaths.allowed(it) && (allowed==null || it in allowed)}) { "병합 결과에 허용 범위 밖 파일이 있습니다." }
        synchronized(publishLocks.computeIfAbsent(source.toRealPath()){Any()}) {
            val originals=changes.associateWith {name ->
                val path=CodePaths.resolve(source,name);val current=if(path.exists())path.readBytes() else null
                val expected=baseline[name]
                require(if(expected==null)current==null else current!=null && current.contentEquals(expected)) { "원본이 작업 중 변경되었습니다: $name. 덮어쓰지 않습니다." }
                require(integration.resolve(name).isRegularFile()) { "파일 삭제는 아직 원본에 반영하지 않습니다." }
                current
            }
            val written=mutableMapOf<String,ByteArray>()
            try {
                changes.forEach {name ->
                    val path=CodePaths.resolve(source,name);val bytes=integration.resolve(name).readBytes()
                    path.parent.createDirectories()
                    val temp=Files.createTempFile(path.parent,".agent-publish-",".tmp")
                    try {temp.writeBytes(bytes);Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)} finally {Files.deleteIfExists(temp)}
                    written[name]=bytes
                }
            } catch(error:Exception) {
                written.forEach {(name,bytes) ->
                    val path=source.resolve(name)
                    if(path.exists() && path.readBytes().contentEquals(bytes))originals[name]?.let {path.writeBytes(it)} ?: Files.deleteIfExists(path)
                };throw error
            }
        }
        return changes
    }
    companion object {
        private val publishLocks=ConcurrentHashMap<Path,Any>()
        fun create(source:Path,runs:Path)=IsolatedAgentProject(source.toRealPath(),runs.resolve(UUID.randomUUID().toString()))
    }
}
