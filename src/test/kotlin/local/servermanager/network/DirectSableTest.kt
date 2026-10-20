package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.*
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*
import kotlin.test.*

class DirectSableTest {
    private fun await(message:String,check:()->Boolean) {val end=System.nanoTime()+10_000_000_000;while(!check()){assertTrue(System.nanoTime()<end,message);Thread.sleep(20)}}
    private fun port()=ServerSocket(0).use {it.localPort}
    private fun handshake(host:String):ByteArray {
        fun v(out:ByteArrayOutputStream,n:Int){var x=n;do {val b=x and 127;x=x ushr 7;out.write(b or if(x!=0)128 else 0)}while(x!=0)}
        val b=ByteArrayOutputStream();v(b,0);v(b,767);v(b,host.length);b.write(host.toByteArray());b.write(26);b.write(44);v(b,2)
        return ByteArrayOutputStream().also {v(it,b.size());it.write(b.toByteArray())}.toByteArray()
    }
    private fun auth(token:UUID)=byteArrayOf(3,36)+token.toString().toByteArray()
    private class Game:AutoCloseable {
        val tcp=ServerSocket(0);val udp=DatagramSocket(InetSocketAddress("127.0.0.1",tcp.localPort));val source=AtomicInteger();val packets=AtomicInteger();val clients=CopyOnWriteArrayList<Socket>()
        init {
            Thread.ofVirtual().start {while(!tcp.isClosed){val s=runCatching {tcp.accept()}.getOrNull() ?: break;clients.add(s);source.set(s.port);Thread.ofVirtual().start {runCatching {val b=ByteArray(65536);while(true){val n=s.getInputStream().read(b);if(n<0)break;s.getOutputStream().write(b,0,n);s.getOutputStream().flush()}}}}}
            Thread.ofVirtual().start {val b=ByteArray(65508);while(!udp.isClosed)try {val packet=DatagramPacket(b,b.size);udp.receive(packet);packets.incrementAndGet();val response=if(packet.data[0].toInt()==3)byteArrayOf(4,12,34) else byteArrayOf(0,56,78);udp.send(DatagramPacket(response,response.size,packet.socketAddress))}catch(_:Exception){break}}
        }
        override fun close(){tcp.close();udp.close();clients.forEach {it.close()}}
    }
    private class Fixture(val game:Game,val hub:DirectRelayHub,val agent:DirectRelayAgent,val player:Socket,val stream:Int,val enabled:AtomicBoolean):AutoCloseable {
        override fun close(){player.close();agent.close();hub.close();game.close()}
    }
    private fun fixture(conflict:Boolean=false):Fixture {
        val relay=UUID.randomUUID().toString();val agentId=UUID.randomUUID().toString();val secret=DirectRelayCrypto.newSecret();val game=Game()
        val public=port();var secure=port();while(secure==public)secure=port()
        val hub=DirectRelayHub(relay,secret);assertTrue(hub.listen(public,secure,"127.0.0.1"))
        hub.applyPolicy(RelayPolicy(1,mapOf(agentId to 1),listOf(RelayRoute("a.example.com",agentId,"a"))))
        if(conflict)DatagramSocket(public).use {hub.sableUdp(true,public,"127.0.0.1");assertEquals("ERROR",hub.status().sableUdp?.state)} else hub.sableUdp(true,public,"127.0.0.1")
        val enabled=AtomicBoolean(true)
        val agent=DirectRelayAgent({RelayEndpoint("127.0.0.1",secure,DirectRelayCrypto.issue(secret,relay,agentId,1,120000))},{if(it=="a")game.tcp.localPort else null},{it=="a"},longArrayOf(30,50),{enabled.get()});agent.start()
        await("agent connected"){hub.status().agents.isNotEmpty()}
        val player=Socket("127.0.0.1",public).apply {soTimeout=3000};val hello=handshake("a.example.com");player.getOutputStream().write(hello);player.getOutputStream().flush();assertContentEquals(hello,player.getInputStream().readNBytes(hello.size))
        await("actual game socket identified"){agent.streamAtPort(game.source.get())!=null}
        return Fixture(game,hub,agent,player,agent.streamAtPort(game.source.get())!!.second,enabled)
    }
    private fun exchange(socket:DatagramSocket,port:Int,payload:ByteArray):ByteArray {
        socket.send(DatagramPacket(payload,payload.size,InetSocketAddress("127.0.0.1",port)))
        val reply=DatagramPacket(ByteArray(2048),2048);socket.receive(reply);return reply.data.copyOf(reply.length)
    }
    @Test fun `direct Sable authenticates real TCP peer and transports UDP round trips without manager data`()=fixture().use {f->
        val token=UUID.randomUUID();assertFalse(f.agent.registerSable(f.stream,"other",token.toString()));assertFalse(f.agent.registerSable(999,"a",token.toString()))
        assertTrue(f.agent.registerSable(f.stream,"a",token.toString()));assertFalse(f.agent.registerSable(f.stream,"a",token.toString()))
        DatagramSocket(InetSocketAddress("127.0.0.1",0)).use {player->
            player.soTimeout=3000;val public=f.hub.gamePort!!
            assertContentEquals(byteArrayOf(4,12,34),exchange(player,public,auth(token)))
            assertContentEquals(byteArrayOf(0,56,78),exchange(player,public,byteArrayOf(5)))
            await("UDP confirmed"){f.hub.status().sableUdp?.state=="CONNECTED"}
            DatagramSocket(InetSocketAddress("127.0.0.1",0)).use {stranger->stranger.soTimeout=150;assertFailsWith<SocketTimeoutException>{exchange(stranger,public,auth(token))};assertFailsWith<SocketTimeoutException>{exchange(stranger,public,auth(UUID.randomUUID()))}}
            val count=f.game.packets.get();f.player.close();await("TCP closes"){f.agent.streamAtPort(f.game.source.get())==null}
            player.soTimeout=200;assertFailsWith<SocketTimeoutException>{exchange(player,public,auth(token))};assertEquals(count,f.game.packets.get())
        }
    }
    @Test fun `UDP port conflict fails closed while the TCP game remains usable`()=fixture(conflict=true).use {f->
        assertFalse(f.agent.registerSable(f.stream,"a",UUID.randomUUID().toString()))
        assertEquals("ERROR",f.hub.status().sableUdp?.state)
        f.player.getOutputStream().write("TCP".toByteArray());f.player.getOutputStream().flush();assertEquals("TCP",String(f.player.getInputStream().readNBytes(3)))
    }
    @Test fun `turning UDP off closes public UDP and retains existing TCP`()=fixture().use {f->
        assertTrue(f.agent.registerSable(f.stream,"a",UUID.randomUUID().toString()));f.enabled.set(false);f.agent.disableSable();f.hub.sableUdp(false,f.hub.gamePort!!)
        assertNull(f.hub.status().sableUdp);assertFalse(f.agent.registerSable(f.stream,"a",UUID.randomUUID().toString()))
        f.player.getOutputStream().write("still TCP".toByteArray());f.player.getOutputStream().flush();assertEquals("still TCP",String(f.player.getInputStream().readNBytes(9)))
    }
    @Test fun `UDP opt in persists separately without changing the Beta13 settings schema`() {
        val home=Files.createTempDirectory("direct-udp-settings");val store=DirectRelayStore(home)
        store.save(DirectRelaySave(DirectRelayConfig(sableUdpEnabled=true),0))
        val old=jacksonObjectMapper().readTree(home.resolve("settings.json").readText());assertFalse(old.path("config").has("sableUdpEnabled"))
        assertTrue(DirectRelayStore(home).config().sableUdpEnabled)
        val changed=(old as com.fasterxml.jackson.databind.node.ObjectNode).put("revision",2);home.resolve("settings.json").writeText(changed.toString())
        assertFalse(DirectRelayStore(home).config().sableUdpEnabled)
        val wire=jacksonObjectMapper().writeValueAsString(AgentRelayConfig(emptyList(),emptySet()));assertFalse(wire.contains("sableUdpEnabled"))
    }
}
