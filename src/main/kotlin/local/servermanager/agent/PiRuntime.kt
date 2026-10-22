package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.*
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.io.path.*

/** Pinned SDK + verified Node, installed automatically inside the private application profile. */
open class PiRuntime(private val directory:Path) {
    data class Installation(val node:Path,val bridge:Path)
    companion object {private val installLock=java.util.concurrent.locks.ReentrantLock();const val SDK="1.3.0"}
    protected fun resource(name:String)=checkNotNull(javaClass.getResourceAsStream("/pi/$name")) {"Pi 구성요소가 패키지에 없습니다: $name"}.use {it.readBytes()}
    internal fun environment(home:Path,node:Path):MutableMap<String,String> = mutableMapOf("HOME" to home.toString(),"USERPROFILE" to home.toString(),"TMPDIR" to home.resolve("tmp").toString(),"TEMP" to home.resolve("tmp").toString(),"TMP" to home.resolve("tmp").toString(),"PATH" to (node.parent.toString()+java.io.File.pathSeparator+listOf(Path.of(System.getProperty("java.home"),"bin").toString(),"/usr/bin","/bin","/usr/sbin","/sbin",System.getenv("SystemRoot")?.let {"$it\\System32"}).filterNotNull().joinToString(java.io.File.pathSeparator)),"JAVA_HOME" to System.getProperty("java.home"),"LANG" to "en_US.UTF-8","TERM" to "dumb","NO_COLOR" to "1","GIT_TERMINAL_PROMPT" to "0","GIT_PAGER" to "cat","GIT_CONFIG_NOSYSTEM" to "1","GIT_CONFIG_GLOBAL" to (if(System.getProperty("os.name").startsWith("Windows"))"NUL"else "/dev/null")).also {env->System.getenv("SystemRoot")?.let {env["SystemRoot"]=it};home.createDirectories();home.resolve("tmp").createDirectories()}
    fun status():Map<String,String> {
        val base=directory.resolve("pi-runtime")
        val ready=if(base.isDirectory())base.listDirectoryEntries().any {it.resolve("ready").isRegularFile()}else false
        return mapOf("engine" to "pi","version" to SDK,"state" to if(ready)"READY"else if(installLock.isLocked)"INSTALLING"else "NOT_INSTALLED")
    }
    open fun ensure(active:()->Unit,status:(String)->Unit):Installation {installLock.lockInterruptibly();try{return ensureLocked(active,status)}finally{installLock.unlock()}}
    private fun ensureLocked(active:()->Unit,status:(String)->Unit):Installation=run {
        active();val mapper=jacksonObjectMapper();val pin=mapper.readTree(resource("node.json"));val version=pin.path("version").asText()
        val os=System.getProperty("os.name").lowercase();val platform=when {os.contains("mac")->"darwin";os.contains("win")->"win";os.contains("linux")->"linux";else->error("Pi 관리 런타임을 지원하지 않는 운영체제입니다.")}
        val arch=when(System.getProperty("os.arch").lowercase()){ "aarch64","arm64"->"arm64";"amd64","x86_64"->"x64";else->error("Pi 런타임 아키텍처를 지원하지 않습니다.")}
        val archive="node-v$version-$platform-$arch"+(if(platform=="win")".zip"else ".tar.gz");val expected=pin.path("sha256").path(archive).asText();check(expected.matches(Regex("[a-f0-9]{64}"))) {"검증된 Pi 런타임이 없습니다: $platform/$arch"}
        val base=directory.resolve("pi-runtime").toAbsolutePath().normalize();base.createDirectories();runCatching {Files.setPosixFilePermissions(base,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))}
        val root=base.resolve("pi-$SDK-node-$version-$platform-$arch");val digest=MessageDigest.getInstance("SHA-256").digest(resource("package-lock.json")).joinToString(""){"%02x".format(it)}
        fun installed(path:Path)=Installation(path.resolve(archive.removeSuffix(".tar.gz").removeSuffix(".zip")).resolve(if(platform=="win")"node.exe"else "bin/node"),path.resolve("bridge.mjs"))
        if(!Files.isSymbolicLink(root) && root.resolve("node_modules/@earendil-works/pi-coding-agent/package.json").isRegularFile() && root.resolve("ready").exists() && root.resolve("ready").readText()==digest && installed(root).node.isRegularFile()) {val bridge=Files.createTempFile(root,"bridge-",".mjs");try {Files.write(bridge,resource("bridge.mjs"));Files.move(bridge,root.resolve("bridge.mjs"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally {bridge.deleteIfExists()};return@run installed(root)}
        if(root.exists()){check(!Files.isSymbolicLink(root));Files.move(root,base.resolve("incomplete-${java.util.UUID.randomUUID()}"),StandardCopyOption.ATOMIC_MOVE)}
        status("Pi $SDK · 검증된 Node $version 준비 중 (최초 실행 시 다운로드)")
        val stage=Files.createTempDirectory(base,"install-");val zip=stage.resolve(archive)
        try {
            val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
            val response=client.send(HttpRequest.newBuilder(URI("https://nodejs.org/dist/v$version/$archive")).timeout(Duration.ofMinutes(3)).GET().build(),HttpResponse.BodyHandlers.ofInputStream())
            response.body().use {input->check(response.statusCode()==200) {"Node 다운로드 HTTP ${response.statusCode()}"};Files.newOutputStream(zip).use {output->val buffer=ByteArray(65536);var size=0L;while(true){active();val n=input.read(buffer);if(n<0)break;size+=n;check(size<=100_000_000);output.write(buffer,0,n)}}}
            val actual=MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString(""){"%02x".format(it)};check(actual==expected) {"Pi Node 무결성 검사 실패"}
            if(platform=="win")ZipInputStream(zip.inputStream()).use {input->while(true){val e=input.nextEntry ?: break;val target=stage.resolve(e.name).normalize();check(target.startsWith(stage));if(e.isDirectory)target.createDirectories()else{target.parent.createDirectories();Files.newOutputStream(target).use {input.copyTo(it)}}}}
            else run(listOf("/usr/bin/tar","-xzf",zip.toString(),"-C",stage.toString()),stage,active,status)
            zip.deleteIfExists();listOf("package.json","package-lock.json","bridge.mjs").forEach {Files.write(stage.resolve(it),resource(it))}
            val installation=installed(stage);status("Pi $SDK 의존성 무결성 확인·설치 중")
            val npm=installation.node.parent.resolve(if(platform=="win")"node_modules/npm/bin/npm-cli.js"else "../lib/node_modules/npm/bin/npm-cli.js").normalize()
            run(listOf(installation.node.toString(),npm.toString(),"ci","--ignore-scripts","--omit=dev","--no-audit","--no-fund","--cache",base.resolve("npm-cache").toString()),stage,active,status)
            active();stage.resolve("ready").writeText(digest)
            check(!root.exists()) {"불완전한 Pi 설치가 남아 있습니다. 관리 앱 Pi 설치 로그를 확인하세요."}
            Files.move(stage,root,StandardCopyOption.ATOMIC_MOVE);status("Pi $SDK 준비 완료");installed(root)
        }finally {if(stage.exists()){runCatching {if(stage.resolve("setup.log").exists())Files.copy(stage.resolve("setup.log"),base.resolve("last-setup.log"),StandardCopyOption.REPLACE_EXISTING)};stage.toFile().deleteRecursively()}}
    }
    private fun run(argv:List<String>,cwd:Path,active:()->Unit,status:(String)->Unit) {
        val log=cwd.resolve("setup.log");val b=ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());b.environment().clear();b.environment().putAll(environment(cwd.resolve("setup-home"),Path.of(argv.first()).takeIf {it.fileName.toString().startsWith("node")} ?: Path.of("/usr/bin/node")))
        val process=b.start();val start=System.nanoTime();var tick=0
        try {while(!process.waitFor(250,TimeUnit.MILLISECONDS)){active();check(System.nanoTime()-start<TimeUnit.MINUTES.toNanos(5)) {"Pi 설치 시간 제한 초과"};if(++tick%16==0)status("Pi 구성요소 설치 진행 중 · ${TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)}초")};check(process.exitValue()==0) {"Pi 구성요소 설치 실패 (exit ${process.exitValue()}). 네트워크·디스크 공간을 확인하세요."}}finally {if(process.isAlive){process.descendants().forEach {it.destroyForcibly()};process.destroyForcibly()}}
    }
}
