package local.servermanager

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TransferBatchTest {
    @Test fun `small file batches are bounded exact binary frames and reduce requests`() {
        val groups=TransferBatch.groups((1..512).map {"mods/$it.jar"}){1024}
        assertEquals(8,groups.size)
        groups.forEach {group->
            val parts=group.map {TransferPart(it,0,ByteArray(1024){n->(n%251).toByte()})}
            val encoded=TransferBatch.encode(parts);val decoded=TransferBatch.decode(encoded)
            assertEquals(group,decoded.map {it.path});decoded.forEach {assertContentEquals(parts.first().bytes,it.bytes)}
            assertFails {TransferBatch.decode(encoded+byteArrayOf(0))}
            assertFails {TransferBatch.decode(encoded.copyOf(encoded.size-1))}
        }
        val mixed=TransferBatch.groups(listOf("a","large","b")){if(it=="large")9_000_000 else 1}
        assertEquals(listOf(listOf("a"),listOf("large"),listOf("b")),mixed)
        assertFails {TransferBatch.decode(TransferBatch.encode(listOf(TransferPart("a",0,byteArrayOf(1)),TransferPart("a",0,byteArrayOf(2)))))}
        assertFails {TransferBatch.encode(listOf(TransferPart("a",0,ByteArray(TransferBatch.SMALL_FILE+1))))}
        assertFails {TransferBatch.encode((1..64).map {TransferPart("$it",0,ByteArray(TransferBatch.SMALL_FILE))})}
    }
    @Test fun `parallel transfer bounds active requests to four and drains failures before returning`() {
        val active=AtomicInteger();val peak=AtomicInteger();val completed=AtomicInteger()
        TransferBatch.parallel((1..24).toList()) {
            val n=active.incrementAndGet();peak.accumulateAndGet(n,::maxOf)
            try {Thread.sleep(15);completed.incrementAndGet()}finally{active.decrementAndGet()}
        }
        assertEquals(24,completed.get());assertTrue(peak.get() in 2..4);assertEquals(0,active.get())
        val started=CountDownLatch(2);val finished=AtomicInteger()
        assertFailsWith<IllegalStateException> {
            TransferBatch.parallel(listOf(1,2)){n->started.countDown();assertTrue(started.await(2,TimeUnit.SECONDS));if(n==1)error("disconnect") else {Thread.sleep(30);finished.incrementAndGet()}}
        }
        assertEquals(1,finished.get())
    }
}
