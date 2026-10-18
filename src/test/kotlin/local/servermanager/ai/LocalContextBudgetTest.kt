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

class LocalContextBudgetTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()

    @Test fun `profile and output settings round-trip without breaking older data files`() {
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u","project",temp.toString()))
        val updated=store.context("c","u",window=16384,output=2048,limit=8800)
        assertEquals(16384,updated.executionWindow);assertEquals(11000,LocalContextBudget(updated.executionWindow,updated.outputTokens).input)
        val reloaded=ProjectAgentStore(temp.resolve("settings")).channel("c")!!
        assertEquals(16384,reloaded.executionWindow);assertEquals(2048,reloaded.outputTokens);assertEquals(8800,reloaded.contextLimit)
        assertEquals(3584,mapper.readTree(temp.resolve("settings/project-agent-context.json").readText()).path("limits").path("c").asInt(),"older 8K apps must not inherit a 16K compression threshold")
        val legacy=mapper.readTree(temp.resolve("settings/project-agent.json").readText()).path("channels").path("c")
        assertFalse(legacy.has("executionWindow"));assertFalse(legacy.has("outputTokens"))
        assertEquals(setOf("window","output"),mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(LocalContextBudget(16384,2048)).fieldNames().asSequence().toSet())
        assertFails {store.context("c","u",window=32768)};assertFails {store.context("c","other",window=16384)}
        assertFails {store.context("c","u",window=8192,limit=8000)}
        assertEquals(3600,LocalContextBudget.DEFAULT.input)
    }

    @Test fun `reset clears plans and stale work cannot resurrect them while home and profile survive`() {
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u","project",temp.toString()))
        store.savePlan("c",ProjectAgentTaskPlan("original request",listOf("read","edit","verify")),0)
        val channel=store.context("c","u",reset=true,window=16384)
        assertNull(store.taskPlan("c"));assertEquals("project",channel.project);assertEquals(temp.toString(),channel.home);assertEquals(16384,channel.executionWindow)
        assertFails {store.savePlan("c",ProjectAgentTaskPlan("stale"),0)}
        assertNull(ProjectAgentStore(temp.resolve("settings")).taskPlan("c"))
    }

    @Test fun `older app context reset invalidates saved plans and uses its new threshold on upgrade`() {
        val directory=temp.resolve("settings");val store=ProjectAgentStore(directory)
        store.addChannel(ProjectAgentChannel("g","c","u","project",temp.toString()))
        val channel=store.context("c","u",window=16384)
        store.savePlan("c",ProjectAgentTaskPlan("old request",listOf("read","edit")),channel.contextEpoch)
        val metadata=mapper.readTree(directory.resolve("project-agent-context.json").readText())
        (metadata.path("epochs") as com.fasterxml.jackson.databind.node.ObjectNode).put("c",channel.contextEpoch+1)
        (metadata.path("limits") as com.fasterxml.jackson.databind.node.ObjectNode).put("c",2048)
        directory.resolve("project-agent-context.json").writeText(mapper.writeValueAsString(metadata))
        val upgraded=ProjectAgentStore(directory)
        assertNull(upgraded.taskPlan("c"));assertEquals(2048,upgraded.channel("c")!!.contextLimit)
        assertEquals(16384,upgraded.channel("c")!!.executionWindow)
    }

    @Test fun `large recent tool text is bounded while requester and original goal stay intact`() {
        val system=ProjectAgentMessage("system","project policy")
        val pinned=ProjectAgentMessage("user","ORIGINAL_GOAL_5127 and the acceptance criteria")
        val history=listOf(ProjectAgentMessage("user","prior"),ProjectAgentMessage("user","UNTRUSTED TOOL RESULT\n"+"large\n".repeat(18000)))
        val messages=ProjectAgentContext.forModel(system,history,pinned,3600)
        assertTrue(messages.sumOf {ProjectAgentContext.estimatedTokens(it.content)}<=3600)
        assertTrue(messages.contains(pinned));assertTrue(messages.last().content.contains("PARTIAL CONTEXT"))
        assertEquals(history.last().content.length,108022,"the original stored message is not mutated")
    }

    @Test fun `actual provider request uses selected window and output budget rather than 8192 constants`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        var request:com.fasterxml.jackson.databind.JsonNode?=null
        server.createContext("/api/ps") {e->val bytes="{\"models\":[]}".toByteArray();e.sendResponseHeaders(200,bytes.size.toLong());e.responseBody.use {it.write(bytes)}}
        server.createContext("/api/show") {e->val bytes="{\"model_info\":{\"qwen3.context_length\":262144}}".toByteArray();e.sendResponseHeaders(200,bytes.size.toLong());e.responseBody.use {it.write(bytes)}}
        server.createContext("/api/chat") {e->request=mapper.readTree(e.requestBody);val bytes="{\"done\":true,\"message\":{\"content\":\"ok\"},\"prompt_eval_count\":4000,\"eval_count\":1}\n".toByteArray();e.sendResponseHeaders(200,bytes.size.toLong());e.responseBody.use {it.write(bytes)}}
        server.start()
        try {
            val answer=LocalLlm(URI("http://127.0.0.1:${server.address.port}")).conversationWithBudget("14b",listOf(ProjectAgentMessage("system","You are 루오노타르"),ProjectAgentMessage("user","text ".repeat(2400))),false,LocalContextBudget(16384,2048))
            assertEquals("ok",answer.text);assertEquals(16384,request!!.path("options").path("num_ctx").asInt());assertEquals(2048,request!!.path("options").path("num_predict").asInt())
            assertTrue(request!!.path("format").isObject);assertEquals(4000L,answer.inputTokens)
        }finally{server.stop(0)}
    }
}
