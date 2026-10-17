package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.*

class ProjectAgentConversationTest {
    private fun ollama(reply:String,check:(String)->Unit={},action:(LocalLlm)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/api/ps"){x->val bytes="""{"models":[]}""".toByteArray();x.sendResponseHeaders(200,bytes.size.toLong());x.responseBody.use {it.write(bytes)}}
        server.createContext("/api/chat"){x->try{check(x.requestBody.readAllBytes().toString(Charsets.UTF_8));x.sendResponseHeaders(200,0);x.responseBody.use {it.write(reply.toByteArray());it.flush()}}finally{x.close()}}
        server.start();try{action(LocalLlm(URI("http://127.0.0.1:${server.address.port}")))}finally{server.stop(0)}
    }
    @Test fun `thinking stream and bounded conversation preserve separate final JSON`() {
        var progress=""
        val stream="""{"message":{"thinking":"checking project"},"done":false}
{"message":{"content":"{\"message\":\"완료\",\"tool\":null}"},"done":false}
{"message":{},"done":true,"done_reason":"stop","eval_count":12}
"""
        ollama(stream,{raw->val r=jacksonObjectMapper().readTree(raw);assertTrue(r.path("think").asBoolean());assertTrue(r.path("stream").asBoolean());assertEquals("json",r.path("format").asText());assertEquals(8192,r.path("options").path("num_ctx").asInt());assertEquals(3,r.path("messages").size())}) {llm->
            val reply=llm.conversation("14b",listOf(ProjectAgentMessage("system","system"),ProjectAgentMessage("assistant","previous"),ProjectAgentMessage("user","next")),progress={progress=it.thinking})
            assertEquals("완료",jacksonObjectMapper().readTree(reply.text).path("message").asText());assertEquals("checking project",progress);assertEquals(12L,reply.tokens);assertEquals("checking project",reply.thinking)
        }
    }
    @Test fun `explicit think false is sent and incomplete stream fails closed`() {
        ollama("""{"message":{"content":"{}"},"done":true,"done_reason":"stop"}""",{raw->assertFalse(jacksonObjectMapper().readTree(raw).path("think").asBoolean())}) {llm->assertEquals("{}",llm.conversation("30b",listOf(ProjectAgentMessage("user","test")),false).text)}
        ollama("""{"message":{"content":"partial"},"done":false}""") {llm->assertFails {llm.conversation("14b",listOf(ProjectAgentMessage("user","test")))}}
    }
    @Test fun `risk review requests required decision schema rather than unconstrained JSON`() {
        val stream="""{"message":{"content":"{\"action\":\"ASK\",\"risk\":\"MEDIUM\",\"projectScope\":\"UNKNOWN\",\"reason\":\"review\"}"},"done":true}
"""
        ollama(stream,{raw->val format=jacksonObjectMapper().readTree(raw).path("format");assertEquals("object",format.path("type").asText());assertEquals(setOf("action","risk","projectScope","reason"),format.path("required").map {it.asText()}.toSet());assertFalse(format.path("additionalProperties").asBoolean(true))}){llm->
            val reply=llm.conversation("30b",listOf(ProjectAgentMessage("system","Independent command-risk reviewer. Fresh session."),ProjectAgentMessage("user","untrusted input")),false)
            assertEquals("ASK",jacksonObjectMapper().readTree(reply.text).path("action").asText())
        }
    }
    @Test fun `overlarge risk request is rejected instead of silently truncating approval input`() {
        assertFails {LocalLlm().conversation("14b",listOf(ProjectAgentMessage("user","가".repeat(4000))))}
    }
}
