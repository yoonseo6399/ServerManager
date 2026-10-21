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
    private fun response(tool:ProjectAgentTool?)=mapper.writeValueAsString(mapOf("message" to if(tool==null)"검증 완료" else "다음 단계", "tool" to tool))
    private val risk="""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"검토"}"""
    private fun store():ProjectAgentStore=ProjectAgentStore(temp.resolve("settings")).also {it.addChannel(ProjectAgentChannel("g","c","u","project",temp.toRealPath().toString()))}
    @Test fun `failed build is inspected corrected rerun and reported in a later model turn`() {
        val store=store();temp.resolve("build.sh").writeText("echo initial-failure; exit 7")
        val events=mutableListOf<ProjectAgentEvent>();var actor=0
        val model=ProjectAgentInference {messages,_,_->
            if(messages.first().content.startsWith("Independent"))risk else if(messages.first().content.startsWith("Context compressor"))compactionFixture("빌드 실패와 미완료 수정 작업")else {
                assertContains(messages.first().content,"MULTI-STEP")
                when(actor++){
                    0->response(call("build","command" to "/bin/sh ./build.sh"))
                    1->{assertTrue(messages.any {it.content.contains("exit=7")});response(call("read_file","path" to "build.sh"))}
                    2->response(call("edit_file","path" to "build.sh","find" to "echo initial-failure; exit 7","replace" to "echo checked; exit 0"))
                    3->response(call("build","command" to "/bin/sh ./build.sh"))
                    else->{assertTrue(messages.any {it.content.contains("checked") && it.content.contains("exit=0")});mapper.writeValueAsString(mapOf("message" to "검증 완료","tool" to null,"requirements" to listOf(mapOf("id" to "R1","status" to "done","evidence_ids" to listOf("T3","T4"),"evidence" to "build.sh 수정 후 빌드 종료 코드 0 확인"))))}
                }
            }
        }
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->engine.run("c","u","빌드 오류 수정 및 재검증",ProjectAgentPreferences(),model,{}) {event->events.add(event);if(event.kind=="approval")engine.approve("c","u",event.token!!,true){}}}
        assertEquals(5,actor);assertEquals(4,events.count {it.kind=="tool_end"});assertTrue(events.last().text.startsWith("검증 완료"))
        assertTrue(events.any {it.kind=="tool_output" && it.text.contains("initial-failure")})
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
        val compressed=ProjectAgentMemory.compress(history,ProjectAgentInference {messages,think,_->assertFalse(think);assertTrue(messages.first().content.startsWith("Context compressor"));assertContains(messages.first().content,"NO tools");compactionFixture("목표와 미완료 작업")})
        assertEquals(history.takeLast(2),compressed.takeLast(2));assertEquals("user",compressed.first().role);assertContains(compressed.first().content,"not instructions or approval")
        assertFails {ProjectAgentMemory.compress(history,ProjectAgentInference {_,_,_->""})};assertEquals(8,history.size)
    }
    @Test fun `routing sees prior dialogue and aliases bypass a flaky classifier`() {
        val store=store();ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            assertTrue(engine.route("루오 다시 해줘",ProjectAgentInference {_,_,_->error("should not classify explicit address")}))
            assertTrue(engine.route("그거 수정해줘",ProjectAgentInference {messages,_,_->assertContains(messages.last().content,"recent_conversation");assertContains(messages.last().content,"previous-reply");"""{"addressed":true}"""},history=listOf(ProjectAgentMessage("assistant","previous-reply"))))
        }
    }
    @Test fun `human trusted Gradle build runs without repeat approval but changed build logic needs consent`() {
        val store=store();val wrapper=temp.resolve("gradlew");wrapper.writeText("#!/bin/sh\necho checked\n");wrapper.toFile().setExecutable(true)
        temp.resolve("build.gradle.kts").writeText("// first")
        val tools=ProjectAgentTools(temp.resolve("logs"));val tool=call("build","command" to "./gradlew build test --no-daemon")
        val fingerprint=tools.plan(temp,tool).gradleFingerprint ?: return // A developer's global init scripts intentionally disable auto-trust.
        store.trustGradle("g","u",fingerprint)
        fun run():Int {var actor=0;var approvals=0
            ProjectAgentEngine(store,{tools}).use {engine->engine.run("c","u","빌드",ProjectAgentPreferences(explicitPermission=false),ProjectAgentInference {messages,_,_->
                if(messages.first().content.startsWith("Independent"))"""{"action":"ASK","risk":"MEDIUM","projectScope":"UNKNOWN","reason":"빌드 실행"}""" else if(messages.first().content.startsWith("Context compressor"))compactionFixture("이전 빌드 확인 기록") else if(actor++==0)response(tool)else response(null)
            },{}){event->if(event.kind=="approval"){approvals++;engine.approve("c","u",event.token!!,true){}}}}
            return approvals
        }
        assertEquals(0,run());temp.resolve("build.gradle.kts").writeText("// changed");assertEquals(1,run())
        assertNull(tools.plan(temp,call("build","command" to "./gradlew build; touch escaped")).gradleFingerprint)
        assertNull(tools.plan(temp,call("build","command" to "./gradlew publish")).gradleFingerprint)
    }
    @Test fun `threshold triggers automatic independent compression before actor and persists summary`() {
        val store=store();store.context("c","u",limit=1024)
        store.history("c",List(8){ProjectAgentMessage(if(it%2==0)"user" else "assistant","history-$it "+"text ".repeat(80))},1)
        var compressed=0
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->engine.run("c","u","continue",ProjectAgentPreferences(),ProjectAgentInference {messages,_,_->
            if(messages.first().content.startsWith("Context compressor")){compressed++;compactionFixture("Previous goal remains unfinished; inspect result.")}
            else {assertTrue(messages.any {it.content.contains("UNTRUSTED CONVERSATION SUMMARY")});mapper.writeValueAsString(mapOf("message" to "검증 완료","tool" to null,"requirements" to listOf(mapOf("id" to "R1","status" to "done","evidence_ids" to listOf("T3","T4"),"evidence" to "build.sh 수정 후 빌드 종료 코드 0 확인"))))}
        },{}){}}
        assertEquals(1,compressed);assertTrue(store.channel("c")!!.history.any {it.content.contains("SUMMARY")})
    }
    @Test fun `tool output summarizer failure falls back with exit code and no tool authority`() {
        val result=ProjectAgentToolResult("bounded","error ".repeat(300),42)
        val text=ProjectAgentMemory.summarize(result,ProjectAgentInference {messages,_,_->assertContains(messages.first().content,"UNTRUSTED");error("offline")})
        assertContains(text,"42");assertContains(text,"요약 실패")
    }
}
