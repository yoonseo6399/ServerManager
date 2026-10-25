package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.net.Socket
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*

data class RunnerIdentity(val managerUrl:String,val id:String,val name:String,val privateKey:String,val platform:String)

object RunnerMain {
    private val mapper=jacksonObjectMapper()
    private val home=Path.of(System.getenv("SERVER_MANAGER_RUNNER_HOME") ?: Path.of(System.getProperty("user.home"),".servermanager-runner").toString()).toAbsolutePath().normalize()
    private val identityFile=home.resolve("identity.json")
    private fun privateFile(path:Path,bytes:ByteArray) {
        path.parent.createDirectories()
        val temp=Files.createTempFile(path.parent,".runner-",".tmp")
        try {
            temp.writeBytes(bytes)
            if(!System.getProperty("os.name").startsWith("Windows")) Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"))
            Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { temp.deleteIfExists() }
    }
    fun run(args:Array<String>) {
        // Background log files and first-run prompts must not inherit an ASCII
        // process locale and replace Korean text with question marks.
        System.setOut(PrintStream(System.out,true,StandardCharsets.UTF_8))
        System.setErr(PrintStream(System.err,true,StandardCharsets.UTF_8))
        when {
            "--runner-pair" in args -> pair(args)
            args.any {it.startsWith("--runner-relay-consent=")} -> relayConsent(args.first {it.startsWith("--runner-relay-consent=")}.substringAfter('='))
            "--runner-status" in args -> {
                val identity=if(identityFile.exists()) mapper.readValue<RunnerIdentity>(identityFile.readText()) else null
                println(identity?.let { "${it.name} (${it.id}) · ${it.managerUrl}" } ?: "등록되지 않은 실행기")
            }
            else -> {
                println("Server Manager 실행기 대기 시작")
                if(!identityFile.exists()) println("이 컴퓨터는 아직 등록되지 않았습니다. 관리 웹의 ‘서버 실행기’에서 패스키로 코드를 발급하고 pair.command / pair.cmd로 등록하세요.")
                // A control layer that cannot talk to a host owning a live game must not report healthy; the supervisor
                // then restores the previous version instead of leaving the game without a controller.
                val preflight=HostLifecycle.preflight(home)
                if(!preflight.ok) {
                    UpdateCoordinator(home).update(UpdateComponents.RUNNER_CONTROL,RunnerAgent.version(),null,"FAILED",error=preflight.detail ?: preflight.reason)
                    System.err.println("상주 게임 서비스 연결 확인 실패: ${preflight.reason}");kotlin.system.exitProcess(3)
                }
                fun ready(){System.getenv("SERVER_MANAGER_RUNNER_READY_FILE")?.let { Path.of(it).writeText("ready") }}
                var agent:RunnerAgent?=null
                var currentId:String?=null
                Runtime.getRuntime().addShutdownHook(Thread { agent?.shutdownGracefully() })
                while(true) {
                    val identity=if(identityFile.exists()) runCatching { mapper.readValue<RunnerIdentity>(identityFile.readText()) }.getOrNull() else null
                    if(identity==null) { ready();Thread.sleep(10000);continue }
                    if(currentId!=identity.id) {
                        val next=RunnerAgent(home,identity)
                        try {next.initializeController(::ready)} catch(e:Exception) {System.err.println("상주 서비스 초기화 실패: ${e.javaClass.simpleName}");kotlin.system.exitProcess(3)}
                        agent=next;currentId=identity.id
                    }
                    try { agent!!.connectLoop() }
                    catch(e:Exception) { System.err.println("중앙 서버 연결 대기: ${e.javaClass.simpleName}");Thread.sleep(5000) }
                    if(agent!!.restartForUpdate()) kotlin.system.exitProcess(75)
                }
            }
        }
    }
    /** Local, file-free way to grant or withdraw "management web owner may use this computer as a TCP relay". A running runner applies it within ~10 seconds. */
    private fun relayConsent(action:String) {
        val store=RelayConsentStore(home)
        val state=when(action) {
            "allow" -> store.set(true,"cli")
            "revoke" -> store.set(false,"cli-revoke")
            "status" -> store.read()
            else -> error("allow, revoke, status 중 하나를 지정하세요")
        }
        println("관리 웹 소유자의 게임 중계 사용 허용 (TCP / Sable UDP): "+(if(state.allowed)"허용" else "허용 안 함")+" (출처: "+state.source+(state.migratedFrom?.let {", 이전된 기존 설정: $it"} ?: "")+(if(state.environmentLocked)", 환경변수로 고정" else "")+")")
    }
    private fun pair(args:Array<String>) {
        val url=args.firstOrNull { it.startsWith("--manager-url=") }?.substringAfter('=')?.trimEnd('/') ?: error("--manager-url=https://관리주소 를 지정하세요")
        val uri=URI(url)
        require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path.isNullOrEmpty()) { "HTTPS 관리 주소를 입력하세요" }
        require(!identityFile.exists()) { "이미 등록된 실행기입니다. 등록을 폐기한 뒤 다시 등록하세요" }
        val console=System.console()
        if(console==null) { print("실행기 등록 코드: ");System.out.flush() }
        val code=console?.readPassword("실행기 등록 코드: ")?.concatToString()
            ?: System.`in`.bufferedReader().readLine()?.trim()
            ?: error("등록 코드를 입력하세요")
        require(code.length in 30..100)
        val pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey=Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
        val platform=if(System.getProperty("os.name").startsWith("Windows")) "WINDOWS" else "MAC"
        val version=RunnerAgent.version()
        val body=mapper.writeValueAsBytes(RunnerPairRequest(code,publicKey,platform,version))
        val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
        val response=client.send(HttpRequest.newBuilder(URI("$url/api/runners/pair")).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode()==200) { "실행기 등록 실패: HTTP ${response.statusCode()}" }
        val result=mapper.readValue<Map<String,String>>(response.body())
        val identity=RunnerIdentity(url,result.getValue("id"),result.getValue("name"),Base64.getUrlEncoder().withoutPadding().encodeToString(pair.private.encoded),platform)
        privateFile(identityFile,mapper.writeValueAsBytes(identity))
        println("실행기 등록 완료: ${identity.name}. --runner 로 연결을 시작하세요.")
    }
}

class RunnerAgent(private val home:Path,private val identity:RunnerIdentity,updateService:RunnerUpdater?=null,private val supervisedRestart:Boolean=System.getenv("RUNNER_BOOTSTRAP_PROTOCOL")?.toIntOrNull()?.let {it>=1}==true):WebSocket.Listener {
    private val serviceBootId=UUID.randomUUID().toString()
    internal var restartProcess:()->Unit={kotlin.system.exitProcess(75)}
    private val mapper=jacksonObjectMapper()
    private val client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build()
    private val workers=Executors.newCachedThreadPool()
    @Volatile private var updatePauseRequested=false
    @Volatile private var pauseAfterDownload=false
    private val preparing=ConcurrentHashMap.newKeySet<String>()
    private val lifecycle=Any()
    private val updater=updateService ?: if(System.getenv("RUNNER_PACKAGE_HOME")!=null) RunnerUpdater(home,version()) {state->
        if(state.phase=="READY" && pauseAfterDownload)updatePauseRequested=true
        send(RunnerMessage("update-status",detail=mapper.writeValueAsString(state)))
        // Mirror into the component status; the current waitReason (if any) is kept by the coordinator on WAITING.
        val stage=when(state.phase){"CHECKING"->"CHECKING";"DOWNLOADING"->"DOWNLOADING";"VERIFYING"->"VERIFYING";"READY","WAITING","PAUSING"->"WAITING";"FAILED"->"FAILED";"CURRENT","IDLE"->"IDLE";else->null}
        if(stage!=null){
            val previous=coordinator.current(UpdateComponents.RUNNER_CONTROL)
            val reason=if(stage=="WAITING")(previous?.waitReason ?: if(state.phase=="READY")"APPLY_WHEN_SAFE" else "CONDITIONS")else null
            coordinator.update(UpdateComponents.RUNNER_CONTROL,version(),state.latest,stage,waitReason=reason,error=if(stage=="FAILED")"업데이트 확인·다운로드·검증 실패 (상세는 실행기 로그)" else null)
            reportUpdateDetail()
        }
    } else null
    @Volatile private var lastUpdateCheck=0L
    private val connection=AtomicReference<WebSocket?>()
    @Volatile private var closed=CountDownLatch(1)
    private val textBuffer=StringBuilder()
    private val binaryBuffer=ByteArrayOutputStream()
    private val sendLock=Any()
    private val tcpRelayAllowed=System.getenv("RUNNER_TCP_RELAY_ENABLED")=="true" || home.resolve("tcp-relay-enabled").let {it.isRegularFile() && it.readText().trim()=="true"}
    private val llmAllowed=System.getenv("RUNNER_LLM_ENABLED")=="true" || home.resolve("llm-enabled").let {it.isRegularFile() && it.readText().trim()=="true"}
    private val llmBusy=java.util.concurrent.atomic.AtomicBoolean()
    private val llmTasks=ConcurrentHashMap<String,java.util.concurrent.FutureTask<Unit>>()
    private fun cancelLlmTasks(){llmTasks.values.forEach {it.cancel(true)};llmTasks.clear()}
    private fun submitLlm(message:RunnerMessage){
        val key=message.token ?: return
        val task=java.util.concurrent.FutureTask<Unit> {llmChat(key,message.state.orEmpty(),message.command.orEmpty(),message.detail=="THINK");Unit}
        if(llmTasks.putIfAbsent(key,task)!=null)return
        workers.submit {try{task.run()}finally{llmTasks.remove(key,task)}}
    }
    @Volatile private var tcpRelay:TcpRelayPeer?=null
    @Volatile private var sessionToken:String?=null
    /** Direct relay (DRP1) survives manager disconnects; only local consent withdrawal or process exit tears it down. */
    @Volatile private var directSable=false
    private val directRelay=DirectRelayRunnerRole(home,::send,{id->configs[id]?.takeIf {processes[id]?.isAlive==true && id in ready && id !in stopping}?.port},{id->processes[id]?.isAlive==true && id in ready && id !in stopping})
    private val processes=ConcurrentHashMap<String,Process>()
    private val gameStreams=ConcurrentHashMap<Int,Socket>()
    private val configs=ConcurrentHashMap<String,Instance>()
    private val uploading=ConcurrentHashMap.newKeySet<String>()
    private val operationLocks=ConcurrentHashMap<String,Any>()
    private val stopMonitors=ConcurrentHashMap<String,StopMonitor>()
    private val stopTimeouts=ConcurrentHashMap.newKeySet<String>()
    private val idleStops=ConcurrentHashMap.newKeySet<String>()
    private val stopping=ConcurrentHashMap.newKeySet<String>()
    private val ready=ConcurrentHashMap.newKeySet<String>()
    private val activity=ConcurrentHashMap<String,AtomicLong>()
    private val query=MinecraftQuery()
    private val queryPorts=ConcurrentHashMap<String,Int>()
    private val sableRegistrations=ConcurrentHashMap<String,CompletableFuture<Boolean>>()
    private val sable by lazy { SableRunner(client,identity.managerUrl,{udpHeaders()},
        {port->gameStreams.entries.firstOrNull {it.value.localPort==port && !it.value.isClosed}?.let {entry->streamInstances[entry.key]?.let {it to entry.key}}},
        {stream->streamInstances[stream]?.let {id->configs[id]?.takeIf {processes[id]?.isAlive==true && id in ready && id !in stopping}?.port}},
        {id,stream,token->
            val result=CompletableFuture<Boolean>();sableRegistrations[token]=result
            try {send(RunnerMessage("sable-register",id,streamId=stream,token=token)) && result.get(1200,TimeUnit.MILLISECONDS)}catch(_:Exception){false}finally{sableRegistrations.remove(token)}
        }) }
    private val streamInstances=ConcurrentHashMap<Int,String>()
    private fun udpHeaders():Map<String,String> {
        val timestamp=System.currentTimeMillis();val nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also {SecureRandom().nextBytes(it)})
        val bytes="sm-runner-udp-v1\n${identity.id}\n$timestamp\n$nonce\n".toByteArray()
        val signature=Signature.getInstance("Ed25519").apply {initSign(signingKey());update(bytes)}.sign()
        return mapOf("X-SM-Runner-Id" to identity.id,"X-SM-Timestamp" to timestamp.toString(),"X-SM-Nonce" to nonce,"X-SM-Signature" to Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
    }

    private val metrics=ProcessMetrics()
    private val folders=RunnerFolders(home)
    private fun instanceRoot(id:String)=home.resolve("instances").resolve(id)
    private fun diagnosticRoot(id:String)=configs[id]?.diagnosticSession?.let {sid->
        require(UUID.fromString(sid).toString()==sid);instanceRoot(id).resolve("diagnostics").resolve(sid)
    }
    private val workspaces=ConcurrentHashMap<String,RunnerWorkspace>()
    private val installationProcesses=ConcurrentHashMap<String,Process>()
    private fun serverDir(id:String)=diagnosticRoot(id)?.resolve("server") ?: folders.directory(id)
    private fun dirty(id:String)=instanceRoot(id).resolve("dirty")
    private fun orphanAlive(id:String):Boolean {
        val marker=dirty(id)
        if(!marker.exists() || processes[id]?.isAlive==true)return false
        val parts=runCatching { marker.readText().trim().split('|') }.getOrNull() ?: return true
        if(parts.size!=2)return true // Older markers have no PID; never assume the world is idle.
        val pid=parts[0].toLongOrNull() ?: return true
        val started=parts[1].toLongOrNull() ?: return true
        val process=ProcessHandle.of(pid).orElse(null) ?: return false
        return process.isAlive && process.info().startInstant().orElse(null)?.toEpochMilli()==started
    }
    private fun javaBin()=Path.of(System.getProperty("java.home"),"bin",if(identity.platform=="WINDOWS") "java.exe" else "java").toString()
    private fun signingKey()=KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(identity.privateKey)))
    // ---- Resident game host -------------------------------------------------------------------------------------
    @Volatile private var hosted:HostedGames?=null
    private var lastHostedRelayReport=System.nanoTime()-java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
    private val hostMode=HostModeStore(home)
    @Volatile var hostModeState=HostModeState()
    private val hostInit=java.util.concurrent.atomic.AtomicBoolean()
    private fun hostedAlive(id:String)=processes[id].let {it is HostedProcess && it.isAlive}
    /** Decides once per control-layer start whether games run through the resident host, then re-attaches survivors. */
    private fun initHost() {
        if(!hostInit.compareAndSet(false,true))return
        val jar=currentJarPath()
        if(jar==null){hostModeState=HostModeState("LEGACY","설치된 JAR가 아닌 개발·테스트 실행");return}
        val windows=identity.platform=="WINDOWS"
        val candidate=HostedGames(home,"runner-${RunnerAgent.version()}",{GameHostMain.spawnDetached(home,javaBin(),jar,windows)},retryMillis=15000)
        // A game from the previous layout that is still alive is neither adopted nor killed; the split waits for its normal stop.
        val owned=if(GameHostClient.readEndpoint(home)!=null)candidate.adoptable().toSet() else emptySet()
        val root=home.resolve("instances")
        val legacy=if(root.exists())root.listDirectoryEntries().filter {it.isDirectory() && it.name !in owned && orphanAlive(it.name)}.map {it.name} else emptyList()
        hostModeState=hostMode.evaluate(legacy)
        if(hostModeState.mode!="SERVICE"){candidate.close();return}
        if(candidate.connection()==null){check(GameHostClient.readEndpoint(home)?.let {ProcessHandle.of(it.pid).map {p->p.isAlive}.orElse(false)}!=true) {"기존 상주 서비스와 통신하지 못해 제어 계층 시작을 중단합니다."};hostModeState=hostMode.fallback("상주 서비스를 시작하지 못해 이전 방식으로 실행합니다: ${candidate.lastError}");return}
        hosted=candidate
        candidate.onRelay {body->runCatching {send(mapper.readValue<RunnerMessage>(body))}}
        owned.forEach {id->
            val config=configs[id] ?: runCatching {mapper.readValue<Instance>(instanceRoot(id).resolve("instance.json").readText())}.getOrNull() ?: return@forEach
            configs[id]=config
            val process=candidate.adopt(id,ConsolePosition.load(instanceRoot(id).resolve("console.seq"))) ?: return@forEach
            processes[id]=process
            // The query port was written to server.properties by the start that launched this process.
            runCatching {Properties().also {p->serverDir(id).resolve("server.properties").inputStream().use(p::load)}.getProperty("query.port")?.toInt()}.getOrNull()?.let {queryPorts[id]=it}
            activity[id]=AtomicLong()
            // `ready` is restored by the replayed "Done" line in watch(); an already-running server is reported as RUNNING at once.
            val wasReady=(process as? HostedProcess)?.readyAtAttach==true
            if(wasReady)ready.add(id)
            send(RunnerMessage("state",id,if(wasReady)"RUNNING" else "STARTING",version(),"제어 계층 업데이트 후 상주 서비스에서 다시 연결",pid=process.pid()))
            watch(id,config,process,CrashReports.snapshot(serverDir(id)),fresh=false)
        }
    }
    private fun currentJarPath()=GameHostMain.packagedJar(RunnerAgent::class.java)
    /** Hosted games are not killed by a control-layer shutdown; only the console position is remembered. */
    private fun detachHosted() {
        processes.forEach {(id,p)->(p as? HostedProcess)?.savePosition(instanceRoot(id).resolve("console.seq"))}
        hosted?.close()
    }
    fun hostStatus()=hosted?.hostStatus() ?: emptyList()
    fun initializeController(ready:()->Unit={}) {initHost();ready()}
    fun connectLoop() {
        initHost()
        closed=CountDownLatch(1)
        val timestamp=System.currentTimeMillis()
        val nonce=ByteArray(24).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val bytes="sm-runner-v1\n${identity.id}\n$timestamp\n$nonce\n".toByteArray()
        val signature=Signature.getInstance("Ed25519").apply { initSign(signingKey());update(bytes) }.sign()
        val encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        val uri=URI(identity.managerUrl.replaceFirst("https://","wss://")+"/api/runners/connect")
        val socket=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20))
            .header("X-SM-Runner-Id",identity.id).header("X-SM-Timestamp",timestamp.toString())
            .header("X-SM-Nonce",nonce).header("X-SM-Signature",encoded)
            .buildAsync(uri,this).join()
        connection.set(socket)
        if(socket.isOutputClosed) closed.countDown()
        while(closed.count>0 && socket.isOutputClosed.not()) {
            val now=System.currentTimeMillis()
            val updateClient=updater
            if(updateClient!=null && now-lastUpdateCheck>6*60*60*1000L) {
                lastUpdateCheck=now
                workers.submit { checkRunnerUpdate() }
            }
            if(restartForUpdate()) kotlin.system.exitProcess(75)
            tryReplaceHost()
            send(RunnerMessage("heartbeat",version=version()));sable.heartbeat()
            val resident=hosted
            if(resident==null)directRelay.tick()
            else if(System.nanoTime()-lastHostedRelayReport>=java.util.concurrent.TimeUnit.SECONDS.toNanos(10)) {
                // Restore callback + request a fresh consent/status after any resident-host reconnect.
                if(resident.relaySession())lastHostedRelayReport=System.nanoTime()
            }
            reportUpdate()
            reportFolders()
            home.resolve("instances").takeIf {it.exists()}?.listDirectoryEntries()?.forEach {dir->dir.resolve("install-result.json").takeIf {it.exists()}?.let {file->runCatching {send(mapper.readValue<RunnerMessage>(file.readText()))}}}
            processes.forEach { (id,process) -> report(id,process) }
            if(sessionToken!=null) home.resolve("instances").takeIf { it.exists() }?.let { root -> Files.list(root).use { dirs -> dirs.filter { it.isDirectory() && (it.resolve("dirty").exists() && processes[it.fileName.toString()]?.isAlive!=true || snapshots(it.fileName.toString()).list().isNotEmpty()) }.forEach { dir ->
                val id=dir.fileName.toString()
                configs[id] ?: runCatching { mapper.readValue<Instance>(dir.resolve("instance.json").readText()) }.getOrNull()?.let { configs[id]=it }
                if(configs[id]!=null) {
                    if(orphanAlive(id))send(RunnerMessage("state",id,"UNKNOWN",version(),"실행기 재시작 전 프로세스가 실행 중이어서 데이터 인계를 보류합니다"))
                    else workers.submit { uploadStopped(id) }
                }
            } } }
            if(sessionToken!=null)home.resolve("instances").takeIf {it.exists()}?.let {root->Files.list(root).use {paths->paths.filter {it.resolve("folder-import.pending").exists()}.forEach {dir->
                runCatching {val config=dir.resolve("instance.json").readText();val request=dir.resolve("folder-import.pending").readText();workers.submit {importFolder(dir.fileName.toString(),config,request)}}
            }}}
            closed.await(10,TimeUnit.SECONDS)
        }
        sable.disconnect();streamInstances.clear();sableRegistrations.values.forEach {it.complete(false)};sableRegistrations.clear()
        tcpRelay?.close();tcpRelay=null
        sessionToken=null;connection.set(null)
        gameStreams.values.forEach { runCatching { it.close() } };gameStreams.clear()
    }
    private fun reportUpdate(){send(RunnerMessage("update-status",detail=mapper.writeValueAsString(updater?.status() ?: RunnerUpdateStatus("UNSUPPORTED"))))}
    fun requestUpdate(pause:Boolean,version:String?=null,allowPrerelease:Boolean=false,allowRollback:Boolean=false) {
        if(pause)pauseAfterDownload=true
        workers.submit {checkRunnerUpdate(version,allowPrerelease,allowRollback)}
    }
    private fun checkRunnerUpdate(targetVersion:String?=null,allowPrerelease:Boolean=false,allowRollback:Boolean=false) {
        val service=updater ?: run {reportUpdate();return}
        runCatching {if(targetVersion==null)service.check() else service.install(targetVersion,allowPrerelease,allowRollback)}.onFailure {
            System.err.println("실행기 업데이트 확인 실패: ${it.javaClass.simpleName}")
            coordinator.update(UpdateComponents.RUNNER_CONTROL,version(),service.status().latest,"FAILED",error=UpdateRedaction.text(it.message ?: "업데이트 검증 실패"));reportUpdateDetail()
        }
        if(service.pending()) {
            if(pauseAfterDownload)updatePauseRequested=true
            service.phase(if(updatePauseRequested && processes.values.none {it.isAlive})"PAUSING" else "WAITING")
        } else if(service.status().phase in setOf("CURRENT","FAILED")){pauseAfterDownload=false}
    }
    private fun reserveUpload(id:String)=synchronized(lifecycle){!updatePauseRequested && !hostReplacing && uploading.add(id)}
    private fun updateCheckpoint(){
        if(updatePauseRequested)throw RetryTransfer("서명된 실행기 업데이트를 위해 전송을 잠시 중지했습니다. 재연결 후 이어받습니다")
        if(hostReplacing)throw RetryTransfer("상주 서비스를 교체하는 중입니다. 잠시 후 다시 시도하세요")
    }
    private val activation=ActivationGuard(home)
    /** Called once the new layer has a manager session; the host attach already happened in initHost(). */
    private fun confirmActivation() {
        val failed=home.resolve("failed-update-version.txt").takeIf {it.exists()}?.readText()?.trim()
        coordinator.reconcileStart(UpdateComponents.RUNNER_CONTROL,version(),failed)
        when(val outcome=activation.check(version())) {
            is ActivationGuard.Outcome.Waiting -> {activation.confirm(version());coordinator.update(UpdateComponents.RUNNER_CONTROL,version(),null,"DONE",message="새 제어 계층이 관리 서버와 상주 서비스에 연결됐습니다")}
            is ActivationGuard.Outcome.RolledBack -> coordinator.update(UpdateComponents.RUNNER_CONTROL,outcome.restored,outcome.failed,"FAILED",error=outcome.reason)
            else -> {}
        }
        hosted?.let {h->
            val host=h.hostVersion()
            if(host!=null)coordinator.update(UpdateComponents.GAME_HOST,host,if(host!=version())version() else null,if(host!=version())"WAITING" else "DONE",waitReason=if(host!=version())"HOST_REPLACED_WHEN_IDLE" else null)
        }
    }
    // ---- Resident host replacement (GAME_HOST) --------------------------------------------------------------------
    // The control layer can be updated while games run, but the host itself is only swapped when it owns nothing:
    // no game, no preparation/transfer/install, and no relay player. New work is refused while the swap is in progress.
    @Volatile private var hostReplacing=false
    private val hostReplaceBusy=java.util.concurrent.atomic.AtomicBoolean()
    private var hostReplaceNotBefore=0L
    private var hostDrainSince=0L
    private var hostReplaceFailures=0
    private var lastHostWait=""
    private fun hostWait(running:String,reason:String,detail:String?) {
        coordinator.update(UpdateComponents.GAME_HOST,running,version(),"WAITING",waitReason=reason,waitDetail=detail)
        val signature="$reason|$detail";if(signature!=lastHostWait){lastHostWait=signature;reportUpdateDetail()}
    }
    /** Must run under [lifecycle]: the same lock every admission of new work takes. */
    private fun hostReplacementBlocker():Pair<String,String>? {
        val alive=processes.values.count {it.isAlive}
        if(alive>0)return "GAME_RUNNING" to "서버 ${alive}개가 실행 중입니다. 모두 종료되면 자동으로 교체합니다"
        val root=home.resolve("instances")
        val dirs=if(root.exists())root.listDirectoryEntries().filter {it.isDirectory()} else emptyList()
        if(dirs.any {orphanAlive(it.name)})return "LEGACY_GAME_RUNNING" to "이전 방식으로 실행 중인 서버가 있습니다. 정상 종료 후 교체합니다"
        if(preparing.isNotEmpty())return "SERVER_PREPARING" to "서버 준비·설치 작업이 끝나면 자동으로 교체합니다"
        if(installationProcesses.values.any {it.isAlive})return "INSTALL_ACTIVE" to "서버 설치가 끝나면 자동으로 교체합니다"
        if(uploading.isNotEmpty() || workspaces.values.any {it.busy()})return "TRANSFER_ACTIVE" to "파일 전송이 끝나면 자동으로 교체합니다"
        if(stopping.isNotEmpty())return "STOP_IN_PROGRESS" to "서버 종료가 끝나면 자동으로 교체합니다"
        if(dirs.any {(it.resolve("dirty").exists() && !hostedAlive(it.name)) || it.resolve("folder-import.pending").exists()})return "DIRTY_HANDOFF_PENDING" to "파일 인계가 끝나면 자동으로 교체합니다"
        return null
    }
    private fun hostRefusalDetail(reason:String,streams:Int)=when(reason) {
        "RELAY_STREAMS_ACTIVE" -> "플레이어 연결 ${streams}개가 끝나면 자동으로 교체합니다 (신규 접속은 잠시 막힙니다)"
        "RELAY_SERVICE_ACTIVE" -> "이 상주 서비스는 중계 배출을 지원하지 않는 이전 버전입니다. 게임·접속이 없을 때 정상 종료 후 다시 시작해야 교체됩니다"
        "GAME_RUNNING" -> "서버가 실행 중입니다. 모두 종료되면 자동으로 교체합니다"
        "HOST_UNREACHABLE" -> "상주 서비스와 통신하지 못했습니다. 다시 시도합니다"
        "HOST_IDENTITY_MISMATCH","NOT_A_HOST_PROCESS","HOST_PROCESS_MISSING","HOST_ENDPOINT_MISSING" -> "상주 서비스의 PID·시작 시각을 확인하지 못해 교체하지 않았습니다"
        else -> "상주 서비스 교체를 보류했습니다 ($reason)"
    }
    /** Called from the connection loop; cheap when there is nothing to do. One attempt at a time, retried every few seconds. */
    fun tryReplaceHost() {
        val h=hosted ?: return
        val running=h.hostVersion() ?: h.connection()?.hostVersion ?: return
        if(running==version()) {
            if(coordinator.reconcileHost(running,version())){hostReplaceFailures=0;lastHostWait="";reportUpdateDetail()}
            return
        }
        if(hostModeState.mode!="SERVICE" || updatePauseRequested || currentJarPath()==null)return
        val now=System.currentTimeMillis()
        if(now<hostReplaceNotBefore)return
        if(!hostReplaceBusy.compareAndSet(false,true))return
        hostReplaceNotBefore=now+3000
        try {replaceHost(h,running)}
        catch(e:Exception) {
            hostReplacing=false;runCatching {h.connection()?.cancelReplacement()}
            hostWait(running,"HOST_REPLACE_ERROR","상주 서비스 교체 중 오류가 발생해 다시 시도합니다 (${e.javaClass.simpleName})")
        } finally {hostReplaceBusy.set(false)}
    }
    private fun replaceHost(h:HostedGames,running:String) {
        val blocker=synchronized(lifecycle) {hostReplacementBlocker() ?: run {hostReplacing=true;null}}
        if(blocker!=null){hostWait(running,blocker.first,blocker.second);return}
        try {
            val client=h.connection()
            if(client==null){hostWait(running,"HOST_UNREACHABLE","상주 서비스에 연결하지 못했습니다. 다시 시도합니다");return}
            val result=HostLifecycle.replaceIdleHost(home,client)
            if(!result.done) {
                if(result.reason=="RELAY_STREAMS_ACTIVE") {
                    val now=System.currentTimeMillis();if(hostDrainSince==0L)hostDrainSince=now
                    // A drain that never ends must not keep new servers out: give the players up to 90 seconds, then back off.
                    if(now-hostDrainSince>90_000){client.cancelReplacement();hostDrainSince=0;hostReplaceNotBefore=now+60_000;hostWait(running,"RELAY_STREAMS_ACTIVE","플레이어 연결이 90초 안에 끝나지 않아 교체를 취소했습니다. 1분 뒤 다시 시도합니다");return}
                } else hostDrainSince=0
                val reason=result.reason ?: "HOST_NOT_READY"
                hostWait(running,reason,hostRefusalDetail(reason,result.streams))
                if(reason!="RELAY_STREAMS_ACTIVE")hostReplaceNotBefore=System.currentTimeMillis()+(if(reason=="RELAY_SERVICE_ACTIVE")60_000 else 5000)
                return
            }
            hostDrainSince=0
            coordinator.update(UpdateComponents.GAME_HOST,running,version(),"APPLYING",message="상주 서비스를 현재 버전으로 교체합니다")
            val next=h.connection()
            if(next==null || next.hostVersion!=version()) {
                hostReplaceFailures++
                coordinator.update(UpdateComponents.GAME_HOST,running,version(),"FAILED",error="새 상주 서비스를 시작하지 못했습니다: ${h.lastError ?: next?.hostVersion ?: "응답 없음"}")
                reportUpdateDetail()
                // No game can exist here, so retrying is safe. After repeated failures the supervisor restores the previous version, which starts its own host.
                if(hostReplaceFailures>=3){System.err.println("새 상주 서비스 시작 반복 실패 · 이전 버전 복구를 요청합니다");kotlin.system.exitProcess(3)}
                return
            }
            hostReplaceFailures=0
            next.onRelay={body->runCatching {send(mapper.readValue<RunnerMessage>(body))}}
            next.relaySession()
            coordinator.update(UpdateComponents.GAME_HOST,next.hostVersion,null,"DONE",message="상주 서비스가 ${next.hostVersion}으로 교체됐습니다")
            lastHostWait="";reportUpdateDetail()
        } finally {hostReplacing=false}
    }
    // ---- Update status (component, stage, wait reason, durable log) -----------------------------------------------
    private val coordinator=UpdateCoordinator(home)
    private fun pendingProps():java.util.Properties? {
        val version=runCatching {home.resolve("updates/pending.txt").readText().trim()}.getOrNull() ?: return null
        return runCatching {java.util.Properties().also {p->home.resolve("updates/$version/update.properties").inputStream().use(p::load)}}.getOrNull()
    }
    private fun waitingFor(service:RunnerUpdater,reason:String,detail:String?=null):Boolean {
        service.phase("WAITING")
        coordinator.update(UpdateComponents.RUNNER_CONTROL,version(),service.status().latest,"WAITING",waitReason=reason,waitDetail=detail)
        reportUpdateDetail();return true
    }
    private fun hostedGamesBlockUpdate(service:RunnerUpdater):Boolean {
        val alive=processes.values.count {it is HostedProcess && it.isAlive}
        if(alive==0)return false
        val props=pendingProps()
        val decision=UpdatePolicy.decide(UpdateComponents.RUNNER_CONTROL,UpdateFacts(hostedGamesAlive=alive,hostProtocol=hosted?.hostProtocol(),
            targetMinHost=props?.getProperty("controller.minHost")?.toIntOrNull() ?: 1,targetMaxHost=props?.getProperty("controller.maxHost")?.toIntOrNull() ?: 1))
        return if(decision.apply)false else waitingFor(service,decision.waitReason ?: "HOST_UNKNOWN")
    }
    /** Sent when the detail changes and on every new session, so a reconnecting manager can read what happened meanwhile. */
    private fun reportUpdateDetail(){send(RunnerMessage("update-detail",detail=mapper.writeValueAsString(coordinator.snapshot(hostModeState))))}
    /**
     * Manager-requested restart of this control layer (same exit code the supervisor already restarts on). Games owned by the
     * resident host keep running. Refuses instead of waiting, so the requester gets the real reason and can retry.
     */
    internal fun restartRequested(requestId:String?=null):String? {
        val reason=synchronized(lifecycle) {
            val root=home.resolve("instances")
            val dirs=if(root.exists())root.listDirectoryEntries().filter {it.isDirectory()} else emptyList()
            when {
                !supervisedRestart -> "UNSUPERVISED"
                updatePauseRequested -> "BUSY"
                updater?.pending()==true || updater?.busy()==true -> "UPDATE_PENDING"
                hostReplacing -> "HOST_REPLACING"
                tcpRelay?.busy()==true || directRelay.busy() -> "RELAY_STREAMS_ACTIVE"
                processes.values.any {it.isAlive && it !is HostedProcess} || dirs.any {orphanAlive(it.name)} -> "LEGACY_GAME_RUNNING"
                processes.values.any {it is HostedProcess && it.isAlive} && hosted?.hostProtocol()==null -> "HOST_UNREACHABLE"
                preparing.isNotEmpty() || installationProcesses.values.any {it.isAlive} -> "SERVER_PREPARING"
                uploading.isNotEmpty() || workspaces.values.any {it.busy()} -> "TRANSFER_ACTIVE"
                dirs.any {(it.resolve("dirty").exists() && !hostedAlive(it.name)) || it.resolve("folder-import.pending").exists()} -> "DIRTY_HANDOFF_PENDING"
                else -> {updatePauseRequested=true;null}
            }
        }
        send(RunnerMessage("restart-result",state=if(reason==null)"ACCEPTED" else "REFUSED",detail=reason,token=requestId))
        if(reason==null)workers.submit {Thread.sleep(700);restartProcess()}
        return reason
    }
    fun restartForUpdate():Boolean=synchronized(lifecycle) {
        val service=updater ?: return false
        if(service.busy() || !service.pending())return false
        val target=pendingProps()?.getProperty("version") ?: service.status().latest
        if(tcpRelay?.busy()==true || directRelay.busy()) {
            val streams=(tcpRelay?.streamCount() ?: 0)+directRelay.streamTotal()
            return waitingFor(service,"RELAY_STREAMS_ACTIVE","플레이어 연결 ${streams}개가 끝나면 자동으로 적용됩니다").let {false}
        }
        val root=home.resolve("instances")
        val dirs=if(root.exists())root.listDirectoryEntries().filter {it.isDirectory()} else emptyList()
        // Games owned by the resident host survive a control-layer restart, but only when the new layer can still talk to that host.
        if(processes.values.any {it.isAlive && it !is HostedProcess} || dirs.any {orphanAlive(it.name)})return waitingFor(service,"LEGACY_GAME_RUNNING").let {false}
        if(preparing.isNotEmpty())return waitingFor(service,"SERVER_PREPARING").let {false}
        if(hostedGamesBlockUpdate(service))return false
        if(uploading.isNotEmpty() || workspaces.values.any {it.busy()}){coordinator.update(UpdateComponents.RUNNER_CONTROL,version(),target,"WAITING",waitReason="TRANSFER_ACTIVE");return false}
        if(!updatePauseRequested && dirs.any {(it.resolve("dirty").exists() && !hostedAlive(it.name)) || it.resolve("folder-import.pending").exists()})return waitingFor(service,"DIRTY_HANDOFF_PENDING").let {false}
        updatePauseRequested=true;service.phase("RESTARTING")
        activation.begin(UpdateComponents.RUNNER_CONTROL,version(),target ?: version(),10*60_000L)
        coordinator.update(UpdateComponents.RUNNER_CONTROL,version(),target,"APPLYING",message="제어 계층을 교체합니다 · 게임 ${processes.values.count {it is HostedProcess && it.isAlive}}개는 상주 서비스가 유지")
        coordinator.update(UpdateComponents.RUNNER_CONTROL,version(),target,"RECONNECTING")
        reportUpdateDetail();true
    }
    fun shutdownGracefully() {
        cancelLlmTasks()
        // Hosted games are left running: this layer may only be restarting for an update.
        detachHosted()
        processes.entries.filter {it.value !is HostedProcess}.forEach { stop(it.key) }
        processes.values.filter {it !is HostedProcess}.forEach { runCatching { it.waitFor() } }
        installationProcesses.values.forEach {runCatching {it.waitFor()}}
        if(!updatePauseRequested)home.resolve("instances").takeIf { it.exists() }?.let { root -> Files.list(root).use { paths -> paths.filter { it.resolve("dirty").exists() || it.resolve("stopped-snapshots").takeIf {p->p.exists()}?.listDirectoryEntries()?.any {p->StoppedSnapshots.valid(p.name)}==true }.forEach { dir -> uploadStopped(dir.fileName.toString()) } } }
        tcpRelay?.close();directRelay.close();sable.close();workers.shutdown()
    }
    override fun onOpen(webSocket:WebSocket) { connection.set(webSocket);webSocket.request(1) }
    override fun onText(webSocket:WebSocket,data:CharSequence,last:Boolean):CompletionStage<*> {
        synchronized(textBuffer) {
            if(textBuffer.length+data.length>4*PiWire.MAX_REQUEST_BYTES){textBuffer.setLength(0);webSocket.abort();return CompletableFuture.completedFuture(null)}
            textBuffer.append(data)
            if(last) {
                val raw=textBuffer.toString();textBuffer.setLength(0)
                if(connection.get()!==webSocket){webSocket.request(1);return CompletableFuture.completedFuture(null)}
                runCatching { mapper.readValue<RunnerMessage>(raw) }.getOrNull()?.let { message ->
                    when(message.type) {
                        "sable-config" -> {sable.enabled=message.state=="ON"}
                        "sable-registered" -> message.token?.let {sableRegistrations[it]?.complete(message.state=="OK")}

                        "session" -> { sessionToken=message.token;tcpRelay?.close();tcpRelay=if(tcpRelayAllowed)TcpRelayPeer(::send,::sendBinary) else null
                            send(RunnerMessage("workspace-capability",state="LIVE_FILES_V1"));send(RunnerMessage("restart-capability",state=if(supervisedRestart)"RESTART_V2" else "OFF",detail=serviceBootId));send(RunnerMessage("update-capability",state=if(updater!=null)"RELEASE_SELECTION_V1" else "OFF",detail=if(updater?.rollbackSupported==true)"ROLLBACK_V1" else null));confirmActivation();hosted?.relaySession() ?: directRelay.onSession();reportUpdateDetail();send(RunnerMessage("relay-capability",state=if(tcpRelayAllowed)"TCP_V1" else "OFF"));send(RunnerMessage("llm-capability",state=if(llmAllowed)"OLLAMA_AGENT_V3" else "OFF",detail="CONTEXT_TOOLS_V1",command="STRATA_V1",version="PI_PROVIDER_V1",token="AGENT_SAFETY_PROFILE_V1"));send(RunnerMessage("llm-context-capability",version="KV_CONTEXT_V1"));reportUpdate();recoverStates() }
                        "relay-config" -> {
                            val port=message.detail?.toIntOrNull()
                            val ok=tcpRelayAllowed && (message.state=="OFF" || port in 1024..65535) && runCatching {tcpRelay?.listen(if(message.state=="ON")port else null);true}.getOrDefault(false)
                            send(RunnerMessage("relay-status",state=if(ok && message.state=="ON")"READY" else if(ok)"OFF" else "FAILED"))
                        }
                        "relay-opened" -> message.streamId?.takeIf {it<0}?.let {tcpRelay?.ready(it)}
                        "relay-closed" -> message.streamId?.takeIf {it<0}?.let {tcpRelay?.end(it)}
                        "drp-relay-config","drp-agent-config","drp-ticket" -> {
                            if(message.type=="drp-agent-config")directSable=runCatching {mapper.readTree(message.detail).path("sableUdpEnabled").asBoolean(false)}.getOrDefault(false)
                            hosted?.relayForward(mapper.writeValueAsString(message)) ?: directRelay.onMessage(message)
                        }
                        "update-request" -> if(message.detail in setOf("WHEN_IDLE","PAUSE_TRANSFER")) {
                            requestUpdate(message.detail=="PAUSE_TRANSFER",message.command,message.state=="BETA_OK",message.state=="STABLE_ROLLBACK")
                        }
                        "restart-request" -> restartRequested(message.token)
                        "llm-request" -> submitLlm(message)
                        "llm-cancel" -> message.token?.let {llmTasks.remove(it)?.cancel(true)}
                        "run-token" -> if(message.instanceId!=null && message.pid!=null && message.token!=null)adoptRunToken(message.instanceId,message.pid,message.token)
                        "workspace" -> if(message.instanceId!=null && message.token!=null && message.detail!=null)workers.submit {workspace(message.instanceId,message.token,message.detail)}
                        "install" -> if(message.instanceId!=null && message.token!=null && message.detail!=null)workers.submit {installServer(message.instanceId,message.token,message.detail)}
                        "start" -> if(message.instanceId!=null && message.detail!=null) workers.submit { beginStart(message.instanceId,message.detail) }
                        "import-folder" -> if(message.instanceId!=null && message.detail!=null && message.token!=null)workers.submit {importFolder(message.instanceId,message.detail,message.token)}
                        "stop" -> message.instanceId?.let { stop(it) }
                        "force-stop" -> if(connection.get()===webSocket && message.instanceId!=null && message.pid!=null) {
                            if(!runCatching {forceStop(message.instanceId,message.pid)}.getOrDefault(false))send(RunnerMessage("log",message.instanceId,"system",detail="강제 종료 실패 · 대상 프로세스가 변경되었거나 종료 요청을 처리할 수 없습니다. 상태를 새로고침하세요."))
                        }
                        "idle-stop" -> if(message.instanceId!=null && message.detail!=null)workers.submit {stopIfEmpty(message.instanceId,message.detail)}
                        "restart" -> message.instanceId?.let { stop(it) }
                        "command" -> if(message.instanceId!=null && message.command!=null) command(message.instanceId,message.command)
                        "open" -> if(message.instanceId!=null && message.streamId!=null) openGame(message.instanceId,message.streamId)
                        "close" -> message.streamId?.let { sable.closeStream(it);streamInstances.remove(it);gameStreams.remove(it)?.close() }
                    }
                }
            }
        }
        webSocket.request(1);return CompletableFuture.completedFuture(null)
    }
    override fun onBinary(webSocket:WebSocket,data:ByteBuffer,last:Boolean):CompletionStage<*> {
        synchronized(binaryBuffer) {
            val bytes=ByteArray(data.remaining());data.get(bytes)
            if(binaryBuffer.size()+bytes.size>65540) { binaryBuffer.reset();webSocket.request(1);return CompletableFuture.completedFuture(null) }
            binaryBuffer.write(bytes)
            if(last) {
                val frame=ByteBuffer.wrap(binaryBuffer.toByteArray());binaryBuffer.reset()
                if(frame.remaining()>=4) {
                    val id=frame.int
                    if(id<0) {
                        if(connection.get()===webSocket)tcpRelay?.receive(id,ByteArray(frame.remaining()).also(frame::get))
                        webSocket.request(1);return CompletableFuture.completedFuture(null)
                    }
                    val stream=gameStreams[id]
                    if(stream!=null) {
                        val chunk=ByteArray(frame.remaining());frame.get(chunk)
                        runCatching { stream.getOutputStream().write(chunk);stream.getOutputStream().flush() }.onFailure { gameStreams.entries.removeIf { it.value===stream };runCatching { stream.close() } }
                    }
                }
            }
        }
        webSocket.request(1);return CompletableFuture.completedFuture(null)
    }
    override fun onClose(webSocket:WebSocket,statusCode:Int,reason:String):CompletionStage<*> { if(connection.get()===webSocket){cancelLlmTasks();closed.countDown()};return CompletableFuture.completedFuture(null) }
    override fun onError(webSocket:WebSocket,error:Throwable) { if(connection.get()===webSocket){cancelLlmTasks();closed.countDown()} }
    private fun send(message:RunnerMessage):Boolean {
        val socket=connection.get() ?: return false
        return runCatching { synchronized(sendLock) { socket.sendText(mapper.writeValueAsString(message),true).join() };true }.getOrDefault(false)
    }
    private fun sendBinary(streamId:Int,bytes:ByteArray,count:Int):Boolean {
        val socket=connection.get() ?: return false
        return runCatching {
            val frame=ByteBuffer.allocate(4+count).putInt(streamId).put(bytes,0,count).flip()
            synchronized(sendLock) { socket.sendBinary(frame,true).join() };true
        }.getOrDefault(false)
    }
    private fun openGame(instanceId:String,streamId:Int) {
        val port=configs[instanceId]?.port ?: return
        if(processes[instanceId]?.isAlive!=true || instanceId !in ready || instanceId in stopping || gameStreams.size>=100)return
        workers.submit {
            val socket=Socket()
            try {
                socket.connect(InetSocketAddress("127.0.0.1",port),3000)
                socket.soTimeout=60000
                gameStreams[streamId]=socket;streamInstances[streamId]=instanceId
                if(!send(RunnerMessage("opened",instanceId=instanceId,streamId=streamId)))return@submit
                val input=socket.getInputStream();val buffer=ByteArray(64*1024)
                while(true) { val count=input.read(buffer);if(count<0)break;if(!sendBinary(streamId,buffer,count))break }
            } catch (_:Exception) {}
            finally {
                gameStreams.remove(streamId,socket);streamInstances.remove(streamId);sable.closeStream(streamId)
                runCatching { socket.close() }
                send(RunnerMessage("closed",instanceId=instanceId,streamId=streamId))
            }
        }
    }
    private class RetryTransfer(message:String):java.io.IOException(message)
    private fun transferResponse(response:HttpResponse<ByteArray>,message:String) {
        if(response.statusCode() in setOf(401,403,408,429) || response.statusCode()>=500)throw RetryTransfer("$message · 연결 복구 후 이어받습니다 (HTTP ${response.statusCode()})")
        check(response.statusCode()==200) {"$message: HTTP ${response.statusCode()}"}
    }
    private fun http(method:String,path:String,body:HttpRequest.BodyPublisher=HttpRequest.BodyPublishers.noBody(),contentType:String?=null):HttpResponse<ByteArray> {
        val token=sessionToken ?: throw RetryTransfer("실행기 연결이 끊겼습니다. 연결 복구 후 이어받습니다")
        val builder=HttpRequest.newBuilder(URI(identity.managerUrl+path)).timeout(Duration.ofMinutes(10))
            .header("X-SM-Runner-Id",identity.id).header("X-SM-Session",token)
        if(contentType!=null) builder.header("Content-Type",contentType)
        return try {client.send(builder.method(method,body).build(),HttpResponse.BodyHandlers.ofByteArray())}catch(e:java.io.IOException){throw RetryTransfer("중앙 서버 연결이 끊겼습니다. 연결 복구 후 이어받습니다") }
    }
    private fun awaitTransfer(id:String,message:String,waiting:()->Unit={},allowMissing:Boolean=false,request:()->HttpResponse<ByteArray>):HttpResponse<ByteArray> {
        val response=try {TransferWait().await(::updateCheckpoint,waiting,request)}catch(e:java.io.IOException){throw RetryTransfer(e.message ?: message)}
        if(!allowMissing || response.statusCode()!=404)transferResponse(response,message)
        return response
    }
    private fun protocolPath(path:String)=path+(if('?' in path)"&" else "?")+"protocol=2"
    private fun fastTransfer(id:String):Boolean {
        val response=http("GET","/api/runners/transfer/$id/capabilities")
        if(response.statusCode()==404)return false
        transferResponse(response,"전송 기능 확인 대기")
        return mapper.readTree(response.body()).path("batchProtocol").asInt()==1
    }
    private fun encode(value:String)=URLEncoder.encode(value,Charsets.UTF_8)
    private fun adoptRunToken(id:String,pid:Long,token:String) {
        require(UUID.fromString(token).toString()==token)
        val process=processes[id] ?: return
        if(!process.isAlive || process.pid()!=pid)return
        configs.computeIfPresent(id){_,cfg->
            if(cfg.runToken==token)cfg else cfg.copy(runToken=token).also {atomicText(instanceRoot(id).resolve("instance.json"),mapper.writeValueAsString(it))}
        }
    }
    private fun settledEditRevision(id:String)=if(workspaces[id]?.busy()==true)null else localEdits(id).takeIf {it.exists()}?.readText()
    private fun localEdits(id:String)=instanceRoot(id).resolve("local-edits.pending")
    private fun workspaceService(id:String)=workspaces.compute(id) {_,previous->
        val directory=serverDir(id)
        if(previous?.matches(directory)==true)previous else {
            check(previous?.busy()!=true)
            RunnerWorkspace(directory,if(folders.custom(id))directory.resolveSibling(".servermanager-files-$id") else instanceRoot(id).resolve("file-history"),instanceRoot(id),beforeMutation={token->
                atomicText(localEdits(id),token ?: UUID.randomUUID().toString())
            }) {check(!updatePauseRequested) {"업데이트 적용 중입니다"}}
        }
    }!!
    /** Ollama runs on this computer only; the manager never gets a way to choose the endpoint or a model tag. */
    private fun llmChat(requestId:String,size:String,prompt:String,think:Boolean) {
        val response=try {
            check(llmAllowed) {"이 실행기는 Qwen 실행이 허용되지 않았습니다"}
            // Probing is read-only and stays available while a chat runs; chat and unload are serialized so only one model is ever loaded.
            val exclusive=size!="probe"
            check(!exclusive || llmBusy.compareAndSet(false,true)) {"이 실행기에서 다른 Qwen 요청이 진행 중입니다"}
            try {
                val llm=LocalLlm()
                val result:Any=when(size) {
                    "probe" -> llm.probe()
                    "unload" -> llm.unloadAll()
                    "install-strata" -> StrataRuntime.install()
                    "install-turboquant" -> TurboQuantRuntime.install()
                    else -> if(size.startsWith("agent-profile:")) {
                        require(prompt.toByteArray(Charsets.UTF_8).size<=2*PiWire.MAX_REQUEST_BYTES)
                        val body=mapper.readTree(prompt)
                        val budget=mapper.treeToValue(body.path("budget"),LocalContextBudget::class.java)
                        val messages=body.path("messages").map {ProjectAgentMessage(it.path("role").asText(),it.path("content").asText(),it.path("native").takeIf {n->n.isTextual}?.asText())}
                        llm.conversationWithBudget(size.substringAfter(':'),messages,think,budget) {frame->send(RunnerMessage("llm-progress",token=requestId,detail=mapper.writeValueAsString(frame.bounded())))}
                    } else if(size.startsWith("agent:")) {
                        require(prompt.length<=60000)
                        llm.conversation(size.substringAfter(':'),mapper.readValue<List<ProjectAgentMessage>>(prompt),think) {frame->send(RunnerMessage("llm-progress",token=requestId,detail=mapper.writeValueAsString(frame.bounded())))}
                    } else llm.chat(size,prompt,think)
                }
                RunnerMessage("llm-result",state="OK",token=requestId,detail=mapper.writeValueAsString(result).also {require(it.length<=200*1024)})
            } finally {if(exclusive)llmBusy.set(false)}
        } catch(e:Exception) {RunnerMessage("llm-result",state="ERROR",token=requestId,detail=(if(e is IllegalStateException||e is IllegalArgumentException)e.message else null)?.take(500) ?: "실행기의 Ollama 요청을 완료하지 못했습니다")}
        send(response)
    }
    private fun workspace(id:String,requestId:String,raw:String) {
        val response=try {
            val request=mapper.readValue<WorkspaceRequest>(raw);val instance=request.config
            require(instance.id==id && instance.runnerId==identity.id && instance.diagnosticSession==null)
            val readOnly=request.op in setOf("LIST","STAT","READ","LOG_LIST","LOG_PAGE","LOG_WINDOW","LOG_STAT","LOG_READ","MOD_SCAN")
            fun execute():RunnerMessage {
                updateCheckpoint()
                check(!instanceRoot(id).resolve("folder-import.pending").exists()) {"최초 폴더 가져오기를 완료하세요"}
                // Never replace an active game's config with a stale read request.
                configs.putIfAbsent(id,instance);instanceRoot(id).createDirectories();serverDir(id).createDirectories()
                if(!readOnly && !instanceRoot(id).resolve("instance.json").exists())atomicText(instanceRoot(id).resolve("instance.json"),mapper.writeValueAsString(configs.getValue(id)))
                val result=mapper.writeValueAsString(workspaceService(id).execute(request));require(result.length<=200*1024)
                return RunnerMessage("workspace-result",id,"OK",token=requestId,detail=result)
            }
            if(readOnly)execute() else synchronized(operationLocks.computeIfAbsent(id){Any()}) {
                synchronized(lifecycle){updateCheckpoint();check(preparing.add(id)) {"실행기 작업이 진행 중입니다"}}
                try {
                val result=execute()
                if(request.op in setOf("WRITE_COMMIT","MKDIR","MOVE","DELETE","MOD_TOGGLE") && instance.syncEnabled() && processes[id]?.isAlive!=true && !orphanAlive(id) && !dirty(id).exists()) {
                    val snapshot=snapshots(id).capture(serverDir(id),null,null,localEdits(id).takeIf {it.exists()}?.readText(),editOnly=true)
                    transferResponse(http("POST","/api/runners/transfer/$id/snapshot-ready",HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(snapshot.ready)),"application/json"),"파일 변경 복사본 접수 대기")
                    workers.submit {uploadSnapshots(id)}
                }
                result
                } finally {preparing.remove(id)}
            }
        }catch(e:Exception){RunnerMessage("workspace-result",id,"ERROR",token=requestId,detail=e.message?.take(500) ?: "파일 작업 실패")}
        send(response)
    }
    private fun installServer(id:String,ticket:String,raw:String) {
        var reserved=false
        try {synchronized(operationLocks.computeIfAbsent(id){Any()}) {try {
            synchronized(lifecycle){updateCheckpoint();check(preparing.add(id));reserved=true}
            require(UUID.fromString(ticket).toString()==ticket)
            val instance=mapper.readValue<Instance>(raw);require(instance.id==id && instance.runnerId==identity.id && instance.diagnosticSession==null)
            check(processes[id]?.isAlive!=true && !orphanAlive(id) && !dirty(id).exists() && id !in uploading) {"서버와 파일 인계를 종료하세요"}
            check(!folders.custom(id)) {"기존 폴더에는 설치하지 않습니다. 폴더 가져오기 또는 동기화 OFF로 기존 서버를 사용하세요"}
            configs[id]=instance;instanceRoot(id).createDirectories();atomicText(instanceRoot(id).resolve("instance.json"),raw)
            val base=serverDir(id).also {it.createDirectories()};check(!base.resolve("world").exists()) {"월드가 있는 폴더에는 새로 설치하지 않습니다"}
            val props=base.resolve("server.properties");if(!props.exists())props.writeText("server-ip=127.0.0.1\nserver-port=${instance.port}\nenable-query=false\n")
            base.resolve("eula.txt").writeText("eula=true\n")
            val installer=LauncherInstaller(LauncherCatalog(home.resolve("launcher-catalogs")))
            installer.install(instance.copy(javaPath=javaBin()),base,instanceRoot(id).resolve("installer.jar"),{args->
                val process=ProcessBuilder(args).directory(base.toFile()).redirectErrorStream(true).start();installationProcesses[id]=process
                try {process.inputStream.bufferedReader().useLines {lines->lines.forEach {line->send(RunnerMessage("log",id,"output",version(),line.take(4096)))}};process.waitFor()}
                finally{installationProcesses.remove(id,process)}
            }) {line->send(RunnerMessage("log",id,"output",version(),line.take(4096)))}
            if(instance.syncEnabled()) {
                val pending=instanceRoot(id).resolve("folder-import.pending");atomicText(instanceRoot(id).resolve("install-sync.pending"),ticket);atomicText(pending,ticket)
                uploadFiles(id,ticket);pending.deleteIfExists();instanceRoot(id).resolve("install-sync.pending").deleteIfExists()
            }
            val result=RunnerMessage("install-result",id,"OK",token=ticket,detail="실행기에서 ${instance.loader} ${instance.loaderVersion} 설치 완료")
            atomicText(instanceRoot(id).resolve("install-result.json"),mapper.writeValueAsString(result));send(result)
        }finally{if(reserved)preparing.remove(id)}}}catch(e:Exception){if(e is RetryTransfer && instanceRoot(id).resolve("install-sync.pending").exists()){send(RunnerMessage("import-progress",id,token=ticket,detail=mapper.writeValueAsString(FolderImportProgress("RETRYING",message="설치는 완료됐습니다. 연결 복구 후 변경 파일 인계를 재개합니다"))));return};val result=RunnerMessage("install-result",id,"ERROR",token=ticket,detail=e.message?.take(500) ?: "실행기 설치 실패");atomicText(instanceRoot(id).resolve("install-result.json"),mapper.writeValueAsString(result));send(result)}
    }
    private fun beginStart(id:String,rawConfig:String)=synchronized(operationLocks.computeIfAbsent(id){Any()}) {
        var spawned=false
        try {
            synchronized(lifecycle){updateCheckpoint();preparing.add(id)}
            check(processes[id]?.isAlive!=true) { "이미 실행 중입니다" }
            check(!dirty(id).exists()) { "종료 복사본을 준비 중입니다. 잠시 후 다시 시작하세요" }
            check(!instanceRoot(id).resolve("folder-import.pending").exists() && (id !in uploading || snapshots(id).list().isNotEmpty())) { "최초 폴더 가져오기를 완료하세요" }
            val instance=mapper.readValue<Instance>(rawConfig)
            require(instance.id==id && instance.runnerId==identity.id)
            configs[id]=instance
            val root=instanceRoot(id).also { it.createDirectories() }
            root.resolve("instance.json").writeText(mapper.writeValueAsString(instance))
            var lastScan=0L
            fun scanning(count:Int,bytes:Long) {
                val now=System.nanoTime();if(count>0 && now-lastScan<1_000_000_000)return;lastScan=now
                send(RunnerMessage("file-progress",id,detail=mapper.writeValueAsString(FolderImportProgress("SCANNING",count,bytes,message="원래 폴더의 외부 변경과 실행 상태를 확인하고 있습니다. 이전에 검사한 파일은 다시 읽지 않습니다",direction="TO_RUNNER",step="원래 폴더 변경 확인",stepNo=1,stepTotal=1))))
            }
            val localPrimary=localEdits(id).exists() || snapshots(id).list().isNotEmpty()
            if(instance.diagnosticSession==null){if(instance.syncEnabled() && !localPrimary){scanning(0,0);folders.checkStopped(id,::scanning)}else {if(folders.custom(id))folders.checkWorldStopped(id);folders.recover(id);workspaceService(id).recover()};folders.prepareRuntime(id,instance)}
            else {serverDir(id).createDirectories();DeltaTransfer.recover(serverDir(id),diagnosticRoot(id)!!.resolve("transfer-backups"))}
            send(RunnerMessage("state",id,"TRANSFERRING",version(),"서버 파일을 받고 있습니다"))
            // Local consent makes the existing folder authoritative until its first stopped upload.
            // Pulling the newly-created empty central profile here would destroy the original world.
            if(instance.syncEnabled() && !localPrimary && (instance.diagnosticSession!=null || !folders.initial(id)))pull(id)
            startProcess(instance) {spawned=true}
        } catch(e:Exception) {
            val state=if(!spawned && processes[id]?.isAlive!=true && !dirty(id).exists())"START_FAILED" else "SYNC_FAILED"
            send(RunnerMessage("file-progress",id,detail=mapper.writeValueAsString(FolderImportProgress("FAILED",message=e.message.orEmpty().take(500),direction="TO_RUNNER"))))
            send(RunnerMessage("state",id,state,version(),e.message?.take(300) ?: "시작 실패"))
        } finally {preparing.remove(id)}
    }
    private fun reportFolders() {
        val root=home.resolve("instances");if(!root.exists())return
        Files.list(root).use {dirs->dirs.filter {it.resolve("server-folder.properties").isRegularFile()}.forEach {dir->
            val id=dir.fileName.toString()
            runCatching {send(RunnerMessage("folder-source",id,if(folders.initial(id))"PENDING" else "SYNCED",version(),folders.directory(id).toString()))}
                .onFailure {send(RunnerMessage("folder-source",id,"INVALID",version(),"등록한 폴더 경로를 확인하세요"))}
        }}
    }
    private fun importFolder(id:String,rawConfig:String,request:String) {
        if(!reserveUpload(id))return
        try {synchronized(operationLocks.computeIfAbsent(id){Any()}) {
            require(UUID.fromString(request).toString()==request)
            check(processes[id]?.isAlive!=true && !dirty(id).exists() && !orphanAlive(id)) { "서버를 정상 종료하고 기존 파일 인계를 먼저 완료하세요" }
            val instance=mapper.readValue<Instance>(rawConfig);require(instance.id==id && instance.runnerId==identity.id)
            val root=instanceRoot(id).also {it.createDirectories()};val pending=root.resolve("folder-import.pending")
            check(!pending.exists() || pending.readText()==request) { "다른 가져오기 요청이 진행 중입니다" }
            if(pending.exists()) {
                val response=http("GET","/api/runners/transfer/$id/import-state?request=$request")
                transferResponse(response,"가져오기 상태 확인 대기")
                when(mapper.readTree(response.body()).path("state").asText()) {
                    "COMPLETE" -> {
                        val remote=http("GET","/api/runners/transfer/$id/manifest");transferResponse(remote,"반영된 파일 목록 확인 대기")
                        val manifest=mapper.readValue<RunnerFileManifest>(remote.body());val local=FileInventory.collectCached(serverDir(id)).let {(f,d)->RunnerFileManifest(f,d)}
                        check(local==manifest) { "반영 완료 후 원래 폴더가 변경됐습니다. 로컬 폴더를 확인하세요" }
                        folders.published(id,manifest);pending.deleteIfExists();instanceRoot(id).resolve("install-sync.pending").deleteIfExists();reportFolders();return
                    }
                    "EXPIRED" -> {pending.deleteIfExists();error("가져오기 요청이 만료됐습니다. 웹에서 다시 요청하세요")}
                }
            }
            check((folders.custom(id) && folders.initial(id)) || !instance.syncEnabled() || instanceRoot(id).resolve("install-sync.pending").takeIf {it.exists()}?.readText()==request) { "가져올 기존 폴더가 등록되어 있지 않거나 이미 가져왔습니다" }
            root.resolve("instance.json").writeText(rawConfig);pending.writeText(request);configs[id]=instance
            folders.checkWorldStopped(id);folders.prepareRuntime(id,instance)
            uploadFiles(id,request)
            pending.deleteIfExists();instanceRoot(id).resolve("install-sync.pending").deleteIfExists();reportFolders()
        }} catch(e:Exception) {
            if(e is RetryTransfer) {
                send(RunnerMessage("import-progress",id,detail=mapper.writeValueAsString(FolderImportProgress("RETRYING",message="연결 복구 후 이미 받은 파일부터 이어받습니다")),token=request))
                return
            }
            if(sessionToken!=null) {
                send(RunnerMessage("import-failed",id,detail=e.message?.take(500) ?: "가져오기 실패",token=request))
                val marker=instanceRoot(id).resolve("folder-import.pending")
                // Sending a WebSocket frame is not an acknowledgement. Keep the ticket until
                // the manager confirms expiration, or recovery confirms the committed files.
                val state=runCatching {val response=http("GET","/api/runners/transfer/$id/import-state?request=$request");check(response.statusCode()==200);mapper.readTree(response.body()).path("state").asText()}.getOrNull()
                if(state=="EXPIRED" && marker.takeIf {it.exists()}?.readText()==request)marker.deleteIfExists()
                if(state=="COMPLETE")send(RunnerMessage("folder-source",id,"INVALID",version(),"가져온 파일과 원래 폴더를 다시 확인하고 있습니다"))
            }
        } finally {uploading.remove(id)}
    }
    private fun pull(id:String) {
        var lastProgress=0L
        fun progress(value:FolderImportProgress,force:Boolean=false) {
            val now=System.nanoTime();if(!force && now-lastProgress<1_000_000_000)return;lastProgress=now
            send(RunnerMessage("file-progress",id,detail=mapper.writeValueAsString(value.copy(direction="TO_RUNNER"))))
        }
        progress(FolderImportProgress("SCANNING",message="관리 서버에 보낼 파일 목록을 요청했습니다",step="관리 서버 파일 목록 수신",stepNo=1,stepTotal=3),true)
        val response=awaitTransfer(id,"서버 파일 목록 검증 대기",{
            progress(FolderImportProgress("SCANNING",message="관리 서버가 보낼 파일 목록을 만들고 있습니다. 이전에 검사한 파일은 다시 읽지 않고 새 파일·변경된 파일만 읽습니다",step="관리 서버 파일 목록 수신",stepNo=1,stepTotal=3))
        }) {http("GET",protocolPath("/api/runners/transfer/$id/manifest"))}
        val manifest=mapper.readValue<RunnerFileManifest>(response.body())
        DeltaTransfer.validate(manifest,serverDir(id))
        val stage=diagnosticRoot(id)?.resolve("stage-${UUID.randomUUID()}")?.createDirectories() ?: folders.stage(id)
        try {
            manifest.directories.forEach { FileInventory.safePath(stage,it).createDirectories() }
            val scanStats=FileInventory.ScanStats()
            fun listing(count:Int,bytes:Long)=FolderImportProgress("SCANNING",count,bytes,message="실행기 폴더의 파일 목록을 만들고 있습니다. 이전에 검사한 파일은 다시 읽지 않습니다",
                step="실행기 파일 목록 작성",stepNo=2,stepTotal=3,reused=scanStats.reused.get(),hashed=scanStats.hashed.get())
            val before=serverDir(id).takeIf {it.exists()}?.let {base->
                FileInventory.collectCached(base,scanStats) {count,bytes->progress(listing(count,bytes))}.let {(f,d)->RunnerFileManifest(f,d)}
            } ?: RunnerFileManifest(emptyMap(),emptySet())
            progress(FolderImportProgress("SCANNING",before.files.size,before.files.values.sumOf {it.size},message="관리 서버 목록과 비교해 받을 파일을 정하고 있습니다",
                step="달라진 파일 비교",stepNo=3,stepTotal=3,reused=scanStats.reused.get(),hashed=scanStats.hashed.get()),true)
            val existing=before.files
            val changed=manifest.files.filter {(path,file)->existing[path]!=file};val total=changed.values.sumOf {it.size};var downloaded=0L;var completed=0
            progress(FolderImportProgress(if(changed.isEmpty())"VERIFYING" else "UPLOADING",0,0,changed.size,total,"동일한 파일은 재사용하고 변경 파일만 다운로드합니다"),true)
            val progressLock=Any()
            fun advanced(bytes:Long=0,done:Int=0)=synchronized(progressLock){downloaded+=bytes;completed+=done;progress(FolderImportProgress("UPLOADING",completed,downloaded,changed.size,total))}
            val fast=fastTransfer(id)
            val groups=if(fast)TransferBatch.groups(changed.keys){changed.getValue(it).size} else changed.keys.map {listOf(it)}
            TransferBatch.parallel(groups) {group->
                if(fast && group.all {changed.getValue(it).size<=TransferBatch.SMALL_FILE}) {
                    val response=http("POST","/api/runners/transfer/$id/files",HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(group)),"application/json")
                    transferResponse(response,"파일 묶음 다운로드 대기")
                    val parts=TransferBatch.decode(response.body())
                    check(parts.map {it.path}.toSet()==group.toSet() && parts.size==group.size)
                    parts.forEach {part->check(part.offset==0L && part.bytes.size.toLong()==changed.getValue(part.path).size)}
                    parts.forEach {part->val target=FileInventory.safePath(stage,part.path);target.parent.createDirectories();target.writeBytes(part.bytes)}
                    advanced(parts.sumOf {it.bytes.size.toLong()},parts.size)
                } else group.forEach {relative->
                    val file=changed.getValue(relative);val target=FileInventory.safePath(stage,relative);target.parent.createDirectories()
                    target.outputStream().use {output->var offset=0L
                        while(offset<file.size){
                            val part=http("GET","/api/runners/transfer/$id/file?path=${encode(relative)}&offset=$offset")
                            transferResponse(part,"서버 파일 다운로드 대기");check(part.body().isNotEmpty() && part.body().size<=minOf(4L*1024*1024,file.size-offset)) {"서버 파일 크기가 변경됐습니다"}
                            output.write(part.body());offset+=part.body().size;advanced(part.body().size.toLong())
                        }
                    };advanced(done=1)
                }
            }
            progress(FolderImportProgress("VERIFYING",completed,downloaded,changed.size,total,"실행기에서 검증한 파일을 반영하고 있습니다"),true)
            val current=serverDir(id)
            current.createDirectories()
            DeltaTransfer.apply(current,stage,before,manifest,diagnosticRoot(id)?.resolve("transfer-backups") ?: folders.transferHistory(id))
            if(diagnosticRoot(id)==null && folders.custom(id))folders.published(id,manifest)
        } finally { if(stage.exists())stage.toFile().deleteRecursively() }
    }
    private fun startProcess(instance:Instance,onSpawned:()->Unit) { synchronized(lifecycle) {
        updateCheckpoint()
        val id=instance.id
        val current=processes.values.filter { it.isAlive }.sumOf { p -> configs.values.firstOrNull { processes[it.id]===p }?.memoryMb?.toLong() ?: 0 }
        val os=java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val limit=maxOf(0L,os.totalMemorySize/(1024*1024)-maxOf(1024L,os.totalMemorySize/(1024*1024)/10)-current)
        require(instance.memoryMb.toLong() in 512L..limit) { "실행기 메모리가 부족합니다 (최대 $limit MiB)" }
        val java=javaBin()
        val args=if(instance.diagnosticSession!=null)Launchers.arguments(instance,serverDir(id),java,identity.platform=="WINDOWS") else folders.arguments(id,instance,java,identity.platform=="WINDOWS")
        Launchers.verifyJava(java,if(instance.diagnosticSession!=null)Launchers.requiredJava(instance,serverDir(id)) else folders.requiredJava(id,instance))
        ServerSocket().use {it.bind(InetSocketAddress("127.0.0.1",instance.port))}
        val properties=serverDir(id).resolve("server.properties")
        val values=Properties();properties.inputStream().use { values.load(it) }
        values.setProperty("server-ip","127.0.0.1");values.setProperty("server-port",instance.port.toString())
        val reservedUdp=queryPorts.filterKeys {it!=id}.values.toSet()+configs.values.filter {it.id!=id}.map {it.port}+
            (hosted?.reservedUdpPorts() ?: directRelay.reservedUdpPorts())
        queryPorts[id]=QueryEndpoint.configure(values,instance.port,reservedUdp)
        properties.outputStream().use { values.store(it,"Server Manager Runner") }
        ready.remove(id)
        activity[id]=AtomicLong()
        val crashesBefore=CrashReports.snapshot(serverDir(id))
        instanceRoot(id).resolve("exit.json").deleteIfExists()
        if(hosted==null)directRelay.prepareVoicechat(id,serverDir(id))
        val builder=ProcessBuilder(args).directory(serverDir(id).toFile()).redirectErrorStream(true)
        val udpStatus=runCatching {
            val resident=hosted
            if(directSable) {
                val environment=resident?.prepareDirectSable(instance,serverDir(id)) ?: directRelay.prepareSable(instance,serverDir(id));builder.environment().putAll(environment.filterKeys {it.startsWith("SM_SABLE_BRIDGE_")});environment["status"] ?: "TCP_FALLBACK"
            } else sable.prepare(instance,serverDir(id),builder)
        }.getOrElse {"UDP 초기화 실패 · TCP 대체 통신을 사용합니다"}
        send(RunnerMessage("sable-status",id,detail=udpStatus))
        val process=hosted?.launch(id,args,serverDir(id),builder.environment().toMap(),instance.port) ?: builder.start()
        onSpawned()
        processes[id]=process
        dirty(id).writeText("${process.pid()}|${process.info().startInstant().orElse(Instant.now()).toEpochMilli()}")
        send(RunnerMessage("state",id,"STARTING",version(),"원격 Minecraft 서버 시작",pid=process.pid()))
        watch(id,instance,process,crashesBefore,fresh=true)
    }
    }
    /** Reads console output, reports state and finishes the stop/handoff path. Shared by fresh starts and re-attached hosted games. */
    private fun watch(id:String,instance:Instance,process:Process,crashesBefore:Map<String,String>,fresh:Boolean) {
        workers.submit {
            val log=diagnosticRoot(id)?.resolve("server/logs/server-manager-console.log") ?: instanceRoot(id).resolve("console.log")
            log.parent.createDirectories()
            log.toFile().appendText("\n--- ${Instant.now()}"+(if(fresh)"" else " · 상주 서비스에서 다시 연결")+" ---\n")
            val hostedProcess=process as? HostedProcess;val position=instanceRoot(id).resolve("console.seq");var lastSaved=0L
            FileOutputStream(log.toFile(),true).bufferedWriter().use { writer ->
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if(LogNoise.dropped(line))return@forEach
                val kind=if(line.contains("ERROR") || line.contains("Exception")) "error" else if(line.contains("WARN")) "warn" else "output"
                if(line.contains("logged in with entity id")||line.contains("joined the game"))activity[id]?.incrementAndGet()
                if(processes[id]===process)stopMonitors[id]?.log(line)
                writer.appendLine(line);writer.flush()
                hostedProcess?.let {it.markWritten();val now=System.currentTimeMillis();if(now-lastSaved>1000){lastSaved=now;it.savePosition(position)}}
                send(RunnerMessage("log",id,kind,version(),line.take(4096)))
                if(line.contains("Done (") && line.contains("For help") && processes[id]===process) {
                    ready.add(id);if(id !in stopping)send(RunnerMessage("state",id,"RUNNING",version(),pid=process.pid()))
                }
            } } }
            val code=process.waitFor();sable.stopped(id);directRelay.stopped(id);queryPorts.remove(id)
            // Release the host record only after the exit report is durable below; until then the host refuses a second start.
            atomicText(instanceRoot(id).resolve("exit.json"),mapper.writeValueAsString(RunnerExitReport(code,CrashReports.changed(serverDir(id),crashesBefore),if(id in stopTimeouts)"STOP_TIMEOUT" else if(id in idleStops)"IDLE" else null)))
            ready.remove(id)
            stopMonitors.remove(id)?.close();stopTimeouts.remove(id);idleStops.remove(id)
            stopping.remove(id)
            processes.remove(id,process)
            if(process is HostedProcess)hosted?.released(id,process.pid())
            send(RunnerMessage("state",id,"SYNCING",version(),"종료 코드 $code · "+if(instance.syncEnabled())"변경 파일 인계 중" else "동기화 OFF · 실행기 파일 보존"))
            uploadStopped(id)
        }
    }
    private fun sendExitReport(id:String,raw:String):HttpResponse<ByteArray> {
        val report=mapper.readValue<RunnerExitReport>(raw)
        val compatible=if(report.reason=="IDLE") {
            val response=http("GET","/api/runners/transfer/$id/capabilities")
            if(response.statusCode()!=404)transferResponse(response,"종료 사유 지원 확인 대기")
            if(response.statusCode()==200 && mapper.readTree(response.body()).path("exitReasonProtocol").asInt()>=1)report else report.copy(reason=null)
        } else report
        return http("POST","/api/runners/transfer/$id/exit",HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(compatible)),"application/json")
    }
    private fun snapshots(id:String)=StoppedSnapshots(instanceRoot(id).resolve("stopped-snapshots"))
    private fun backgroundEnabled(id:String):Boolean {
        if(configs[id]?.runToken==null || configs[id]?.syncEnabled()!=true || diagnosticRoot(id)!=null)return false
        val response=http("GET","/api/runners/transfer/$id/capabilities")
        transferResponse(response,"종료 복사본 기능 확인 대기")
        return mapper.readTree(response.body()).path("stoppedSnapshotProtocol").asInt()>=1
    }
    private fun uploadSnapshots(id:String) {
        if(sessionToken==null)return
        var reserved=false
        try {
            synchronized(operationLocks.computeIfAbsent(id){Any()}) {
                if(dirty(id).exists() && processes[id]?.isAlive!=true && !orphanAlive(id)) {
                    val exit=instanceRoot(id).resolve("exit.json").takeIf {it.exists()}?.let {mapper.readValue<RunnerExitReport>(it.readText())}
                    send(RunnerMessage("file-progress",id,detail=mapper.writeValueAsString(FolderImportProgress("SCANNING",message="종료 복사본 준비 중 · 준비가 끝나면 바로 다시 시작할 수 있습니다"))))
                    val token=configs[id]?.runToken
                    val previous=snapshots(id).list().lastOrNull {it.ready.runToken==token}
                    val captured=previous ?: snapshots(id).capture(serverDir(id),token,exit,settledEditRevision(id))
                    transferResponse(http("POST","/api/runners/transfer/$id/snapshot-ready",HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(captured.ready)),"application/json"),"종료 복사본 접수 대기")
                    // Durable copy is ready before releasing the original folder for a new process.
                    dirty(id).deleteIfExists()
                }
            }
            if(!reserveUpload(id))return
            reserved=true
            while(true) {
                val snapshot=snapshots(id).list().firstOrNull() ?: break
                transferResponse(http("POST","/api/runners/transfer/$id/snapshot-ready",HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(snapshot.ready)),"application/json"),"종료 복사본 접수 대기")
                val receipt=snapshot.path.resolve("upload.pending")
                val completed=if(receipt.exists()) {
                    val transfer=receipt.readText().trim();require(UUID.fromString(transfer).toString()==transfer)
                    val response=awaitTransfer(id,"복사본 반영 확인 대기") {http("GET",protocolPath("/api/runners/transfer/$id/transfer-state?transferId=$transfer"))}
                    if(mapper.readTree(response.body()).path("state").asText()=="COMPLETE") {
                        awaitTransfer(id,"복사본 완료 확인 대기") {http("POST",protocolPath("/api/runners/transfer/$id/commit?transferId=$transfer"))};true
                    } else false
                } else false
                if(!completed)uploadFiles(id,null,snapshot.path.resolve("server"),snapshot.path,snapshot.ready.generation)
                synchronized(operationLocks.computeIfAbsent(id){Any()}) {
                    val last=snapshots(id).list().size==1
                    if(last && processes[id]?.isAlive!=true && !orphanAlive(id) && !dirty(id).exists()) {
                        // A newer live edit must never be erased by completion of an older copy.
                        val marker=localEdits(id)
                        if(!marker.exists() || marker.readText()==snapshot.ready.localRevision) {
                            val manifest=FileInventory.collectCached(serverDir(id)).let {(f,d)->RunnerFileManifest(f,d)}
                            val copied=FileInventory.collectCached(snapshot.path.resolve("server")).let {(f,d)->RunnerFileManifest(f,d)}
                            if(manifest==copied){folders.published(id,manifest);marker.deleteIfExists()}
                        }
                    }
                    snapshots(id).remove(snapshot)
                }
            }
        } catch(e:Exception) {
            // Runtime state belongs to the current game; background failures must not turn it into SYNC_FAILED.
            if(dirty(id).exists() && processes[id]?.isAlive!=true) {
                send(RunnerMessage("file-progress",id,detail=mapper.writeValueAsString(FolderImportProgress("FAILED",message="종료 복사본 준비 실패: "+e.message.orEmpty().take(300)))))
                send(RunnerMessage("state",id,"SYNC_FAILED",version(),"종료 복사본을 준비하지 못했습니다. 원본은 보존했습니다."))
            }
            send(RunnerMessage("log",id,"warn",version(),"종료 복사본 전송 대기: "+e.message.orEmpty().take(300)))
            snapshots(id).list().firstOrNull()?.let {s->runCatching {http("POST","/api/runners/transfer/$id/snapshot-progress?generation=${s.ready.generation}",HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(FolderImportProgress("RETRYING",message=e.message.orEmpty().take(300)))),"application/json")}}
        }finally{if(reserved)uploading.remove(id)}
    }
    private fun uploadStopped(id:String) {
        if(snapshots(id).list().isNotEmpty() || runCatching {backgroundEnabled(id)}.getOrDefault(false)){uploadSnapshots(id);return}

        if(!dirty(id).exists() || processes[id]?.isAlive==true || orphanAlive(id) || sessionToken==null || !reserveUpload(id))return
        synchronized(operationLocks.computeIfAbsent(id){Any()}) { try {
            if(configs[id]?.syncEnabled()==false) {
                check(!instanceRoot(id).resolve("upload.pending").exists()) {"이전 인계를 먼저 완료하세요"}
                instanceRoot(id).resolve("exit.json").takeIf {it.exists()}?.let {exit->transferResponse(sendExitReport(id,exit.readText()),"서버 종료 보고 대기")}
                dirty(id).deleteIfExists();send(RunnerMessage("state",id,"STOPPED",version(),"동기화 OFF · 원본 파일은 현재 실행기에 있습니다"));return
            }
            val receipt=instanceRoot(id).resolve("upload.pending")
            if(receipt.exists()) {
                val transfer=receipt.readText().trim();require(UUID.fromString(transfer).toString()==transfer)
                val result=awaitTransfer(id,"파일 반영 확인 대기",allowMissing=true) {http("GET",protocolPath("/api/runners/transfer/$id/transfer-state?transferId=$transfer"))}
                // Old managers have no receipt endpoint; they retain the previous behavior.
                if(result.statusCode()!=404) {
                    transferResponse(result,"파일 반영 확인 대기")
                    if(mapper.readTree(result.body()).path("state").asText()=="COMPLETE") {
                        awaitTransfer(id,"반영 완료 확인 대기") {http("POST",protocolPath("/api/runners/transfer/$id/commit?transferId=$transfer"))}
                        val response=awaitTransfer(id,"파일 반영 검증 대기") {http("GET",protocolPath("/api/runners/transfer/$id/manifest"))}
                        val central=mapper.readValue<RunnerFileManifest>(response.body())
                        check(FileInventory.collectCached(serverDir(id)).let {(f,d)->RunnerFileManifest(f,d)}==central) {"반영 완료 후 파일이 변경됐습니다. 로컬 파일을 보존하고 확인하세요"}
                        if(diagnosticRoot(id)==null)folders.published(id,central)
                        dirty(id).deleteIfExists();receipt.deleteIfExists();send(RunnerMessage("state",id,"STOPPED",version(),"이미 완료된 파일 인계를 확인했습니다"));return
                    }
                }
            }
            // A separate authenticated request arrives before commit, independent of WSS ordering.
            instanceRoot(id).resolve("exit.json").takeIf {it.exists()}?.let {exit->
                val response=sendExitReport(id,exit.readText())
                if(response.statusCode()!=404)transferResponse(response,"서버 종료 정보 전송 대기")
            }
            uploadFiles(id,null)
            dirty(id).deleteIfExists()
            send(RunnerMessage("state",id,"STOPPED",version(),"서버 파일 인계 완료"))
        } catch(e:Exception) {
            send(RunnerMessage("file-progress",id,detail=mapper.writeValueAsString(FolderImportProgress(if(e is RetryTransfer)"RETRYING" else "FAILED",message=e.message.orEmpty().take(500)))))
            send(RunnerMessage("state",id,"SYNC_FAILED",version(),e.message?.take(300) ?: "서버 파일 인계 실패"))
        }
        finally { uploading.remove(id) } }
    }
    private fun uploadFiles(id:String,request:String?,source:Path=serverDir(id),receiptRoot:Path=instanceRoot(id),generation:String?=null) {
            var lastProgress=0L
            fun progress(value:FolderImportProgress,force:Boolean=false) {
                val now=System.nanoTime();if(!force && now-lastProgress<1_000_000_000)return;lastProgress=now
                if(generation==null)send(RunnerMessage(if(request==null)"file-progress" else "import-progress",id,detail=mapper.writeValueAsString(value),token=request))
            }
            progress(FolderImportProgress("SCANNING",message="원래 폴더의 파일 목록을 만들고 있습니다. 이전에 검사한 파일은 다시 읽지 않습니다",step="원래 폴더 파일 목록 작성",stepNo=1,stepTotal=3),true)
            val snapshot=FileInventory.snapshot(source)
            val scanStats=FileInventory.ScanStats()
            val (files,dirs)=FileInventory.collectCached(source,scanStats) {count,bytes->updateCheckpoint()
                progress(FolderImportProgress("SCANNING",count,bytes,message="원래 폴더의 파일 목록을 만들고 있습니다. 이전에 검사한 파일은 다시 읽지 않습니다",
                    step="원래 폴더 파일 목록 작성",stepNo=1,stepTotal=3,reused=scanStats.reused.get(),hashed=scanStats.hashed.get()))}
            val manifest=RunnerFileManifest(files,dirs)
            progress(FolderImportProgress("SCANNING",files.size,files.values.sumOf {it.size},message="관리 서버가 이어받기를 지원하는지 확인하고 있습니다",
                step="관리 서버 연결 확인",stepNo=2,stepTotal=3,reused=scanStats.reused.get(),hashed=scanStats.hashed.get()),true)
            val capability=awaitTransfer(id,"이어받기 지원 확인 대기",allowMissing=true) {http("GET",protocolPath("/api/runners/transfer/$id/transfer-state?transferId=00000000-0000-0000-0000-000000000000"))}
            val resumable=if(capability.statusCode()==404)false else {transferResponse(capability,"이어받기 지원 확인 대기");true}
            val body=mutableMapOf<String,Any>("manifest" to manifest)
            if(request!=null)body["importRequest"]=request
            if(generation!=null)body["generation"]=generation
            if(resumable)body["resumeProtocol"]=1
            check(FileInventory.snapshot(source)==snapshot) {"파일 검사 중 원본이 변경됐습니다. 받은 파일은 보존했습니다"}
            val encoded=mapper.writeValueAsBytes(body)
            val response=awaitTransfer(id,"파일 인계 준비 대기",{
                progress(FolderImportProgress("SCANNING",files.size,files.values.sumOf {it.size},message="관리 서버가 중앙 파일과 비교해 전송할 파일을 정하고 있습니다. 원본 목록 결과를 재사용합니다",
                    step="관리 서버 비교·전송 계획",stepNo=3,stepTotal=3,reused=scanStats.reused.get(),hashed=scanStats.hashed.get()))
            }) {http("POST",protocolPath("/api/runners/transfer/$id/begin"),HttpRequest.BodyPublishers.ofByteArray(encoded),"application/json")}
            check(FileInventory.snapshot(source)==snapshot) {"전송 대기 중 원본이 변경됐습니다. 원본 파일을 보존하고 확인하세요"}
            val plan=mapper.readValue<RunnerTransferPlan>(response.body())
            if(request==null)atomicText(receiptRoot.resolve("upload.pending"),plan.transferId)
            val total=if(plan.totalFiles>0)plan.totalBytes else plan.changedFiles.sumOf {files.getValue(it).size};val totalFiles=if(plan.totalFiles>0)plan.totalFiles else plan.changedFiles.size
            var uploaded=plan.completedBytes;var completed=plan.completedFiles
            val progressLock=Any()
            fun advanced(bytes:Long=0,done:Int=0)=synchronized(progressLock){uploaded+=bytes;completed+=done;progress(FolderImportProgress("UPLOADING",completed,uploaded,totalFiles,total))}
            progress(FolderImportProgress("UPLOADING",completed,uploaded,totalFiles,total),true)
            val fast=fastTransfer(id)
            val groups=if(fast)TransferBatch.groups(plan.changedFiles){files.getValue(it).size} else plan.changedFiles.map {listOf(it)}
            TransferBatch.parallel(groups) {group->
                updateCheckpoint()
                if(fast && group.all {files.getValue(it).size<=TransferBatch.SMALL_FILE}) {
                    val parts=group.map {path->
                        val offset=plan.offsets[path] ?: 0L
                        FileInventory.safePath(source,path).inputStream().use {input->input.skipNBytes(offset);TransferPart(path,offset,input.readNBytes(TransferBatch.SMALL_FILE+1))}
                    }
                    updateCheckpoint()
                    transferResponse(http("PUT","/api/runners/transfer/$id/batch?transferId=${plan.transferId}",HttpRequest.BodyPublishers.ofByteArray(TransferBatch.encode(parts)),"application/octet-stream"),"묶음 전송 대기")
                    advanced(parts.sumOf {it.bytes.size.toLong()},parts.size)
                } else group.forEach {path->
                    val file=FileInventory.safePath(source,path)
                    file.inputStream().use {input->
                        var offset=plan.offsets[path] ?: 0L
                        require(offset in 0..files.getValue(path).size);input.skipNBytes(offset)
                        while(true){updateCheckpoint();val bytes=input.readNBytes(4*1024*1024);if(bytes.isEmpty())break
                            val part=http("PUT","/api/runners/transfer/$id/chunk?transferId=${plan.transferId}&path=${encode(path)}&offset=$offset",HttpRequest.BodyPublishers.ofByteArray(bytes),"application/octet-stream")
                            transferResponse(part,"파일 청크 전송 대기");offset+=bytes.size;advanced(bytes.size.toLong())
                        }
                    };advanced(done=1)
                }
            }
            if(request!=null) {
                folders.checkWorldStopped(id)
                check(FileInventory.snapshot(source)==snapshot) { "가져오는 동안 원래 파일이 변경됐습니다. 중앙 파일은 교체하지 않았습니다. 서버가 정지됐는지 확인하고 다시 시도하세요" }
            }
            progress(FolderImportProgress("VERIFYING",completed,uploaded,totalFiles,total,"관리 서버에서 해시 검증·반영 중입니다. 대용량 폴더는 시간이 걸릴 수 있습니다"),true)
            awaitTransfer(id,"서버 파일 반영 확인 대기",{
                progress(FolderImportProgress("VERIFYING",completed,uploaded,totalFiles,total,"중앙 검증·반영 중 · 받은 파일은 재전송하지 않습니다"))
            }) {http("POST",protocolPath("/api/runners/transfer/$id/commit?transferId=${plan.transferId}"))}
            if(generation==null)receiptRoot.resolve("upload.pending").deleteIfExists()
            if(generation==null && diagnosticRoot(id)==null)folders.published(id,manifest)
    }
    private fun stop(id:String):Boolean=synchronized(lifecycle) {
        val process=processes[id] ?: return false
        if(!runCatching {process.outputStream.write("stop\n".toByteArray());process.outputStream.flush()}.isSuccess)return false
        stopping.add(id)
        stopMonitors.computeIfAbsent(id) {
            StopMonitor(process,serverDir(id),{processes[id]===process&&id in stopping}) {
                synchronized(lifecycle) {
                    if(processes[id]===process&&process.isAlive&&id in stopping) {
                        stopTimeouts.add(id)
                        send(RunnerMessage("stop-progress",id,pid=process.pid(),detail=mapper.writeValueAsString(StopWatchStatus(0,"3분 동안 종료 저장 진전 없음 · 자동 강제 종료",true))))
                        process.descendants().use {children->children.forEach {runCatching {it.destroyForcibly()}}};process.destroyForcibly()
                    }
                }
            }
        }
        send(RunnerMessage("state",id,"STOPPING",version(),"저장 후 종료 중",pid=process.pid()))
        return true
    }
    private fun forceStop(id:String,expectedPid:Long):Boolean=synchronized(lifecycle) {
        // Only terminate this agent's tracked process, never a PID supplied to the OS.
        val process=processes[id] ?: return false
        if(expectedPid<=0 || !process.isAlive || process.pid()!=expectedPid)return false
        stopping.add(id);idleStops.remove(id)
        send(RunnerMessage("state",id,"STOPPING",version(),"사용자 요청으로 강제 종료 중 · 저장되지 않은 변경은 손실될 수 있습니다",pid=expectedPid))
        process.descendants().use {children->children.forEach {child->runCatching {child.destroyForcibly()}}}
        process.destroyForcibly()
        // The normal process waiter confirms exit, records metadata, then uploads.
        // Keep processes/dirty/lease intact until that path has finished.
        true
    }
    private fun stopIfEmpty(id:String,requestId:String) {
        val process=processes[id];val counter=activity[id];val before=counter?.get()
        val config=configs[id]
        val count=if(process?.isAlive==true&&id in ready&&id !in stopping&&config!=null)queryPorts[id]?.let(query::currentCount) else null
        val accepted=synchronized(lifecycle) {
            if(process==null||counter==null||processes[id]!==process||!process.isAlive||id !in ready||id in stopping||counter.get()!=before||count!=0)false
            else {idleStops.add(id);stop(id).also {if(!it)idleStops.remove(id)}}
        }
        send(RunnerMessage("idle-result",instanceId=id,state=if(accepted)"ACCEPTED" else "DENIED",detail=requestId))
    }
    private fun command(id:String,value:String) {
        if(value.isBlank() || value.length>4096 || value.contains('\n') || value.contains('\r')) return
        processes[id]?.takeIf { it.isAlive }?.let { runCatching { it.outputStream.write((value+"\n").toByteArray());it.outputStream.flush() } }
    }
    private fun report(id:String,process:Process) {
        if(!process.isAlive)return
        stopMonitors[id]?.let {monitor->send(RunnerMessage("stop-progress",id,pid=process.pid(),detail=mapper.writeValueAsString(monitor.status())))}
        val sample=metrics.sample(process.toHandle())
        val players=queryPorts[id]?.let(query::players)
        send(RunnerMessage("query-status",id,state=if(id in ready && players==null)"UNKNOWN" else "KNOWN"))
        send(RunnerMessage("state",id,if(id in stopping)"STOPPING" else if(id in ready)"RUNNING" else "STARTING",version(),pid=process.pid(),cpuPercent=sample.cpuPercent,rssMb=sample.rssMb,
            uptimeSeconds=sample.uptimeSeconds,playerCount=players?.count,players=players?.names))
    }
    private fun recoverStates() {
        home.resolve("instances").takeIf {it.exists()}?.listDirectoryEntries()?.filter {it.isDirectory() && it.resolve("stopped-snapshots").isDirectory()}?.forEach {dir->
            if(snapshots(dir.name).list().isNotEmpty())workers.submit {uploadSnapshots(dir.name)}
        }

        processes.forEach { (id,process)->report(id,process) }
        home.resolve("instances").takeIf { it.exists() }?.let { root -> Files.list(root).use { dirs -> dirs.filter { it.isDirectory() && (it.resolve("dirty").exists() && processes[it.fileName.toString()]?.isAlive!=true || snapshots(it.fileName.toString()).list().isNotEmpty()) }.forEach { dir ->
            val id=dir.fileName.toString()
            if(orphanAlive(id))send(RunnerMessage("state",id,"UNKNOWN",version(),"실행기 재시작 전 Minecraft 프로세스가 계속 실행 중입니다"))
            else workers.submit { uploadStopped(id) }
        } } }
    }
    companion object { fun version()=RunnerAgent::class.java.getResourceAsStream("/app-version.txt")?.bufferedReader()?.use { it.readText().trim() } ?: "dev" }
}
