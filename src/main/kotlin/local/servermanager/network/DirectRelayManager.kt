package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

/**
 * Direct relay settings. [relayRunnerId] is the live relay; [candidateRunnerId] is being prepared; [retiring] keeps an old
 * relay serving existing and DNS-lagging players until the given epoch-millis, then drains it.
 */
data class DirectRelayConfig(val enabled:Boolean=false,val relayRunnerId:String?=null,val candidateRunnerId:String?=null,val securePort:Int=6400,
    val agentHost:String="",val publicHost:String="",val retiring:Map<String,Long> =emptyMap(),val graceSeconds:Long=600,val relayHosts:Map<String,RelayHostSpec> =emptyMap())
data class RelayHostSpec(val agentHost:String="",val publicHost:String="")
private data class DirectRelayDisk(val config:DirectRelayConfig=DirectRelayConfig(),val revision:Long=0,val epochs:Map<String,Long> =emptyMap())
data class DirectRelaySave(val config:DirectRelayConfig,val revision:Long)

class DirectRelayStore(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("settings.json")
    private var disk:DirectRelayDisk
    init {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(file))
        directory.createDirectories()
        runCatching {Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))}
        disk=if(file.exists())mapper.readValue(file.readText()) else DirectRelayDisk()
    }
    @Synchronized fun config()=disk.config
    @Synchronized fun revision()=disk.revision
    @Synchronized fun epoch(relay:String,agent:String)=disk.epochs["$relay:$agent"] ?: 1L
    /** Epochs only move on revocation/reassignment, so a manager restart never cuts healthy tunnels. */
    @Synchronized fun bumpEpochs(match:(String,String)->Boolean) {
        val now=System.currentTimeMillis()
        val next=disk.epochs.toMutableMap()
        disk.epochs.keys.forEach {key->val (r,a)=key.split(':');if(match(r,a))next[key]=maxOf(now,(next[key] ?: 0)+1)}
        persist(disk.copy(epochs=next))
    }
    @Synchronized fun ensureEpoch(relay:String,agent:String) {if("$relay:$agent" !in disk.epochs)persist(disk.copy(epochs=disk.epochs+("$relay:$agent" to 1L)))}
    @Synchronized fun save(request:DirectRelaySave):Long {
        check(request.revision==disk.revision) {"직접 중계 설정이 다른 화면에서 변경되었습니다"}
        persist(disk.copy(config=request.config,revision=disk.revision+1));return disk.revision
    }
    private fun persist(next:DirectRelayDisk) {
        val tmp=Files.createTempFile(directory,".direct-relay-",".tmp")
        try {tmp.writeBytes(mapper.writeValueAsBytes(next));Files.move(tmp,file,ATOMIC_MOVE,REPLACE_EXISTING)} finally {tmp.deleteIfExists()}
        disk=next
    }
}

/** What the manager must know about the rest of the app; kept narrow so the relay logic is testable in isolation. */
interface RelayWorld {
    /** Registered active devices remain visible even before the first capability report. */
    fun registeredRunnerIds():Set<String> = emptySet()
    fun runner(id:String):RunnerDevice?
    fun connected(id:String):Boolean
    fun runnerName(id:String):String
    /** Instances assigned to remote runners: (instanceId, runnerId, hostnames that must reach it). */
    fun routes():List<Triple<String,String,List<String>>>
    fun send(runnerId:String,message:RunnerMessage):Boolean
    fun resolveHost(host:String):List<String>
    fun gamePort():Int
}

data class RelayCheck(val status:String,val detail:String)
data class RelayRouterRow(val label:String,val externalPort:Int,val internalAddress:String?,val internalPort:Int)
data class RelayRunnerView(val runnerId:String,val name:String,val role:String,val connected:Boolean,val capable:Boolean,
    val consentAllowed:Boolean,val consentSource:String,val consentMigrated:Boolean,val consentLockedByEnvironment:Boolean,
    val active:Boolean,val ready:Boolean,val streams:Int,val internalAddresses:List<String>,val publicHost:String,val gamePort:Int,val securePort:Int,
    val router:List<RelayRouterRow>,val diagnostics:Map<String,RelayCheck>,val impactIfCut:String)
data class AgentRunnerView(val runnerId:String,val name:String,val relays:List<AgentRelayStatus>)
data class DirectRelayView(val revision:Long,val config:DirectRelayConfig,val phase:String,val relays:List<RelayRunnerView>,val agents:List<AgentRunnerView>,val message:String,val gamePort:Int)

class DirectRelayManager(val store:DirectRelayStore,private val world:RelayWorld,private val clock:()->Long={System.currentTimeMillis()}) {
    private val mapper=jacksonObjectMapper()
    private val secrets=ConcurrentHashMap<String,ByteArray>()
    private val reports=ConcurrentHashMap<String,RunnerRelayReport>()
    private val consents=ConcurrentHashMap<String,RelayConsentState>()
    private val seenAt=ConcurrentHashMap<String,Long>()
    private val generation=java.util.concurrent.atomic.AtomicLong(clock())
    private val cuts=ConcurrentHashMap<String,Long>()
    private val externalChecks=ConcurrentHashMap<String,Pair<Long,RelayCheck>>()
    private val sentHost=ConcurrentHashMap<String,String>()
    private val sentAgent=ConcurrentHashMap<String,String>()

    fun consent(id:String)=consents[id]
    fun onConsent(id:String,state:RelayConsentState){consents[id]=state}
    fun onReport(id:String,report:RunnerRelayReport){reports[id]=report;consents[id]=report.consent;seenAt[id]=clock()}
    /** Entry point for the authenticated WSS session of [id]; returns true when the message belonged to the direct relay protocol. */
    fun onRunnerMessage(id:String,message:RunnerMessage):Boolean {
        when(message.type) {
            "relay-consent" -> runCatching {onConsent(id,mapper.readValue<RelayConsentState>(message.detail ?: return true))}
            "drp-status" -> runCatching {onReport(id,mapper.readValue<RunnerRelayReport>(message.detail ?: return true))}
            "drp-ticket-request" -> if(message.token!=null && message.detail!=null)ticket(id,message.detail,message.token)
            else -> return false
        }
        return true
    }
    fun onDisconnect(id:String){reports.remove(id);consents.remove(id);seenAt.remove(id);sentHost.remove(id);sentAgent.remove(id)}

    private fun config()=store.config()
    private fun agentHostFor(id:String):String {val c=config();return c.relayHosts[id]?.agentHost?.ifBlank {null} ?: c.agentHost.ifBlank {c.publicHost}}
    private fun publicHostFor(id:String):String {val c=config();return c.relayHosts[id]?.publicHost?.ifBlank {null} ?: c.publicHost.ifBlank {c.agentHost}}
    private fun relays():Map<String,String> {
        val c=config();val out=linkedMapOf<String,String>()
        if(!c.enabled)return out
        c.relayRunnerId?.let {out[it]="ACTIVE"};c.candidateRunnerId?.let {out.putIfAbsent(it,"CANDIDATE")}
        c.retiring.forEach {(id,until)->if(until>clock())out.putIfAbsent(id,"RETIRING")}
        return out
    }
    private fun relayUsable(id:String)=world.runner(id)!=null && world.connected(id) && consents[id]?.allowed==true
    private fun routesFor(relay:String):List<Triple<String,String,List<String>>> =
        if(cuts.containsKey(relay))emptyList() else world.routes().filter {(instance,runner,_)->world.runner(runner)?.allowedInstanceIds?.contains(instance)==true}
    private fun secret(relay:String)=secrets.computeIfAbsent(relay){DirectRelayCrypto.newSecret()}

    /** Pushes desired relay/agent state to the runners. Safe to call repeatedly; unchanged payloads are not resent. */
    @Synchronized fun reconcile() {
        val c=config();val active=relays()
        val routes=world.routes().filter {(instance,runner,_)->world.runner(runner)?.allowedInstanceIds?.contains(instance)==true}
        val agentIds=routes.map {it.second}.toSet()
        // Relay hosts
        (active.keys+reports.keys.filter {reports[it]?.hostActive==true}).forEach {relay->
            val role=active[relay]
            if(!world.connected(relay)){sentHost.remove(relay);return@forEach}
            if(role==null || !relayUsable(relay)) {
                if(reports[relay]?.hostActive==true && sentHost[relay]!="OFF"){
                    val off=RelayHostConfig(false,relay,Base64.getUrlEncoder().withoutPadding().encodeToString(secret(relay)),world.gamePort(),c.securePort,RelayPolicy(0,emptyMap(),emptyList()),drain=role==null && cuts[relay]==null,drainSeconds=c.graceSeconds)
                    if(world.send(relay,RunnerMessage("drp-relay-config",detail=mapper.writeValueAsString(off))))sentHost[relay]="OFF"
                }
                return@forEach
            }
            val usableRoutes=routesFor(relay)
            usableRoutes.forEach {(_,runner,_)->store.ensureEpoch(relay,runner)}
            val policy=RelayPolicy(generation.updateAndGet {maxOf(it+1,clock())},
                usableRoutes.map {it.second}.toSet().associateWith {store.epoch(relay,it)},
                usableRoutes.flatMap {(instance,runner,hosts)->hosts.map {RelayRoute(it,runner,instance)}})
            val signature=mapper.writeValueAsString(policy.copy(generation=0))+c.securePort+world.gamePort()
            if(sentHost[relay]==signature && reports[relay]?.hub?.gamePort!=null)return@forEach
            val host=RelayHostConfig(true,relay,Base64.getUrlEncoder().withoutPadding().encodeToString(secret(relay)),world.gamePort(),c.securePort,policy)
            if(world.send(relay,RunnerMessage("drp-relay-config",detail=mapper.writeValueAsString(host))))sentHost[relay]=signature
        }
        // Game runners dial every live relay target (active + candidate + retiring)
        agentIds.forEach {agent->
            val targets=active.keys.filter {relayUsable(it)}.map {AgentRelayTarget(it,agentHostFor(it),c.securePort)}
            val instances=routes.filter {it.second==agent}.map {it.first}.toSet()
            val cfg=AgentRelayConfig(targets,instances,c.graceSeconds)
            val body=mapper.writeValueAsString(cfg)
            if(sentAgent[agent]==body)return@forEach
            if(targets.isNotEmpty() || sentAgent.containsKey(agent))if(world.send(agent,RunnerMessage("drp-agent-config",detail=body)))sentAgent[agent]=body
        }
    }

    /** Ticket gate: the game runner must be the approved agent for at least one route on that live, consenting relay. */
    fun ticket(agent:String,relay:String,token:String) {
        fun deny(reason:String){world.send(agent,RunnerMessage("drp-ticket",token=token,state="DENIED",detail=reason))}
        if(world.runner(agent)==null || !world.connected(agent))return deny("AGENT_NOT_AUTHORIZED")
        if(relay !in relays())return deny("RELAY_NOT_ASSIGNED")
        if(cuts.containsKey(relay))return deny("RELAY_CUT")
        if(consents[relay]?.allowed!=true)return deny("RELAY_CONSENT_DENIED")
        if(!world.connected(relay))return deny("RELAY_OFFLINE")
        if(routesFor(relay).none {it.second==agent})return deny("NO_APPROVED_ROUTE")
        val ticket=DirectRelayCrypto.issue(secret(relay),relay,agent,store.epoch(relay,agent),120_000,clock())
        val json=mapper.writeValueAsString(mapOf("body" to ticket.body,"key" to Base64.getUrlEncoder().withoutPadding().encodeToString(ticket.key)))
        world.send(agent,RunnerMessage("drp-ticket",token=token,state="OK",detail=json))
    }

    @Synchronized fun save(request:DirectRelaySave):DirectRelayView {
        val c=request.config
        val previous=config()
        check(!c.enabled || !previous.enabled || previous.relayRunnerId==null || c.relayRunnerId==previous.relayRunnerId) {"중계 대상 변경은 새 중계 준비·전환을 사용하세요"}
        require(c.securePort in 1024..65535 && c.securePort!=world.gamePort()) {"실행기 연결 포트는 1024~65535이며 게임 포트와 달라야 합니다"}
        listOf(c.agentHost,c.publicHost).forEach {require(it.isEmpty() || (it.length<=253 && it.matches(Regex("[A-Za-z0-9.-]+")))) {"호스트 이름 또는 IPv4 주소만 입력하세요"}}
        require(c.graceSeconds in 0..86400)
        if(c.enabled){
            require(c.relayRunnerId!=null || c.candidateRunnerId!=null) {"중계 실행기를 선택하세요"}
            c.relayRunnerId?.let {require(world.runner(it)!=null) {"등록된 실행기를 선택하세요"}}
            // Turning a relay on for the first time needs local consent; later changes go through prepare/commit.
            if(!previous.enabled || previous.relayRunnerId!=c.relayRunnerId)c.relayRunnerId?.let {require(consents[it]?.allowed==true && world.connected(it)) {"실행기 소유자가 로컬에서 TCP 중계 사용을 허용하고 온라인이어야 합니다"}}
            require(c.agentHost.isNotBlank() || c.publicHost.isNotBlank()) {"게임 실행기가 연결할 중계 주소를 입력하세요"}
        }
        store.save(request);cuts.clear();reconcile();return view()
    }
    @Synchronized fun prepare(runner:String,spec:RelayHostSpec?=null):DirectRelayView {
        val c=config();check(c.enabled && c.relayRunnerId!=null) {"먼저 직접 중계를 켜세요"}
        require(runner!=c.relayRunnerId) {"이미 사용 중인 중계입니다"}
        require(world.runner(runner)!=null && consents[runner]?.allowed==true && world.connected(runner)) {"대상 실행기가 온라인이고 로컬 허용이 필요합니다"}
        spec?.let {require(listOf(it.agentHost,it.publicHost).all {h->h.isEmpty() || h.matches(Regex("[A-Za-z0-9.-]{1,253}"))}) {"호스트 이름 또는 IPv4 주소만 입력하세요"}}
        store.save(DirectRelaySave(c.copy(candidateRunnerId=runner,relayHosts=if(spec!=null)c.relayHosts+(runner to spec) else c.relayHosts),store.revision()));reconcile();return view()
    }
    /** Switches only when the candidate is ready; the old relay keeps serving existing and DNS-lagging players for the grace period. */
    @Synchronized fun commit():DirectRelayView {
        val c=config();val candidate=c.candidateRunnerId ?: error("준비 중인 대상이 없습니다")
        check(isReady(candidate)) {"새 중계 대상이 아직 준비되지 않았습니다. 진단을 확인하세요"}
        val retiring=c.retiring+(c.relayRunnerId?.let {it to clock()+c.graceSeconds*1000} ?: ("" to 0L))-""
        store.save(DirectRelaySave(c.copy(relayRunnerId=candidate,candidateRunnerId=null,retiring=retiring),store.revision()));reconcile();return view()
    }
    @Synchronized fun cancelCandidate():DirectRelayView {
        val c=config();store.save(DirectRelaySave(c.copy(candidateRunnerId=null),store.revision()));reconcile();return view()
    }
    /** Immediate cut for consent withdrawal or emergencies. Returns how many live game streams were affected. */
    @Synchronized fun cut(runner:String):Int {
        val impacted=reports[runner]?.hub?.agents?.sumOf {it.streams} ?: 0
        cuts[runner]=clock();store.bumpEpochs {r,_->r==runner};reconcile();return impacted
    }
    fun isReady(relay:String):Boolean {
        val r=reports[relay] ?: return false;val hub=r.hub ?: return false
        if(!relayUsable(relay) || !hub.gameListening || !hub.secureListening || hub.lastBindError!=null)return false
        val needed=routesFor(relay).map {it.second}.toSet()
        return needed.isNotEmpty() && needed.all {agent->hub.agents.any {it.agentId==agent && it.epoch==store.epoch(relay,agent)}}
    }
    private fun diag(status:String,detail:String)=RelayCheck(status,detail)

    fun view():DirectRelayView {
        val c=config();val roles=relays();val all=(world.registeredRunnerIds()+roles.keys+reports.keys+consents.keys).sortedBy {world.runnerName(it)}
        val gamePort=world.gamePort()
        val relayViews=all.filter {world.runner(it)!=null}.map {id->
            val role=roles[id] ?: "NONE";val rep=reports[id];val consent=consents[id];val hub=rep?.hub
            val hosts=publicHostFor(id)
            val internal=rep?.addresses.orEmpty()
            val resolved=if(hosts.isBlank())emptyList() else runCatching {world.resolveHost(hosts)}.getOrDefault(emptyList())
            val external=externalChecks[id]?.second ?: diag("UNVERIFIED","인터넷 바깥에서의 접속은 검증하지 않았습니다. 로컬 포트가 열려 있어도 외부 접속 성공을 뜻하지 않습니다")
            val agentsConnected=hub?.agents?.size ?: 0
            val needed=routesFor(id).map {it.second}.toSet()
            RelayRunnerView(id,world.runnerName(id),role,world.connected(id),rep?.hub!=null || consent!=null,consent?.allowed==true,consent?.source ?: "unknown",consent?.migratedFrom!=null,consent?.environmentLocked==true,
                rep?.hostActive==true,isReady(id),hub?.agents?.sumOf {it.streams} ?: 0,internal,hosts,gamePort,c.securePort,
                listOf(RelayRouterRow("게임 접속 (플레이어 → 중계)",gamePort,internal.firstOrNull(),gamePort),RelayRouterRow("실행기 연결 (게임 실행기 → 중계)",c.securePort,internal.firstOrNull(),c.securePort)),
                mapOf(
                    "consent" to when {consent==null->diag("UNKNOWN","중계 상태 보고를 기다리고 있습니다. 실행기 버전과 연결을 확인하세요");consent.allowed->diag("OK","로컬 허용: ${consent.source}");else->diag("DENIED","실행기 로컬 설정에서 허용되지 않았습니다")},
                    "portConflict" to when {hub==null -> diag("UNKNOWN","중계가 시작되지 않았습니다"); hub.lastBindError!=null -> diag("CONFLICT",hub.lastBindError); hub.gameListening && hub.secureListening -> diag("OK","게임 포트와 연결 포트를 열었습니다"); else -> diag("CLOSED","포트가 닫혀 있습니다")},
                    "agentConnection" to when {role=="NONE" -> diag("UNKNOWN","중계 대상이 아닙니다"); needed.isEmpty() -> diag("NO_ROUTE","승인된 서버 경로가 없습니다"); agentsConnected>=needed.size && isReady(id) -> diag("OK","게임 실행기 ${agentsConnected}대 연결"); else -> diag("WAITING","게임 실행기 연결 대기 ${agentsConnected}/${needed.size}")},
                    "dnsTarget" to when {hosts.isBlank() -> diag("UNSET","중계 공개 주소가 설정되지 않았습니다"); resolved.isEmpty() -> diag("UNRESOLVED","$hosts 를 조회하지 못했습니다"); else -> diag("RESOLVED","$hosts → ${resolved.joinToString()} (이 실행기 소유 주소인지는 검증하지 않습니다)")},
                    "externalReachability" to external),
                (hub?.agents?.sumOf {it.streams} ?: 0).let {if(it>0)"즉시 차단하면 진행 중인 접속 ${it}개가 끊깁니다" else "진행 중인 접속 없음"})
        }
        val agentViews=reports.filter {it.value.agents.isNotEmpty()}.map {(id,rep)->AgentRunnerView(id,world.runnerName(id),rep.agents)}
        val phase=when {!c.enabled -> "OFF"; c.candidateRunnerId!=null -> if(isReady(c.candidateRunnerId)) "CANDIDATE_READY" else "PREPARING"; c.relayRunnerId!=null && isReady(c.relayRunnerId) -> "ACTIVE"; c.relayRunnerId!=null -> "WAITING"; else -> "OFF"}
        val message=when(phase){"OFF"->"직접 중계 꺼짐";"ACTIVE"->"직접 중계 준비 완료";"CANDIDATE_READY"->"새 중계 대상 준비 완료 · 전환 가능";"PREPARING"->"새 중계 대상 준비 중";else->"중계 준비 대기 · 진단을 확인하세요"}
        return DirectRelayView(store.revision(),c,phase,relayViews,agentViews,message,gamePort)
    }

    /**
     * Connect test against ONLY the configured relay host and ports, from the manager's own network.
     * It never accepts a caller-supplied target and its result says where it was measured from.
     */
    fun externalCheck(relay:String):RelayCheck {
        val c=config();require(relay in relays()) {"설정된 중계 실행기만 검사할 수 있습니다"}
        val last=externalChecks[relay]?.first ?: 0
        require(clock()-last>=10_000) {"검사는 10초에 한 번만 실행할 수 있습니다"}
        val host=publicHostFor(relay).ifBlank {null} ?: error("중계 공개 주소를 먼저 설정하세요")
        val results=listOf(world.gamePort(),c.securePort).map {port->
            runCatching {Socket().use {it.connect(InetSocketAddress(host,port),3000)}}.isSuccess
        }
        val check=RelayCheck(if(results.all {it})"REACHABLE_FROM_MANAGER" else "NOT_REACHABLE_FROM_MANAGER",
            "관리 서버 위치에서 $host 의 게임 포트=${if(results[0])"열림" else "닫힘"}, 연결 포트=${if(results[1])"열림" else "닫힘"}. 플레이어가 있는 외부 인터넷에서의 성공을 보장하지 않습니다")
        externalChecks[relay]=clock() to check;return check
    }
}

@Service
class DirectRelayService(private val registry:RunnerRegistry,private val manager:ServerManager,private val network:GameNetwork,private val auth:AuthStore) {
    @Volatile var sender:(String,RunnerMessage)->Boolean={_,_->false}
    @Volatile var connectedCheck:(String)->Boolean={false}
    val core=DirectRelayManager(DirectRelayStore(manager.root.resolve("security/direct-relay")),object:RelayWorld {
        override fun registeredRunnerIds()=registry.activeIds()
        override fun runner(id:String)=registry.active(id)
        override fun connected(id:String)=connectedCheck(id)
        override fun runnerName(id:String)=registry.device(id)?.name ?: id
        override fun routes():List<Triple<String,String,List<String>>> {
            val config=network.config()
            if(!config.enabled)return emptyList()
            return manager.list().mapNotNull {row->
                val instance=row["config"] as Instance
                val runner=instance.runnerId ?: return@mapNotNull null
                val hosts=mutableListOf(GameDomains.hostname(config,instance.id))
                if(config.defaultInstanceId==instance.id)listOf(config.defaultHostname,config.targetHost).filter {it.isNotBlank()}.forEach {hosts.add(GameDomains.host(it))}
                Triple(instance.id,runner,hosts.distinct())
            }
        }
        override fun send(runnerId:String,message:RunnerMessage)=sender(runnerId,message)
        override fun resolveHost(host:String)=InetAddress.getAllByName(host).map {it.hostAddress}
        override fun gamePort()=network.config().port
    })
    @Scheduled(fixedDelay=3000) fun tick(){runCatching {core.reconcile()}}
}

@RestController
@RequestMapping("/api/admin/direct-relay")
class DirectRelayApi(private val service:DirectRelayService) {
    data class Runner(val runnerId:String)
    private fun noStore(body:Any)=ResponseEntity.ok().header("Cache-Control","no-store").body(body)
    @GetMapping fun get()=noStore(service.core.view())
    @PutMapping fun save(@RequestBody request:DirectRelaySave,req:HttpServletRequest):DirectRelayView {freshDesktop(req);return service.core.save(request)}
    data class Prepare(val runnerId:String,val agentHost:String="",val publicHost:String="")
    @PostMapping("/prepare") fun prepare(@RequestBody request:Prepare,req:HttpServletRequest):DirectRelayView {freshDesktop(req);return service.core.prepare(request.runnerId,RelayHostSpec(request.agentHost,request.publicHost))}
    @PostMapping("/commit") fun commit(req:HttpServletRequest):DirectRelayView {freshDesktop(req);return service.core.commit()}
    @PostMapping("/cancel") fun cancel(req:HttpServletRequest):DirectRelayView {freshDesktop(req);return service.core.cancelCandidate()}
    @PostMapping("/cut") fun cut(@RequestBody request:Runner,req:HttpServletRequest):Map<String,Any> {freshDesktop(req);return mapOf("impactedStreams" to service.core.cut(request.runnerId))}
    @PostMapping("/external-check") fun check(@RequestBody request:Runner,req:HttpServletRequest):RelayCheck {freshDesktop(req);return service.core.externalCheck(request.runnerId)}
}
