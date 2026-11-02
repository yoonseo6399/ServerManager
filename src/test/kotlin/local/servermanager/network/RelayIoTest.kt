package local.servermanager

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class RelayIoTest {
    private fun await(check:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!check()){assertTrue(System.nanoTime()<end,"condition not reached");Thread.sleep(10)}}
    private class Target(private val block:Boolean=false):Socket() {
        val entered=CountDownLatch(1);val release=CountDownLatch(if(block)1 else 0)
        val bytes=ByteArrayOutputStream();val ended=AtomicBoolean()
        private val output=object:OutputStream(){
            override fun write(value:Int){write(byteArrayOf(value.toByte()))}
            override fun write(value:ByteArray,offset:Int,length:Int){entered.countDown();release.await();if(ended.get())throw java.net.SocketException("closed");synchronized(bytes){bytes.write(value,offset,length)}}
        }
        override fun getOutputStream()=output
        override fun close(){ended.set(true);release.countDown()}
    }
    @Test fun `blocked player writer cannot hold another player and deadline releases all budget`() {
        val budget=RelayByteBudget(4096);val events=CopyOnWriteArrayList<String>();val sink=RelayEvents {event,_->events.add(event)}
        val failed=CompletableFuture<String>();val slow=Target(true);val healthy=Target()
        RelaySocketWriter(slow,budget,sink,mapOf("stream" to 1),{failed.complete(it)},writeTimeoutMillis=150).use {a->
            RelaySocketWriter(healthy,budget,sink,mapOf("stream" to 2),{fail("healthy writer failed: $it")}).use {b->
                assertTrue(a.offer(ByteArray(1024)));assertTrue(slow.entered.await(1,TimeUnit.SECONDS))
                assertTrue(b.offer("unaffected".toByteArray()));await {healthy.bytes.size()==10}
                assertEquals("ENDPOINT_WRITE_TIMEOUT",failed.get(3,TimeUnit.SECONDS))
                assertFalse(healthy.ended.get());assertTrue(b.offer("!".toByteArray()));await {healthy.bytes.size()==11}
            }
        }
        await {budget.usedBytes()==0L};assertTrue(events.contains("stream_closed"))
    }
    @Test fun `backlog overflow disconnects only offender without waiting on its blocked socket`() {
        val budget=RelayByteBudget(8);val socket=Target(true);val failed=CompletableFuture<String>()
        RelaySocketWriter(socket,budget,RelayEvents.NONE,emptyMap(),{failed.complete(it)},maxBytes=8).use {out->
            assertTrue(out.offer(ByteArray(8)));assertTrue(socket.entered.await(1,TimeUnit.SECONDS))
            assertFalse(out.offer(byteArrayOf(1)));assertEquals("ENDPOINT_BACKPRESSURE",failed.get(1,TimeUnit.SECONDS))
        }
        await {budget.usedBytes()==0L}
    }
    @Test fun `remote close drains accepted bytes in order before closing endpoint`() {
        val socket=Target();val budget=RelayByteBudget()
        RelaySocketWriter(socket,budget,RelayEvents.NONE,emptyMap(),{fail(it)}).use {out->
            assertTrue(out.offer("first".toByteArray()));assertTrue(out.offer("second".toByteArray()));out.finish("REMOTE_CLOSE")
            await {socket.ended.get()};assertEquals("firstsecond",socket.bytes.toString());assertFalse(out.offer(byteArrayOf(1)))
        }
        assertEquals(0,budget.usedBytes())
    }
    @Test fun `tunnel queue is fair and preserves OPEN DATA CLOSE order within each stream`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val order=CopyOnWriteArrayList<Pair<Int,Int>>()
        RelayFrameWriter({f->if(order.isEmpty()){entered.countDown();release.await()};order.add(f.stream to f.type)}, {fail(it)},RelayEvents.NONE,emptyMap()).use {out->
            out.send(SecureChannel.Frame(SecureChannel.OPEN,1,byteArrayOf()));assertTrue(entered.await(1,TimeUnit.SECONDS))
            repeat(2){out.send(SecureChannel.Frame(SecureChannel.DATA,1,byteArrayOf(1)))}
            out.send(SecureChannel.Frame(SecureChannel.CLOSE,1,byteArrayOf()))
            out.send(SecureChannel.Frame(SecureChannel.DATA,2,byteArrayOf(2)))
            release.countDown();await {order.size==5}
            assertEquals(listOf(1 to 1,1 to 4,2 to 4,1 to 4,1 to 5),order.toList())
        }
    }
    @Test fun `tunnel write deadline aborts a stuck transport rather than hanging all senders forever`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val failed=CompletableFuture<String>()
        RelayFrameWriter({_ ->entered.countDown();release.await()},{failed.complete(it);release.countDown()},RelayEvents.NONE,emptyMap(),writeTimeoutMillis=150).use {out->
            out.send(SecureChannel.Frame(SecureChannel.DATA,1,byteArrayOf(1)));assertTrue(entered.await(1,TimeUnit.SECONDS))
            assertEquals("TUNNEL_WRITE_TIMEOUT",failed.get(3,TimeUnit.SECONDS))
            assertFailsWith<java.io.IOException>{out.send(SecureChannel.Frame(SecureChannel.DATA,2,byteArrayOf(2)))}
        }
    }
    @Test fun `normal burst applies bounded backpressure to its reader and close notification is flushed`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val writes=CopyOnWriteArrayList<Int>();val done=CompletableFuture<Unit>()
        RelayFrameWriter({f->entered.countDown();release.await();writes.add(f.type)}, {fail(it)},RelayEvents.NONE,emptyMap(),byteLimit=8,streamLimit=8).use {out->
            out.send(SecureChannel.Frame(SecureChannel.DATA,1,ByteArray(8)));assertTrue(entered.await(1,TimeUnit.SECONDS))
            val sender=CompletableFuture.runAsync {out.send(SecureChannel.Frame(SecureChannel.DATA,1,ByteArray(8)),true)}
            assertFalse(sender.isDone);release.countDown();sender.get(2,TimeUnit.SECONDS)
            out.send(SecureChannel.Frame(SecureChannel.GOAWAY,0,byteArrayOf()));out.finish {done.complete(Unit)}
            done.get(2,TimeUnit.SECONDS);assertEquals(listOf(4,4,8),writes.toList())
        }
    }
}
