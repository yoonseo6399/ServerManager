package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import org.junit.jupiter.api.Test
import kotlin.test.*

class StrataLlmTest {
    private val mapper=jacksonObjectMapper()
    private fun serve(data:String,check:(String)->Unit={},run:(StrataLlm)->Unit) {
        val s=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        s.createContext("/v1/chat/completions") {ex->try {check(ex.requestBody.readAllBytes().toString(Charsets.UTF_8));val b=data.toByteArray();ex.sendResponseHeaders(200,b.size.toLong());ex.responseBody.use {it.write(b)}}finally{ex.close()}}
        s.start();try {run(StrataLlm(URI("http://127.0.0.1:${s.address.port}"),{8192},{LlmMemory(totalBytes=24000,usedBytes=17000)}))}finally{s.stop(0)}
    }
    private val system="You are a file assistant.\n${ProjectAgentNativeTools.MARKER}\nRequester access level: EDIT"
    @Test fun `SSE function fragments become native permission proposals with real throughput`() {
        val chunks=listOf(
            mapOf("choices" to listOf(mapOf("delta" to mapOf("reasoning_content" to "working","tool_calls" to listOf(mapOf("index" to 0,"id" to "call_1","function" to mapOf("name" to "read_file","arguments" to "{\"path\":"))))))),
            mapOf("choices" to listOf(mapOf("delta" to mapOf("tool_calls" to listOf(mapOf("index" to 0,"function" to mapOf("arguments" to "\"note.txt\"}"))))))),
            mapOf("choices" to listOf(mapOf("delta" to emptyMap<String,Any>(),"finish_reason" to "tool_calls")),"usage" to mapOf("prompt_tokens" to 100,"completion_tokens" to 20),"timings" to mapOf("predicted_per_second" to 7.7)))
        val events=chunks.joinToString(""){"data: "+mapper.writeValueAsString(it)+"\n\n"}+"data: [DONE]\n\n"
        serve(events,{raw->val r=mapper.readTree(raw);assertTrue(r.path("stream").asBoolean());assertEquals(StrataRuntime.MODEL,r.path("model").asText());assertTrue(r.path("tools").isArray);assertFalse(r.has("format"));assertFalse(r.path("chat_template_kwargs").path("enable_thinking").asBoolean())}) {llm->
            val progress=mutableListOf<QwenProgress>();val answer=llm.conversation(listOf(ProjectAgentMessage("system",system),ProjectAgentMessage("user","Read note.txt")),false,LocalContextBudget(),progress::add)
            assertEquals("read_file",mapper.readTree(answer.text).path("tool").path("name").asText());assertEquals(7.7,answer.tokensPerSecond);assertEquals(100,answer.inputTokens);assertEquals(20,answer.tokens);assertNotNull(answer.memory);assertTrue(progress.any {it.thinking=="working"})
            val wire=llm.wire(listOf(ProjectAgentMessage("assistant","",answer.nativeMessage),ProjectAgentMessage("assistant","",ProjectAgentNativeTools.result("read_file","original"))))
            assertEquals("call_1",wire[1].path("tool_call_id").asText());assertEquals("original",wire[1].path("content").asText());assertEquals("working",wire[0].path("reasoning_content").asText());assertTrue(wire[0].path("tool_calls")[0].path("function").path("arguments").isTextual)
        }
    }
    @Test fun `unfinished streams and oversized Flash context fail instead of fabricated completion`() {
        serve("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n") {llm->assertFails {llm.conversation(listOf(ProjectAgentMessage("user","test")),false,LocalContextBudget(),plain=true)}}
        var started=false
        assertFails {StrataLlm(context={started=true;8192}).conversation(listOf(ProjectAgentMessage("user","test")),false,LocalContextBudget(16384))};assertFalse(started)
        assertFails {StrataLlm(URI("http://192.168.1.2:18080"))}
    }
    @Test fun `timings exclude prompt time and unavailable telemetry stays unknown`() {
        assertEquals(12.5,LlmMemory.rate(25,2_000_000_000));assertNull(LlmMemory.rate(25,0));assertNull(LlmMemory.rate(25,null))
        assertEquals(1572864,LlmMemory.parseSwap("total = 4.00M used = 1.50M free = 2.50M"))
        val text=QwenMetricsText.footer(QwenUsage(tokensPerSecond=12.5,memory=LlmMemory(totalBytes=24L*1073741824,usedBytes=18L*1073741824,pressure="정상")))
        assertContains(text,"12.5 tok/s");assertContains(text,"18.0/24.0 GiB");assertFalse(QwenMetricsText.footer(QwenUsage()).contains("0.0 tok/s"))
    }
    @Test fun `Pi native schemas bypass legacy parser and preserve multiple calls`() {
        val data="data: " + mapper.writeValueAsString(mapOf("choices" to listOf(mapOf("delta" to mapOf("tool_calls" to listOf(
            mapOf("index" to 0,"id" to "pi_read","function" to mapOf("name" to "read","arguments" to "{\"path\":\"note.txt\"}")),
            mapOf("index" to 1,"id" to "pi_search","function" to mapOf("name" to "search","arguments" to "{\"pattern\":\"value\"}")))),"finish_reason" to "tool_calls")))) + "\n\ndata: [DONE]\n\n"
        val q=mapper.writeValueAsString(mapOf("messages" to listOf(mapOf("role" to "developer","content" to "Pi native instructions"),mapOf("role" to "user","content" to "read")),"tools" to listOf(mapOf("type" to "function","function" to mapOf("name" to "read","parameters" to mapOf("type" to "object","properties" to emptyMap<String,Any>()))))))
        serve(data,{raw->val body=mapper.readTree(raw);assertEquals("system",body.path("messages")[0].path("role").asText());assertEquals("read",body.path("tools")[0].path("function").path("name").asText());assertFalse(body.has("response_format"))}) {llm->
            val result=llm.conversation(listOf(ProjectAgentMessage("system",PiWire.PREFIX+q)),false,LocalContextBudget())
            val raw=mapper.readTree(result.nativeMessage!!);assertEquals(2,raw.path("tool_calls").size());assertEquals("read",raw.path("tool_calls")[0].path("function").path("name").asText());assertEquals("search",raw.path("tool_calls")[1].path("function").path("name").asText())
        }
    }

}
