package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentStopTest {
    @TempDir lateinit var temporary:Path
    @Test fun `stop rejects another user and stale run button then interrupts current owner job`() {
        val jobs=ProjectAgentJobs();val first=jobs.begin("channel","owner");jobs.finish("channel",first)
        val started=CountDownLatch(1);val interrupted=CountDownLatch(1);val current=AtomicReference<ProjectAgentJobs.Job>()
        val thread=Thread {val job=jobs.begin("channel","owner");current.set(job);started.countDown();try{Thread.sleep(30000)}catch(_:InterruptedException){interrupted.countDown()}finally{jobs.finish("channel",job)}}
        try {
            thread.start();assertTrue(started.await(2,TimeUnit.SECONDS))
            assertFails {jobs.stop("channel","other",current.get().id)};assertFails {jobs.stop("channel","owner",first.id)};assertTrue(thread.isAlive)
            jobs.stop("channel","owner",current.get().id);assertTrue(interrupted.await(2,TimeUnit.SECONDS));thread.join(2000);assertFalse(thread.isAlive)
        }finally{jobs.close();thread.interrupt();thread.join(1000)}
    }
    @Test fun `Pi stop interrupts blocked inference and no native write can run afterward`() {
        PiTestFixture(temporary).use {f->val entered=CountDownLatch(1);val stopped=CountDownLatch(1)
            val model=f.model {entered.countDown();CountDownLatch(1).await();PiTestFixture.call("write",mapOf("path" to "bad","content" to "bad"))}
            val runner=Thread {try {f.run(model)}catch(_:Exception){stopped.countDown()}}
            try {runner.start();assertTrue(entered.await(10,TimeUnit.SECONDS));f.engine.stop("c","u");assertTrue(stopped.await(3,TimeUnit.SECONDS));runner.join(3000);assertFalse(runner.isAlive);assertFalse(f.home.resolve("bad").exists());assertTrue(f.events.none {it.kind=="tool_start"})}finally {runner.interrupt();runner.join(3000)}
        }
    }
}
