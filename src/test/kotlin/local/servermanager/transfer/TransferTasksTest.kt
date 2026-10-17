package local.servermanager

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TransferTasksTest {
    private fun key(id:String="one",request:String="receipt")=TransferTasks.Key(id,"runner","path","commit",request)
    private fun <T:Any> completed(tasks:TransferTasks,key:TransferTasks.Key,consume:Boolean=false,work:()->T):T {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<deadline){tasks.poll(key,consume,work)?.let{return it};Thread.sleep(5)}
        error("Background transaction did not complete")
    }
    @Test fun `slow transaction is single flight and does not block polling or another instance`() {
        TransferTasks().use {tasks->
            val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
            val work={calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS));"committed"}
            try {
                assertNull(tasks.poll(key(),work=work));assertTrue(entered.await(5,TimeUnit.SECONDS))
                val start=System.nanoTime()
                repeat(20){assertNull(tasks.poll(key(),work=work));assertTrue(tasks.busy("one"))}
                assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1))
                assertNull(tasks.poll(key(request="other")){error("Must not run alongside this instance")})
                assertEquals("independent",completed(tasks,key("two")){"independent"})
            }finally{release.countDown()}
            assertEquals("committed",completed(tasks,key(),work=work));assertEquals(1,calls.get())
            assertEquals("committed",tasks.poll<String>(key()){error("Commit result must be idempotent")})
        }
    }
    @Test fun `consumed begin result refreshes offsets and failed jobs can retry`() {
        TransferTasks().use {tasks->
            val calls=AtomicInteger()
            assertEquals(1,completed(tasks,key(),true){calls.incrementAndGet()})
            assertEquals(2,completed(tasks,key(),true){calls.incrementAndGet()})
            tasks.poll(key()){throw IllegalStateException("fixture")}
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(tasks.busy("one")&&System.nanoTime()<deadline)Thread.sleep(5)
            assertFailsWith<IllegalStateException>{tasks.poll(key()){error("cached failure")}}
            assertEquals("recovered",completed(tasks,key()){ "recovered" })
        }
    }
}
