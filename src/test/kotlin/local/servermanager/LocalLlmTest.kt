package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.*

class LocalLlmTest {
    private fun stub(status:Int=200,body:String,checkRequest:(String)->Unit={},run:(LocalLlm)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/api/chat") {exchange->
            try {checkRequest(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8));val bytes=body.toByteArray();exchange.sendResponseHeaders(status,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}} finally {exchange.close()}
        }
        server.start()
        try {run(LocalLlm(URI("http://127.0.0.1:${server.address.port}")))} finally {server.stop(0)}
    }
    @Test fun `chat sends bounded plain local requests and returns final content only`() {
        for(size in listOf("14b","30b"))stub(body="""{"done":true,"done_reason":"stop","message":{"content":"답변","thinking":"private reasoning"},"eval_count":7}""",checkRequest={raw->
            val req=jacksonObjectMapper().readTree(raw)
            assertEquals("qwen3:$size",req.path("model").asText());assertFalse(req.path("stream").asBoolean());assertFalse(req.path("think").asBoolean())
            assertEquals(0,req.path("keep_alive").asInt());assertEquals(2048,req.path("options").path("num_predict").asInt());assertFalse(req.has("tools"))
        }) {model->val answer=model.chat(size,"질문");assertEquals("답변",answer.text);assertEquals(7L,answer.tokens);assertFalse(answer.limited)}
    }
    @Test fun `thinking option is explicit and token limit is reported as incomplete`() {
        stub(body="""{"done":true,"done_reason":"length","message":{"content":"partial"}}""",checkRequest={assertTrue(jacksonObjectMapper().readTree(it).path("think").asBoolean())}) {
            assertTrue(it.chat("30b","question",true).limited)
        }
    }
    @Test fun `provider failures are not exposed and empty or unfinished answers fail`() {
        stub(status=500,body="secret echoed input") {assertFalse(assertFails {it.chat("14b","question")}.message.orEmpty().contains("secret"))}
        for(body in listOf("""{"done":false,"message":{"content":"partial"}}""","""{"done":true,"message":{"content":""}}"""))stub(body=body) {assertFails {it.chat("14b","question")}}
    }
    @Test fun `remote URLs and unsupported models are rejected`() {
        for(url in listOf("http://192.168.1.2:11434","https://localhost:11434","http://user:password@localhost:11434","http://localhost:11434/other"))assertFails {LocalLlm(URI(url))}
        assertFails {LocalLlm().chat("70b","question")}
    }
}
