package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
import kotlin.io.path.*
import kotlin.test.*

/** The relay hub and agent tunnel live in the resident hosts, so replacing every control layer does not drop players. */
class HostedRelayTest {
    private val closeables=mutableListOf<AutoCloseable>()
    @AfterEach fun cleanup(){closeables.reversed().forEach {runCatching {it.close()}}}
    private fun <T:AutoCloseable> T.track():T{closeables.add(this);return this}
    private val mapper=jacksonObjectMapper()
    private fun javaBin()=Path.of(System.getProperty("java.home"),"bin","java").toString()
    private fun freePort()=ServerSocket(0).use {it.localPort}
    private fun await(m:String,t:Long=15000,tick:()->Unit={},c:()->Boolean){val end=System.currentTimeMillis()+t;while(true){tick();if(c())return;assertTrue(System.currentTimeMillis()<end,m);Thread.sleep(40)}}
    private fun varint(o:ByteArrayOutputStream,v0:Int){var v=v0;while(true){if(v and 0x7f.inv()==0){o.write(v);return};o.write(v and 0x7f or 0x80);v=v ushr 7}}
    private fun handshake(host:String):ByteArray {val b=ByteArrayOutputStream();varint(b,0);varint(b,767);val n=host.toByteArray();varint(b,n.size);b.write(n);b.write(0x63);b.write(0xdd);varint(b,2);val p=ByteArrayOutputStream();varint(p,b.size());b.writeTo(p);return p.toByteArray()}
    private fun roundTrip(s:Socket,text:String):Boolean {s.getOutputStream().write(text.toByteArray());s.getOutputStream().flush();val b=ByteArray(text.length);var n=0;while(n<b.size){val r=s.getInputStream().read(b,n,b.size-n);if(r<0)return false;n+=r};return String(b)==text}

    @Test fun `players stay connected while both control layers are replaced and new players connect afterwards`() {
        val host="play.example.com";val inst="inst-1"
        val relayId=UUID.randomUUID().toString();val agentId=UUID.randomUUID().toString()
        val relayHome=Files.createTempDirectory("hr-relay");val agentHome=Files.createTempDirectory("hr-agent");val gameDir=Files.createTempDirectory("hr-game")
        RelayConsentStore(relayHome){null}.set(true,"test")
        listOf(relayHome,agentHome).forEach {GameHostService(it,"0.5.2").track().also {s->assertTrue(s.start())}}
        // Fake "Minecraft": a hosted process that reports ready, plus a TCP echo on the game port.
        val echo=ServerSocket(0).track();Thread.ofVirtual().start {while(!echo.isClosed){val s=runCatching {echo.accept()}.getOrNull() ?: break;Thread.ofVirtual().start {runCatching {val i=s.getInputStream();val o=s.getOutputStream();val b=ByteArray(65536);while(true){val n=i.read(b);if(n<0)break;o.write(b,0,n);o.flush()}}}}}
        val seed=GameHostClient.connect(agentHome,"seed").track()
        seed.start(inst,listOf(javaBin(),"-cp",System.getProperty("java.class.path"),"local.servermanager.FakeGameMain",gameDir.toString(),"chatter"),gameDir,System.getenv(),port=echo.localPort);seed.close()
        val gamePort=freePort();val securePort=freePort()
        val homes=mapOf(relayId to relayHome,agentId to agentHome)
        val clients=ConcurrentHashMap<String,GameHostClient>()
        val store=DirectRelayStore(Files.createTempDirectory("hr-store"))
        lateinit var manager:DirectRelayManager
        val world=object:RelayWorld {
            override fun runner(id:String)=RunnerDevice(id,id.take(4),"MAC","o","k","n",allowedInstanceIds=setOf(inst))
            override fun connected(id:String)=clients[id]?.let {!it.disconnected} ?: false
            override fun runnerName(id:String)=id.take(4)
            override fun routes()=listOf(Triple(inst,agentId,listOf(host)))
            override fun send(runnerId:String,message:RunnerMessage)=clients[runnerId]?.relayForward(mapper.writeValueAsString(message)) ?: false
            override fun resolveHost(host:String)=listOf("203.0.113.9")
            override fun gamePort()=gamePort
        }
        manager=DirectRelayManager(store,world)
        fun attachControllers(tag:String) {
            homes.forEach {(id,home)->
                clients.remove(id)?.close()
                val c=GameHostClient.connect(home,"$tag-$id",retryMillis=10000)
                c.onRelay={body->runCatching {manager.onRunnerMessage(id,mapper.readValue<RunnerMessage>(body))}}
                clients[id]=c;closeables.add(c);c.relaySession()
            }
        }
        attachControllers("v1")
        await("consent seen",c={manager.consent(relayId)?.allowed==true})
        manager.save(DirectRelaySave(DirectRelayConfig(true,relayId,null,securePort,"127.0.0.1","relay.example.com"),store.revision()))
        await("relay ready",tick={manager.reconcile()}){manager.isReady(relayId)}
        val live=Socket("127.0.0.1",gamePort).apply {soTimeout=8000};live.getOutputStream().write(handshake(host));live.getOutputStream().flush();live.getInputStream().readNBytes(handshake(host).size)
        assertTrue(roundTrip(live,"before-update"))
        // Every control layer goes away (both updates at once). The hosts keep the game, the hub and the tunnel.
        clients.values.forEach {it.close()};clients.clear()
        Thread.sleep(1500)
        assertTrue(roundTrip(live,"during-update"),"existing player connection survives with no controller attached")
        val during=Socket("127.0.0.1",gamePort).apply {soTimeout=8000};during.getOutputStream().write(handshake(host));during.getOutputStream().flush()
        assertTrue(runCatching {during.getInputStream().readNBytes(handshake(host).size).size}.getOrDefault(0)>0,"approved tunnel still serves new players while the manager side is away")
        during.close()
        attachControllers("v2")
        await("manager sees the hosts again",tick={manager.reconcile()}){manager.isReady(relayId)}
        assertTrue(roundTrip(live,"after-update"))
        assertEquals(1,Files.readAllLines(gameDir/"launches.txt").count {it.isNotBlank()},"the game was never restarted")
        live.close()
        clients.values.forEach {it.close()};clients.clear()
        GameHostClient.connect(agentHome,"cleanup",retryMillis=10000).track().stop(inst,Files.readAllLines(gameDir/"launches.txt").first().trim().toLong())
    }
}
