package local.servermanager

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.*
import kotlin.test.*

/** Manager + relay runner + game runner wired through an in-memory control channel that counts every byte it carries. */
class DirectRelayManagerTest {
    private val closeables=mutableListOf<AutoCloseable>()
    private fun <T:AutoCloseable> T.track():T{closeables.add(this);return this}
    @AfterEach fun cleanup(){closeables.reversed().forEach {runCatching {it.close()}}}
    private val host="play.example.com"
    private val controlBytes=AtomicLong()
    private fun freePort()=ServerSocket(0).use {it.localPort}

    private class Echo:AutoCloseable {
        val server=ServerSocket(0);val seen=java.util.concurrent.CopyOnWriteArrayList<String>()
        init {Thread.ofVirtual().start {while(!server.isClosed){val s=runCatching {server.accept()}.getOrNull() ?: break
            Thread.ofVirtual().start {runCatching {val b=ByteArray(65536);val i=s.getInputStream();val o=s.getOutputStream();while(true){val n=i.read(b);if(n<0)break;seen.add(String(b,0,minOf(n,16)));o.write(b,0,n);o.flush()}}}}}}
        override fun close(){server.close()}
    }
    private inner class RunnerNode(val id:String,val dir:Path,val gamePorts:Map<String,Int> =emptyMap(),val ready:()->Boolean={true}) {
        lateinit var role:DirectRelayRunnerRole;val consent=RelayConsentStore(dir){null}
        fun start(world:Harness){role=DirectRelayRunnerRole(dir,{m->controlBytes.addAndGet(m.detail.orEmpty().length.toLong()+64);world.toManager(id,m);true},{gamePorts[it]},{ready()},consent,longArrayOf(50,100),"127.0.0.1").also {world.nodes[id]=this};role.onSession()}
    }
    private inner class Harness(val store:DirectRelayStore,val gamePort:Int):RelayWorld {
        val nodes=ConcurrentHashMap<String,RunnerNode>()
        val devices=ConcurrentHashMap<String,RunnerDevice>()
        val offline=ConcurrentHashMap.newKeySet<String>()
        val instances=mutableListOf<Triple<String,String,List<String>>>()
        lateinit var manager:DirectRelayManager
        fun build(){manager=DirectRelayManager(store,this)}
        fun toManager(from:String,m:RunnerMessage){controlBytes.addAndGet(m.detail.orEmpty().length.toLong()+64);manager.onRunnerMessage(from,m)}
        override fun registeredRunnerIds()=devices.keys.toSet()
        override fun runner(id:String)=devices[id]
        override fun connected(id:String)=nodes.containsKey(id) && !offline.contains(id)
        override fun runnerName(id:String)=id.take(4)
        override fun routes()=instances.toList()
        override fun send(runnerId:String,message:RunnerMessage):Boolean {
            val node=nodes[runnerId] ?: return false;if(offline.contains(runnerId))return false
            controlBytes.addAndGet(message.detail.orEmpty().length.toLong()+64);return node.role.onMessage(message)
        }
        override fun resolveHost(host:String)=listOf("203.0.113.9")
        override fun gamePort()=gamePort
        fun pump(){manager.reconcile();nodes.values.forEach {it.role.tick()}}
    }
    private fun device(id:String,vararg instance:String)=RunnerDevice(id,id.take(4),"MAC","owner","key","now",allowedInstanceIds=instance.toSet())
    private fun id()=UUID.randomUUID().toString()
    private fun await(message:String,timeout:Long=8000,tick:()->Unit={},condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(true){tick();if(condition())return;assertTrue(System.currentTimeMillis()<end,message);Thread.sleep(40)}}
    private fun varint(out:ByteArrayOutputStream,value:Int){var v=value;while(true){if(v and 0x7f.inv()==0){out.write(v);return};out.write(v and 0x7f or 0x80);v=v ushr 7}}
    private fun handshake(host:String):ByteArray {
        val body=ByteArrayOutputStream();varint(body,0);varint(body,767);val n=host.toByteArray();varint(body,n.size);body.write(n);body.write(0x63);body.write(0xdd);varint(body,2)
        val p=ByteArrayOutputStream();varint(p,body.size());body.writeTo(p);return p.toByteArray()
    }
    private fun player(port:Int):Socket {val s=Socket();s.connect(InetSocketAddress("127.0.0.1",port),3000);s.soTimeout=5000;val h=handshake(host);s.getOutputStream().write(h);s.getOutputStream().flush();s.getInputStream().readNBytes(h.size);return s} // the fake game echoes the handshake back first
    private fun roundTrip(s:Socket,text:String):Boolean {s.getOutputStream().write(text.toByteArray());s.getOutputStream().flush();val b=ByteArray(text.length);var got=0;while(got<b.size){val n=s.getInputStream().read(b,got,b.size-got);if(n<0)return false;got+=n};return String(b)==text}

    private class World(val h:Harness,val relay:RunnerNode,val game:RunnerNode,val echo:Echo,val secure:Int,val inst:String)
    private fun world(consent:Boolean=true,storeDir:Path=Files.createTempDirectory("drm")):World {
        val echo=Echo().track();val inst="inst-1"
        val h=Harness(DirectRelayStore(storeDir.resolve("store")),freePort());h.build()
        val relay=RunnerNode(id(),Files.createTempDirectory("relay"));val game=RunnerNode(id(),Files.createTempDirectory("game"),mapOf(inst to echo.server.localPort))
        h.devices[relay.id]=device(relay.id);h.devices[game.id]=device(game.id,inst);h.instances.add(Triple(inst,game.id,listOf(host)))
        if(consent)relay.consent.set(true,"test")
        relay.start(h);game.start(h);closeables.add(AutoCloseable {relay.role.close();game.role.close()})
        return World(h,relay,game,echo,freePort(),inst)
    }
    private fun enable(w:World,relay:RunnerNode=w.relay,agentHost:String="127.0.0.1")=w.h.manager.save(DirectRelaySave(DirectRelayConfig(true,relay.id,null,w.secure,agentHost,"relay.example.com"),w.h.store.revision()))

    @Test fun `registered devices remain visible before their first report without assuming consent`() {
        val h=Harness(DirectRelayStore(Files.createTempDirectory("relay-empty-registry")),6700);h.build()
        val fresh=id();val offline=id()
        h.devices[fresh]=device(fresh);h.devices[offline]=device(offline)
        val rows=h.manager.view().relays
        assertEquals(setOf(fresh,offline),rows.map {it.runnerId}.toSet())
        assertTrue(rows.none {it.consentAllowed || it.capable || it.ready})
        assertTrue(rows.all {it.diagnostics.getValue("consent").status=="UNKNOWN"})
        assertEquals(6700,h.manager.view().gamePort)
        val req=DirectRelaySave(DirectRelayConfig(true,fresh,securePort=6400,agentHost="127.0.0.1"),h.store.revision())
        assertFailsWith<IllegalArgumentException>{h.manager.save(req)}
        h.manager.onConsent("unregistered",RelayConsentState(true,"forged"))
        assertFalse(h.manager.view().relays.any {it.runnerId=="unregistered"})
        h.devices.remove(offline);assertEquals(listOf(fresh),h.manager.view().relays.map {it.runnerId})
    }
    @Test fun `first save persists selected relay and reconnect requires fresh local consent`() {
        val w=world()
        val saved=enable(w)
        assertEquals(w.relay.id,saved.config.relayRunnerId);assertTrue(saved.config.enabled)
        assertEquals(w.h.gamePort,saved.gamePort)
        w.h.manager.onDisconnect(w.relay.id)
        val pending=w.h.manager.view().relays.single {it.runnerId==w.relay.id}
        assertFalse(pending.capable);assertFalse(pending.consentAllowed)
        w.h.manager.save(DirectRelaySave(w.h.store.config().copy(enabled=false),w.h.store.revision()))
        assertFailsWith<IllegalArgumentException>{enable(w)}
        w.relay.role.onSession();assertTrue(enable(w).config.enabled)
        val alternate=id();w.h.devices[alternate]=device(alternate)
        assertFailsWith<IllegalStateException>{w.h.manager.save(DirectRelaySave(w.h.store.config().copy(relayRunnerId=alternate),w.h.store.revision()))}
        val stale=w.h.store.revision()-1
        assertFails {w.h.manager.save(DirectRelaySave(w.h.store.config(),stale))}
    }
    @Test fun `UDP opt in requires both authenticated game and relay host capabilities`() {
        val w=world()
        // Simulate an old resident host behind an updated controller: version must come from the host report.
        w.h.manager.onRunnerMessage(w.game.id,RunnerMessage("relay-consent",detail=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(w.game.consent.read())))
        val next=DirectRelayConfig(true,w.relay.id,securePort=w.secure,agentHost="127.0.0.1",sableUdpEnabled=true)
        assertFailsWith<IllegalArgumentException>{w.h.manager.save(DirectRelaySave(next,w.h.store.revision()))}
        assertTrue(w.h.manager.view().sableUdpBlockers.contains(w.game.id.take(4)))
        w.game.role.onSession()
        val saved=w.h.manager.save(DirectRelaySave(next,w.h.store.revision()))
        assertTrue(saved.config.sableUdpEnabled)
        await("UDP relay ready",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
        val view=w.h.manager.view().relays.single {it.runnerId==w.relay.id}
        assertTrue(view.sableUdpCapable)
        assertEquals(listOf("TCP","UDP","TCP"),view.router.map {it.protocol})
        assertEquals(listOf(w.h.gamePort,w.h.gamePort,w.secure),view.router.map {it.externalPort})
        assertEquals("WAITING",w.h.manager.sableStatus(w.inst).state)
        w.h.manager.onDisconnect(w.game.id)
        assertFailsWith<IllegalArgumentException>{w.h.manager.save(DirectRelaySave(next,w.h.store.revision()))}
        // TCP mode can still be selected while an old host is waiting for an idle update.
        assertFalse(w.h.manager.save(DirectRelaySave(next.copy(sableUdpEnabled=false),w.h.store.revision())).config.sableUdpEnabled)
    }
    @Test fun `UDP listener recovers after port conflict without replacing live TCP streams`() {
        val w=world()
        val blocker=java.net.DatagramSocket(java.net.InetSocketAddress("127.0.0.1",w.h.gamePort)).track()
        w.h.manager.save(DirectRelaySave(DirectRelayConfig(true,w.relay.id,securePort=w.secure,agentHost="127.0.0.1",sableUdpEnabled=true),w.h.store.revision()))
        await("TCP tunnel up",tick=w.h::pump){w.h.manager.view().relays.single {it.runnerId==w.relay.id}.diagnostics.getValue("portConflict").status=="OK" && w.h.manager.view().agents.any {it.relays.any {a->a.state=="CONNECTED"}}}
        player(w.h.gamePort).use {s->
            assertTrue(roundTrip(s,"TCP during UDP conflict"));assertFalse(w.h.manager.isReady(w.relay.id))
            assertEquals("ERROR",w.h.manager.sableStatus(w.inst).state)
            blocker.close()
            await("UDP retry recovers",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
            assertTrue(roundTrip(s,"TCP after UDP retry"))
        }
    }
    @Test fun `per instance UDP status never borrows another servers confirmed connection`() {
        val w=world();enable(w)
        val cfg=w.h.store.config().copy(sableUdpEnabled=true)
        w.h.manager.save(DirectRelaySave(cfg,w.h.store.revision()))
        await("ready",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        // The aggregate belongs to another server; this instance must continue to show WAITING.
        val body=RunnerRelayReport(w.relay.consent.read(),RelayHubStatus(true,w.h.gamePort,true,w.secure,1,emptyList(),emptyList(),emptyMap(),emptyMap(),null,
            SableRelayStatus("CONNECTED",1),mapOf("another" to SableRelayStatus("CONNECTED",1))),emptyList(),true,true)
        w.h.manager.onRunnerMessage(w.relay.id,RunnerMessage("drp-status",version="DRP1_UDP1",detail=mapper.writeValueAsString(body)))
        assertEquals("WAITING",w.h.manager.sableStatus(w.inst).state)
        assertEquals("CONNECTED",w.h.manager.sableStatus("another").state)
    }

    @Test fun `default install is not allowed and a remote owner cannot enable the relay`() {
        val w=world(consent=false)
        assertFalse(w.relay.consent.read().allowed)
        assertFailsWith<IllegalArgumentException> {enable(w)}
        // Even a forged/forced remote config is ignored by the runner itself.
        w.relay.role.onMessage(RunnerMessage("drp-relay-config",detail=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(
            RelayHostConfig(true,w.relay.id,java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),w.h.gamePort,w.secure,RelayPolicy(5,emptyMap(),emptyList())))))
        assertFailsWith<java.net.ConnectException> {Socket("127.0.0.1",w.secure).close()}
        val view=w.h.manager.view().relays.single {it.runnerId==w.relay.id}.let {it}
        assertFalse(view.consentAllowed);assertFalse(view.active)
    }

    @Test fun `game data bypasses the manager and the control channel stays tiny`() {
        val w=world();enable(w)
        await("relay ready",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
        val before=controlBytes.get()
        player(w.h.gamePort).use {s->
            val payload="x".repeat(60000)
            repeat(40){assertTrue(roundTrip(s,payload),"round trip failed")}
        }
        w.h.pump()
        val carried=controlBytes.get()-before
        assertTrue(carried<20_000,"manager control channel carried $carried bytes while 4.8MB of game data moved")
        assertEquals("ACTIVE",w.h.manager.view().phase)
    }

    @Test fun `allowed and active and ready are reported separately`() {
        val w=world();enable(w)
        val first=w.h.manager.view().relays.single {it.runnerId==w.relay.id}
        assertTrue(first.consentAllowed)
        await("ready",tick=w.h::pump){w.h.manager.view().relays.single {it.runnerId==w.relay.id}.ready}
        val view=w.h.manager.view().relays.single {it.runnerId==w.relay.id}
        assertTrue(view.active && view.ready)
        assertEquals("UNVERIFIED",view.diagnostics.getValue("externalReachability").status,"local ports being open must not count as internet reachability")
        assertEquals("OK",view.diagnostics.getValue("portConflict").status)
        assertEquals(listOf(w.h.gamePort,w.secure),view.router.map {it.externalPort})
        assertTrue(view.router.all {it.internalPort==it.externalPort})
    }

    @Test fun `local revocation tears the relay down at once and survives the owner re-enabling`() {
        val w=world();enable(w);await("ready",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
        val live=player(w.h.gamePort);assertTrue(roundTrip(live,"hello"))
        w.relay.consent.set(false,"local-revoke");w.relay.role.tick()
        assertTrue(runCatching {live.getOutputStream().write(1);live.getInputStream().read()}.getOrDefault(-1)<0,"live stream must end when consent is withdrawn")
        Socket().use {s->assertFailsWith<java.net.SocketException> {s.connect(InetSocketAddress("127.0.0.1",w.h.gamePort),1000)}}
        w.h.pump();enable(w) // owner tries again
        w.h.pump();Thread.sleep(300)
        Socket().use {s->assertFailsWith<java.net.SocketException> {s.connect(InetSocketAddress("127.0.0.1",w.h.gamePort),1000)}}
        val view=w.h.manager.view().relays.single {it.runnerId==w.relay.id}
        assertFalse(view.consentAllowed);assertFalse(view.active);assertFalse(view.ready)
        // Granting again restores service without any remote step beyond the existing configuration.
        w.relay.consent.set(true,"local-allow");await("restored",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
    }

    @Test fun `consent persists across restart and legacy grants are migrated and visible`() {
        val dir=Files.createTempDirectory("consent")
        val store=RelayConsentStore(dir){null};store.set(true,"installer")
        assertTrue(RelayConsentStore(dir){null}.read().allowed)
        RelayConsentStore(dir){null}.set(false,"local-revoke");assertFalse(RelayConsentStore(dir){null}.read().allowed)
        val old=Files.createTempDirectory("legacy");(old/"tcp-relay-enabled").writeText("true")
        val migrated=RelayConsentStore(old){null}.read()
        assertTrue(migrated.allowed);assertEquals("tcp-relay-enabled",migrated.migratedFrom);assertEquals("migrated",migrated.source)
        assertTrue((old/"direct-relay.json").exists())
        (old/"tcp-relay-enabled").deleteExisting()
        assertFalse(RelayConsentStore(old){null}.read().allowed,"removing the documented legacy marker still revokes")
        val env=RelayConsentStore(Files.createTempDirectory("env")){if(it=="RUNNER_TCP_RELAY_ENABLED")"true" else null}.read()
        assertTrue(env.allowed && env.environmentLocked)
        assertFalse(RelayConsentStore(Files.createTempDirectory("fresh")){null}.read().allowed,"new installs default to off")
    }

    @Test fun `a game runner that is not approved for the instance cannot obtain a ticket`() {
        val w=world();enable(w);await("ready",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
        val stranger=RunnerNode(id(),Files.createTempDirectory("stranger"),mapOf(w.inst to w.echo.server.localPort))
        w.h.devices[stranger.id]=device(stranger.id) // registered and online, but no server assignment
        stranger.start(w.h);closeables.add(AutoCloseable {stranger.role.close()})
        // A rogue runner config (the manager would never send this) still gets nothing from the manager.
        stranger.role.onMessage(RunnerMessage("drp-agent-config",detail=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(
            AgentRelayConfig(listOf(AgentRelayTarget(w.relay.id,"127.0.0.1",w.secure)),setOf(w.inst)))))
        await("denied"){w.h.manager.view().agents.any {a->a.runnerId==stranger.id && a.relays.any {it.state=="REJECTED"}}.also {if(!it)stranger.role.report()}}
        val state=w.h.manager.view().agents.single {it.runnerId==stranger.id}.relays.single()
        assertEquals("NO_APPROVED_ROUTE",state.reason)
        assertTrue(w.relay.role.busy().not(),"no stream may exist for an unapproved agent")
    }

    @Test fun `route switch is prepared first then committed and the old relay keeps existing streams`() {
        val w=world();enable(w);await("ready",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
        val secondAddress=if(System.getProperty("os.name").startsWith("Mac"))java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap {it.inetAddresses.toList()}.firstOrNull {it is java.net.Inet4Address && it.isSiteLocalAddress}?.hostAddress ?: "127.0.0.2" else "127.0.0.2"
        val second=RunnerNode(id(),Files.createTempDirectory("relay2"));w.h.devices[second.id]=device(second.id)
        second.role=DirectRelayRunnerRole(second.dir,{m->w.h.toManager(second.id,m);true},{null},{false},second.consent,longArrayOf(50,100),secondAddress)
        w.h.nodes[second.id]=second;closeables.add(AutoCloseable {second.role.close()});second.role.onSession()
        assertFailsWith<IllegalArgumentException> {w.h.manager.prepare(second.id,RelayHostSpec(secondAddress,"relay2.example.com"))} // no local consent yet
        second.consent.set(true,"test");second.role.tick()
        val live=player(w.h.gamePort);assertTrue(roundTrip(live,"before-switch"))
        w.h.manager.prepare(second.id,RelayHostSpec(secondAddress,"relay2.example.com"))
        assertFailsWith<IllegalStateException> {w.h.manager.commit()} // hub/agent not up yet
        await("candidate ready",tick=w.h::pump){w.h.manager.isReady(second.id)}
        assertEquals("CANDIDATE_READY",w.h.manager.view().phase)
        assertTrue(roundTrip(live,"during-prepare"),"existing path unaffected while the new target is prepared")
        w.h.manager.commit()
        assertEquals(second.id,w.h.store.config().relayRunnerId);assertTrue(w.h.store.config().retiring.containsKey(w.relay.id))
        assertTrue(roundTrip(live,"after-commit"),"existing connection survives until it ends naturally")
        // New connections to the new relay reach the game through the new path.
        val viaNew=Socket();viaNew.connect(InetSocketAddress(secondAddress,w.h.gamePort),3000);viaNew.soTimeout=5000
        viaNew.getOutputStream().write(handshake(host));viaNew.getOutputStream().flush();viaNew.getInputStream().readNBytes(handshake(host).size)
        assertTrue(roundTrip(viaNew,"new-path"));viaNew.close();live.close()
        assertTrue(w.echo.seen.any {it.contains("new-path")})
    }

    @Test fun `temporary controller disconnect keeps tunnel authorization while real grant removal revokes it`() {
        val w=world();enable(w);await("ready",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
        player(w.h.gamePort).use {live ->
            assertTrue(roundTrip(live,"before"))
            w.h.offline.add(w.game.id);w.h.manager.onDisconnect(w.game.id);w.h.pump()
            assertTrue(roundTrip(live,"offline"))
            w.h.devices[w.game.id]=device(w.game.id) // persisted authorization is actually revoked
            w.h.pump()
            assertTrue(runCatching {live.getOutputStream().write(1);live.getInputStream().read()}.getOrDefault(-1)<0)
        }
    }
    @Test fun `cut blocks immediately and reports the impact`() {
        val w=world();enable(w);await("ready",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
        val live=player(w.h.gamePort);assertTrue(roundTrip(live,"hi"))
        w.h.pump()
        val impact=w.h.manager.cut(w.relay.id)
        assertEquals(1,impact)
        assertTrue(runCatching {live.getOutputStream().write(1);live.getInputStream().read()}.getOrDefault(-1)<0)
        val agent=w.game.role
        await("agent refused",tick=w.h::pump){agent.report();true}
        assertFalse(w.h.manager.isReady(w.relay.id))
    }

    @Test fun `manager restart keeps tunnels and new connections resume after recovery`() {
        val dir=Files.createTempDirectory("restart")
        val w=world(storeDir=dir);enable(w);await("ready",tick=w.h::pump){w.h.manager.isReady(w.relay.id)}
        val live=player(w.h.gamePort);assertTrue(roundTrip(live,"before"))
        // Manager goes away: nodes are unreachable, nothing can be authorised or changed.
        w.h.offline.addAll(listOf(w.relay.id,w.game.id))
        assertTrue(roundTrip(live,"during-outage"),"approved traffic continues without the manager")
        // New manager process: fresh secrets, but same persisted epochs.
        val restarted=Harness(DirectRelayStore(dir.resolve("store")),w.h.gamePort);restarted.build()
        restarted.devices.putAll(w.h.devices);restarted.instances.addAll(w.h.instances);restarted.nodes.putAll(w.h.nodes)
        listOf(w.relay,w.game).forEach {n->n.role.close();n.start(restarted)}
        await("recovered",tick=restarted::pump){restarted.manager.isReady(w.relay.id)}
        player(w.h.gamePort).use {assertTrue(roundTrip(it,"after-recovery"))}
    }

    @Test fun `port conflict on the secure port is diagnosed separately`() {
        val w=world();val blocker=ServerSocket().track().also {it.reuseAddress=false;it.bind(InetSocketAddress("127.0.0.1",w.secure),1)}
        enable(w);w.h.pump();Thread.sleep(200);w.h.pump()
        val view=w.h.manager.view().relays.single {it.runnerId==w.relay.id}
        assertEquals("CONFLICT",view.diagnostics.getValue("portConflict").status)
        assertFalse(view.ready)
        assertNotEquals("CONFLICT",view.diagnostics.getValue("consent").status)
    }
}
