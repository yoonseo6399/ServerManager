package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import kotlin.io.path.*

/**
 * Whether this computer runs games through the resident host.
 *  LEGACY                – games are children of the control layer (pre-split behaviour).
 *  PENDING_NORMAL_STOP   – a game started by the old layout is still alive; it is never killed or force-adopted.
 *                          The split is applied after its next normal stop.
 *  SERVICE               – games are owned by the host and survive control-layer updates.
 */
data class HostModeState(val mode:String="LEGACY",val reason:String?=null,val since:String="",val legacyGames:List<String> =emptyList())

class HostModeStore(private val home:Path,private val env:(String)->String?={System.getenv(it)}) {
    private val mapper=jacksonObjectMapper()
    private val file=home.resolve("host-mode.json")
    @Synchronized fun read():HostModeState=runCatching {mapper.readValue(file.readText(),HostModeState::class.java)}.getOrNull() ?: HostModeState(since=Instant.now().toString())
    @Synchronized private fun write(state:HostModeState):HostModeState {
        home.createDirectories()
        val tmp=Files.createTempFile(home,".host-mode-",".tmp")
        try {tmp.writeText(mapper.writeValueAsString(state));Files.move(tmp,file,ATOMIC_MOVE,REPLACE_EXISTING)} finally {tmp.deleteIfExists()}
        return state
    }
    /** The operator can pin the old layout (`SERVER_MANAGER_GAME_HOST=off`); that is reported, never silent. */
    fun disabled()=env("SERVER_MANAGER_GAME_HOST")?.lowercase() in setOf("off","false","0")
    /**
     * Decides the mode at control-layer start. [legacyGamesAlive] are servers still running from the previous layout
     * (their processes belong to a dead or old controller). They are left alone; the switch waits for their normal stop.
     */
    @Synchronized fun evaluate(legacyGamesAlive:List<String>):HostModeState {
        val now=Instant.now().toString();val current=read()
        return when {
            disabled() -> write(HostModeState("LEGACY","운영자가 SERVER_MANAGER_GAME_HOST=off 로 상주 서비스를 끔",now))
            legacyGamesAlive.isNotEmpty() -> write(HostModeState("PENDING_NORMAL_STOP","이전 방식으로 실행 중인 게임이 정상 종료될 때까지 서비스 분리를 보류합니다",current.since.ifEmpty {now},legacyGamesAlive.sorted()))
            current.mode=="SERVICE" -> current
            else -> write(HostModeState("SERVICE",null,now))
        }
    }
    @Synchronized fun fallback(reason:String)=write(HostModeState("LEGACY",reason.take(300),Instant.now().toString()))
}

/** What the control layer needs from the host, independent of whether it is the desktop manager or a runner. */
interface LocalGameLauncher {
    /** A process owned by the resident host, or null when the legacy in-process launch must be used. */
    fun launch(id:String,command:List<String>,dir:Path,env:Map<String,String>,port:Int?=null):Process?
    /** A still-running game owned by the host, for re-attachment after a controller update. */
    fun adopt(id:String,afterSeq:Long=0):Process?
    fun adoptable():List<String>
    fun released(id:String,pid:Long)
}

class HostedGames(private val home:Path,private val controllerId:String,private val spawn:()->Boolean,
        private val retryMillis:Long=15000,private val minHost:Int=1):LocalGameLauncher,AutoCloseable {
    @Volatile private var client:GameHostClient?=null
    @Volatile private var relayOutput:(String)->Unit={}
    @Volatile var lastError:String?=null;private set
    @Synchronized fun connection():GameHostClient? {
        client?.takeIf {!it.disconnected}?.let {return it}
        client=null
        return try {
            if(GameHostClient.readEndpoint(home)==null || !reachable())spawn()
            GameHostClient.connect(home,controllerId,minHost=minHost,retryMillis=retryMillis).also {it.onRelay=relayOutput;client=it;lastError=null}
        } catch(e:Exception) {lastError=(e.message ?: e.javaClass.simpleName).take(200);null}
    }
    private fun reachable():Boolean {
        val endpoint=GameHostClient.readEndpoint(home) ?: return false
        return runCatching {java.net.Socket().use {it.connect(java.net.InetSocketAddress("127.0.0.1",endpoint.port),1000)};true}.getOrDefault(false)
    }
    var onConsole:(String,Long,String)->Unit={_,_,_->}
    override fun launch(id:String,command:List<String>,dir:Path,env:Map<String,String>,port:Int?):Process? {
        val c=connection() ?: return null
        c.onConsole=onConsole
        val result=c.start(id,command,dir,env,port)
        check(!result.duplicate) {"이미 상주 서비스가 이 서버의 프로세스를 소유하고 있습니다 (PID ${result.process.pid()})"}
        return result.process
    }
    override fun adopt(id:String,afterSeq:Long):Process? {val c=connection() ?: return null;c.onConsole=onConsole;return c.adopt(id,afterSeq)}
    override fun adoptable():List<String> {val c=connection() ?: return emptyList();return runCatching {c.status().filter {it.managed && it.alive}.map {it.id}}.getOrDefault(emptyList())}
    override fun released(id:String,pid:Long){runCatching {client?.ackExit(id,pid)}}
    fun hostStatus():List<HostInstanceStatus> =runCatching {connection()?.status()}.getOrNull() ?: emptyList()
    /** Relay control traffic goes to the role that lives in the host; manager-bound output comes back through [onRelay]. */
    fun relayForward(json:String):Boolean=connection()?.relayForward(json) ?: false
    fun relaySession():Boolean=connection()?.relaySession() ?: false
    fun onRelay(handler:(String)->Unit){relayOutput=handler;connection()?.onRelay=handler}
    fun hostProtocol():Int?=client?.takeIf {!it.disconnected}?.hostProtocol
    fun hostVersion():String?=client?.takeIf {!it.disconnected}?.hostVersion
    /** Detaches the controller. Games keep running; this is what an update or a shutdown must call. */
    override fun close(){client?.close();client=null}
}

object GameHostMain {
    fun packagedJar(type:Class<*>):String? {val location=runCatching {type.protectionDomain.codeSource.location.toString()}.getOrNull() ?: return null;return resolveJarLocation(location)}
    fun resolveJarLocation(location:String):String? {
        val raw=location.removePrefix("jar:")
        val file=if(raw.startsWith("nested:"))"file:"+raw.removePrefix("nested:").substringBefore("/!") else raw.substringBefore("!")
        return runCatching {Path.of(java.net.URI(file))}.getOrNull()?.takeIf {it.isRegularFile() && it.toString().endsWith(".jar")}?.toString()
    }
    /** `--game-host`: runs until the process is terminated. Games are never stopped when the host handle goes away. */
    fun run(home:Path,version:String) {
        System.setOut(java.io.PrintStream(System.out,true,Charsets.UTF_8))
        val service=GameHostService(home,version)
        if(!service.start()){println("상주 게임 서비스가 이미 실행 중입니다");return}
        println("상주 게임 서비스 시작 · 프로토콜 ${GameHostProtocol.VERSION} · 포트 ${service.port()}")
        Thread.currentThread().join()
    }
    private fun quote(value:String)="\"" + value.replace("\"","\\\"") + "\""
    /**
     * Starts the host so that it outlives the process (and login-task job) that launched it.
     * macOS: an independent transient launchd job (not a child of the controller job). Windows: a WMI-created process, which is not part of a Task Scheduler job.
     */
    fun spawnDetached(home:Path,javaBin:String,jar:String,windows:Boolean,extraEnv:Map<String,String> =emptyMap()):Boolean {
        home.createDirectories()
        val log=home.resolve("host.log")
        val args=listOf(javaBin,"-jar",jar,"--game-host")
        val mac=System.getProperty("os.name").startsWith("Mac")
        val builder=if(mac) {
            // nohup only ignores SIGHUP; launchd can still terminate a controller's entire process group.
            // A separately submitted job gives the host its own process group and lifetime.
            val endpoint=GameHostClient.readEndpoint(home)
            if(endpoint!=null && ProcessHandle.of(endpoint.pid).map {it.isAlive}.orElse(false))return false
            if(HostLifecycle.liveGames(home).isNotEmpty())return false
            val label="local.servermanager.game-host."+java.security.MessageDigest.getInstance("SHA-256").digest(home.toAbsolutePath().normalize().toString().toByteArray()).take(12).joinToString(""){"%02x".format(it)}
            val removed=ProcessBuilder("/bin/launchctl","remove",label).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if(!removed.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){removed.destroyForcibly();return false}
            val inherited=System.getenv("RUNNER_TCP_RELAY_ENABLED")?.let {mapOf("RUNNER_TCP_RELAY_ENABLED" to it)} ?: emptyMap()
            val env=(inherited+extraEnv).entries.map {it.key+"="+it.value}
            atomicText(home.resolve("host-launch-label.txt"),label)
            ProcessBuilder(listOf("/bin/launchctl","submit","-l",label,"-o",log.toString(),"-e",log.toString(),"--","/usr/bin/env")+env+args+listOf("--host-home="+home.toString()))
        } else if(windows) {
            val command=args.joinToString(" ") {quote(it)}+" --host-home="+quote(home.toString())
            ProcessBuilder("powershell","-NoProfile","-NonInteractive","-Command",
                "\$r=Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine='"+command.replace("'","''")+"'};if(\$r.ReturnValue -ne 0){exit 1}")
        } else {
            val shell=args.joinToString(" ") {"'"+it.replace("'","'\\''")+"'"}+" --host-home='"+home.toString().replace("'","'\\''")+"' >>'"+log.toString().replace("'","'\\''")+"' 2>&1 &"
            ProcessBuilder("/bin/sh","-c","nohup $shell")
        }
        builder.environment().putAll(extraEnv)
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val process=builder.start()
        if(!process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS))return false
        if(process.exitValue()!=0)return false
        val end=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<end){if(GameHostClient.readEndpoint(home)!=null)return true;Thread.sleep(200)}
        return false
    }
}

/** Desktop manager side: puts local Minecraft processes under the resident host when running from the installed JAR. */
@org.springframework.stereotype.Service
class HostedLocalGamesService(private val manager:ServerManager) {
    private val logger=org.slf4j.LoggerFactory.getLogger(HostedLocalGamesService::class.java)
    @Volatile var modeState=HostModeState()
    private var games:HostedGames?=null
    private fun jar()=GameHostMain.packagedJar(HostedLocalGamesService::class.java)
    @jakarta.annotation.PostConstruct fun attach() {
        // Only the installed JAR can start a separate host JVM; development and test runs keep the in-process layout.
        val jar=jar() ?: run {modeState=HostModeState("LEGACY","설치된 JAR가 아닌 개발·테스트 실행");return}
        val home=manager.root.resolve("game-host")
        val store=HostModeStore(home)
        val state=store.evaluate(emptyList())
        modeState=state
        if(state.mode!="SERVICE")return
        val windows=System.getProperty("os.name").startsWith("Windows")
        val java=Path.of(System.getProperty("java.home"),"bin",if(windows)"java.exe" else "java").toString()
        val hosted=HostedGames(home,"desktop-manager",{GameHostMain.spawnDetached(home,java,jar,windows)})
        if(hosted.connection()==null){
            check(HostLifecycle.liveGames(home).isEmpty() && GameHostClient.readEndpoint(home)?.let {ProcessHandle.of(it.pid).map {p->p.isAlive}.orElse(false)}!=true) {"실행 중인 게임의 상주 서비스에 재접속하지 못했습니다. 새 버전 시작을 중단합니다."}
            modeState=store.fallback("상주 서비스를 시작하지 못해 이전 방식으로 실행합니다: ${hosted.lastError}")
            logger.warn("Game host unavailable: {}",hosted.lastError);return
        }
        games=hosted;manager.localLauncher=hosted
        val adopted=manager.adoptHostedGames()
        if(adopted.isNotEmpty())logger.info("Re-attached {} hosted game(s)",adopted.size)
    }
    @jakarta.annotation.PreDestroy fun detach(){manager.localLauncher=null;games?.close()}
}

/** Safe replacement of the resident host itself: only when it owns no live game, never by guessing. */
object HostLifecycle {
    private val mapper=jacksonObjectMapper()
    private data class StateRow(val id:String,val pid:Long,val startMillis:Long,val exitCode:Int?=null,val exitAcked:Boolean=false)
    private data class StateFile(val instances:List<StateRow> =emptyList())
    /** Live games recorded by the host, verified by PID and start time so a recycled PID is never mistaken for a game. */
    fun liveGames(home:Path):List<String> {
        val file=home.resolve("host/state.json");if(!file.isRegularFile())return emptyList()
        val state=runCatching {mapper.readValue(file.readText(),StateFile::class.java)}.getOrNull() ?: return listOf("<unreadable-state>")
        return state.instances.filter {row->row.exitCode==null && ProcessHandle.of(row.pid).map {h->h.isAlive && h.info().startInstant().map {s->s.toEpochMilli()}.orElse(-1)==row.startMillis}.orElse(false)}.map {it.id}
    }
    /**
     * Terminates the host for [home] only if it records no live game. Returns null on success or the refusal reason.
     * Used for service updates and for hosts whose protocol this controller cannot speak.
     */
    fun replaceIfIdle(home:Path):String? {
        val live=liveGames(home)
        if(live.isNotEmpty())return "GAME_RUNNING"
        val endpoint=GameHostClient.readEndpoint(home) ?: return null
        val handle=ProcessHandle.of(endpoint.pid).orElse(null) ?: return null
        // Identity check: the PID must still be a host JVM, not an unrelated process that reused it.
        // Exact start time recorded by the host itself; the command line is only a fallback for endpoints written by older hosts.
        val identified=if(endpoint.startMillis>0)handle.info().startInstant().map {it.toEpochMilli()}.orElse(-1)==endpoint.startMillis
            else (handle.info().commandLine().orElse("")+handle.info().arguments().map {it.joinToString(" ")}.orElse("")).contains("--game-host")
        if(!identified)return "NOT_A_HOST_PROCESS"
        val client=try {GameHostClient.connect(home,"host-replacement",retryMillis=1000)} catch(e:GameHostRejected){return e.reason} catch(_:Exception){return "HOST_UNREACHABLE"}
        try {
            val state=client.prepareReplacementState()
            if(!state.ready){client.cancelReplacement();return if(state.state=="DRAINING")"RELAY_STREAMS_ACTIVE" else "HOST_NOT_PREPARED"}
        } catch(e:GameHostRejected){return e.reason} catch(_:Exception){return "HOST_UNREACHABLE"} finally {client.close()}
        handle.destroy()
        if(!runCatching {handle.onExit().get(10,java.util.concurrent.TimeUnit.SECONDS)}.isSuccess){handle.destroyForcibly()}
        runCatching {home.resolve("host/endpoint.json").deleteIfExists()}
        return null
    }
    private fun hostIdentified(endpoint:HostEndpoint,handle:ProcessHandle):Boolean {
        // Exact start time recorded by the host itself; the command line is only a fallback for endpoints written by older hosts.
        return if(endpoint.startMillis>0)handle.info().startInstant().map {it.toEpochMilli()}.orElse(-1)==endpoint.startMillis
            else (handle.info().commandLine().orElse("")+handle.info().arguments().map {it.joinToString(" ")}.orElse("")).contains("--game-host")
    }
    /** [done] means the host exited and its endpoint was cleared. Otherwise [reason] says why it was not replaced; [streams] counts relay players still draining. */
    data class Replacement(val done:Boolean,val reason:String?=null,val streams:Int=0)
    /**
     * One non-blocking step of a host replacement through the controller's own connection (a second connection would be refused
     * as CONTROLLER_ACTIVE). The host refuses while a game is alive, blocks new starts and relay players, and reports READY only
     * after established relay streams ended on their own. The PID must match the authenticated host and its recorded start time.
     */
    fun replaceIdleHost(home:Path,client:GameHostClient):Replacement {
        fun back(reason:String):Replacement {client.cancelReplacement();return Replacement(false,reason)}
        if(liveGames(home).isNotEmpty())return back("GAME_RUNNING")
        val endpoint=GameHostClient.readEndpoint(home) ?: return Replacement(false,"HOST_ENDPOINT_MISSING")
        if(client.hostPid>0 && client.hostPid!=endpoint.pid)return Replacement(false,"HOST_IDENTITY_MISMATCH")
        val handle=ProcessHandle.of(endpoint.pid).orElse(null) ?: return Replacement(false,"HOST_PROCESS_MISSING")
        if(!hostIdentified(endpoint,handle))return Replacement(false,"NOT_A_HOST_PROCESS")
        val state=try {client.prepareReplacementState()} catch(e:GameHostRejected){return Replacement(false,e.reason)} catch(_:Exception){return Replacement(false,"HOST_UNREACHABLE")}
        if(!state.ready)return Replacement(false,if(state.state=="DRAINING")"RELAY_STREAMS_ACTIVE" else state.state,state.streams)
        // The flag is up, so nothing new can start; verify once more from the outside before ending the process.
        if(liveGames(home).isNotEmpty())return back("GAME_RUNNING")
        if(!hostIdentified(endpoint,handle) || !handle.isAlive)return back("NOT_A_HOST_PROCESS")
        client.close()
        handle.destroy()
        if(!runCatching {handle.onExit().get(10,java.util.concurrent.TimeUnit.SECONDS)}.isSuccess)return Replacement(false,"HOST_DID_NOT_EXIT")
        // Only the endpoint that still names the process that just exited is removed.
        if(GameHostClient.readEndpoint(home)?.pid==endpoint.pid)runCatching {home.resolve("host/endpoint.json").deleteIfExists()}
        return Replacement(true)
    }
    data class Preflight(val ok:Boolean,val reason:String?=null,val detail:String?=null)
    /**
     * Run before the control layer reports itself healthy. If it cannot talk to a host that owns a live game, it must not
     * come up (the supervisor then rolls back to the previous version) instead of leaving the game uncontrolled.
     */
    fun preflight(home:Path,protocol:Int=GameHostProtocol.VERSION,minHost:Int=1):Preflight {
        if(GameHostClient.readEndpoint(home)==null)return Preflight(true)
        return try {
            GameHostClient.connect(home,"preflight",protocol,minHost,retryMillis=20000).close();Preflight(true)
        } catch(e:GameHostRejected) {
            when(e.reason) {
                "PROTOCOL_INCOMPATIBLE" -> Preflight(false,"HOST_PROTOCOL_INCOMPATIBLE","기존 상주 서비스의 게임·중계 유휴 상태를 인증해 확인할 수 없어 새 제어 계층 시작을 보류합니다")
                "CONTROLLER_ACTIVE" -> Preflight(false,"CONTROLLER_ACTIVE","이전 제어 계층이 아직 종료되지 않았습니다")
                else -> Preflight(false,e.reason)
            }
        } catch(e:java.io.IOException) {
            // Endpoint file without a listening host: stale, nothing to protect unless a game process is recorded alive.
            if(liveGames(home).isEmpty() && GameHostClient.readEndpoint(home)?.let {ProcessHandle.of(it.pid).map {p->p.isAlive}.orElse(false)}!=true)Preflight(true,"STALE_ENDPOINT") else Preflight(false,"HOST_UNREACHABLE_WITH_LIVE_GAME","기존 상주 서비스의 유휴 상태를 확인하지 못했습니다")
        }
    }
}
