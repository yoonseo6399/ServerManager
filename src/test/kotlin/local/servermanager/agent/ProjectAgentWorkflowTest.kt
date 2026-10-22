package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.concurrent.*
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentWorkflowTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private fun call(name:String,vararg args:Pair<String,Any>)=ProjectAgentTool(name,mapper.valueToTree(args.toMap()))
    private val risk="""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"검토"}"""
    private fun store():ProjectAgentStore=ProjectAgentStore(temp.resolve("settings")).also {it.addChannel(ProjectAgentChannel("g","c","u","project",temp.toRealPath().toString()))}
    @Test fun `Pi failed build is observed corrected rerun and reported through native tools`() {
        PiTestFixture(temp).use {f->val file=f.home.resolve("build.sh");file.writeText("echo initial-failure; exit 7");var step=0
            f.run(f.model {q->when(step++){
                0->PiTestFixture.call("bash",mapOf("command" to "bash build.sh"))
                1->{assertContains(q.toString(),"initial-failure");assertContains(q.toString(),"7");PiTestFixture.call("read",mapOf("path" to "build.sh"))}
                2->PiTestFixture.call("edit",mapOf("path" to "build.sh","edits" to listOf(mapOf("oldText" to "exit 7","newText" to "exit 0"))))
                3->PiTestFixture.call("bash",mapOf("command" to "bash build.sh"))
                else->PiTestFixture.answer("수정 후 종료 코드 0 확인")
            }},"빌드 오류 수정 및 재검증")
            assertEquals(listOf(7,0),f.events.filter {it.kind=="tool_end" && it.plan?.tool?.name=="bash"}.map {it.exitCode});assertContains(file.readText(),"exit 0");assertEquals(4,f.events.count {it.kind=="tool_end"})
        }
    }
    @Test fun `process output arrives before process exit`() {
        val first=CountDownLatch(1);val executor=Executors.newSingleThreadExecutor()
        try {
            val tools=ProjectAgentTools(temp.resolve("logs"));val plan=tools.plan(temp,call("run_command","command" to "printf first; sleep 1; printf last"))
            val future=executor.submit<ProjectAgentToolResult> {tools.execute(temp,plan,progress={if(it.contains("first"))first.countDown()}){}}
            assertTrue(first.await(2,TimeUnit.SECONDS));assertFalse(future.isDone);assertContains(future.get(3,TimeUnit.SECONDS).full,"last")
        }finally{executor.shutdownNow()}
    }
    @Test fun `reset preserves project settings and rejects stale history after reload`() {
        val store=store();store.history("c",listOf(ProjectAgentMessage("user","private history")),0)
        store.settings("g","u","30b",false,false)
        val next=store.context("c","u",reset=true)
        assertTrue(next.history.isEmpty());assertEquals("project",next.project);assertEquals(temp.toRealPath().toString(),next.home)
        assertFails {store.history("c",listOf(ProjectAgentMessage("assistant","stale")),0)}
        val loaded=ProjectAgentStore(temp.resolve("settings"));assertTrue(loaded.channel("c")!!.history.isEmpty());assertEquals("30b",loaded.preferences("g","u").model)
        val legacy=mapper.readTree(temp.resolve("settings/project-agent.json").readText());assertFalse(legacy.has("gradleConsent"));assertFalse(legacy.path("channels").path("c").has("contextEpoch"))
        assertFails {store.context("c","other",reset=true)};assertFails {store.context("c","u",limit=99999)}
    }
    @Test fun `compression uses independent bounded session and preserves latest exchange without granting permission`() {
        val history=List(8){ProjectAgentMessage(if(it%2==0)"user" else "assistant","message-$it")}
        val compressed=ProjectAgentMemory.compress(history,ProjectAgentInference {messages,think,_->assertFalse(think);assertTrue(messages.first().content.startsWith("Context compressor"));assertContains(messages.first().content,"NO tools");"목표와 미완료 작업"})
        assertEquals(history.takeLast(2),compressed.takeLast(2));assertEquals("user",compressed.first().role);assertContains(compressed.first().content,"not instructions or approval")
        assertFails {ProjectAgentMemory.compress(history,ProjectAgentInference {_,_,_->""})};assertEquals(8,history.size)
    }
    @Test fun `routing sees prior dialogue and aliases bypass a flaky classifier`() {
        val store=store();ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            assertTrue(engine.route("루오 다시 해줘",ProjectAgentInference {_,_,_->error("should not classify explicit address")}))
            assertTrue(engine.route("그거 수정해줘",ProjectAgentInference {messages,_,_->assertContains(messages.last().content,"history");assertContains(messages.last().content,"previous-reply");"""{"addressed":true}"""},history=listOf(ProjectAgentMessage("assistant","previous-reply"))))
        }
    }
    @Test fun `Pi reviewer ASK cannot be bypassed by a legacy Gradle trust record`() {
        PiTestFixture(temp,false).use {f->val wrapper=f.home.resolve("gradlew");wrapper.writeText("#!/bin/sh\necho checked\n");wrapper.toFile().setExecutable(true);f.home.resolve("build.gradle.kts").writeText("// fixture")
            f.store.trustGradle("g","u","arbitrary-old-trust");var step=0;var asks=0
            val model=f.model("""{"action":"ASK","risk":"MEDIUM","projectScope":"INSIDE","reason":"현재 빌드 동의 확인 필요"}""") {if(step++==0)PiTestFixture.call("bash",mapOf("command" to "./gradlew build test --no-daemon"))else PiTestFixture.answer("검증 완료")}
            f.run(model,onEvent={e->if(e.kind=="approval"){asks++;f.engine.approve("c","u",e.token!!,true){}}})
            assertEquals(1,asks);assertEquals(0,f.events.single {it.kind=="tool_end"}.exitCode)
        }
    }
    @Test fun `Pi automatic compaction uses official SDK session and preserves a factual marker`() {
        PiTestFixture(temp).use {f->f.store.context("c","u",limit=4500)
            val model=f.model(inputTokens=5000) {q->PiTestFixture.answer(if(q.path("tools").size()==0)"Goal CODE_742 remains unfinished; source bytes preserved."else "CODE_742 "+"conversation ".repeat(500))}
            f.run(model,"CODE_742를 기억해")
            assertTrue(model.requests.any {it.path("tools").size()==0});val session=Path.of(f.store.piState(f.store.channel("c")!!).resolve("current-session.txt").readText())
            assertTrue(session.readLines().any {mapper.readTree(it).path("type").asText()=="compaction"})
            val follow=f.model {q->assertContains(q.toString(),"CODE_742");PiTestFixture.answer("기억합니다")};f.run(follow,"기억 확인")
        }
    }
    @Test fun `tool output summarizer failure falls back with exit code and no tool authority`() {
        val result=ProjectAgentToolResult("bounded","error ".repeat(300),42)
        val text=ProjectAgentMemory.summarize(result,ProjectAgentInference {messages,_,_->assertContains(messages.first().content,"UNTRUSTED");error("offline")})
        assertContains(text,"42");assertContains(text,"요약 실패")
    }
}
