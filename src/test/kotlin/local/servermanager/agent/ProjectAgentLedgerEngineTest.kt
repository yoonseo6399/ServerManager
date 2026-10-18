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

    @Test fun `the run cannot finish while tracked requirements are open and an unproven claim is rejected`() {
        setup();var actor=0;val events=mutableListOf<ProjectAgentEvent>();var sawRejection=false;var lastSeen=""
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            engine.run("c","u","u",request,prefs,ProjectAgentInference {messages,_,_->when {
                messages.first().content.startsWith("Requirement extractor")->"""{"items":["a.txt 파일을 만들고 hello를 넣는다","결과를 알려준다"]}"""
                messages.first().content.startsWith("Context compressor")->"이전 목표와 실제 결과를 보존한 테스트 요약"
            messages.first().content.startsWith("Independent")->allow
                else->when(actor++) {
                    0->"""{"message":"목록 확인","tool":{"name":"list_files","arguments":{}}}"""
                    1->"""{"message":"이미 끝났습니다. 파일을 만들었고 결과를 확인했습니다.","status":"completed","tool":null,"requirements":[{"id":"R1","status":"done","evidence":"I created the file already"}]}"""
                    2->{lastSeen=messages.takeLast(3).joinToString(" // ") {it.role+":"+it.content.take(400)};sawRejection=messages.any {it.content.contains("Rejected claims") && it.content.contains("R1")};"""{"message":"파일 생성","tool":{"name":"write_file","arguments":{"path":"a.txt","content":"hello\n"}}}"""}
                    else->"""{"message":"a.txt 생성 완료","status":"completed","tool":null,"requirements":[{"id":"R1","status":"done","evidence_ids":["T3"],"evidence":"write_file applied a.txt successfully"},{"id":"R2","status":"done","evidence":"reported in this final message"}]}"""
                }
            }},ProjectAgentLimits(8,AgentAccess.OWNER),{}) {e->events.add(e);if(e.kind=="approval")engine.approve("c","u",e.token!!,true){}}
        }
        assertEquals(4,actor,"the premature finish must be sent back instead of ending the run")
        assertTrue(sawRejection,"the model must be told why its claim was rejected: "+lastSeen)
        assertEquals("hello\n",home().resolve("a.txt").readText())
        assertTrue(events.any {it.kind=="status" && it.text.contains("미완료 요구사항 2개")})
        assertTrue(events.any {it.kind=="ledger" && it.text.contains("2개를 추적")})
        val done=events.last {it.kind=="done"}.text
        assertTrue(done.contains("✅ R1") && done.contains("✅ R2"),done)
    }

    @Test fun `repetition stop reports files actually saved without claiming full completion`() {
        setup();var actor=0;val events=mutableListOf<ProjectAgentEvent>()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            engine.run("c","u","u","a.txt 파일을 생성해",prefs,ProjectAgentInference {messages,_,_->when {
                messages.first().content.startsWith("Context compressor")->"아직 보고하지 못함"
                messages.first().content.startsWith("Independent")->allow
                actor++==0->"""{"message":"저장","tool":{"name":"write_file","arguments":{"path":"a.txt","content":"preserved"}}}"""
                else->"""{"message":"조회","tool":{"name":"list_files","arguments":{}}}"""
            }},ProjectAgentLimits(10,AgentAccess.OWNER),{}) {e->events.add(e);if(e.kind=="approval")engine.approve("c","u",e.token!!,true){}}
        }
        val done=events.last {it.kind=="done"}.text
        assertContains(done,"반복");assertContains(done,"완료로 처리하지 않았습니다")
        assertContains(done,"백엔드가 확인한 실제 변경");assertContains(done,"a.txt")
        assertEquals("preserved",home().resolve("a.txt").readText())
        assertContains(store.notes("c")!!.lastResult,"백엔드가 확인한 실제 변경")
    }

    @Test fun `unproven final success is never displayed and exhausting repair ends honestly`() {
        setup();val events=mutableListOf<ProjectAgentEvent>()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            engine.run("c","u","u","a.txt 파일을 생성해",prefs,ProjectAgentInference {messages,_,_->
                if(messages.first().content.startsWith("Context compressor"))"아직 파일 생성 근거 없음"
                else """{"message":"모든 파일 생성 완료","status":"completed","tool":null,"requirements":[{"id":"R1","status":"done","evidence_ids":["T999"],"evidence":"파일 생성을 완료했습니다"}]}"""
            },ProjectAgentLimits(8,AgentAccess.OWNER),{}) {events.add(it)}
        }
        assertFalse(home().resolve("a.txt").exists())
        assertFalse(events.any {it.kind=="message" && it.text.contains("모든 파일 생성 완료")})
        assertTrue(events.last {it.kind=="done"}.text.startsWith("작업 중단:"))
        assertContains(events.last().text,"미완료")
    }

    @Test fun `a blocked item or a clarifying question may end the run and is reported`() {
        setup();val events=mutableListOf<ProjectAgentEvent>()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            engine.run("c","u","u",request,prefs,ProjectAgentInference {messages,_,_->when {
                messages.first().content.startsWith("Requirement extractor")->"""{"items":["a.txt 파일을 만든다","결과를 알려준다"]}"""
                else->"""{"message":"어느 폴더에 만들까요?","status":"clarify","tool":null}"""
            }},ProjectAgentLimits(5,AgentAccess.OWNER),{}) {e->events.add(e)}
        }
        val done=events.last {it.kind=="done"}.text
        assertTrue(done.contains("어느 폴더") && done.contains("⬜ R1") && done.contains("미완료"),done)
        assertEquals(listOf("a.txt 파일을 만든다","결과를 알려준다"),store.notes("c")!!.open,"unfinished items are carried to the next request")
    }

    @Test fun `unfinished items and the last request are carried to a short follow up`() {
        setup();val seen=mutableListOf<List<ProjectAgentMessage>>()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            engine.run("c","u","u",request,prefs,ProjectAgentInference {messages,_,_->when {
                messages.first().content.startsWith("Requirement extractor")->"""{"items":["a.txt 파일을 만든다","결과를 알려준다"]}"""
                messages.first().content.startsWith("Context compressor")->"이전 목표와 실제 결과를 보존한 테스트 요약"
            messages.first().content.startsWith("Independent")->allow
                else->"""{"message":"목록 확인","tool":{"name":"list_files","arguments":{}}}"""
            }},ProjectAgentLimits(2,AgentAccess.OWNER),{}) {}
            val notes=store.notes("c")!!
            assertEquals(request.take(300),notes.lastRequest);assertEquals(2,notes.open.size)
            engine.run("c","u","u","이어서 계속해줘",prefs,ProjectAgentInference {messages,_,_->
                if(messages.first().content.startsWith("Independent"))allow else if(messages.first().content.startsWith("Context compressor"))"테스트 요약" else {seen.add(messages);"""{"message":"계속합니다","status":"clarify","tool":null}"""}
            },ProjectAgentLimits(2,AgentAccess.OWNER),{}) {}
        }
        val pinned=seen.first().first {it.content.contains("current_requester")}.content
        assertTrue(pinned.contains("previous_task_notes") && pinned.contains("requirements"),pinned)
        assertTrue(pinned.contains("a.txt 파일을 만든다") && pinned.contains("이어서 계속해줘"),pinned)
    }

    @Test fun `a mid task instruction becomes a new tracked requirement`() {
        setup();val release=java.util.concurrent.CountDownLatch(1);val started=java.util.concurrent.CountDownLatch(1);var actor=0;val seen=mutableListOf<String>()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            val runner=Thread {engine.run("c","u","u",request,prefs,ProjectAgentInference {messages,_,_->when {
                messages.first().content.startsWith("Requirement extractor")->"""{"items":["a.txt 파일을 만든다","결과를 알려준다"]}"""
                messages.first().content.startsWith("Context compressor")->"이전 목표와 실제 결과를 보존한 테스트 요약"
            messages.first().content.startsWith("Independent")->allow
                else->{
                    if(actor++==0){started.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS)}
                    seen.add(messages.first {it.content.contains("current_requester")}.content)
                    """{"message":"계속","status":"clarify","tool":null}"""
                }
            }},ProjectAgentLimits(3,AgentAccess.OWNER),{}) {}}
            runner.start();assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(engine.steer("c","u","추가로 README에 사용법 섹션을 작성해줘"))
            release.countDown();runner.join(15000)
        }
        assertTrue(seen.drop(1).any {it.contains("README에 사용법 섹션")},seen.toString())
    }
}
