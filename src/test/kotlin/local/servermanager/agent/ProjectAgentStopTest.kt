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
    @Test fun `engine stop interrupts blocked inference before it can propose or execute a tool`() {
        val home=temporary.resolve("project").createDirectories();val store=ProjectAgentStore(temporary.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","owner",null,home.toString()))
        val entered=CountDownLatch(1);val stopped=CountDownLatch(1)
        ProjectAgentEngine(store,{ProjectAgentTools(temporary.resolve("logs"))}).use {engine->
            val thread=Thread {try{engine.run("c","owner","request",store.preferences("g","owner"),ProjectAgentInference {_,_,_->entered.countDown();CountDownLatch(1).await();"""{"message":"bad","tool":{"name":"write_file","arguments":{"path":"bad","content":"bad"}}}"""},{},{})}catch(_:Exception){stopped.countDown()}}
            try {thread.start();assertTrue(entered.await(2,TimeUnit.SECONDS));engine.stop("c","owner");assertTrue(stopped.await(2,TimeUnit.SECONDS));thread.join(2000);assertFalse(home.resolve("bad").exists())}
            finally{thread.interrupt();thread.join(1000)}
        }
    }
}
