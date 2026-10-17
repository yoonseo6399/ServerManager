package local.servermanager

import org.junit.jupiter.api.Test
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DatagramOutboxTest {
    @Test fun `slow data connection has bounded queue and expired datagrams do not accumulate latency`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val written=AtomicInteger()
        DatagramOutbox {written.incrementAndGet();entered.countDown();release.await(2,TimeUnit.SECONDS);true}.use {outbox->
            assertTrue(outbox.offer(byteArrayOf(1)));assertTrue(entered.await(1,TimeUnit.SECONDS))
            repeat(64){assertTrue(outbox.offer(byteArrayOf(2)))};assertFalse(outbox.offer(byteArrayOf(3)))
            Thread.sleep(130);release.countDown();Thread.sleep(50)
            assertEquals(1,written.get(),"Stale movement packets must be discarded after a stalled connection")
        }
    }
}
