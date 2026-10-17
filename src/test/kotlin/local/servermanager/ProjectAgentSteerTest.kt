package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentSteerTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private lateinit var prefs:ProjectAgentPreferences

    private fun setup():ProjectAgentStore {
        val home=temp.resolve("project").createDirectories().toRealPath()
        val store=ProjectAgentStore(temp.resolve("settings"))
        store.addChannel(ProjectAgentChannel("guild","channel","owner","demo",home.toString()))
        prefs=store.settings("guild","owner","14b",true,true)
        return store
    }

    @Test fun `steering is refused for other users and when nothing is running`() {
        val store=setup()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {e->
            assertFalse(e.steer("channel","owner","hello"))
            assertFailsWith<IllegalStateException> {e.steer("channel","intruder","hello")}
        }
    }

    @Test fun `an instruction sent during a run reaches the next model call and is shown as an event`() {
        val store=setup()
        val inFirstCall=CountDownLatch(1);val release=CountDownLatch(1)
        val seen=mutableListOf<List<ProjectAgentMessage>>()
        val events=java.util.concurrent.CopyOnWriteArrayList<ProjectAgentEvent>()
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val model=ProjectAgentInference {messages,_,_->
            seen.add(messages)
            if(calls.incrementAndGet()==1){inFirstCall.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
                mapper.writeValueAsString(mapOf("message" to "done with the first goal","status" to "completed","tool" to null))}
            else mapper.writeValueAsString(mapOf("message" to "now doing the new goal","status" to "completed","tool" to null))
        }
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {e->
            val runner=Thread {e.run("channel","owner","owner","first goal",prefs,model,ProjectAgentLimits(5,AgentAccess.OWNER),{},{events.add(it)})}
            runner.start()
            assertTrue(inFirstCall.await(10,TimeUnit.SECONDS))
            assertTrue(e.steer("channel","owner","change direction: do the second goal instead"))
            release.countDown();runner.join(15000)
            assertEquals(2,calls.get(),"the run must continue instead of finishing with the old goal")
            assertTrue(seen[1].any {it.role=="user" && it.content.contains("mid_task_instruction") && it.content.contains("second goal")})
            assertFalse(seen[0].any {it.role=="user" && it.content.contains("mid_task_instruction")})
            assertTrue(events.any {it.kind=="done" && it.text.contains("new goal")})
        }
    }

    @Test fun `a steer already waiting is applied before the first call and the queue is bounded`() {
        val store=setup()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {e->
            val started=CountDownLatch(1);val release=CountDownLatch(1)
            val model=ProjectAgentInference {_,_,_->started.countDown();assertTrue(release.await(10,TimeUnit.SECONDS))
                mapper.writeValueAsString(mapOf("message" to "ok","status" to "completed","tool" to null))}
            val runner=Thread {e.run("channel","owner","owner","goal",prefs,model,ProjectAgentLimits(3,AgentAccess.OWNER),{},{})}
            runner.start();assertTrue(started.await(10,TimeUnit.SECONDS))
            repeat(5) {assertTrue(e.steer("channel","owner","msg $it"))}
            assertFailsWith<IllegalStateException> {e.steer("channel","owner","one too many")}
            assertFailsWith<IllegalArgumentException> {e.steer("channel","owner","   ")}
            e.stop("channel","owner");release.countDown();runner.join(15000)
        }
    }
}
