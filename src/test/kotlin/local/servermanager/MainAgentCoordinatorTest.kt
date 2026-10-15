package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

class MainAgentCoordinatorTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private fun source():Path=temp.resolve("source").also {
        it.createDirectories();it.resolve("A.kt").writeText("fun a() = 1\n");it.resolve("B.kt").writeText("fun b() = 1\n")
        it.resolve("gradlew").writeText("echo integration-test-ok\nexit 0\n")
    }
    private fun call(id:String,name:String,args:Any)=mapOf("type" to "function_call","id" to "fc-$id","call_id" to id,"name" to name,"arguments" to mapper.writeValueAsString(args))
    private fun response(vararg outputs:Any):JsonNode=mapper.valueToTree(mapOf("status" to "completed","output" to outputs.toList(),"usage" to mapOf("total_tokens" to 10)))
    private fun finalMessage()=mapOf("type" to "message","id" to "msg-final","role" to "assistant","phase" to "final_answer","content" to listOf(mapOf("type" to "output_text","text" to "완료했습니다.")))

    @Test fun `main tool calls run workers concurrently merge test and publish while preserving reasoning items`() {
        val source=source();val entered=CountDownLatch(2);var turns=0
        val main=AgentModel {request ->
            turns++;assertEquals("gpt-test",request.path("model").asText());assertEquals("high",request.path("reasoning").path("effort").asText())
            assertTrue(request.path("include").any {it.asText()=="reasoning.encrypted_content"})
            if(turns>1)assertTrue(request.path("input").any {it.path("encrypted_content").asText()=="opaque-reasoning"})
            when(turns) {
                1->response(mapOf("type" to "reasoning","id" to "reason","encrypted_content" to "opaque-reasoning","summary" to listOf(mapOf("type" to "summary_text","text" to "두 독립 작업으로 나눕니다."))),
                    call("c1","delegate_tasks",mapOf("tasks" to listOf(mapOf("name" to "A","goal" to "A 변경","files" to listOf("A.kt")),mapOf("name" to "B","goal" to "B 변경","files" to listOf("B.kt"))))))
                2->response(call("c2","wait_tasks",mapOf("ids" to listOf("sub-1","sub-2"))))
                3->{assertContains(source.resolve("A.kt").readText(),"= 1");response(call("c3","merge_tasks",mapOf("ids" to listOf("sub-1","sub-2"))))}
                4->response(call("c4","publish_changes",emptyMap<String,String>()))
                else->response(finalMessage())
            }
        }
        val worker:(String,(AgentEvent)->Unit)->AgentModel={id,_->AgentModel {
            entered.countDown();assertTrue(entered.await(5,TimeUnit.SECONDS),"workers did not execute concurrently")
            val file=if(id=="sub-1")"A.kt" else "B.kt"
            mapper.valueToTree(mapOf("candidates" to listOf(mapOf("finishReason" to "STOP","content" to mapOf("parts" to listOf(mapOf("text" to """{"actions":[{"action":"replace_text","path":"$file","before":"= 1","after":"= 2"}],"finish":true,"message":"수정합니다."}""")))))))
        }}
        val events=mutableListOf<AgentEvent>()
        val workerEvents=java.util.Collections.synchronizedList(mutableListOf<Pair<String,AgentEvent>>())
        val result=MainAgentCoordinator(main,worker,MainAgentConfig("gpt-test","high",2),source,temp.resolve("runs"),true,true,emit=events::add,
            workerEmit={t,e->workerEvents.add(t.id to e)}).run("두 파일을 병렬로 변경해")
        assertTrue(result.completed);assertEquals(4,result.calls);assertEquals(40L,result.tokens)
        assertEquals(setOf("A.kt","B.kt"),result.published.toSet())
        assertContains(source.resolve("A.kt").readText(),"= 2");assertContains(source.resolve("B.kt").readText(),"= 2")
        assertTrue(events.any {it.kind=="reasoning"});assertTrue(events.any {it.text.contains("integration-test-ok")})
        for(id in listOf("sub-1","sub-2")) {
            val kinds=workerEvents.filter {it.first==id}.map {it.second.kind}
            assertEquals("task_start",kinds.first());assertTrue(kinds.indexOf("task_end")<kinds.indexOf("task_merge"))
        }
        assertTrue(events.any {it.zone=="integration" && it.kind=="tool_start"})
        assertTrue(events.filter {it.text.contains("integration-test-ok")}.all {it.zone=="integration"})
        assertFalse(source.resolve(".git").exists())
    }
    @Test fun `conflicting worker branches are retained and merge abort preserves integration`() {
        val source=source();val project=IsolatedAgentProject.create(source,temp.resolve("runs"))
        val a=project.branch("a");val b=project.branch("b")
        a.resolve("A.kt").writeText("fun a() = 2\n");b.resolve("A.kt").writeText("fun a() = 3\n")
        assertContains(project.merge("a",a),"병합 완료")
        assertContains(project.merge("b",b),"병합 충돌")
        assertContains(project.integration.resolve("A.kt").readText(),"= 2")
        assertContains(b.resolve("A.kt").readText(),"= 3")
        assertContains(source.resolve("A.kt").readText(),"= 1")
    }
    @Test fun `publishing refuses concurrent original edits without overwriting them`() {
        val source=source();val project=IsolatedAgentProject.create(source,temp.resolve("runs"))
        project.integration.resolve("A.kt").writeText("fun a() = 2\n")
        source.resolve("A.kt").writeText("fun a() = 9\n")
        assertFailsWith<IllegalArgumentException> {project.publish()}
        assertContains(source.resolve("A.kt").readText(),"= 9")
    }
    @Test fun `snapshot excludes private files and new files can be published`() {
        val source=source();source.resolve(".env").writeText("secret")
        source.resolve("VERSION").writeText("1.0.0");source.resolve("LICENSE").writeText("test license")
        source.resolve("security").createDirectory();source.resolve("security/token.txt").writeText("secret")
        val project=IsolatedAgentProject.create(source,temp.resolve("runs"))
        assertFalse(project.integration.resolve(".env").exists());assertFalse(project.integration.resolve("security").exists())
        assertEquals("1.0.0",project.integration.resolve("VERSION").readText())
        assertEquals("test license",project.integration.resolve("LICENSE").readText())
        val scope=AgentWorkspace(project.integration,listOf("New.kt"),true,false,true)
        scope.execute(mapper.readTree("""{"action":"create_file","path":"New.kt","content":"class New"}"""))
        assertEquals(listOf("New.kt"),project.publish())
        assertEquals("class New",source.resolve("New.kt").readText())
    }
    @Test fun `read only coordinator cannot create or publish files`() {
        val source=source();var calls=0
        val model=AgentModel {req ->
            if(calls++==0)response(call("c1","create_file",mapOf("path" to "Bad.kt","content" to "bad")))
            else {assertTrue(req.path("input").any {it.path("output").asText().contains("새 파일 생성 권한")});response(finalMessage())}
        }
        MainAgentCoordinator(model,{_,_->error("no workers expected")},MainAgentConfig(),source,temp.resolve("runs"),false,false).run("조사")
        assertFalse(source.resolve("Bad.kt").exists())
    }
    @Test fun `failed final test returns diagnostics to model and leaves original source untouched`() {
        val source=source();source.resolve("gradlew").writeText("echo integration-test-failed\nexit 1\n")
        var calls=0
        val model=AgentModel {req ->
            if(calls++==0)response(
                call("read","read_file",mapOf("path" to "A.kt")),
                call("edit","replace_text",mapOf("path" to "A.kt","before" to "= 1","after" to "= 2")),
                call("publish","publish_changes",emptyMap<String,String>()))
            else {
                assertTrue(req.path("input").any {it.path("output").asText().contains("integration-test-failed")})
                response(finalMessage())
            }
        }
        val result=MainAgentCoordinator(model,{_,_->error("no workers expected")},MainAgentConfig(),source,temp.resolve("runs"),true,true).run("변경하고 검증")
        assertFalse(result.completed);assertTrue(result.published.isEmpty());assertEquals(2,calls)
        assertContains(source.resolve("A.kt").readText(),"= 1")
    }
}
