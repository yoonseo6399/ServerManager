package local.servermanager

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class VoiceChatRelayTest {
    private fun await(check:()->Boolean){val end=System.nanoTime()+5_000_000_000;while(!check()){assertTrue(System.nanoTime()<end,"timeout");Thread.sleep(10)}}
    private fun handshake(host:String):ByteArray {
        fun v(out:ByteArrayOutputStream,n:Int){var x=n;do {val b=x and 127;x=x ushr 7;out.write(b or if(x!=0)128 else 0)}while(x!=0)}
        val b=ByteArrayOutputStream();v(b,0);v(b,767);v(b,host.length);b.write(host.toByteArray());b.write(26);b.write(44);v(b,2)
        return ByteArrayOutputStream().also {v(it,b.size());it.write(b.toByteArray())}.toByteArray()
    }
    private class Game(val marker:String):AutoCloseable {
        val tcp=ServerSocket(0);val udp=DatagramSocket(InetSocketAddress("127.0.0.1",0));val count=AtomicInteger();val clients=CopyOnWriteArrayList<Socket>()
        init {
            Thread.ofVirtual().start {while(!tcp.isClosed){val s=runCatching {tcp.accept()}.getOrNull() ?: break;clients.add(s);Thread.ofVirtual().start {s.use {runCatching {val b=ByteArray(65536);while(true){val n=s.getInputStream().read(b);if(n<0)break;s.getOutputStream().write(b,0,n);s.getOutputStream().flush()}}}}}}
            Thread.ofVirtual().start {val b=ByteArray(16384);while(!udp.isClosed)try {val p=DatagramPacket(b,b.size);udp.receive(p);count.incrementAndGet();val r=marker.toByteArray()+p.data.copyOf(p.length);udp.send(DatagramPacket(r,r.size,p.socketAddress))}catch(_:Exception){break}}
        }
        override fun close(){tcp.close();udp.close();clients.forEach {it.close()}}
    }
    private class Fixture:AutoCloseable {
        val a=Game("A:");val b=Game("B:");val relay=UUID.randomUUID().toString();val agentId=UUID.randomUUID().toString();val secret=DirectRelayCrypto.newSecret()
        val hub=DirectRelayHub(relay,secret);val publicA=DatagramSocket(0).use {it.localPort};val publicB=DatagramSocket(0).use {it.localPort};val players=CopyOnWriteArrayList<Socket>()
        val targets=CopyOnWriteArrayList(listOf(VoiceChatTarget("a",a.udp.localPort,"localhost:$publicA"),VoiceChatTarget("b",b.udp.localPort,"localhost:$publicB")))
        val agent:DirectRelayAgent
        init {
            check(hub.listen(0,0,"127.0.0.1"));hub.applyPolicy(RelayPolicy(1,mapOf(agentId to 1),listOf(RelayRoute("a.example.com",agentId,"a"),RelayRoute("b.example.com",agentId,"b"))))
            hub.voiceChat(listOf(VoiceChatRelayConfig("a",publicA,a.udp.localPort),VoiceChatRelayConfig("b",publicB,b.udp.localPort)),"127.0.0.1")
            agent=DirectRelayAgent({RelayEndpoint("127.0.0.1",hub.securePort!!,DirectRelayCrypto.issue(secret,relay,agentId,1,120000))},{when(it){"a"->a.tcp.localPort;"b"->b.tcp.localPort;else->null}},{true},longArrayOf(30),{false},{targets.toList()});agent.start()
        }
        override fun close(){players.forEach {it.close()};agent.close();hub.close();a.close();b.close()}
    }
    private fun connect(f:Fixture,id:String):Socket {
        await {f.hub.status().agents.isNotEmpty()};val s=Socket("127.0.0.1",f.hub.gamePort!!);s.soTimeout=3000
        f.players.add(s);val h=handshake("$id.example.com");s.getOutputStream().write(h);s.getOutputStream().flush();assertContentEquals(h,s.getInputStream().readNBytes(h.size));return s
    }
    private fun exchange(socket:DatagramSocket,port:Int,text:String):String {
        val bytes=text.toByteArray();socket.send(DatagramPacket(bytes,bytes.size,InetSocketAddress("127.0.0.1",port)));val p=DatagramPacket(ByteArray(16384),16384);socket.receive(p);return String(p.data,0,p.length)
    }
    @Test fun `voice UDP uses approved encrypted tunnel and isolates instances`()=Fixture().use {f->
        connect(f,"a");connect(f,"b")
        DatagramSocket(InetSocketAddress("127.0.0.1",0)).use {p->p.soTimeout=3000
            assertEquals("A:hello",exchange(p,f.publicA,"hello"));assertEquals("B:hello",exchange(p,f.publicB,"hello"))
            await {f.hub.status().voicechat["a"]?.state=="CONNECTED"};assertEquals(1,f.a.count.get());assertEquals(1,f.b.count.get())
        }
    }
    @Test fun `no TCP player and closed TCP player cannot use voice relay`()=Fixture().use {f->
        await {f.hub.status().agents.isNotEmpty()}
        DatagramSocket(InetSocketAddress("127.0.0.1",0)).use {p->p.soTimeout=200
            assertFailsWith<SocketTimeoutException>{exchange(p,f.publicA,"denied")};assertEquals(0,f.a.count.get())
            val tcp=connect(f,"a");p.soTimeout=3000;assertEquals("A:allowed",exchange(p,f.publicA,"allowed"));tcp.close()
            await {f.hub.streamCount()==0};p.soTimeout=200;assertFailsWith<SocketTimeoutException>{exchange(p,f.publicA,"denied-again")};assertEquals(1,f.a.count.get())
        }
    }
    @Test fun `policy revocation and voice disable close UDP while retaining unrelated TCP`()=Fixture().use {f->
        val tcp=connect(f,"a");DatagramSocket().use {p->p.soTimeout=3000;assertEquals("A:yes",exchange(p,f.publicA,"yes"))
            f.hub.voiceChat(emptyList());assertTrue(f.hub.status().voicechat.isEmpty());p.soTimeout=200;assertFailsWith<SocketTimeoutException>{exchange(p,f.publicA,"no")}
            tcp.getOutputStream().write("ok".toByteArray());tcp.getOutputStream().flush();assertEquals("ok",String(tcp.getInputStream().readNBytes(2)))
            f.hub.voiceChat(listOf(VoiceChatRelayConfig("a",f.publicA,f.a.udp.localPort)),"127.0.0.1");f.hub.applyPolicy(RelayPolicy(2,emptyMap(),emptyList()))
            await {f.hub.streamCount()==0};assertFailsWith<SocketTimeoutException>{exchange(p,f.publicA,"revoked")};Unit
        }
    }
    @Test fun `voice port conflict reports error without breaking game TCP`()=Fixture().use {f->
        val tcp=connect(f,"a");DatagramSocket(0).use {occupied->f.hub.voiceChat(listOf(VoiceChatRelayConfig("a",occupied.localPort,f.a.udp.localPort)),"127.0.0.1");assertEquals("ERROR",f.hub.status().voicechat["a"]?.state)}
        tcp.getOutputStream().write("ok".toByteArray());tcp.getOutputStream().flush();assertEquals("ok",String(tcp.getInputStream().readNBytes(2)))
    }
    @Test fun `public voice UDP shares game TCP number without Sable`()=Fixture().use {f->
        val port=f.hub.gamePort!!;f.hub.voiceChat(listOf(VoiceChatRelayConfig("a",port,f.a.udp.localPort)),"127.0.0.1");connect(f,"a")
        DatagramSocket().use {p->p.soTimeout=3000;assertEquals("A:same-port",exchange(p,port,"same-port"))}
    }
    @Test fun `voice envelope rejects malformed and oversized messages`() {
        assertNull(VoiceDatagram.decode(byteArrayOf()));assertNull(VoiceDatagram.decode(byteArrayOf(66,1,2)));assertNull(VoiceDatagram.decode(byteArrayOf(1,47,1)))
        assertFailsWith<IllegalArgumentException>{VoiceDatagram.encode("a",ByteArray(8193))};assertFailsWith<IllegalArgumentException>{VoiceDatagram.encode("../bad",byteArrayOf(1))}
        val pair=VoiceDatagram.decode(VoiceDatagram.encode("a",byteArrayOf(1,2)))!!;assertEquals("a",pair.first);assertContentEquals(byteArrayOf(1,2),pair.second)
    }
}
