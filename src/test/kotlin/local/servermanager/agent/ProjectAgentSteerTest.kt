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

    @Test fun `Pi steering reaches the next actor call and emits user visible acknowledgement`() {
        PiTestFixture(temp,false).use {f->val entered=CountDownLatch(1);val release=CountDownLatch(1);val accepted=CountDownLatch(1);var step=0;val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
            val model=f.model {q->if(step++==0){entered.countDown();check(release.await(10,TimeUnit.SECONDS));PiTestFixture.answer("first goal")}else {assertContains(q.toString(),"second goal");PiTestFixture.answer("new goal")}}
            val runner=Thread {try{f.run(model,onEvent={if(it.kind=="steer")accepted.countDown()})}catch(t:Throwable){failure.set(t)}}
            try {runner.start();assertTrue(entered.await(10,TimeUnit.SECONDS));assertFails {f.engine.steer("c","other","intruder")};assertTrue(f.engine.steer("c","u","change direction: second goal"));assertTrue(accepted.await(3,TimeUnit.SECONDS));release.countDown();runner.join(15000);assertFalse(runner.isAlive);failure.get()?.let {throw it};assertEquals(2,model.actorCalls);assertEquals("new goal",f.events.last().text)}finally{release.countDown();f.engine.stop("c","u");runner.join(3000)}
        }
    }

    @Test fun `Pi startup steering is retained before session creation and capped at five inputs`() {
        PiTestFixture(temp,false).use {f->val entered=CountDownLatch(1);val release=CountDownLatch(1);val first=java.util.concurrent.atomic.AtomicBoolean(true);val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
            val model=f.model {PiTestFixture.answer("startup applied")}
            val runner=Thread {try{f.run(model,authorize={if(first.compareAndSet(true,false)){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}})}catch(t:Throwable){failure.set(t)}}
            try {runner.start();assertTrue(entered.await(10,TimeUnit.SECONDS));repeat(5){assertTrue(f.engine.steer("c","u","startup-$it"))};assertFails {f.engine.steer("c","u","sixth")};assertFails {f.engine.steer("c","u","   ")};release.countDown();runner.join(15000);assertFalse(runner.isAlive);failure.get()?.let {throw it};assertContains(model.requests.first().toString(),"startup-0");repeat(5){assertContains(model.requests.joinToString(),"startup-$it")};assertTrue(model.actorCalls in 1..6);assertEquals(5,f.events.count {it.kind=="steer"})}finally {release.countDown();f.engine.stop("c","u");runner.join(3000)}
        }
    }
}
