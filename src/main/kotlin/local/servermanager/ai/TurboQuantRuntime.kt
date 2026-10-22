package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** Opt-in pinned fork. Never patches Ollama, changes wired limits or attaches to another process. */
object TurboQuantRuntime {
    const val VERSION="tqp-v0.4.0"
    private const val HASH="f38d0c68e2db29b705ec3944360296c1f8003e4f920d60e6740f9968f3f93769"
    private const val SSL_HASH="82b33ce156b289eaf95657b9ec16e8f49cb49c882cdeaeaf57e3e7d67fa85e25"
    private val root=Path.of(System.getProperty("user.home"),".servermanager-llm","turboquant")
    private val release=root.resolve(VERSION)
    private val binary=release.resolve("turboquant-plus-$VERSION/llama-server")
    private val mapper=jacksonObjectMapper()
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    val endpoint:URI=URI("http://127.0.0.1:18081")
    @Volatile private var process:Process?=null
    private var lockChannel:java.nio.channels.FileChannel?=null
    private var lock:java.nio.channels.FileLock?=null
    @Volatile private var profile:Triple<Path,Int,String>?=null
    private var secret:String?=null
    @Volatile private var installing=false
    @Volatile private var message="27B TurboQuant는 실험 기능입니다. 설치는 모델을 로드하지 않습니다."
    data class Status(val supported:Boolean,val ready:Boolean,val installing:Boolean,val running:Boolean,val version:String,val context:Int?,val message:String)
    private fun supported()=System.getProperty("os.name").contains("Mac",true) && System.getProperty("os.arch") in setOf("arm64","aarch64") && System.getProperty("os.version").substringBefore('.').toIntOrNull()?.let {it>=15}==true
    private fun ssl():Path?=if(!release.exists())null else Files.walk(release).use {s->s.filter {it.fileName.toString()=="libssl.3.dylib" && Files.isRegularFile(it)}.findFirst().orElse(null)?.parent}
    fun status()=Status(supported(),supported() && binary.isExecutable() && release.resolve("verified").exists(),installing,process?.isAlive==true,VERSION,profile?.second,message)
    @Synchronized fun authorization():String="Bearer "+(secret ?: error("TurboQuant가 시작되지 않았습니다."))
    private fun privateDir(path:Path){path.createDirectories();Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"))}
    private fun acquire(){privateDir(root);val c=java.nio.channels.FileChannel.open(root.resolve("service.lock"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE);val l=runCatching {c.tryLock()}.getOrNull();if(l==null){c.close();error("다른 관리 앱/실행기가 TurboQuant를 사용 중입니다.")};lockChannel=c;lock=l}
    private fun releaseLock(){runCatching {lock?.release()};runCatching {lockChannel?.close()};lock=null;lockChannel=null}
    /** Fresh desktop authorization is required by the API; only fixed vendor URLs/hashes are accepted. */
    @Synchronized fun install():Status {
        check(supported()) {"TurboQuant 설치는 macOS15 이상 Apple Silicon에서 지원합니다."}
        check(!installing && process?.isAlive!=true) {"TurboQuant가 실행/설치 중입니다."}
        acquire();installing=true;message="TurboQuant와 격리 OpenSSL 준비 중 · 모델 로딩 없음"
        Thread({try {
            privateDir(release)
            download("https://github.com/TheTom/llama-cpp-turboquant/releases/download/$VERSION/turboquant-plus-$VERSION-macos-arm64-metal.tar.gz",HASH,release.resolve("engine.tar.gz"))
            extract(release.resolve("engine.tar.gz"),release)
            val auth=client.send(HttpRequest.newBuilder(URI("https://ghcr.io/token?service=ghcr.io&scope=repository:homebrew/core/openssl/3:pull")).timeout(Duration.ofSeconds(30)).GET().build(),HttpResponse.BodyHandlers.ofString())
            check(auth.statusCode()==200);val token=mapper.readTree(auth.body()).path("token").asText();check(token.isNotBlank())
            download("https://ghcr.io/v2/homebrew/core/openssl/3/blobs/sha256:$SSL_HASH",SSL_HASH,release.resolve("openssl.tar.gz"),"Bearer $token")
            val deps=release.resolve("openssl");privateDir(deps);extract(release.resolve("openssl.tar.gz"),deps)
            val libs=ssl() ?: error("OpenSSL 라이브러리를 찾지 못했습니다.")
            val help=release.resolve("support.log")
            val p=ProcessBuilder(binary.toString(),"--help").apply {environment()["DYLD_LIBRARY_PATH"]=libs.toString()}.redirectErrorStream(true).redirectOutput(help.toFile()).start()
            if(!p.waitFor(20,TimeUnit.SECONDS)){p.destroyForcibly();error("TurboQuant 지원 검사 시간 초과")}
            check(p.exitValue()==0 && help.readText().contains("turbo4")) {"TurboQuant 지원 검사 실패 · $help 확인"}
            release.resolve("verified").writeText("$VERSION\n$HASH\n$SSL_HASH\n")
            message="TurboQuant 준비됨 · Q8 K + Turbo4 V · 27B 64K 실모델 시험은 아직 미실행"
        }catch(e:Exception){Files.deleteIfExists(release.resolve("verified"));message="TurboQuant 설치 실패: "+e.message.orEmpty().take(300)}finally{synchronized(this){installing=false;releaseLock()}}},"turboquant-install").apply {isDaemon=true;start()}
        return status()
    }
    private fun download(url:String,hash:String,file:Path,authorization:String?=null) {
        val request=HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(5)).apply {authorization?.let {header("Authorization",it)}}.GET().build()
        val r=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build().send(request,HttpResponse.BodyHandlers.ofInputStream())
        r.body().use {input->check(r.statusCode()==200) {"고정 의존성 다운로드 HTTP ${r.statusCode()}"};Files.newOutputStream(file).use {out->val buffer=ByteArray(65536);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;check(total<=100_000_000);out.write(buffer,0,n)}}}
        val digest=MessageDigest.getInstance("SHA-256");Files.newInputStream(file).use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        check(digest.digest().joinToString(""){"%02x".format(it)}==hash) {"고정 의존성 SHA-256 불일치"}
    }
    private fun extract(archive:Path,destination:Path) {
        // Archives are hash-pinned vendor artifacts, never user/model-selected paths or URLs.
        val p=ProcessBuilder("/usr/bin/tar","-xzf",archive.toString(),"-C",destination.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if(!p.waitFor(60,TimeUnit.SECONDS)){p.destroyForcibly();error("TurboQuant 압축 해제 시간 초과")};check(p.exitValue()==0)
    }
    internal fun arguments(binary:Path,model:Path,window:Int,alias:String,keyFile:Path):List<String> {
        require(window in LocalContextBudget.WINDOWS)
        return listOf(binary.toString(),"--model",model.toString(),"--host","127.0.0.1","--port","18081","--parallel","1","--ctx-size",window.toString(),"--fit-ctx",window.toString(),"--gpu-layers","auto","--cache-type-k","q8_0","--cache-type-v","turbo4","--flash-attn","on","--jinja","--alias",alias,"--api-key-file",keyFile.toString(),"--cache-ram","0")
    }
    @Synchronized fun start(model:Path,window:Int,alias:String,allowCriticalMemory:Boolean=false):Int {
        check(status().ready && !installing) {"TurboQuant 엔진을 먼저 준비하세요."}
        val next=Triple(model,window,alias)
        if(process?.isAlive==true && profile==next)return window
        if(process!=null)stop()
        acquire()
        try {
            ServerSocket().use {it.bind(java.net.InetSocketAddress("127.0.0.1",18081))}
            val token=UUID.randomUUID().toString()+UUID.randomUUID();secret=token
            val key=root.resolve("api-key");Files.writeString(key,token);Files.setPosixFilePermissions(key,PosixFilePermissions.fromString("rw-------"))
            val log=root.resolve("server.log");if(log.exists() && log.fileSize()>10_000_000)Files.move(log,root.resolve("server.previous.log"),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val p=ProcessBuilder(arguments(binary,model,window,alias,key)).apply {environment()["DYLD_LIBRARY_PATH"]=ssl()?.toString() ?: error("격리 OpenSSL이 없습니다.")}.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start()
            process=p;message="TurboQuant ${window/1024}K 시작 중 · 모델 준비 대기"
            val guard=LlmMemoryGuard(allowCriticalMemory)
            repeat(240){
                guard.check()
                if(Thread.currentThread().isInterrupted)throw InterruptedException()
                check(p.isAlive) {"TurboQuant 시작 실패 · $log 확인"}
                val actual=runCatching {val r=client.send(HttpRequest.newBuilder(endpoint.resolve("/props")).timeout(Duration.ofSeconds(2)).header("Authorization","Bearer $token").GET().build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()==200)mapper.readTree(r.body()).path("default_generation_settings").path("n_ctx").asInt()else null}.getOrNull()
                if(actual!=null){check(actual==window) {"TurboQuant 실행 창 불일치: 요청 $window / 실제 $actual"};profile=next;message="TurboQuant · Q8 K + Turbo4 V · ${window/1024}K 실행 중(실험)";return actual}
                Thread.sleep(500)
            }
            error("TurboQuant 시작 시간 초과 · $log 확인")
        }catch(e:Exception){try{stop()}finally{if(process==null){secret=null;Files.deleteIfExists(root.resolve("api-key"));releaseLock()}};throw e}
    }
    @Synchronized fun stop() {
        val p=process ?: return
        val interrupted=Thread.interrupted()
        try {p.destroy();if(!p.waitFor(10,TimeUnit.SECONDS)){p.destroyForcibly();check(p.waitFor(5,TimeUnit.SECONDS)) {"TurboQuant 종료를 확인하지 못했습니다."}};process=null;profile=null;secret=null;Files.deleteIfExists(root.resolve("api-key"));releaseLock();message="TurboQuant 종료됨"}finally{if(interrupted)Thread.currentThread().interrupt()}
    }
    init {Runtime.getRuntime().addShutdownHook(Thread({runCatching {stop()}},"turboquant-stop"))}
}
