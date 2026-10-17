package local.servermanager

import org.junit.jupiter.api.Test
import java.net.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class SableRelayTest {
    private val loopback=InetAddress.getByName("127.0.0.1")
    private fun auth(token:UUID)=byteArrayOf(3,36)+token.toString().toByteArray()
    @Test fun `shared port authenticates exact stream and prevents cross server responses`() {
        val active=ConcurrentHashMap<Pair<String,Int>,SableRoute>()
        active["mac" to 1]=SableRoute("alpha",loopback);active["win" to 2]=SableRoute("beta",loopback)
        val frames=Collections.synchronizedList(mutableListOf<Pair<String,ByteArray>>())
        SableRelay({r,s->active[r to s]},{r,b->frames.add(r to b);true}).use {relay->
            val publicPort=DatagramSocket(0).use {it.localPort};relay.listen(publicPort)
            val a=UUID.randomUUID();val b=UUID.randomUUID()
            assertFalse(relay.register("win",1,"alpha",a));assertFalse(relay.register("mac",1,"beta",a))
            assertTrue(relay.register("mac",1,"alpha",a));assertTrue(relay.register("win",2,"beta",b))
            DatagramSocket(InetSocketAddress(loopback,0)).use {pa->DatagramSocket(InetSocketAddress(loopback,0)).use {pb->
                pa.soTimeout=2000;pb.soTimeout=2000
                val addressA=InetSocketAddress(loopback,pa.localPort);val addressB=InetSocketAddress(loopback,pb.localPort)
                assertFalse(relay.receive(addressA,auth(UUID.randomUUID())))
                assertFalse(relay.receive(addressA,byteArrayOf(0xfe.toByte(),0xfd.toByte(),9)))
                assertFalse(relay.receive(addressA,byteArrayOf(5)))
                assertTrue(relay.receive(addressA,auth(a)));assertTrue(relay.receive(addressB,auth(b)))
                assertEquals(listOf("mac","win"),frames.map {it.first});assertEquals(1,ByteBuffer.wrap(frames[0].second).int)
                assertFalse(relay.receive(addressB,auth(a)))
                assertFalse(relay.downstream("win",ByteBuffer.allocate(5).putInt(1).put(4).array()))
                assertTrue(relay.downstream("mac",ByteBuffer.allocate(5).putInt(1).put(4).array()))
                val response=DatagramPacket(ByteArray(8),8);pa.receive(response);assertEquals(4,response.data[0].toInt());assertEquals(publicPort,response.port)
                assertEquals("WAITING",relay.status("alpha").state)
                assertTrue(relay.receive(addressA,byteArrayOf(5)))
                assertEquals("CONNECTED",relay.status("alpha").state)
                val snapshot=byteArrayOf(1,0,127,-1,42)
                assertTrue(relay.downstream("mac",ByteBuffer.allocate(4+snapshot.size).putInt(1).put(snapshot).array()))
                val raw=DatagramPacket(ByteArray(32),32);pa.receive(raw);assertContentEquals(snapshot,raw.data.copyOf(raw.length))
                active.remove("mac" to 1)
                assertFalse(relay.receive(addressA,byteArrayOf(5)))
                assertFalse(relay.downstream("mac",ByteBuffer.allocate(5).putInt(1).put(4).array()))
                relay.removeRunner("win");assertFalse(relay.receive(addressB,byteArrayOf(5)))
                assertEquals(0,relay.status("beta").connections)
            }}
        }
    }
    @Test fun `bad authentication never produces traffic and rate limits valid clients`() {
        var count=0
        SableRelay({_,_->SableRoute("a",loopback)},{_,_->count++;true}).use {relay->
            relay.listen(DatagramSocket(0).use {it.localPort})
            val token=UUID.randomUUID();assertTrue(relay.register("r",1,"a",token))
            val peer=InetSocketAddress(loopback,12345)
            assertFalse(relay.receive(peer,auth(token)+0));assertFalse(relay.receive(peer,byteArrayOf(3,127)))
            assertFalse(relay.receive(InetSocketAddress("127.0.0.2",12345),auth(token)));assertEquals(0,count)
            assertTrue(relay.receive(peer,auth(token)))
            repeat(100){relay.receive(peer,byteArrayOf(5))};assertEquals(64,count)
            relay.removeStream("r",1);assertFalse(relay.receive(peer,auth(token)))
        }
    }
    @Test fun `public UDP socket receives authentication without replying to unauthenticated senders`() {
        val received=AtomicBoolean()
        SableRelay({_,_->SableRoute("a",loopback)},{_,_->received.set(true);true}).use {relay->
            val port=DatagramSocket(0).use {it.localPort};relay.listen(port)
            val token=UUID.randomUUID();assertTrue(relay.register("r",1,"a",token))
            DatagramSocket().use {client->
                client.soTimeout=100;val bad=auth(UUID.randomUUID());client.send(DatagramPacket(bad,bad.size,loopback,port))
                assertFailsWith<SocketTimeoutException>{client.receive(DatagramPacket(ByteArray(128),128))}
                val bytes=auth(token);client.send(DatagramPacket(bytes,bytes.size,loopback,port))
                val deadline=System.nanoTime()+2_000_000_000;while(!received.get() && System.nanoTime()<deadline)Thread.sleep(5)
                assertTrue(received.get())
            }
        }
    }
}
