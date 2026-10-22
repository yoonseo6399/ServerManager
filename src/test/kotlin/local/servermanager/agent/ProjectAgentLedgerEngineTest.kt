package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentLedgerEngineTest {
    @TempDir lateinit var temp:Path
    private val allow="""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"ok"}"""
    private lateinit var store:ProjectAgentStore
    private lateinit var prefs:ProjectAgentPreferences

    private fun setup() {
        val home=temp.resolve("project").createDirectories().toRealPath()
        store=ProjectAgentStore(temp.resolve("settings"))
        store.addChannel(ProjectAgentChannel("g","c","u",null,home.toString()))
        prefs=store.settings("g","u","14b",true,true)
    }
    private fun home()=Path.of(store.channel("c")!!.home)
    private val request="- a.txt 파일을 만들고 hello를 넣어줘. 다른 파일은 건드리지 마.\n- 결과를 알려줘."

    @Test fun `Pi multi step request creates and verifies actual output without legacy ledger injection`() {
        PiTestFixture(temp).use {f->var step=0;val model=f.model {q->when(step++){
            0->PiTestFixture.call("list_files",emptyMap<String,Any>())
            1->PiTestFixture.call("write",mapOf("path" to "a.txt","content" to "hello\n"))
            2->PiTestFixture.call("read",mapOf("path" to "a.txt"))
            else->{assertContains(q.toString(),"hello");PiTestFixture.answer("a.txt 생성과 재조회 완료")}
        }}
            f.run(model,request);assertEquals("hello\n",f.home.resolve("a.txt").readText());assertEquals(3,f.events.count {it.kind=="tool_end"})
            assertTrue(f.events.none {it.kind=="ledger"});assertNull(f.store.notes("c"));assertEquals(4,model.actorCalls)
        }
    }

    @Test fun `Pi turn exhaustion preserves completed writes without emitting a fake success`() {
        PiTestFixture(temp).use {f->var step=0;val model=f.model {if(step++==0)PiTestFixture.call("write",mapOf("path" to "a.txt","content" to "preserved"))else PiTestFixture.call("list_files",emptyMap<String,Any>())}
            assertFails {f.run(model,turns=3)};assertEquals("preserved",f.home.resolve("a.txt").readText());assertEquals(3,model.actorCalls)
            assertTrue(f.events.any {it.kind=="tool_end" && it.plan?.tool?.name=="write"});assertTrue(f.events.none {it.kind=="done"})
        }
    }

    @Test fun `Pi prose evidence JSON cannot create a tool receipt or mutate a file`() {
        PiTestFixture(temp,false).use {f->val claim="""{"message":"파일 생성 완료","requirements":[{"id":"R1","evidence_ids":["T999"]}]}"""
            val model=f.model {PiTestFixture.answer(claim)};f.run(model,"a.txt 만들기")
            assertFalse(f.home.resolve("a.txt").exists());assertTrue(f.events.none {it.kind=="tool_start" || it.kind=="ledger"});assertNull(f.store.notes("c"));assertEquals(1,model.actorCalls)
        }
    }

    @Test fun `Pi asks clarification and receives exact informational answer without execution approval`() {
        PiTestFixture(temp).use {f->var step=0;var answered=""
            val model=f.model {q->if(step++==0)PiTestFixture.call("ask_user",mapOf("question" to "어느 폴더?","choices" to listOf("A","B")))else {assertContains(q.toString(),"CUSTOM_DIR");assertContains(q.toString(),"execution_approved");PiTestFixture.answer("답변 확인")}}
            f.run(model,onEvent={e->if(e.kind=="question")assertTrue(f.engine.answerQuestion("c","u",null,"CUSTOM_DIR"){});if(e.kind=="question_answered")answered=e.text})
            assertEquals("CUSTOM_DIR",answered);assertFalse(f.home.resolve("a.txt").exists());assertTrue(f.events.none {it.kind=="approval"})
        }
    }

    @Test fun `Pi unfinished tool history and original request survive a follow up without legacy task notes`() {
        PiTestFixture(temp,false).use {f->f.home.resolve("a.txt").writeText("PRESERVED_742")
            assertFails {f.run(f.model {PiTestFixture.call("read",mapOf("path" to "a.txt"))},"ORIGINAL_TASK_742 파일 확인",turns=2)}
            val follow=f.model {q->assertContains(q.toString(),"ORIGINAL_TASK_742");assertContains(q.toString(),"PRESERVED_742");PiTestFixture.answer("이어서 확인")}
            f.run(follow,"이어서 계속해줘");assertEquals(1,follow.actorCalls);assertNull(f.store.notes("c"))
        }
    }

    @Test fun `Pi mid task instruction enters native steering history and authenticated intent`() {
        PiTestFixture(temp,false).use {f->val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val accepted=java.util.concurrent.CountDownLatch(1);var step=0
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
            val model=f.model {q->if(step++==0){entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS));PiTestFixture.call("list_files",emptyMap<String,Any>())}else {assertContains(q.toString(),"README_742");PiTestFixture.answer("개입 반영")}}
            val runner=Thread {try {f.run(model,onEvent={if(it.kind=="steer")accepted.countDown()})}catch(t:Throwable){failure.set(t)}}
            try {runner.start();assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));assertTrue(f.engine.steer("c","u","README_742를 추가해줘"));assertTrue(accepted.await(3,java.util.concurrent.TimeUnit.SECONDS));release.countDown();runner.join(15000);assertFalse(runner.isAlive);failure.get()?.let {throw it};assertEquals(2,model.actorCalls)}finally {release.countDown();f.engine.stop("c","u");runner.join(3000)}
        }
    }
}
