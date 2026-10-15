package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Plain chat only: no project files, tool execution, or provider fallback. */
class LocalLlm(private val endpoint:URI=URI(System.getenv("OLLAMA_URL") ?: "http://127.0.0.1:11434")) {
    private val mapper=jacksonObjectMapper()
    init {
        require(endpoint.scheme=="http" && endpoint.host in setOf("localhost","127.0.0.1","[::1]","::1") && endpoint.rawUserInfo==null && endpoint.rawQuery==null && endpoint.rawFragment==null && endpoint.path in setOf("","/")) {"OLLAMA_URL은 로컬 Ollama의 HTTP 주소여야 합니다."}
    }
    data class Answer(val text:String,val limited:Boolean,val seconds:Double,val tokens:Long)
    fun chat(size:String,prompt:String,think:Boolean=false):Answer {
        require(size in setOf("14b","30b") && prompt.isNotBlank() && prompt.length<=6000)
        val model=System.getenv(if(size=="14b")"OLLAMA_MODEL_14B" else "OLLAMA_MODEL_30B") ?: "qwen3:$size"
        val body=mapper.writeValueAsString(mapOf("model" to model,"messages" to listOf(mapOf("role" to "user","content" to prompt)),
            "think" to think,"stream" to false,"keep_alive" to 0,"options" to mapOf("num_ctx" to 8192,"num_predict" to 2048)))
        val request=HttpRequest.newBuilder(endpoint.resolve("/api/chat")).timeout(Duration.ofMinutes(3))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val bytes=ByteArrayOutputStream()
        val start=System.nanoTime()
        val pending=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().sendAsync(request,HttpResponse.BodyHandlers.ofByteArrayConsumer {chunk->
            if(chunk.isPresent) { val value=chunk.get();check(bytes.size()+value.size<=262144) {"로컬 모델 응답이 크기 제한을 초과했습니다."};bytes.write(value) }
        })
        val response=try {pending.get(185,TimeUnit.SECONDS)} catch(e:Exception) {
            pending.cancel(true);throw IllegalStateException("Ollama 요청 실패 또는 3분 제한 초과입니다. 로컬 모델·메모리·Ollama 상태를 확인하세요.",e)
        }
        check(response.statusCode()==200) {"Ollama HTTP ${response.statusCode()}: 실행 상태와 설치된 모델 태그를 확인하세요."}
        val result=mapper.readTree(bytes.toByteArray())
        check(result.path("done").asBoolean() && !result.has("error")) {"Ollama가 완료된 응답을 반환하지 않았습니다."}
        val text=result.path("message").path("content").asText()
        check(text.isNotBlank()) {"최종 답변이 없습니다. 출력 한도 또는 모델 상태를 확인하세요."}
        return Answer(text,result.path("done_reason").asText()=="length",(System.nanoTime()-start)/1e9,result.path("eval_count").asLong())
    }
}
