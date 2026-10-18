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

    @Test fun `native selection grants no execution and selected build still requires review and human approval`() {
        val home=temp.resolve("project").createDirectories().toRealPath()
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u",null,home.toString()))
        val prefs=store.settings("g","u","14b",true,true)
        var actor=0;var nativeReply:String?=null;var reviews=0;var approvals=0;val events=mutableListOf<ProjectAgentEvent>()
        val inference=object:ProjectAgentInference {
            override fun nativeTools()=true
            override fun nativeMessage()=nativeReply
            override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
                if(messages.first().content.startsWith("Independent")){reviews++;return """{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"fixture build"}"""}
                if(messages.first().content.startsWith("Context compressor"))return "fixture build unfinished"
                val fn=when(actor++) {
                    0->{assertFalse(ProjectAgentNativeTools.names(messages.first().content).contains("build"));call("build","""{"command":"/bin/echo checked"}""")}
                    1->{assertTrue(messages.any {it.content.contains("등록되지 않은 함수")});call("select_tools","""{"names":["build"],"plan":["build","inspect exit","report"]}""")}
                    2->{assertTrue(ProjectAgentNativeTools.names(messages.first().content).contains("build"));call("build","""{"command":"/bin/echo checked"}""")}
                    else->{assertTrue(ProjectAgentNativeTools.wire(messages).any {it.path("role").asText()=="tool" && it.path("tool_name").asText()=="build"});call("report_task","""{"message":"빌드 종료 코드 0 확인","status":"completed","requirements":[{"id":"R1","status":"done","evidence_ids":["T3"],"evidence":"build returned exit=0 and checked"}]}""")}
                }
                nativeReply=ProjectAgentNativeTools.assistant("",listOf(fn));return ProjectAgentNativeTools.reply("",listOf(fn))
            }
        }
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            engine.run("c","u","u","빌드",prefs,inference,ProjectAgentLimits(8,AgentAccess.OWNER),{}) {event->
                events.add(event)
                if(event.kind=="approval"){assertEquals("build",event.plan!!.tool.name);approvals++;engine.approve("c","u",event.token!!,true){}}
            }
        }
        assertEquals(1,approvals);assertEquals(1,reviews);assertEquals(1,events.count {it.kind=="tool_start"})
        assertContains(events.last {it.kind=="done"}.text,"✅ R1")
        assertEquals(listOf("build","inspect exit","report"),store.taskPlan("c")!!.steps)
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
