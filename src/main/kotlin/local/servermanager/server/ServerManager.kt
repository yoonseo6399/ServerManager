package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Scheduled
import java.nio.file.*
import java.time.Instant
import java.util.UUID
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.io.path.*

data class Instance(val id: String, var name: String, val loader: String = "NeoForge", val minecraft: String = "1.21.1", var loaderVersion: String, var port: Int, var memoryMb: Int, var javaPath: String, var runnerId:String?=null, @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) val diagnosticSession:String?=null, @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) val fileSyncEnabled:Boolean?=null, @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) val runToken:String?=null) {fun syncEnabled()=fileSyncEnabled!=false}
data class CreateRequest(val name: String, val loaderVersion: String, val port: Int = 25565, val memoryMb: Int = 2048, val javaPath: String = System.getProperty("java.home") + "/bin/java", val loader:String="NeoForge",val minecraft:String="1.21.1")
data class Event(val seq: Long, val time: String, val kind: String, val text: String)
data class FileEntry(val name: String, val path: String, val directory: Boolean, val size: Long)
data class MemoryCapacity(val totalMb: Long, val freeMb: Long, val reservedMb: Long, val maxHeapMb: Long, val committedHeapMb: Long, val startableHeapMb: Long)
data class ServerSignal(val instanceId:String, val kind:String, val detail:String, val exit:ServerExit?=null, val error:DiscordErrorSummary?=null,val startActor:StartActor?=null)
interface RemoteController {
    fun workspace(instance:Instance,request:WorkspaceRequest):com.fasterxml.jackson.databind.JsonNode {error("실행기를 업데이트하세요")}
    fun supportsWorkspace(instance:Instance):Boolean=false
    fun supportsLiveFiles(instance:Instance):Boolean=false
    fun install(instance:Instance,requestId:String) {error("실행기를 업데이트하세요")}
    fun start(instance:Instance)
    fun stop(instance:Instance)
    fun supportsForceStop(instance:Instance):Boolean=false
    fun forceStop(instance:Instance,pid:Long) {error("원격 강제 종료에는 실행기 0.4.7 이상이 필요합니다")}
    fun restart(instance:Instance)
    fun command(instance:Instance,value:String)
    fun completed(instance:Instance) {}
    fun sharedGamePort():Int?=null
    fun sableStatus(instance:Instance):SableRelayStatus?=null
    fun stopIfEmpty(instance:Instance,requestId:String):Boolean=false
    fun importFolder(instance:Instance,requestId:String) {error("실행기를 업데이트한 뒤 기존 폴더를 가져오세요")}
}

class RuntimeState {
    @Volatile var process: Process? = null
    @Volatile var recoveringFiles=false
    var state = "NEW"
    var seq = 0L
    val events = ArrayDeque<Event>()
    @Volatile var resources: ResourceSample? = null
    @Volatile var players: PlayerSample? = null
    @Volatile var playerQueryInProgress = false
    @Volatile var queryPort:Int?=null
    @Volatile var playerQueryMessage:String?=null
    @Volatile var remoteStopRequested = false
    @Volatile var remoteRestartRequested = false
    @Volatile var remoteWorkerReady = false
    @Volatile var remoteCloudReady = false
    @Volatile var remotePid:Long? = null
    @Volatile var diagnosticActive=false
    @Volatile var diagnosticSession:String?=null
    @Volatile var remoteFileBusy=false
    @Volatile var modBusy=false
    @Volatile var cloudSyncActive=false
    @Volatile var activityVersion=0L
    @Volatile var stopReason:String?=null
    @Volatile var idleCheckPending=false
    var idleRequestId:String?=null
    var idleRequestAt=0L
    val transferMeter=TransferMeter()
    var transferAuthoritative=false
    var folderSource:Map<String,String>?=null
    var folderImport:FolderImportProgress?=null
    var fileTransfer:FolderImportProgress?=null
    val discordError=DiscordErrorBuffer()
    @Volatile internal var stopMonitor:StopMonitor?=null
    @Volatile var remoteStopProgress:StopWatchStatus?=null
    var startActor:StartActor?=null
    var restartActor:StartActor?=null
    var startNotified=false
    var lastExit:ServerExit?=null
    var exitReport:RunnerExitReport?=null
    var exitError:DiscordErrorSummary?=null
    var exitErrorCaptured=false
    var crashesBefore:Map<String,String> = emptyMap()
    var remoteConsoleWriteFailed=false
    fun event(kind: String, text: String) = synchronized(this) {
        events.addLast(Event(++seq, Instant.now().toString(), kind, text))
        while(events.size > 10000) events.removeFirst()
    }
}

@Service
class ServerManager {
    val root: Path = Path.of(System.getenv("SERVER_MANAGER_DATA") ?: ".local-data").toAbsolutePath().normalize()
    private val mapper = jacksonObjectMapper()
    private val instances = ConcurrentHashMap<String, Instance>()
    private val runtimes = ConcurrentHashMap<String, RuntimeState>()
    private val workers = Executors.newCachedThreadPool()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(20)).build()
    private val catalog by lazy { VersionCatalog(root.resolve("neoforge-versions.json")) }
    private val software by lazy { LauncherCatalog(root.resolve("launcher-catalogs")) }
    private val installer by lazy { LauncherInstaller(software) }
    private val metrics = ProcessMetrics()
    private val query = MinecraftQuery()
    private val signalListeners = java.util.concurrent.CopyOnWriteArrayList<(ServerSignal)->Unit>()
    @Volatile private var remoteController:RemoteController?=null
    @Volatile private var autoRestartStatus:((String)->AutoRestartStatus)?=null
    fun attachAutoRestartStatus(provider:(String)->AutoRestartStatus){autoRestartStatus=provider}
    @Volatile private var powerStatus:((String)->PowerStatus)?=null
    fun attachPowerStatus(provider:(String)->PowerStatus){powerStatus=provider}
    private val joined=Regex("(?:logged in with entity id|joined the game)")
    private fun playerActivity(r:RuntimeState,line:String){if(joined.containsMatchIn(line))synchronized(r){r.activityVersion++}}
    fun setDiagnosticActive(id:String,active:Boolean){val r=runtime(id);synchronized(r){check(!active||!r.idleCheckPending){"무인 종료 직전 확인이 끝난 뒤 진단을 시작하세요"};r.diagnosticActive=active;r.activityVersion++}}
    fun attachRemoteController(controller:RemoteController) { remoteController=controller }
    fun onSignal(listener:(ServerSignal)->Unit):AutoCloseable { signalListeners.add(listener); return AutoCloseable { signalListeners.remove(listener) } }
    private fun signal(id:String,kind:String,detail:String,exit:ServerExit?=null,error:DiscordErrorSummary?=null) { signalListeners.forEach { listener -> runCatching { listener(ServerSignal(id,kind,detail,exit,error,if(kind=="start")runtime(id).startActor else null)) } } }
    fun automaticRestartError(id:String,detail:String) {runtime(id).event("error",detail);signal(id,"error",detail)}
    private fun started(id:String,r:RuntimeState) { if(!r.startNotified){r.startNotified=true;signal(id,"start","서버 시작을 완료했습니다. 이제 접속할 수 있습니다.")} }
    @Scheduled(fixedDelay = 2000) fun sampleResources() {
        val pids = mutableSetOf<Long>()
        runtimes.forEach { (id,r) ->
            if(instances[id]?.runnerId!=null) return@forEach
            val process = r.process
            r.resources = if (process?.isAlive == true) { pids.add(process.pid()); metrics.sample(process.toHandle()) } else null
        }
        metrics.retain(pids)
    }
    @Scheduled(fixedDelay = 10000) fun samplePlayers() {
        instances.values.forEach { i ->
            val r=runtimes[i.id] ?: return@forEach
            if(i.runnerId!=null) return@forEach
            if(r.process?.isAlive!=true || r.state!="RUNNING") { r.players=null;return@forEach }
            if(r.playerQueryInProgress) return@forEach
            r.playerQueryInProgress=true
            workers.submit {
                try { r.players=r.queryPort?.let(query::players);r.playerQueryMessage=if(r.players==null)"플레이어 조회 응답이 없습니다. 인원은 불명이며 무인 자동 종료를 보류합니다." else null }
                finally { r.playerQueryInProgress=false }
            }
        }
    }
    init {
        root.createDirectories()
        Files.list(root).use { dirs -> dirs.filter { it.resolve("instance.json").exists() }.forEach {
            val instance: Instance = mapper.readValue(it.resolve("instance.json").readText())
            serverDir(instance.id).createDirectories()
            instances[instance.id] = instance
            val r=RuntimeState()
            if(root.resolve(instance.id).resolve("remote-lease").exists()) {
                val actorFile=root.resolve(instance.id).resolve("start-actor.json")
                if(actorFile.isRegularFile() && !Files.isSymbolicLink(actorFile) && actorFile.fileSize()<=16384)r.startActor=runCatching {mapper.readValue<StartActor>(actorFile.readText())}.getOrNull()
            }
            root.resolve(instance.id).resolve("diagnostic.active").takeIf {it.exists()}?.readText()?.trim()?.let {sid->
                require(UUID.fromString(sid).toString()==sid);r.diagnosticSession=sid;r.diagnosticActive=true
            }
            fun initialState()=when {root.resolve(instance.id).resolve("remote-lease").exists()->"UNKNOWN";root.resolve(instance.id).resolve("import.pending").takeIf {it.exists()}?.readText()?.let {it!="mod-diagnostic:${r.diagnosticSession}" && it!="mod-toggle"}==true->"IMPORT_FAILED";root.resolve(instance.id).resolve("installed.ok").exists()->"STOPPED";else->"NEW"}
            val profile=r.diagnosticSession?.let {root.resolve(instance.id).resolve("diagnostics").resolve(it)} ?: root.resolve(instance.id)
            val history=profile.resolve("runner-backups")
            val modHistory=root.resolve(instance.id).resolve("mod-backups")
            val diagnosticMods=profile.resolve("mod-backups")
            val recover=history.exists() || modHistory.exists() || diagnosticMods.exists() || root.resolve(instance.id).resolve("import.pending").takeIf {it.exists()}?.readText()=="mod-toggle"
            r.recoveringFiles=recover;r.state=if(recover)"SYNCING" else initialState()
            runtimes[instance.id]=r
            if(recover)workers.submit {
                try {
                    DeltaTransfer.recover(serverDir(instance.id),history)
                    if(r.diagnosticActive && serverDir(instance.id).resolve("mods").exists())DeltaTransfer.recover(serverDir(instance.id).resolve("mods"),diagnosticMods)
                    originalServerDir(instance.id).resolve("mods").takeIf {it.exists()}?.let {DeltaTransfer.recover(it,modHistory)}
                    root.resolve(instance.id).resolve("import.pending").takeIf {it.exists() && it.readText()=="mod-toggle"}?.deleteIfExists()
                    synchronized(r) {r.state=initialState();r.recoveringFiles=false;r.event("system","파일 전송 복구 검사를 완료했습니다")}
                } catch(e:Exception) {synchronized(r) {r.state="SYNC_FAILED";r.recoveringFiles=false;r.event("error","파일 전송 복구 실패: ${e.message?.take(300)}")}}
            }
        } }
    }
    fun instance(id: String): Instance = instances[id] ?: throw IllegalArgumentException("인스턴스를 찾을 수 없습니다")
    fun runtime(id: String): RuntimeState { instance(id); return runtimes.getValue(id) }
    fun profileDir(id:String):Path = runtimes[id]?.diagnosticSession?.let {root.resolve(id).resolve("diagnostics").resolve(it)} ?: root.resolve(id)
    fun serverDir(id: String): Path = profileDir(id).resolve("server")
    fun originalServerDir(id:String):Path = root.resolve(id).resolve("server")
    fun reserveMods(id:String,diagnostic:Boolean=false) {val r=runtime(id);synchronized(r){
        check(instance(id).syncEnabled()) {"동기화 OFF에서는 중앙 복사본으로 모드 검사를 할 수 없습니다. 실행기 파일 관리자에서 수정하세요"};checkIdle(r,diagnostic);check(!r.cloudSyncActive) {"운영 클라우드 동기화가 끝난 뒤 검사하세요"};check(r.state in setOf("STOPPED","FAILED","NEW")) {"서버와 파일 인계를 먼저 완료하세요"}
        check(!root.resolve(id).resolve("remote-lease").exists() && !root.resolve(id).resolve("folder-import.pending").exists()) {"파일 인계가 진행 중입니다"}
        if(instance(id).runnerId!=null && r.lastExit!=null)check(r.remoteWorkerReady) {"실행기가 파일 인계를 확인하고 있습니다. 잠시 후 다시 시도하세요"}
        r.modBusy=true
    }}
    fun releaseMods(id:String){runtime(id).modBusy=false}
    /** True while edits are impossible (running, transferring, another mod job). The mod list can still be read without reserving files. */
    fun modsViewOnly(id:String):Boolean=runCatching {reserveMods(id);releaseMods(id)}.isFailure
    fun diagnosticContext(id:String,sid:String?) {val r=runtime(id);synchronized(r){
        check(r.modBusy && r.process?.isAlive!=true && !root.resolve(id).resolve("remote-lease").exists())
        val marker=root.resolve(id).resolve("diagnostic.active");val guard=root.resolve(id).resolve("import.pending")
        if(sid!=null){require(UUID.fromString(sid).toString()==sid);check(!guard.exists() || guard.readText()=="mod-diagnostic:$sid");guard.writeText("mod-diagnostic:$sid");atomicText(marker,sid)}
        else {check(!guard.exists() || guard.readText()=="mod-diagnostic:${r.diagnosticSession}");marker.deleteIfExists();guard.deleteIfExists()}
        r.diagnosticSession=sid;r.diagnosticActive=sid!=null;r.activityVersion++
    }}
    private fun save(i: Instance) {
        val file = root.resolve(i.id).resolve("instance.json")
        val tmp = file.resolveSibling("instance.json.tmp")
        tmp.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(i))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    fun <T> withRemoteFiles(id:String,write:Boolean=true,action:()->T):T {
        val r=runtime(id)
        synchronized(r){
            check(!r.remoteFileBusy && !r.modBusy && !r.recoveringFiles && !r.diagnosticActive) {"다른 파일 작업 또는 복구가 진행 중입니다"}
            check(r.state !in setOf("DELETED","INSTALLING","IMPORTING")) {"설치·가져오기가 끝난 뒤 변경하세요"}
            check(!folderImportMarker(id).exists()) {"최초 폴더 가져오기가 진행 중입니다"}
            r.remoteFileBusy=true
            if(write && instance(id).syncEnabled())atomicText(root.resolve(id).resolve("runner-edits.pending"),UUID.randomUUID().toString())
        }
        try{return action()}finally{r.remoteFileBusy=false}
    }
    fun liveFilesAvailable(id:String)=instance(id).runnerId!=null && remoteController?.supportsLiveFiles(instance(id))==true
    fun remoteWorkspace(id:String,request:WorkspaceRequest):com.fasterxml.jackson.databind.JsonNode {
        val i=instance(id);check(i.runnerId!=null) {"실행기를 선택하세요"}
        check(!i.syncEnabled() || remoteController?.supportsLiveFiles(i)==true) {"원격 파일·모드 관리에는 실행기를 새 버전으로 업데이트하세요"}
        return (remoteController ?: error("실행기 연결이 없습니다")).workspace(i.copy(),request.copy(config=i.copy(),mutationToken=if(liveFilesAvailable(id) && request.op !in setOf("LIST","STAT","READ","LOG_LIST","LOG_PAGE","LOG_WINDOW","MOD_SCAN"))root.resolve(id).resolve("runner-edits.pending").takeIf {it.exists()}?.readText() else null))
    }
    @Synchronized fun setFileSync(id:String,enabled:Boolean):Map<String,Any?> {
        val r=runtime(id);synchronized(r){
            checkIdle(r);val i=instance(id);require(i.runnerId!=null) {"먼저 서버 실행기를 선택하세요"}
            check(remoteController?.supportsWorkspace(i)==true) {"실행기를 0.4.5 이상으로 업데이트하고 연결하세요"}
            if(enabled==i.syncEnabled())return view(id)
            if(enabled){importRemoteFolder(id,true);return view(id)}
            check(!root.resolve(id).resolve("remote-lease").exists() && !folderImportMarker(id).exists()) {"파일 인계를 완료한 뒤 동기화를 끄세요"}
            instances[id]=i.copy(fileSyncEnabled=false);save(instance(id));r.fileTransfer=null;r.folderImport=null
            r.event("system","파일 동기화 OFF · 현재 실행기의 원본 폴더만 사용합니다")
        };return view(id)
    }
    fun list() = instances.values.sortedBy { it.name }.map { view(it.id) }
    @Synchronized fun <T> withIdle(action:()->T):T {
        check(list().all { it["pid"]==null && it["diagnosticActive"]!=true && it["modBusy"]!=true && it["state"] in setOf("NEW","STOPPED","FAILED","IMPORT_FAILED") }) { "모든 서버와 작업을 종료한 뒤 네트워크 설정을 변경하세요" }
        return action()
    }
    @Synchronized fun <T> withStopped(id:String,action:()->T):T {
        val r=runtime(id)
        synchronized(r) {checkIdle(r);check(r.state in setOf("NEW","STOPPED","FAILED","IMPORT_FAILED"));return action()}
    }
    fun capacity(excluding: String? = null): MemoryCapacity {
        val os=java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val total=os.totalMemorySize / (1024*1024)
        val reserve=maxOf(1024L, (total+9)/10)
        val max=maxOf(0L,total-reserve)
        val committed=instances.values.filter { it.id!=excluding && runtimes[it.id]?.process?.isAlive==true }.sumOf { it.memoryMb.toLong() }
        return MemoryCapacity(total,os.freeMemorySize/(1024*1024),reserve,max,committed,maxOf(0L,max-committed))
    }
    fun view(id: String): Map<String, Any?> {
        val i = instance(id); val r = runtime(id)
        val result=synchronized(r) { mapOf("config" to i.copy(), "state" to r.state, "pid" to (if(i.runnerId!=null) r.remotePid else r.process?.takeIf { it.isAlive }?.pid()), "resources" to r.resources?.takeIf { (i.runnerId!=null || r.process?.isAlive == true) && (i.runnerId!=null || it.pid == r.process?.pid()) }, "players" to r.players?.takeIf { r.state=="RUNNING" && java.time.Duration.between(Instant.parse(it.sampledAt),Instant.now()).seconds<30 }, "directory" to (if(i.syncEnabled())serverDir(id).toString() else r.folderSource?.get("path") ?: "현재 실행기의 원본 서버 폴더"), "eulaAccepted" to eulaAccepted(id),"activityVersion" to r.activityVersion,"diagnosticActive" to r.diagnosticActive,"modBusy" to (r.modBusy||r.remoteFileBusy),"liveFiles" to liveFilesAvailable(id),"backgroundSync" to backgroundPending(id)) }
        val transfer=synchronized(r) {mapOf("folderSource" to r.folderSource,"folderImport" to r.folderImport?.let(r.transferMeter::view),"fileTransfer" to r.fileTransfer?.let(r.transferMeter::view))}
        return result+transfer+mapOf("shutdownWatchdog" to (r.stopMonitor?.status() ?: r.remoteStopProgress),"forceStopSupported" to (i.runnerId==null || remoteController?.supportsForceStop(i)==true),"playerQueryMessage" to r.playerQueryMessage?.takeIf {r.state=="RUNNING"},"sableUdp" to remoteController?.sableStatus(i),"powerSaving" to powerStatus?.invoke(id),"autoRestart" to autoRestartStatus?.invoke(id))
    }
    private fun validate(req: CreateRequest) {
        require(req.name.isNotBlank() && req.name.length <= 80) { "이름은 1~80자로 입력하세요" }
        Launchers.validate(req.loader,req.minecraft,req.loaderVersion)
        require(req.port in 1024..65535) { "포트 범위는 1024~65535입니다" }
        val capacity=capacity()
        require(req.memoryMb.toLong() in 512L..capacity.maxHeapMb) { "이 Mac의 메모리 설정 범위는 512~${capacity.maxHeapMb} MiB입니다 (물리 ${capacity.totalMb} MiB, OS 여유 ${capacity.reservedMb} MiB)" }
        require(Path.of(req.javaPath).isExecutable()) { "실행 가능한 Java 경로를 지정하세요" }
    }
    @Synchronized fun create(req: CreateRequest): Map<String, Any?> {
        validate(req)
        require(remoteController?.sharedGamePort()!=null || instances.values.none { it.port == req.port }) { "다른 인스턴스가 사용하는 포트입니다" }
        val i = Instance(UUID.randomUUID().toString(), req.name, loader=req.loader,minecraft=req.minecraft,loaderVersion=req.loaderVersion, port=req.port, memoryMb=req.memoryMb, javaPath=req.javaPath)
        serverDir(i.id).createDirectories(); instances[i.id] = i; runtimes[i.id] = RuntimeState()
        writeProperties(i); save(i); acceptEula(i.id); return view(i.id)
    }
    @Synchronized fun update(id: String, req: CreateRequest): Map<String, Any?> {
        validate(req); val r = runtime(id)
        synchronized(r) {
            checkIdle(r)
            val i = instance(id)
            require(req.loaderVersion == i.loaderVersion && req.loader==i.loader && req.minecraft==i.minecraft) { "설치된 로더 버전 변경은 새 인스턴스로 진행하세요" }
            require(remoteController?.sharedGamePort()!=null || instances.values.none { it.id != id && it.port == req.port }) { "다른 인스턴스가 사용하는 포트입니다" }
            i.name=req.name; i.port=req.port; i.memoryMb=req.memoryMb; i.javaPath=req.javaPath
            if(i.syncEnabled() && i.runnerId==null)writeProperties(i); save(i)
        }
        return view(id)
    }
    @Synchronized fun assignRunner(id:String,runnerId:String?):Map<String,Any?> {
        val r=runtime(id)
        synchronized(r) {
            checkIdle(r)
            check(r.state in setOf("NEW","STOPPED","FAILED","IMPORT_FAILED")) { "서버가 완전히 종료된 뒤 실행기를 변경하세요" }
            check(instance(id).syncEnabled() || instance(id).runnerId==runnerId) {"파일 동기화가 꺼져 있어 실행기를 변경할 수 없습니다. 먼저 동기화를 켜고 최신 파일 반영을 완료하세요"}
            instance(id).runnerId=runnerId
            r.folderSource=null;r.folderImport=null;r.fileTransfer=null
            save(instance(id))
        }
        return view(id)
    }
    @Synchronized fun deleteInstance(id:String):Map<String,Any> {
        val r=runtime(id)
        synchronized(r) {
            checkIdle(r)
            val archive=root.resolve("deleted-instances").also { it.createDirectories() }
                .resolve("$id-${System.currentTimeMillis()}")
            Files.move(root.resolve(id),archive,StandardCopyOption.ATOMIC_MOVE)
            r.state="DELETED"
            instances.remove(id);runtimes.remove(id)
            return mapOf("deleted" to id,"archived" to true)
        }
    }
    private fun writeProperties(i: Instance) {
        val path=serverDir(i.id).resolve("server.properties")
        val props=Properties(); if(path.exists()) path.inputStream().use { props.load(it) }
        props.setProperty("server-ip", "127.0.0.1"); props.setProperty("server-port", i.port.toString())
        props.setProperty("enable-rcon", "false"); runtime(i.id).queryPort=QueryEndpoint.configure(props,i.port)
        if(!props.containsKey("online-mode")) props.setProperty("online-mode", "true")
        if(!props.containsKey("view-distance")) props.setProperty("view-distance", "6")
        path.outputStream().use { props.store(it, "Local Server Manager") }
    }
    fun eulaAccepted(id: String): Boolean {
        val path=serverDir(id).resolve("eula.txt"); if(!path.exists()) return false
        return Properties().apply { path.inputStream().use { load(it) } }.getProperty("eula") == "true"
    }
    fun acceptEula(id: String) { val r=runtime(id); synchronized(r) { checkIdle(r); serverDir(id).resolve("eula.txt").writeText("# Accepted through local UI at ${Instant.now()}\neula=true\n") } }
    fun versions(): List<String> = catalog.get().versions
    fun versionCatalog(refresh: Boolean,loader:String="NeoForge",minecraft:String="1.21.1") = if(loader=="NeoForge" && minecraft=="1.21.1")catalog.get(refresh) else software.versions(loader,minecraft,refresh)
    fun minecraftVersions(loader:String,refresh:Boolean=false)=software.games(loader,refresh)
    private fun fetch(url: String): String {
        val res=http.send(HttpRequest.newBuilder(URI(url)).timeout(java.time.Duration.ofMinutes(2)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode()==200) { "다운로드 실패: HTTP ${res.statusCode()}" }; return res.body()
    }
    fun install(id: String) {
        val i=instance(id); val r=runtime(id)
        synchronized(r) {
            checkIdle(r)
            require(r.state != "IMPORT_FAILED") {"실패한 팩은 원본 mrpack에서 다시 가져오세요"};require(!root.resolve(id).resolve("installed.ok").exists()) {"이미 설치된 인스턴스입니다"}
            if(i.runnerId!=null) {
                check(remoteController?.supportsWorkspace(i)==true) {"선택한 실행기를 0.4.5 이상으로 업데이트하세요"}
                val ticket=UUID.randomUUID().toString();atomicText(root.resolve(id).resolve("remote-install.pending"),ticket)
                atomicText(root.resolve(id).resolve("remote-lease"),i.runnerId!!)
                if(i.syncEnabled())atomicText(folderImportMarker(id),ticket)
                r.fileTransfer=null;r.folderImport=null;r.transferAuthoritative=false;r.transferMeter.reset();r.state="INSTALLING";r.event("system","선택한 실행기에서 ${i.loader} 설치 중")
                try {remoteController!!.install(i.copy(),ticket)}catch(e:Exception){root.resolve(id).resolve("remote-install.pending").deleteIfExists();root.resolve(id).resolve("remote-lease").deleteIfExists();folderImportMarker(id).deleteIfExists();r.state="FAILED";throw e};return
            }
            require(r.state != "IMPORT_FAILED") { "실패한 팩은 원본 mrpack에서 다시 가져오세요" }; require(!root.resolve(id).resolve("installed.ok").exists()) { "이미 설치된 인스턴스입니다" }
            r.state="INSTALLING"; r.event("system", "${i.loader} ${i.loaderVersion} 설치 준비 중")
        }
        workers.submit {
            try {
                installer.install(i,serverDir(id),root.resolve(id).resolve("installer.jar"),{ args ->
                    val p=ProcessBuilder(args).directory(serverDir(id).toFile()).redirectErrorStream(true).start()
                    synchronized(r) { r.process=p }
                    consume(id,p)
                    p.waitFor().also { synchronized(r) { r.process=null } }
                }) { text -> r.event("system",text) }
                root.resolve(id).resolve("installed.ok").writeText(i.loaderVersion)
                synchronized(r) { r.process=null;r.state="STOPPED";r.event("system","${i.loader} 설치 완료") }
            } catch(e: Exception) { synchronized(r) { r.process=null; r.state="FAILED"; r.event("error", e.message ?: "설치 실패") } }
        }
    }
    fun remoteInstallResult(id:String,runner:String,ticket:String,success:Boolean,detail:String?) {
        // Persisted runner results can arrive after deletion or while its runtime lock is held.
        // Such a late result must not terminate the runner's shared control connection.
        val i=instances[id] ?: return
        val r=runtimes[id] ?: return
        synchronized(r){
            if(instances[id]!==i || runtimes[id]!==r || r.state=="DELETED" || i.runnerId!=runner || root.resolve(id).resolve("remote-install.pending").takeIf {it.exists()}?.readText()!=ticket)return
            if(success && i.syncEnabled())return
            root.resolve(id).resolve("remote-install.pending").deleteIfExists();folderImportMarker(id).deleteIfExists();root.resolve(id).resolve("remote-lease").deleteIfExists()
            r.state=if(success)"STOPPED" else "FAILED";r.remotePid=null
            if(success)root.resolve(id).resolve("installed.ok").writeText("runner-local")
            r.event(if(success)"system" else "error",detail?.take(500) ?: "실행기 설치 결과");remoteController?.completed(i)
        }
    }
    fun checkTransferReady(id:String) {check(!runtime(id).modBusy) {"모드 작업이 진행 중입니다"};check(!runtime(id).recoveringFiles) {"중단된 파일 반영을 복구하고 있습니다. 완료 후 다시 시도하세요"}}
    private fun checkIdle(r: RuntimeState,allowDiagnostic:Boolean=false,allowBackground:Boolean=false) {if(!allowBackground)check(runtimes.entries.none {it.value===r && backgroundPending(it.key)}) {"종료 복사본 전송 중입니다. 같은 실행기에서 시작·원격 파일 변경은 가능합니다"};check(!r.remoteFileBusy) {"실행기 파일 요청을 처리 중입니다"}; check(!r.modBusy) {"모드 작업이 진행 중입니다"};check(!r.cloudSyncActive) {"클라우드 동기화가 진행 중입니다"};check(allowDiagnostic || !r.diagnosticActive) {"모드 검사 패널에서 먼저 검사를 일시 중단하거나 종료하세요"}; check(!r.recoveringFiles) {"파일 복구가 끝난 뒤 다시 시도하세요"};check(r.state!="DELETED") { "삭제된 서버입니다" }; check(r.process?.isAlive != true && r.state !in listOf("INSTALLING","STARTING","RUNNING","STOPPING","IMPORTING","TRANSFERRING","SYNCING","SYNC_FAILED","UNKNOWN")) { "서버가 실행 중이거나 작업 중입니다" } }
    private fun folderImportMarker(id:String)=root.resolve(id).resolve("folder-import.pending")
    @Synchronized fun importRemoteFolder(id:String,enableSync:Boolean=false) {
        val i=instance(id);val r=runtime(id)
        synchronized(r) {
            checkIdle(r);check(i.syncEnabled() || enableSync) {"파일 동기화를 켜서 실행기 원본을 반영하세요"};val runner=i.runnerId ?: error("기존 폴더를 등록한 실행기를 선택하세요")
            val previous=r.state;val request=UUID.randomUUID().toString();if(enableSync)atomicText(root.resolve(id).resolve("sync-enable.pending"),request)
            folderImportMarker(id).writeText(request);root.resolve(id).resolve("remote-lease").writeText(runner)
            r.state="IMPORTING";r.fileTransfer=null;r.transferAuthoritative=false;r.transferMeter.reset();r.folderImport=FolderImportProgress("SCANNING",message="실행기의 기존 폴더를 확인하고 있습니다")
            try {(remoteController ?: error("실행기 연결을 확인하세요")).importFolder(i.copy(),request)}
            catch(e:Exception){folderImportMarker(id).deleteIfExists();root.resolve(id).resolve("remote-lease").deleteIfExists();root.resolve(id).resolve("sync-enable.pending").deleteIfExists();r.state=previous;r.folderImport=FolderImportProgress("FAILED",message=e.message.orEmpty());throw e}
            r.event("system","Minecraft를 실행하지 않고 기존 서버 폴더를 가져옵니다")
        }
    }
    fun checkFolderImport(id:String,runner:String,request:String) {
        val r=runtime(id)
        synchronized(r) {check(!r.recoveringFiles && instance(id).runnerId==runner && r.state in setOf("INSTALLING","IMPORTING","UNKNOWN") && r.remotePid==null && folderImportMarker(id).takeIf {it.exists()}?.readText()==request) { "기존 폴더 가져오기 요청이 만료됐거나 실행기가 변경됐습니다" }}
    }
    fun folderImportState(id:String,runner:String,request:String):String {
        require(instance(id).runnerId==runner)
        if(root.resolve(id).resolve("folder-import.completed").takeIf {it.exists()}?.readText()==request)return "COMPLETE"
        return if(folderImportMarker(id).takeIf {it.exists()}?.readText()==request)"PENDING" else "EXPIRED"
    }
    fun pendingFolderImports(runner:String):List<Pair<Instance,String>> = instances.values.filter {it.runnerId==runner && runtime(it.id).state=="UNKNOWN"}.mapNotNull {i->
        folderImportMarker(i.id).takeIf {it.exists()}?.readText()?.let {i.copy() to it}
    }
    fun folderSource(id:String,runner:String,state:String,path:String?) {
        if(instance(id).runnerId!=runner || state !in setOf("PENDING","SYNCED","INVALID"))return
        val r=runtime(id);synchronized(r){r.folderSource=mapOf("state" to state,"path" to path.orEmpty().take(1000))}
    }
    fun beginTransferProgress(id:String,bytes:Long){val r=runtime(id);synchronized(r){r.transferAuthoritative=true;r.transferMeter.reset(bytes)}}
    fun runnerFolderProgress(id:String,runner:String,request:String,progress:FolderImportProgress) {
        val r=runtime(id);synchronized(r){
            if(r.transferAuthoritative && progress.phase in setOf("SCANNING","UPLOADING","VERIFYING"))return
            val current=if(progress.phase=="RETRYING")r.folderImport?.copy(phase="RETRYING",message=progress.message) ?: progress else progress
            folderImportProgress(id,runner,request,current)
        }
    }
    fun runnerFileProgress(id:String,runner:String,progress:FolderImportProgress) {
        val r=runtime(id);synchronized(r){
            if(r.transferAuthoritative && progress.phase in setOf("SCANNING","UPLOADING","VERIFYING"))return
            val current=if(progress.phase=="RETRYING")r.fileTransfer?.copy(phase="RETRYING",message=progress.message) ?: progress else progress
            fileTransferProgress(id,runner,current)
        }
    }
    fun folderImportProgress(id:String,runner:String,request:String,progress:FolderImportProgress) {
        val r=runtime(id);synchronized(r){checkFolderImport(id,runner,request);r.folderImport=progress.validated();r.transferMeter.observe(progress);r.state="IMPORTING"}
    }
    private fun snapshotState(id:String)=root.resolve(id).resolve("snapshot-sync.json")
    private data class SnapshotSync(val runner:String,val pending:List<String> = emptyList(),val seen:List<String> = emptyList(),val revisions:Map<String,String?> = emptyMap())
    private fun snapshotSync(id:String):SnapshotSync?=snapshotState(id).takeIf {it.exists()}?.let {require(!Files.isSymbolicLink(it) && it.fileSize()<1024*1024);mapper.readValue<SnapshotSync>(it.readText())}
    fun backgroundPending(id:String)=snapshotSync(id)?.pending?.isNotEmpty()==true || root.resolve(id).resolve("runner-edits.pending").exists()
    fun checkSnapshot(id:String,runner:String,generation:String) {
        require(StoppedSnapshots.valid(generation));val state=snapshotSync(id)
        check(state?.runner==runner && state.pending.firstOrNull()==generation) {"종료 복사본의 전송 순서가 다릅니다"}
    }
    fun snapshotReady(id:String,runner:String,ready:SnapshotReady) {
        require(instance(id).runnerId==runner && instance(id).syncEnabled() && ready.exit?.crashReport.let {it==null || CrashReports.validName(it)})
        require(StoppedSnapshots.valid(ready.generation));val r=runtime(id)
        synchronized(r) {
            val old=snapshotSync(id) ?: SnapshotSync(runner);check(old.runner==runner)
            if(ready.generation in old.seen)return
            val token=root.resolve(id).resolve("remote-run")
            check(if(ready.editOnly) {
                val edits=root.resolve(id).resolve("runner-edits.pending")
                ready.localRevision!=null && edits.takeIf {it.exists()}?.readText()==ready.localRevision && !root.resolve(id).resolve("remote-lease").exists()
            } else ready.runToken!=null && token.takeIf {it.exists()}?.readText()==ready.runToken && root.resolve(id).resolve("remote-lease").exists()) {"현재 실행에 해당하는 종료 복사본만 접수합니다"}
            check(old.pending.size<32)
            atomicText(snapshotState(id),mapper.writeValueAsString(old.copy(pending=old.pending+ready.generation,seen=(old.seen+ready.generation).takeLast(1024),revisions=old.revisions+(ready.generation to ready.localRevision))))
            if(ready.editOnly){r.fileTransfer=FolderImportProgress("UPLOADING",message="실행기 파일 변경을 중앙 복구본에 백그라운드 반영 중");return}
            val requested=r.remoteStopRequested
            val exit=ServerExit(ready.exit?.code,requested,if(requested)r.stopReason ?: "MANUAL" else "UNEXPECTED",ready.exit?.crashReport)
            root.resolve(id).resolve("remote-lease").deleteIfExists();token.deleteIfExists();root.resolve(id).resolve("remote-exit.json").deleteIfExists()
            r.lastExit=exit;r.exitReport=null;r.remotePid=null;r.resources=null;r.players=null;r.remoteStopRequested=false;r.state="STOPPED"
            r.remoteWorkerReady=true;r.remoteCloudReady=true
            r.fileTransfer=FolderImportProgress("UPLOADING",message="종료 복사본을 백그라운드 전송 중 · 현재 실행기에서 다시 시작 가능")
            r.event("system","종료 복사본 준비 완료 · 현재 실행기에서 다시 시작할 수 있습니다")
            signal(id,"stop",exit.summary()+" 종료 복사본을 백그라운드 전송합니다.",exit,if(!requested)r.discordError.recent() else null)
            remoteController?.completed(instance(id))
        }
        resumeRemoteRestart(id)
    }
    fun snapshotCommitted(id:String,runner:String,generation:String) {
        val r=runtime(id);synchronized(r) {
            val state=snapshotSync(id) ?: error("종료 복사본 기록이 없습니다");require(state.runner==runner && instance(id).runnerId==runner)
            if(generation !in state.pending && generation in state.seen)return
            checkSnapshot(id,runner,generation)
            val next=state.copy(pending=state.pending.drop(1),revisions=state.revisions-generation);
            val edits=root.resolve(id).resolve("runner-edits.pending")
            if(edits.exists() && state.revisions[generation]==edits.readText() && !r.remoteFileBusy)edits.deleteIfExists()
            atomicText(snapshotState(id),mapper.writeValueAsString(next))
            if(next.pending.isEmpty())r.fileTransfer=(r.fileTransfer ?: FolderImportProgress("COMPLETE")).copy(phase="COMPLETE",message="종료 복사본 전송 완료 · 현재 게임 실행 상태 유지")
        }
    }
    fun snapshotProgress(id:String,runner:String,generation:String,progress:FolderImportProgress) {
        val r=runtime(id);synchronized(r){checkSnapshot(id,runner,generation);r.fileTransfer=progress.validated();r.transferMeter.observe(progress)}
    }
    fun fileTransferProgress(id:String,runner:String,progress:FolderImportProgress) {
        val r=runtime(id);synchronized(r) {
            if(instance(id).runnerId!=runner || r.state !in setOf("TRANSFERRING","SYNCING","SYNC_FAILED","UNKNOWN") || !root.resolve(id).resolve("remote-lease").exists())return
            r.fileTransfer=progress.validated();r.transferMeter.observe(progress)
        }
    }
    fun folderImportFailed(id:String,runner:String,request:String,detail:String?) {
        val r=runtime(id)
        synchronized(r) {
            checkFolderImport(id,runner,request)
            // Legacy runners report transient HTTP failures as import-failed.
            // Keep their authenticated ticket so their recovery loop can reuse
            // the staged payload instead of starting a new destructive import.
            if(Regex("HTTP\\s+(?:401|403|408|429|5\\d{2})(?!\\d)").containsMatchIn(detail.orEmpty())) {
                r.state="IMPORTING";r.folderImport=(r.folderImport ?: FolderImportProgress("RETRYING")).copy(phase="RETRYING",message="연결 복구 후 받은 파일부터 이어받습니다 · ${detail.orEmpty().take(300)}")
                return
            }
            folderImportMarker(id).deleteIfExists();root.resolve(id).resolve("remote-lease").deleteIfExists()
            r.state="FAILED";r.folderImport=FolderImportProgress("FAILED",message=detail.orEmpty().take(500));r.event("error","기존 폴더 가져오기 실패: ${detail.orEmpty().take(500)}")
        }
    }
    fun start(id:String)=startAs(id,StartActor("SYSTEM","관리 시스템"))
    fun startAs(id:String,actor:StartActor)=startInternal(id,null,actor)
    fun startDiagnostic(id:String,session:String)=startInternal(id,session,StartActor("SYSTEM","모드 검사"))
    @Synchronized private fun startInternal(id: String,session:String?,actor:StartActor) {
        val i=instance(id); val r=runtime(id)
        synchronized(r) {
            checkIdle(r,true,true);check(if(r.diagnosticActive)session!=null && session==r.diagnosticSession else session==null) {"모드 검사 패널에서 다음 시도를 준비하고 시작하세요"}; require(r.state != "IMPORT_FAILED") { "실패한 팩은 시작할 수 없습니다. 원본 mrpack에서 다시 가져오세요" }; require(eulaAccepted(id)) { "Minecraft EULA에 먼저 동의하세요" }
            r.activityVersion++;r.stopReason=null;r.idleCheckPending=false;r.idleRequestId=null;r.startActor=actor;r.restartActor=null
            r.discordError.clear();r.exitError=null;r.exitErrorCaptured=false;r.startNotified=false;r.exitReport=null;r.lastExit=null;r.remoteCloudReady=false;r.remoteWorkerReady=false
            r.fileTransfer=null;r.transferAuthoritative=false;r.transferMeter.reset()
            if(i.runnerId!=null) {
                val controller=remoteController ?: error("원격 실행기 연결 기능을 사용할 수 없습니다")
                root.resolve(id).resolve("remote-exit.json").deleteIfExists()
                atomicText(root.resolve(id).resolve("start-actor.json"),mapper.writeValueAsString(actor))
                root.resolve(id).resolve("remote-lease").writeText(i.runnerId!!)
                r.remoteStopRequested=false;r.remoteRestartRequested=false;r.state="TRANSFERRING";r.players=null;r.resources=null
                val runToken=if(controller.supportsLiveFiles(i))UUID.randomUUID().toString() else null
                if(runToken!=null)atomicText(root.resolve(id).resolve("remote-run"),runToken)
                try { controller.start(i.copy(diagnosticSession=r.diagnosticSession,runToken=runToken)) } catch(e:Exception) { root.resolve(id).resolve("remote-lease").deleteIfExists();r.state="STOPPED";throw e }
                r.event("system","실행기 ${i.runnerId}로 서버 데이터를 인계하고 있습니다")
                return
            }
            require(remoteController?.sharedGamePort()==null) { "공유 중계 모드에서는 별도 실행기를 등록하고 이 서버에 배정하세요" }
            require(instances.values.none { other -> other.id!=id && other.runnerId==null && other.port==i.port && runtimes[other.id]?.process?.isAlive==true }) { "이 컴퓨터에서 같은 게임 포트를 사용하는 서버가 실행 중입니다" }
            val capacity=capacity(id)
            require(i.memoryMb.toLong() in 512L..capacity.startableHeapMb) { "시작 가능한 최대 힙은 ${capacity.startableHeapMb} MiB입니다. 다른 실행 인스턴스가 ${capacity.committedHeapMb} MiB를 예약하고 있습니다" }
            val args=Launchers.arguments(i,serverDir(id))
            Launchers.verifyJava(i.javaPath,Launchers.requiredJava(i,serverDir(id)))
            ServerSocket(i.port,1,InetAddress.getByName("127.0.0.1")).use { }
            writeProperties(i)
            r.crashesBefore=CrashReports.snapshot(serverDir(id))
            // The resident host (when active) owns the process so it survives a manager restart or update.
            val builder=ProcessBuilder(args).directory(serverDir(id).toFile()).redirectErrorStream(true)
            val p=localLauncher?.launch(id,args,serverDir(id),builder.environment().toMap(),i.port) ?: builder.start()
            r.process=p; r.state="STARTING"; r.players=null; r.event("system","서버 시작 · 127.0.0.1:${i.port} · PID ${p.pid()}"+if(p is HostedProcess)" · 상주 서비스가 프로세스를 소유" else "")
            watch(id,r,p)
        }
    }
    private fun watch(id:String,r:RuntimeState,p:Process) {
        workers.submit {
            consume(id,p); val code=p.waitFor()
            val (exit,summary)=synchronized(r) {
                if(r.process!==p)return@submit
                val requested=r.state=="STOPPING"
                r.stopMonitor?.close();r.stopMonitor=null;r.process=null;r.state=if(code==0)"STOPPED" else "FAILED"
                ServerExit(code,requested,if(requested)r.stopReason ?: "MANUAL" else "UNEXPECTED",CrashReports.changed(serverDir(id),r.crashesBefore)).also {r.lastExit=it;r.event("system",it.summary())} to r.discordError.recent()
            }
            if(p is HostedProcess)localLauncher?.released(id,p.pid())
            signal(id,"stop",exit.summary(),exit,if(!exit.requested)summary else null)
        }
    }
    /** Optional resident game host; null keeps the original in-process behaviour. */
    @Volatile var localLauncher:LocalGameLauncher?=null
    /**
     * Re-attaches games the resident host kept running while this app was replaced. Only servers that are configured as
     * local, currently have no process here and are alive in the host are adopted; nothing is ever started or stopped.
     * Returns the adopted instance IDs.
     */
    fun adoptHostedGames():List<String> {
        val launcher=localLauncher ?: return emptyList()
        return launcher.adoptable().filter {id->instances[id]?.runnerId==null && instances.containsKey(id)}.mapNotNull {id->
            val r=runtime(id)
            synchronized(r) {
                if(r.process!=null)return@mapNotNull null
                val p=launcher.adopt(id,ConsolePosition.load(profileDir(id).resolve("console.seq"))) ?: return@mapNotNull null
                r.process=p;r.state=if((p as? HostedProcess)?.readyAtAttach==true)"RUNNING" else "STARTING";r.players=null;r.startNotified=true;r.crashesBefore=CrashReports.snapshot(serverDir(id))
                r.event("system","상주 서비스에서 실행 중인 서버에 다시 연결했습니다 · PID ${p.pid()}")
                watch(id,r,p);id
            }
        }
    }
    private fun consume(id: String,p: Process) {
        val r=runtime(id)
        val log=profileDir(id).resolve("console.log")
        log.toFile().appendText("\n--- ${Instant.now()} ---\n")
        // Lines replayed from the resident host were already written by the previous manager process.
        val hostedProcess=p as? HostedProcess;val position=profileDir(id).resolve("console.seq");var lastSaved=0L
        java.io.FileOutputStream(log.toFile(),true).bufferedWriter().use { writer ->
            p.inputStream.bufferedReader().useLines { lines -> lines.forEach { raw ->
                if(LogNoise.dropped(raw))return@forEach
                val line=raw.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
                playerActivity(r,line)
                if(r.process===p)r.stopMonitor?.log(line)
                val kind=when { line.contains("ERROR") || line.contains("Exception") -> "error"; line.contains("WARN") -> "warn"; else -> "output" }
                synchronized(r) { if(r.state=="STARTING" && line.contains("Done (") && line.contains("For help")) {r.state="RUNNING";started(id,r)}; r.event(kind,line) }
                if(r.discordError.offer(line,kind=="error")) signal(id,"error","서버 로그에서 오류가 감지되었습니다.",error=r.discordError.snapshot())
                writer.appendLine(raw); writer.flush()
                hostedProcess?.let {it.markWritten();val now=System.currentTimeMillis();if(now-lastSaved>1000){lastSaved=now;it.savePosition(position)}}
            } }
        }
    }
    fun command(id: String, command: String) {
        require(command.isNotBlank() && command.length<=4096 && !command.contains('\n') && !command.contains('\r')) { "한 줄 명령을 입력하세요" }
        val r=runtime(id); synchronized(r) {
            check(r.state in listOf("RUNNING","STARTING")) { "실행 중인 서버가 없습니다" }
            val instance=instance(id)
            if(instance.runnerId!=null) {
                val controller=remoteController ?: error("원격 실행기 연결 기능을 사용할 수 없습니다")
                if(command.trim()=="stop") { if(r.stopReason==null)r.stopReason="MANUAL";controller.stop(instance);r.remoteStopRequested=true;r.state="STOPPING" }
                else controller.command(instance,command)
                r.event("input",command)
                return
            }
            val p=r.process ?: error("프로세스가 없습니다")
            p.outputStream.write((command+"\n").toByteArray()); p.outputStream.flush(); r.event("input",command)
            if(command.trim()=="stop") {if(r.stopReason==null)r.stopReason="MANUAL";r.state="STOPPING";armStopMonitor(id,r,p)}
        }
    }
    private fun armStopMonitor(id:String,r:RuntimeState,p:Process) {
        if(r.stopMonitor!=null)return
        r.stopMonitor=StopMonitor(p,serverDir(id),{r.process===p&&r.state=="STOPPING"}) {
            synchronized(r) {
                if(r.process===p&&p.isAlive&&r.state=="STOPPING") {
                    r.stopReason="STOP_TIMEOUT";r.event("system","3분 동안 종료 저장 진전이 없어 Minecraft를 자동 강제 종료합니다. 저장되지 않은 변경은 손실될 수 있습니다.")
                    p.descendants().use {children->children.forEach {runCatching {it.destroyForcibly()}}};p.destroyForcibly()
                }
            }
        }
    }
    fun stop(id: String,force: Boolean) { val r=runtime(id); synchronized(r) {
        val i=instance(id)
        if(i.runnerId!=null) {
            if(!force){command(id,"stop");return}
            check(r.state in setOf("STARTING","RUNNING","STOPPING")) {"강제 종료할 실행 중인 서버가 없습니다"}
            val pid=r.remotePid ?: error("실행기의 Minecraft PID를 확인한 뒤 다시 시도하세요")
            val controller=remoteController ?: error("원격 실행기 연결 기능을 사용할 수 없습니다")
            controller.forceStop(i,pid)
            r.stopReason="FORCED";r.remoteStopRequested=true;r.remoteRestartRequested=false;r.state="STOPPING"
            r.event("system","원격 강제 종료 요청 · 저장되지 않은 변경은 손실될 수 있습니다. 프로세스 종료 확인 후 파일을 인계합니다.")
            return
        }
        if(force) { val p=r.process ?: error("프로세스가 없습니다");r.stopReason="FORCED"; r.state="STOPPING"; p.descendants().use {children->children.forEach { it.destroyForcibly() }}; p.destroyForcibly(); r.event("system","강제 종료 요청") }
        else command(id,"stop")
    } }
    fun restart(id:String)=restartAs(id,StartActor("SYSTEM","관리 시스템"))
    fun restartAs(id: String,actor:StartActor) {
        val r=runtime(id);check(!r.diagnosticActive) {"모드 검사에서는 각 시도를 종료한 뒤 패널에서 다시 시작하세요"}
        if(instance(id).runnerId!=null) {
            synchronized(r) { check(r.state=="RUNNING") { "실행 중인 서버만 재시작할 수 있습니다" };r.restartActor=actor;r.stopReason="RESTART";remoteController?.restart(instance(id)) ?: error("실행기 연결이 없습니다");r.remoteStopRequested=true;r.remoteRestartRequested=true;r.state="STOPPING";r.event("system","원격 서버 재시작 요청") }
            return
        }
        val old=synchronized(r) { check(r.state=="RUNNING") { "실행 중인 서버만 재시작할 수 있습니다" }; val p=r.process!!;r.stopReason="RESTART"; command(id,"stop"); p }
        workers.submit {
            if(!old.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)) { r.event("error","정상 종료가 지연되어 재시작을 취소했습니다. 상태를 확인하세요."); return@submit }
            synchronized(r) { if(r.process===old) { r.process=null; r.state="STOPPED" } }
            runCatching { startAs(id,actor) }.onFailure { r.event("error","재시작 실패: ${it.message}") }
        }
    }
    fun remoteState(id:String,runnerId:String,state:String,detail:String?=null) {
        val i=instance(id);if(i.runnerId!=runnerId || state !in setOf("STARTING","RUNNING","STOPPING","SYNCING","SYNC_FAILED","UNKNOWN")) return
        val r=runtime(id)
        synchronized(r) {
            if(r.recoveringFiles)return
            // HTTP commit and WebSocket status travel independently. A delayed status
            // must not resurrect a transfer whose lease has already been completed.
            if(state!="UNKNOWN" && !root.resolve(id).resolve("remote-lease").exists())return
            if(state=="SYNCING" && !r.exitErrorCaptured){r.exitError=r.discordError.recent();r.exitErrorCaptured=true}
            if(state=="SYNCING" && r.exitReport==null)r.exitReport=RunnerExitReport(Regex("종료 코드 (-?[0-9]+)").find(detail.orEmpty())?.groupValues?.get(1)?.toIntOrNull())
            r.state=state;if(state!="STOPPING")r.remoteStopProgress=null;if(state=="RUNNING")started(id,r);if(state=="SYNCING"){r.remotePid=null;r.resources=null;r.players=null};if(state=="RUNNING"){r.fileTransfer=r.fileTransfer?.copy(phase="COMPLETE")};if(state=="SYNC_FAILED"){r.fileTransfer=(r.fileTransfer ?: FolderImportProgress("FAILED")).copy(phase="FAILED",message=detail?.take(500) ?: "실행기 연결과 파일 인계 상태를 확인하세요")};detail?.take(1000)?.let { r.event("system",it) }
        }
    }
    fun remoteExited(id:String,runnerId:String,report:RunnerExitReport) {
        require(instance(id).runnerId==runnerId)
        require(report.crashReport==null || CrashReports.validName(report.crashReport))
        val r=runtime(id);synchronized(r) {
            check(root.resolve(id).resolve("remote-lease").exists() && !folderImportMarker(id).exists()) {"실행 중이던 서버의 종료만 보고할 수 있습니다"}
            require(report.reason==null||report.reason in setOf("STOP_TIMEOUT","IDLE"))
            atomicText(root.resolve(id).resolve("remote-exit.json"),mapper.writeValueAsString(report))
            if(report.reason=="IDLE" && r.stopReason !in setOf("FORCED","STOP_TIMEOUT","SHUTDOWN")){r.stopReason="IDLE";r.remoteStopRequested=true;r.remoteRestartRequested=false}
            if(report.reason=="STOP_TIMEOUT"){r.stopReason="STOP_TIMEOUT";r.remoteStopRequested=true;r.remoteRestartRequested=false}
            r.exitReport=report
            if(!r.exitErrorCaptured){r.exitError=r.discordError.recent();r.exitErrorCaptured=true}
        }
    }
    fun remoteStartFailed(id:String,runnerId:String,detail:String?) {
        val i=instance(id);if(i.runnerId!=runnerId)return
        val r=runtime(id)
        synchronized(r) {
            if(r.state!="TRANSFERRING" || r.remotePid!=null)return
            root.resolve(id).resolve("remote-lease").deleteIfExists()
            r.state="FAILED";r.remoteRestartRequested=false
            r.event("error",detail?.take(1000) ?: "실행기에서 서버를 시작하지 못했습니다")
            remoteController?.completed(i)
        }
    }
    fun remoteWorkerReady(id:String,runnerId:String) {
        if(instance(id).runnerId!=runnerId)return
        val r=runtime(id)
        if(!instance(id).syncEnabled() && r.state in setOf("SYNCING","UNKNOWN") && r.exitReport!=null && root.resolve(id).resolve("remote-lease").exists() && !folderImportMarker(id).exists())remoteCommitted(id,runnerId)
        synchronized(r) { if(r.state=="STOPPED") {r.remoteWorkerReady=true;if(!instance(id).syncEnabled())r.remoteCloudReady=true} }
        resumeRemoteRestart(id)
    }
    fun remoteCloudReady(id:String) {
        val r=runtime(id)
        synchronized(r) { if(r.state in setOf("STOPPED","FAILED")) r.remoteCloudReady=true }
        resumeRemoteRestart(id)
    }
    private fun resumeRemoteRestart(id:String) {
        val r=runtime(id)
        val restart=synchronized(r) { if(r.remoteRestartRequested && r.remoteWorkerReady && r.remoteCloudReady && r.state=="STOPPED") { r.remoteRestartRequested=false;r.restartActor ?: StartActor("SYSTEM","관리 시스템") } else null }
        if(restart!=null) workers.submit { runCatching { startAs(id,restart) }.onFailure { synchronized(r) { r.event("error","원격 서버 재시작 실패: ${it.message}") } } }
    }
    fun remoteStopProgress(id:String,runnerId:String,pid:Long,status:StopWatchStatus) {
        if(instance(id).runnerId!=runnerId)return
        val r=runtime(id);synchronized(r) {
            if(r.state!="STOPPING"||r.remotePid!=pid)return
            r.remoteStopProgress=status
            if(status.forced){r.stopReason="STOP_TIMEOUT";r.remoteStopRequested=true;r.remoteRestartRequested=false}
        }
    }
    fun remoteLog(id:String,runnerId:String,kind:String,line:String) {
        if(instance(id).runnerId!=runnerId || LogNoise.dropped(line))return
        val r=runtime(id)
        playerActivity(r,line)
        synchronized(r) {
            val text=line.take(4096)
            // The web console reads this raw file even if an earlier installation already created it.
            // Keeping runner output only in events hides early crashes behind that old installation log.
            try {
                Files.writeString(profileDir(id).resolve("console.log"),text+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS)
                r.remoteConsoleWriteFailed=false
            } catch(e:Exception) {
                // A disk/permission failure must not tear down the runner's shared WebSocket.
                if(!r.remoteConsoleWriteFailed)org.slf4j.LoggerFactory.getLogger(ServerManager::class.java).warn("Cannot append runner console for {}: {}",id,e.message)
                r.remoteConsoleWriteFailed=true
            }
            r.event(if(kind in setOf("error","warn","output")) kind else "output",text)
        }
        if(r.discordError.offer(line,kind=="error")) signal(id,"error","원격 서버 로그에서 오류가 감지되었습니다.",error=r.discordError.snapshot())
    }
    fun remoteMetrics(id:String,runnerId:String,pid:Long?,cpu:Double?,rss:Double?,uptime:Long?,playerCount:Int?,names:List<String>?) {
        if(instance(id).runnerId!=runnerId)return
        val r=runtime(id)
        synchronized(r) {
            if(r.state !in setOf("STARTING","RUNNING","STOPPING")) return
            r.remotePid=pid?.takeIf { it>0 }
            r.resources=ResourceSample(r.remotePid ?: 0,cpu?.takeIf { it.isFinite() && it>=0 },rss?.takeIf { it.isFinite() && it>=0 },uptime?.takeIf { it>=0 },Instant.now().toString(),"runner")
            r.players=playerCount?.takeIf { it in 0..100000 }?.let { PlayerSample(it,names.orEmpty().take(100).map(String::trim).filter { name->name.isNotBlank() && name.length<=32 },Instant.now().toString()) }
        }
    }
    fun remoteDisconnected(runnerId:String) {
        instances.values.filter { it.runnerId==runnerId }.forEach { i ->
            val r=runtime(i.id)
            synchronized(r) {r.folderSource=null; if(r.state in setOf("STARTING","RUNNING","STOPPING","TRANSFERRING","SYNCING","IMPORTING")) { r.state="UNKNOWN";r.players=null;r.resources=null;r.remotePid=null;r.event("system","실행기 연결이 끊겨 서버 상태를 확인할 수 없습니다") } }
        }
    }
    fun remoteCommitted(id:String,runnerId:String,importRequest:String?=null) {
        val i=instance(id);require(i.runnerId==runnerId)
        val r=runtime(id)
        if(importRequest!=null) {
            checkFolderImport(id,runnerId,importRequest)
            synchronized(r) {
                val remoteInstall=root.resolve(id).resolve("remote-install.pending").takeIf {it.exists()}?.readText()==importRequest
                root.resolve(id).resolve("folder-import.completed").writeText(importRequest)
                folderImportMarker(id).deleteIfExists();root.resolve(id).resolve("remote-lease").deleteIfExists()
                root.resolve(id).resolve("installed.ok").writeText("existing-folder")
                if(root.resolve(id).resolve("sync-enable.pending").takeIf {it.exists()}?.readText()==importRequest){instances[id]=i.copy(fileSyncEnabled=null);save(instance(id));root.resolve(id).resolve("sync-enable.pending").deleteIfExists()}
                root.resolve(id).resolve("remote-install.pending").deleteIfExists()
                r.state="STOPPED";r.folderImport=(r.folderImport ?: FolderImportProgress("COMPLETE")).copy(phase="COMPLETE",message=if(remoteInstall)"실행기에서 ${i.loader} 설치와 중앙 반영을 완료했습니다" else "기존 폴더를 가져왔습니다. 파일 관리자에서 확인할 수 있습니다")
                r.event("system",if(remoteInstall)"실행기에서 ${i.loader} 설치 완료" else "기존 서버 폴더 가져오기 완료 · 서버를 실행하지 않았습니다")
            }
            signal(id,"import","기존 서버 파일 가져오기를 완료했습니다")
            return
        }
        val (exit,summary)=synchronized(r) {
            val savedExit=root.resolve(id).resolve("remote-exit.json")
            if(r.exitReport==null && savedExit.isRegularFile() && !Files.isSymbolicLink(savedExit) && savedExit.fileSize()<=65536) {
                r.exitReport=mapper.readValue<RunnerExitReport>(savedExit.readText())
                if(r.exitReport?.reason in setOf("IDLE","STOP_TIMEOUT")){r.stopReason=r.exitReport!!.reason;r.remoteStopRequested=true;r.remoteRestartRequested=false}
            }
            root.resolve(id).resolve("remote-lease").deleteIfExists()
            savedExit.deleteIfExists()
            val value=r.remoteStopRequested;r.remoteStopRequested=false;r.remoteWorkerReady=false;r.remoteCloudReady=false;r.state="STOPPED";r.players=null;r.resources=null;r.remotePid=null;r.remoteStopProgress=null
            r.fileTransfer=(r.fileTransfer ?: FolderImportProgress("COMPLETE")).copy(phase="COMPLETE",message="서버 파일 인계를 완료했습니다")
            r.event("system","원격 서버 파일 인계를 완료했습니다")
            ServerExit(r.exitReport?.code,value,if(value)r.stopReason ?: "MANUAL" else "UNEXPECTED",r.exitReport?.crashReport).also {r.lastExit=it} to (if(r.exitErrorCaptured)r.exitError else r.discordError.recent())
        }
        remoteController?.completed(i)
        signal(id,"stop",exit.summary()+if(i.syncEnabled())" 변경 파일 인계를 완료했습니다." else " 파일 동기화 OFF · 실행기 원본을 보존합니다.",exit,if(!exit.requested)summary else null)
    }
    fun remoteQueryStatus(id:String,runner:String,unknown:Boolean){val r=runtime(id);if(instance(id).runnerId==runner)r.playerQueryMessage=if(unknown)"실행기의 플레이어 조회 응답이 없습니다. 인원은 불명이며 무인 자동 종료를 보류합니다." else null}
    fun events(id: String,after: Long): List<Event> { val r=runtime(id); return synchronized(r) { r.events.filter { it.seq>after }.take(2000) } }
    fun stopIfEmpty(id:String,activity:Long):Boolean {
        val i=instance(id);val r=runtime(id)
        val process=synchronized(r) {
            if(r.state!="RUNNING"||r.diagnosticActive||r.activityVersion!=activity||r.idleCheckPending)return false
            if(i.runnerId!=null) {
                val request=UUID.randomUUID().toString();r.idleRequestId=request;r.idleRequestAt=System.nanoTime();r.idleCheckPending=true
                if(remoteController?.stopIfEmpty(i.copy(),request)==true)return true
                r.idleCheckPending=false;r.idleRequestId=null;return false
            }
            r.idleCheckPending=true;r.idleRequestAt=System.nanoTime();r.process
        } ?: run {synchronized(r){r.idleCheckPending=false};return false}
        try {
            val count=r.queryPort?.let(query::currentCount)
            synchronized(r) {
                if(r.process!==process||!process.isAlive||r.state!="RUNNING"||r.diagnosticActive||r.activityVersion!=activity||count!=0){r.players=null;r.activityVersion++;return false}
                r.stopReason="IDLE";command(id,"stop");r.event("system","${powerStatus?.invoke(id)?.config?.minutes ?: 30}분 연속 무인 상태를 확인하여 저장 후 정상 종료합니다")
                return true
            }
        } finally {synchronized(r){r.idleCheckPending=false}}
    }
    fun remoteIdleResult(id:String,runnerId:String,request:String?,accepted:Boolean) {
        if(instance(id).runnerId!=runnerId)return
        val r=runtime(id)
        synchronized(r) {
            if(!r.idleCheckPending||request==null||r.idleRequestId!=request)return
            r.idleCheckPending=false;r.idleRequestId=null
            if(accepted && r.stopReason !in setOf("FORCED","STOP_TIMEOUT","MANUAL","RESTART","SHUTDOWN")){r.stopReason="IDLE";r.remoteStopRequested=true;if(r.state=="RUNNING")r.state="STOPPING";r.event("system","실행기에서 무인 상태를 다시 확인하여 저장 후 정상 종료합니다")}
            else {r.activityVersion++;r.event("system","실행기의 무인 상태를 확인하지 못해 자동 종료를 취소했습니다")}
        }
    }
    fun expireIdleCheck(id:String) {
        val r=runtime(id);synchronized(r){if(r.idleCheckPending&&System.nanoTime()-r.idleRequestAt>20_000_000_000L){r.idleCheckPending=false;r.idleRequestId=null;r.activityVersion++}}
    }
    /** Fallback for servers that have no console.log here: searches the retained in-memory console, numbering lines by event sequence. */
    fun eventWindow(id:String,from:Long,limit:Int,query:String?):LogFiles.Window {
        require(from>=1 && limit in 1..500 && (query?.length ?: 0)<=200) { "로그 조회 범위를 확인하세요" }
        val needle=query?.trim()?.lowercase()?.takeIf {it.isNotEmpty()};val r=runtime(id)
        return synchronized(r) {
            val rows=r.events.filter {it.seq>=from && it.kind!="system" && it.kind!="input" && (needle==null || it.text.lowercase().contains(needle))}
            val page=rows.take(limit).map {LogFiles.Row(it.seq,it.kind,it.text)}
            LogFiles.Window(page,r.events.lastOrNull()?.seq ?: 0,page.firstOrNull()?.no ?: 0,page.lastOrNull()?.no ?: 0,if(rows.size>limit)rows[limit].seq else null)
        }
    }
    fun eventPage(id:String,before:Long?,after:Long?,limit:Int):Map<String,Any?> {
        require(limit in 1..500) { "로그 조회 개수는 1~500입니다" }
        require(before==null || after==null) { "이전 또는 이후 커서 중 하나만 지정하세요" }
        val r=runtime(id)
        return synchronized(r) {
            val rows=when { before!=null -> r.events.filter { it.seq<before }.takeLast(limit); after!=null -> r.events.filter { it.seq>after }.take(limit); else -> r.events.takeLast(limit) }
            mapOf("events" to rows,"hasOlder" to (rows.firstOrNull()?.let { first->r.events.firstOrNull()?.seq?.let { it<first.seq } } ?: false),"oldest" to r.events.firstOrNull()?.seq)
        }
    }
    fun path(id: String, relative: String): Path {
        check(instance(id).syncEnabled()) {"동기화 OFF에서는 실행기 원본 파일을 사용하세요"};checkTransferReady(id);val base=serverDir(id).toRealPath(); val p=base.resolve(relative).normalize()
        require(p.startsWith(base)) { "인스턴스 외부 경로입니다" }
        var ancestor: Path?=p
        while(ancestor!=null && ancestor.startsWith(base)) { require(!Files.isSymbolicLink(ancestor)) { "심볼릭 링크는 지원하지 않습니다" }; ancestor=ancestor.parent }
        return p
    }
    fun files(id: String,relative: String): List<FileEntry> {
        val p=path(id,relative); require(p.isDirectory()) { "폴더가 아닙니다" }
        return Files.list(p).use { entries -> entries.filter { !Files.isSymbolicLink(it) }.map { FileEntry(it.name,serverDir(id).relativize(it).toString(),it.isDirectory(),if(it.isDirectory()) 0 else it.fileSize()) }.toList().sortedWith(compareBy<FileEntry> { !it.directory }.thenBy { it.name }) }
    }
    fun text(id: String,relative: String): Map<String,String> {
        val p=path(id,relative); require(p.isRegularFile() && p.fileSize()<=2*1024*1024) { "2 MiB 이하 텍스트 파일만 편집할 수 있습니다" }
        val bytes=p.inputStream().use {it.readNBytes(2*1024*1024+1)}
        require(bytes.size<=2*1024*1024) {"2 MiB 이하 텍스트 파일만 편집할 수 있습니다"}
        require(!bytes.contains(0.toByte())) { "바이너리 파일은 다운로드하세요" }
        return mapOf("content" to bytes.toString(Charsets.UTF_8), "revision" to digest(bytes))
    }
    private fun digest(bytes: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun write(id:String,relative:String,bytes:ByteArray,revision:String?=null) {
        require(bytes.size<=2*1024*1024) {"텍스트 저장은 2 MiB까지 가능합니다. 큰 파일은 업로드하세요"}
        bytes.inputStream().use {writeStream(id,relative,it,revision,2L*1024*1024)}
    }
    fun writeStream(id:String,relative:String,input:java.io.InputStream,revision:String?=null,limit:Long=256L*1024*1024) {
        require(limit in 0..256L*1024*1024)
        val r=runtime(id);synchronized(r) {
            checkIdle(r);val p=path(id,relative);require(p!=serverDir(id)) {"파일 경로를 지정하세요"}
            if(revision!=null)check(p.isRegularFile() && FileInventory.digest(p)==revision) {"파일이 변경되었습니다. 다시 열어주세요"}
            p.parent.createDirectories()
            val tmp=Files.createTempFile(p.parent,".save-",".tmp")
            try {
                tmp.outputStream().use {output->
                    val buffer=ByteArray(64*1024);var total=0L
                    while(true) {val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit) {"파일 크기 제한을 초과했습니다"};output.write(buffer,0,n)}
                }
                if(p.exists()) {val backup=root.resolve(id).resolve("backups").resolve(UUID.randomUUID().toString()+"-"+p.name);backup.parent.createDirectories();Files.copy(p,backup)}
                Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
            } finally {tmp.deleteIfExists()}
        }
    }
    fun mkdir(id: String,relative: String) { val r=runtime(id); synchronized(r) { checkIdle(r); path(id,relative).createDirectories() } }
    fun move(id: String,from: String,to: String) { val r=runtime(id); synchronized(r) { checkIdle(r); val source=path(id,from); val target=path(id,to); require(source!=serverDir(id) && !target.exists()) { "대상 경로를 확인하세요" }; Files.move(source,target) } }
    fun delete(id: String,relative: String) { val r=runtime(id); synchronized(r) { checkIdle(r); val p=path(id,relative); require(p!=serverDir(id)) { "서버 루트는 삭제할 수 없습니다" }; val trash=root.resolve(id).resolve("trash"); trash.createDirectories(); Files.move(p,trash.resolve(UUID.randomUUID().toString()+"-"+p.name)) } }
    @PreDestroy fun shutdown() {
        // Games owned by the resident host keep running; only remember how far the console was written and detach.
        runtimes.entries.forEach {(id,r)->(r.process as? HostedProcess)?.savePosition(profileDir(id).resolve("console.seq"))}
        val processes=runtimes.entries.mapNotNull { (id,r) -> synchronized(r) { r.process?.takeIf {it !is HostedProcess}?.also {r.stopReason="SHUTDOWN";r.state="STOPPING";armStopMonitor(id,r,it)} } }.filter { it.isAlive }
        processes.forEach { p ->
            runCatching { p.outputStream.write("stop\n".toByteArray()); p.outputStream.flush() }
        }
        processes.forEach { it.waitFor() }
        workers.shutdownNow()
    }
}
