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

    private class FakeOllama(var loaded:MutableList<String>,val installed:List<String>,val stuck:Boolean=false) {
        val calls=java.util.concurrent.CopyOnWriteArrayList<String>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        private fun reply(ex:com.sun.net.httpserver.HttpExchange,body:String) {val bytes=body.toByteArray();ex.sendResponseHeaders(200,bytes.size.toLong());ex.responseBody.use {it.write(bytes)}}
        init {
            server.createContext("/api/version") {reply(it,"""{"version":"0.12.0"}""")}
            server.createContext("/api/tags") {reply(it,"""{"models":[${installed.joinToString(",") {n->"""{"name":"$n","size":9000000000}"""}}]}""")}
            server.createContext("/api/ps") {reply(it,"""{"models":[${loaded.joinToString(",") {n->"""{"name":"$n"}"""}}]}""")}
            server.createContext("/api/generate") {ex->
                val req=jacksonObjectMapper().readTree(ex.requestBody.readAllBytes());calls+="unload:"+req.path("model").asText()
                assertEquals(0,req.path("keep_alive").asInt())
                if(!stuck)loaded.remove(req.path("model").asText())
                reply(ex,"""{"done":true}""")
            }
            server.createContext("/api/chat") {ex->
                val req=jacksonObjectMapper().readTree(ex.requestBody.readAllBytes());calls+="chat:"+req.path("model").asText()+" loaded="+loaded.joinToString("+")
                reply(ex,"""{"done":true,"message":{"content":"ok"},"eval_count":1}""")
            }
            server.start()
        }
        fun llm()=LocalLlm(URI("http://127.0.0.1:${server.address.port}"))
        fun stop()=server.stop(0)
    }

    @Test fun `probe reports installed tags and loaded models and tolerates a missing Ollama`() {
        val ollama=FakeOllama(mutableListOf("qwen3:30b"),listOf("qwen3:14b","llama3:8b"))
        try {
            val status=ollama.llm().probe()
            assertTrue(status.reachable);assertEquals("0.12.0",status.version)
            assertEquals(mapOf("14b" to true,"30b" to false),status.installed)
            assertEquals(listOf("qwen3:30b"),status.loaded);assertContains(status.message,"30B")
        } finally {ollama.stop()}
        val down=LocalLlm(URI("http://127.0.0.1:1")).probe()
        assertFalse(down.reachable);assertEquals(mapOf("14b" to false,"30b" to false),down.installed)
    }

    @Test fun `switching models unloads the other Qwen model first but leaves unrelated models alone`() {
        val ollama=FakeOllama(mutableListOf("qwen3:30b","llama3:8b"),listOf("qwen3:14b","qwen3:30b"))
        try {
            assertEquals("ok",ollama.llm().chat("14b","질문").text)
            assertEquals(listOf("unload:qwen3:30b","chat:qwen3:14b loaded=llama3:8b"),ollama.calls.toList())
        } finally {ollama.stop()}
    }

    @Test fun `unloading fails closed when the old model stays resident`() {
        val ollama=FakeOllama(mutableListOf("qwen3:30b"),listOf("qwen3:14b","qwen3:30b"),stuck=true)
        try {
            assertFails {ollama.llm().chat("14b","질문")}
            assertTrue(ollama.calls.none {it.startsWith("chat:")})
        } finally {ollama.stop()}
    }

    @Test fun `unloadQwen frees every Qwen model and reports what it freed`() {
        val ollama=FakeOllama(mutableListOf("qwen3:14b","other:1"),listOf("qwen3:14b"))
        try {
            assertEquals(listOf("qwen3:14b"),ollama.llm().unloadQwen())
            assertEquals(listOf("other:1"),ollama.loaded)
        } finally {ollama.stop()}
    }
}
