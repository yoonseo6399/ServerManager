package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** A pinned, local-only Strata installation. Only this process's own children may be stopped. */
object StrataRuntime {
    const val MODEL="qwen3.8-flash-next-q2_0"
    val root:Path=Path.of(System.getProperty("user.home"),".servermanager-llm","strata")
    private val control=root.resolve("strata-control-0.1.46.3-macos-arm64")
    private val config=root.resolve("flash-install/strata-q2_0.json")
    private val mapper=jacksonObjectMapper()
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
    val endpoint:URI=URI("http://127.0.0.1:18080")
    @Volatile private var process:Process?=null
    private var ownedChildren:List<ProcessHandle> = emptyList()
    private var runtimeLock:java.nio.channels.FileLock?=null
    private var lockChannel:java.nio.channels.FileChannel?=null
    @Volatile private var installing=false
    @Volatile private var message="Flash-Next는 Strata 설치 후 사용할 수 있습니다."
    data class Status(val supported:Boolean,val installed:Boolean,val running:Boolean,val state:String,val message:String,val context:Int?=null,val log:String?=null)
    fun supported()=System.getProperty("os.name").contains("Mac",true) && System.getProperty("os.arch") in setOf("aarch64","arm64")
    fun installed()=config.isRegularFile() && control.resolve("bin/strata").isExecutable()
    private fun health():com.fasterxml.jackson.databind.JsonNode?=runCatching {
        val r=client.send(HttpRequest.newBuilder(endpoint.resolve("/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString())
        if(r.statusCode()==200)mapper.readTree(r.body()).takeIf {it.path("loaded").asBoolean() && it.path("model").asText()==MODEL}else null
    }.getOrNull()
    fun status():Status {
        val alive=process?.isAlive==true;val h=if(alive)health()else null
        return Status(supported(),installed(),h!=null,when {installing->"INSTALLING";h!=null->"RUNNING";alive->"STARTING";installed()->"READY";else->"MISSING"},if(!supported())"Flash-Next 자동 실행은 Apple Silicon Mac에서 지원합니다."else if(installed() && message.startsWith("Flash-Next는 Strata"))"Strata 설치됨 · 첫 Flash-Next 요청에서 자동 시작합니다."else message,h?.path("max_context")?.asInt()?.takeIf {it>0},if(installing || message.contains("실패"))installLog()else null)
    }
    private fun installLog():String?=runCatching {java.io.RandomAccessFile(root.resolve("install.log").toFile(),"r").use {f->f.seek((f.length()-4096).coerceAtLeast(0));ByteArray((f.length()-f.filePointer).toInt()).also {f.readFully(it)}.toString(Charsets.UTF_8).replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"),"")}}.getOrNull()
    private fun acquireLock() {
        privateDir(root)
        val c=java.nio.channels.FileChannel.open(root.resolve("service.lock"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE)
        val lock=runCatching {c.tryLock()}.getOrNull()
        if(lock==null){c.close();error("다른 관리 앱 또는 실행기가 Strata를 사용 중입니다. 해당 컴퓨터의 추론 실행기를 지정하세요.")}
        lockChannel=c;runtimeLock=lock
    }
    private fun releaseLock(){runCatching {runtimeLock?.release()};runCatching {lockChannel?.close()};runtimeLock=null;lockChannel=null}
    private fun privateDir(path:Path){path.createDirectories();Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"))}
    private fun environment():Map<String,String> = mapOf(
        "STRATA_INSTALL_DIR" to root.resolve("flash-install").toString(),"STRATA_COMPONENTS_DIR" to root.resolve("flash-components").toString(),
        "STRATA_PROFILE_DIR" to root.resolve("profiles").toString(),"STRATA_STATE_DIR" to root.resolve("state").toString(),"STRATA_RUNTIME_DIR" to root.resolve("runtime").toString(),"XDG_DATA_HOME" to root.resolve("xdg").toString(),
        "STRATA_METAL_STREAM" to "1","STRATA_METAL_ARENA_GB" to "4","STRATA_METAL_PROMPT_GEMM" to "0","STRATA_METAL_PROMPT" to "window","COOP_K4" to "0","STRATA_ROUTE_TAIL_SKIP" to "0","STRATA_PREFILL_CPU_SHARE" to "0","PYTHONUNBUFFERED" to "1")
    private fun builder(args:List<String>)=ProcessBuilder(args).apply {environment().putAll(StrataRuntime.environment());redirectErrorStream(true)}
    @Synchronized fun install():Status {
        check(supported()) {"Strata 자동 설치는 Apple Silicon Mac에서만 지원합니다."}
        check(process?.isAlive!=true) {"Flash-Next를 먼저 메모리에서 내려주세요."}
        if(installing || installed())return status()
        acquireLock();installing=true;message="Strata 설치 시작 · 모델 약 66.4GB 다운로드 · 여유 공간 75GB 이상 필요"
        Thread({try {
            privateDir(root);check(Files.getFileStore(root).usableSpace>=75_000_000_000L) {"여유 공간이 75GB 미만입니다."}
            download("strata-control-0.1.46.3-macos-arm64","8040f585fd99178b6e0300f9daa4d5550cdb836be62dfa70bfa2c9343c7a9442")
            download("strata-engine-0.1.46-macos-arm64-metal","b9a86daba9b76a189594f987233b82572bff2ac2c6e33b6786600010bcbf8c43")
            privateDir(root.resolve("flash-components/engine"));val link=root.resolve("flash-components/engine/0.1.46-metal")
            if(!link.exists())Files.createSymbolicLink(link,root.resolve("strata-engine-0.1.46-macos-arm64-metal"))
            privateDir(root.resolve("runtime"));privateDir(root.resolve("flash-install"))
            message="Flash-Next 모델 다운로드·검증·준비 중 (약 66.4GB, 설치 로그에서 진행 확인)"
            val pb=builder(listOf(control.resolve("python/bin/python3").toString(),control.resolve("setup.py").toString(),"--yes","--no-start","--family","qwen","--model","Q2_0","--context","8192","--vision","no","--data-dir",root.resolve("flash-models").toString(),"--host","127.0.0.1","--port","18080","--no-browser"))
            pb.environment()["STRATA_CONTROL_INSTALL"]="1"
            val p=pb.redirectOutput(root.resolve("install.log").toFile()).start()
            check(p.waitFor(6,TimeUnit.HOURS).also {if(!it)p.destroyForcibly()} && p.exitValue()==0 && installed()) {"모델 준비 실패 · 실행 컴퓨터의 ${root.resolve("install.log")} 확인"}
            message="Strata 설치 완료 · 다음 Flash-Next 요청에서 자동 시작합니다."
        }catch(e:Exception){message=(e.message ?: "Strata 설치 실패").take(500)}finally{synchronized(this){installing=false;releaseLock()}}},"strata-install").apply {isDaemon=true;start()}
        return status()
    }
    private fun download(name:String,hash:String) {
        message="검증된 Strata 구성요소 다운로드 중: $name"
        val archive=root.resolve("$name.tar.gz")
        fun digest()=Files.newInputStream(archive).use {input->val md=MessageDigest.getInstance("SHA-256");val buf=ByteArray(1048576);while(true){val n=input.read(buf);if(n<0)break;md.update(buf,0,n)};md.digest().joinToString(""){"%02x".format(it)}}
        if(!archive.isRegularFile() || digest()!=hash){
            val r=client.send(HttpRequest.newBuilder(URI("https://github.com/Niko1221/Strata/releases/download/v0.2.1/$name.tar.gz")).timeout(Duration.ofMinutes(20)).GET().build(),HttpResponse.BodyHandlers.ofFile(archive,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE))
            check(r.statusCode()==200 && digest()==hash) {"Strata 구성요소 고정 SHA-256 검증 실패"}
        }
        val p=ProcessBuilder("/usr/bin/tar","-xzf",archive.toString(),"-C",root.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        check(p.waitFor(60,TimeUnit.SECONDS) && p.exitValue()==0) {"Strata 구성요소 압축 해제 실패"}
    }
    @Synchronized fun start():Int {
        check(supported()) {"Flash-Next 자동 실행은 Apple Silicon Mac에서 지원합니다."}
        check(!installing && installed()) {"관리웹의 Flash-Next 설치를 먼저 실행하세요."}
        if(process?.isAlive==true){val h=health();check(h!=null) {"Strata 시작 중입니다. 잠시 후 다시 요청하세요."};return h.path("max_context").asInt(8192)}
        if(process!=null)stop()
        acquireLock()
        try {
        // Never attach to or terminate another service already listening on this port.
        java.net.ServerSocket().use {it.bind(java.net.InetSocketAddress("127.0.0.1",18080))}
        privateDir(root.resolve("runtime"));val log=root.resolve("server.log");if(log.exists() && log.fileSize()>10_000_000)Files.move(log,root.resolve("server.previous.log"),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val p=builder(listOf(control.resolve("bin/strata").toString(),"serve","--config",config.toString(),"--host","127.0.0.1","--port","18080","--parallel","1","--offline","--ready-file",root.resolve("runtime/ready.json").toString(),"--shutdown-timeout","15","--output","jsonl")).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start()
        process=p;message="Flash-Next 시작 중"
        repeat(180){if(Thread.currentThread().isInterrupted){stop();throw InterruptedException()};check(p.isAlive) {"Strata 시작 실패 · ${log} 확인"};health()?.let {ownedChildren=p.descendants().toList();message="Flash-Next 실행 중 · SSD 오프로딩 · GPU 작업 공간 4GB";return it.path("max_context").asInt(8192)};Thread.sleep(500)}
        error("Strata 시작 시간 초과 · $log 확인")
        }catch(e:Exception){try{stop()}catch(cleanup:Exception){e.addSuppressed(cleanup)};if(process==null)releaseLock();throw e}
    }
    @Synchronized fun stop() {
        val p=process ?: return
        val interrupted=Thread.interrupted()
        try {
            val children=(ownedChildren+p.descendants().toList()).distinctBy {it.pid()}
            p.destroy();if(!p.waitFor(20,TimeUnit.SECONDS))p.destroyForcibly()
            children.asReversed().forEach {if(it.isAlive)it.destroy()}
            children.asReversed().forEach {child->if(child.isAlive)runCatching {child.onExit().get(5,TimeUnit.SECONDS)}.onFailure {_ ->child.destroyForcibly()}}
            if(p.isAlive)p.waitFor(5,TimeUnit.SECONDS)
            check(!p.isAlive && children.none {it.isAlive}) {"Strata 프로세스 종료를 확인하지 못했습니다. 모델 전환을 중단합니다."}
            process=null;ownedChildren=emptyList();releaseLock();message="Flash-Next를 메모리에서 내렸습니다."
        }finally {if(interrupted)Thread.currentThread().interrupt()}
    }

    fun memory():LlmMemory {
        val p=process?.takeIf {it.isAlive} ?: return LlmMemory.sample()
        val ids=(listOf(p.pid())+p.descendants().map {it.pid()}.toList()).toSet()
        val rss=LlmMemory.command("/bin/ps","-axo","pid=,rss=")?.lineSequence()?.mapNotNull {line->val f=line.trim().split(Regex("\\s+"));if(f.size==2 && f[0].toLongOrNull() in ids)f[1].toLongOrNull()else null}?.sum()?.times(1024)
        return LlmMemory.sample(rss,"RSS 합계·공유 중복 가능")
    }
    init {Runtime.getRuntime().addShutdownHook(Thread({runCatching {stop()}},"strata-stop"))}
}
