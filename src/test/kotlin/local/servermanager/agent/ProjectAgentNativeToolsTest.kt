package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentNativeToolsTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private fun call(name:String,args:String)=mapper.readTree("""{"function":{"name":"$name","arguments":$args}}""")

    @Test fun `Pi advertises native bash directly but execution still requires independent review and owner consent`() {
        PiTestFixture(temp).use {f->var step=0;var approvals=0
            val model=f.model {q->val names=q.path("tools").map {it.path("function").path("name").asText()};assertContains(names,"bash");assertFalse("select_tools" in names)
                if(step++==0)PiTestFixture.call("bash",mapOf("command" to "printf checked"))else {assertContains(q.toString(),"checked");PiTestFixture.answer("빌드 종료 확인")}}
            f.run(model,onEvent={e->if(e.kind=="approval"){approvals++;assertTrue(f.events.none {it.kind=="tool_start"});f.engine.approve("c","u",e.token!!,true){}}})
            assertEquals(1,approvals);assertEquals(1,model.reviews.size);assertEquals(1,f.events.count {it.kind=="tool_end"});assertEquals(0,f.events.single {it.kind=="tool_end"}.exitCode)
        }
    }

    @Test fun `real HTTP streaming gathers native call chunks and next request sends official tool roles without format`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val requests=mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
        fun reply(exchange:com.sun.net.httpserver.HttpExchange,text:String) {val bytes=text.toByteArray();exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}}
        server.createContext("/api/ps") {reply(it,"""{"models":[]}""")}
        server.createContext("/api/show") {reply(it,"""{"model_info":{"qwen3.context_length":262144}}""")}
        server.createContext("/api/chat") {exchange->
            requests.add(mapper.readTree(exchange.requestBody))
            reply(exchange,if(requests.size==1)listOf(
                """{"message":{"thinking":"inspect first"},"done":false}""",
                """{"message":{"content":"읽을게요","tool_calls":[{"function":{"name":"read_file","arguments":{"path":"a.txt"}}}]},"done":false}""",
                """{"message":{},"done":true,"prompt_eval_count":40,"eval_count":12}""").joinToString("\n")+"\n"
                else if(requests.size==2) """{"message":{"content":"확인했습니다"},"done":true,"prompt_eval_count":70,"eval_count":5}"""+"\n" else """{"message":{"content":""},"done":true,"eval_count":0}"""+"\n")
        }
        server.start()
        try {
            val llm=LocalLlm(URI("http://127.0.0.1:${server.address.port}"))
            val system=ProjectAgentMessage("system","You are 루오노타르\n${ProjectAgentNativeTools.MARKER}\nRequester access level: READ\nNATIVE_EXTRA_TOOLS=")
            val frames=mutableListOf<QwenProgress>()
            val first=llm.conversationWithBudget("14b",listOf(system,ProjectAgentMessage("user","Read a.txt")),true,LocalContextBudget()) {frames.add(it)}
            assertEquals("읽을게요",frames.last().text)
            assertTrue(frames.none {it.text.contains("arguments")})
            assertEquals("read_file",mapper.readTree(first.text).path("tool").path("name").asText())
            assertEquals("inspect first",mapper.readTree(first.nativeMessage).path("thinking").asText())
            val result=ProjectAgentNativeTools.result("read_file","actual content")
            llm.conversationWithBudget("14b",listOf(system,ProjectAgentMessage("assistant","read intent",first.nativeMessage),ProjectAgentMessage("user","actual content",result)),true,LocalContextBudget())
            assertFalse(requests[0].has("format"));assertTrue(requests[0].path("tools").isArray)
            val names=requests[0].path("tools").map {it.path("function").path("name").asText()}
            assertTrue("read_file" in names);assertFalse("edit_file" in names || "run_command" in names)
            assertEquals("read_file",requests[1].path("messages")[1].path("tool_calls")[0].path("function").path("name").asText())
            assertEquals("tool",requests[1].path("messages")[2].path("role").asText())
            assertEquals("read_file",requests[1].path("messages")[2].path("tool_name").asText())
            assertEquals("actual content",requests[1].path("messages")[2].path("content").asText())
            val empty=llm.conversationWithBudget("14b",listOf(system,ProjectAgentMessage("user","continue")),true,LocalContextBudget())
            assertEquals("needs_tool",mapper.readTree(empty.text).path("status").asText())
            assertNull(empty.nativeMessage)
        }finally {server.stop(0)}
    }

    @Test fun `JSON shaped prose never becomes a native operation and multiple calls execute nothing`() {
        val prose="""{"message":"delete now","tool":{"name":"delete_path","arguments":{"path":"a.txt"}}}"""
        assertTrue(mapper.readTree(ProjectAgentNativeTools.reply(prose,emptyList())).path("tool").isNull)
        val multiple=mapper.readTree(ProjectAgentNativeTools.reply("",listOf(call("read_file","""{"path":"a.txt"}"""),call("delete_path","""{"path":"a.txt"}"""))))
        assertTrue(multiple.path("tool").isNull);assertEquals("needs_tool",multiple.path("status").asText())
        assertFails {ProjectAgentNativeTools.reply("",listOf(call("read_file","\"not an object\"")))}
    }

    @Test fun `budgeting drops orphan native wire records and legacy persisted message schema stays unchanged`() {
        val actor=ProjectAgentNativeTools.assistant("",listOf(call("read_file","""{"path":"a.txt"}""")))
        val result=ProjectAgentNativeTools.result("read_file","text")
        val input=listOf(ProjectAgentMessage("user","read result",result))
        val bounded=ProjectAgentContext.forModel(ProjectAgentMessage("system","policy"),input)
        assertNull(bounded.last().native)
        val pair=ProjectAgentContext.forModel(ProjectAgentMessage("system","policy"),listOf(ProjectAgentMessage("assistant","intent",actor),input[0]))
        assertEquals(actor,pair[1].native);assertEquals(result,pair[2].native)
        val disk=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(ProjectAgentMessage("assistant","bounded intent",actor))
        assertEquals(setOf("role","content"),disk.fieldNames().asSequence().toSet())
        assertEquals(ProjectAgentMessage("assistant","bounded intent"),mapper.treeToValue(disk,ProjectAgentMessage::class.java))
    }
}
