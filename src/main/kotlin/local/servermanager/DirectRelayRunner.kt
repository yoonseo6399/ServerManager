package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

/** Local owner's consent to let the management web owner use this computer as a direct TCP relay. Default is OFF. */
data class RelayConsentState(val allowed:Boolean=false,val source:String="default",val migratedFrom:String?=null,val updatedAt:String="",
    val environmentLocked:Boolean=false)

/**
 * The runner never needs a hand-written file: the installer checkbox and `--runner-relay-consent=allow|revoke|status`
 * write `direct-relay.json`. Older explicit grants (`tcp-relay-enabled` containing `true`, or RUNNER_TCP_RELAY_ENABLED=true)
 * are carried over and flagged so the UI can show where the permission came from.
 */
class RelayConsentStore(private val home:Path,private val env:(String)->String?={System.getenv(it)}) {
    private val mapper=jacksonObjectMapper()
    private val file=home.resolve("direct-relay.json")
    private val legacy=home.resolve("tcp-relay-enabled")
    private fun legacyAllowed()=legacy.isRegularFile() && runCatching {legacy.readText().trim()=="true"}.getOrDefault(false)
    @Synchronized fun read():RelayConsentState {
        if(file.exists()) {
            val stored=runCatching {mapper.readValue<RelayConsentState>(file.readText())}.getOrNull() ?: return RelayConsentState(false,"unreadable-denied")
            // Deleting the old marker file was the documented way to revoke; keep honouring it for migrated grants.
            if(stored.allowed && stored.migratedFrom=="tcp-relay-enabled" && !legacyAllowed() && env("RUNNER_TCP_RELAY_ENABLED")!="true")
                return write(RelayConsentState(false,"legacy-marker-removed",stored.migratedFrom,Instant.now().toString()))
            return stored
        }
        if(legacyAllowed())return write(RelayConsentState(true,"migrated","tcp-relay-enabled",Instant.now().toString()))
        if(env("RUNNER_TCP_RELAY_ENABLED")=="true")return RelayConsentState(true,"environment",null,"",environmentLocked=true)
        return RelayConsentState()
    }
    @Synchronized fun set(allowed:Boolean,source:String):RelayConsentState {
        val previous=runCatching {read()}.getOrNull()
        return write(RelayConsentState(allowed,source,previous?.migratedFrom,Instant.now().toString()))
    }
    private fun write(state:RelayConsentState):RelayConsentState {
        home.createDirectories()
        val tmp=Files.createTempFile(home,".relay-consent-",".tmp")
        try {
            tmp.writeBytes(mapper.writeValueAsBytes(state))
            if(!System.getProperty("os.name").startsWith("Windows"))Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"))
            Files.move(tmp,file,ATOMIC_MOVE,REPLACE_EXISTING)
        } finally {tmp.deleteIfExists()}
        return state
    }
}

data class RelayHostConfig(val on:Boolean,val relayId:String,val secret:String,val gamePort:Int,val securePort:Int,val policy:RelayPolicy,
    val drain:Boolean=false,val drainSeconds:Long=600)
data class AgentRelayTarget(val relayId:String,val host:String,val port:Int,val drain:Boolean=false)
data class AgentRelayConfig(val relays:List<AgentRelayTarget>,val instances:Set<String>,val drainSeconds:Long=600)
data class AgentRelayStatus(val relayId:String,val state:String,val reason:String?,val streams:Int,val connectedAt:Long?)
data class RunnerRelayReport(val consent:RelayConsentState,val hub:RelayHubStatus?,val agents:List<AgentRelayStatus>,val hostActive:Boolean,val bindOk:Boolean,val addresses:List<String> =emptyList())

/**
 * Runner side of the direct relay. Owns the relay hub (when this computer is the chosen relay) and one agent per relay target
 * (when it hosts approved servers). The manager only sends control messages; no game bytes pass through [send].
 * Local consent is re-read on every [tick]; revoking it tears the hub down immediately regardless of what the manager says.
 */
class DirectRelayRunnerRole(home:Path,private val send:(RunnerMessage)->Boolean,private val gamePort:(String)->Int?,private val ready:(String)->Boolean,
        private val consent:RelayConsentStore=RelayConsentStore(home),private val backoff:LongArray=longArrayOf(500,1000,2000,5000,10000),private val bindAddress:String="0.0.0.0") {
    private val mapper=jacksonObjectMapper()
    @Volatile private var hub:DirectRelayHub?=null
    @Volatile private var hubConfig:RelayHostConfig?=null
    @Volatile private var lastBindOk=true
    private val agents=ConcurrentHashMap<String,DirectRelayAgent>()
    private val targets=ConcurrentHashMap<String,AgentRelayTarget>()
    @Volatile private var allowedInstances:Set<String> =emptySet()
    private val tickets=ConcurrentHashMap<String,Pair<String,CompletableFuture<RelayTicket?>>>()
    @Volatile private var announced:RelayConsentState?=null

    fun busy()=(hub?.streamCount() ?: 0)>0 || agents.values.any {it.streamCount()>0}
    private fun reportConsent(state:RelayConsentState) {
        announced=state
        send(RunnerMessage("relay-consent",state=if(state.allowed)"ALLOWED" else "DENIED",version="DRP1",detail=mapper.writeValueAsString(state)))
    }
    fun onSession(){reportConsent(consent.read());report()}
    /** Called every heartbeat. Honours a local revocation within one tick without waiting for the manager. */
    fun tick() {
        val state=consent.read()
        if(announced!=state)reportConsent(state)
        if(!state.allowed && hub!=null){hub?.close();hub=null;hubConfig=null}
        report()
    }
    fun report() {
        val h=hub;val state=announced ?: consent.read()
        val body=RunnerRelayReport(state,h?.status(),agents.map {(id,a)->AgentRelayStatus(id,a.state.name,a.reason,a.streamCount(),a.connectedAt)},h!=null,lastBindOk,localAddresses())
        send(RunnerMessage("drp-status",detail=mapper.writeValueAsString(body)))
    }
    /** Returns true when [message] belonged to the direct relay protocol. */
    fun onMessage(message:RunnerMessage):Boolean {
        when(message.type) {
            "drp-relay-config" -> runCatching {host(mapper.readValue<RelayHostConfig>(message.detail ?: return true))}.onFailure {lastBindOk=false}
            "drp-agent-config" -> runCatching {agent(mapper.readValue<AgentRelayConfig>(message.detail ?: return true))}
            "drp-ticket" -> message.token?.let {key->
                val (relayId,future)=tickets[key] ?: return true
                if(message.state=="OK")runCatching {mapper.readTree(message.detail)}.onSuccess {n->future.complete(RelayTicket(n.path("body").asText(),Base64.getUrlDecoder().decode(n.path("key").asText())))}.onFailure {future.complete(null)}
                else {message.detail?.take(40)?.let {denial[relayId]=it};future.complete(null)}
            }
            else -> return false
        }
        report();return true
    }
    private fun host(config:RelayHostConfig) {
        val state=consent.read()
        if(announced!=state)reportConsent(state)
        if(!state.allowed){hub?.close();hub=null;hubConfig=null;return} // remote owner cannot override the local decision
        if(!config.on){hub?.let {if(config.drain)it.drainAndClose(config.drainSeconds*1000) else it.close()};hub=null;hubConfig=null;return}
        require(UUID.fromString(config.relayId).toString()==config.relayId && config.gamePort in 1024..65535 && config.securePort in 1024..65535 && config.gamePort!=config.securePort)
        val secret=Base64.getUrlDecoder().decode(config.secret);require(secret.size==32)
        var active=hub
        if(active==null || hubConfig?.relayId!=config.relayId){active?.close();active=DirectRelayHub(config.relayId,secret);hub=active}
        active.rotateSecret(secret)
        lastBindOk=active.listen(config.gamePort,config.securePort,bindAddress)
        active.applyPolicy(config.policy)
        hubConfig=config
    }
    private fun ticketFor(relayId:String):RelayTicket? {
        val key=UUID.randomUUID().toString();val future=CompletableFuture<RelayTicket?>();tickets[key]=relayId to future
        return try {if(!send(RunnerMessage("drp-ticket-request",token=key,detail=relayId)))null else future.get(4,TimeUnit.SECONDS)}
        catch(_:Exception){null} finally {tickets.remove(key)}
    }
    /** A manager that answers DENIED is different from one that is unreachable; surface the stated reason. */
    private fun endpointFor(target:AgentRelayTarget):RelayEndpoint? {
        val ticket=ticketFor(target.relayId)
        denial.remove(target.relayId)?.let {throw DirectRelayRejected(it)}
        return ticket?.let {RelayEndpoint(target.host,target.port,it)}
    }
    private val denial=ConcurrentHashMap<String,String>()
    private fun agent(config:AgentRelayConfig) {
        allowedInstances=config.instances.toSet()
        config.relays.forEach {target->
            targets[target.relayId]=target
            if(target.drain){agents.remove(target.relayId)?.drain(config.drainSeconds*1000);return@forEach}
            if(agents.containsKey(target.relayId))return@forEach
            val relayId=target.relayId
            val agent=DirectRelayAgent({endpointFor(target)},
                {id->if(id in allowedInstances)gamePort(id) else null},{id->id in allowedInstances && ready(id)},backoff)
            agents[relayId]=agent;agent.start()
        }
        // Targets the manager no longer lists are drained, never cut mid-game.
        agents.keys.filter {id->config.relays.none {it.relayId==id}}.forEach {id->agents.remove(id)?.drain(config.drainSeconds*1000)}
    }
    fun close() {hub?.close();hub=null;agents.values.forEach {it.close()};agents.clear()}
}

/** Private IPv4 addresses to enter in the router's port-forward rule. Never includes loopback or link-local. */
internal fun localAddresses():List<String> = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList().filter {it.isUp && !it.isLoopback && !it.isVirtual}
        .flatMap {it.inetAddresses.toList()}.filterIsInstance<java.net.Inet4Address>().filter {!it.isLoopbackAddress && !it.isLinkLocalAddress}.map {it.hostAddress}.distinct()
}.getOrDefault(emptyList())
