package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Set by [RunnerGateway]: sends Qwen chat to a connected runner that opted in with RUNNER_LLM_ENABLED. Null result means no such runner. */
object RemoteLlm {
    data class Result(val answer:LocalLlm.Answer,val runnerName:String)
    @Volatile var provider:((String,String,Boolean,String?)->Result?)?=null
    /** [runnerId] pins one runner; the provider throws when it is offline or has not opted in. */
    fun chat(size:String,prompt:String,think:Boolean,runnerId:String?=null):Result?=provider?.invoke(size,prompt,think,runnerId)
}

/** Plain chat only: no project files, tool execution, or provider fallback. */
class LocalLlm(private val endpoint:URI=URI(System.getenv("OLLAMA_URL") ?: "http://127.0.0.1:11434")) {
    private val mapper=jacksonObjectMapper()
    init {
        require(endpoint.scheme=="http" && endpoint.host in setOf("localhost","127.0.0.1","[::1]","::1") && endpoint.rawUserInfo==null && endpoint.rawQuery==null && endpoint.rawFragment==null && endpoint.path in setOf("","/")) {"OLLAMA_URL은 로컬 Ollama의 HTTP 주소여야 합니다."}
    }
    data class OllamaModel(val name:String,val sizeBytes:Long)
    data class Status(val reachable:Boolean,val version:String?,val models:List<OllamaModel>,val loaded:List<String>,val tags:Map<String,String>,val installed:Map<String,Boolean>,val message:String)
    data class Answer(val text:String,val limited:Boolean,val seconds:Double,val tokens:Long)
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private fun get(path:String):com.fasterxml.jackson.databind.JsonNode {
        val request=HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(8)).GET().build()
        val response=client.send(request,HttpResponse.BodyHandlers.ofInputStream())
        response.body().use {body->
            check(response.statusCode()==200) {"Ollama HTTP ${response.statusCode()}"}
            val bytes=body.readNBytes(1_048_577)
            check(bytes.size<=1_048_576) {"Ollama 응답이 크기 제한을 초과했습니다."}
            return mapper.readTree(bytes)
        }
    }
    /** Names of models currently held in memory, or empty when Ollama cannot be asked. */
    fun loaded():List<String> = runCatching {get("/api/ps").path("models").map {it.path("name").asText()}.filter {it.isNotBlank()}}.getOrDefault(emptyList())
    fun unload(model:String) {
        val body=mapper.writeValueAsString(mapOf("model" to model,"keep_alive" to 0,"stream" to false))
        val request=HttpRequest.newBuilder(endpoint.resolve("/api/generate")).timeout(Duration.ofSeconds(30))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        check(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200) {"Ollama가 모델 언로드를 거부했습니다."}
    }
    /** Unloads Qwen models other than [keep] (all of them when null) and waits until Ollama reports them gone. */
    fun unloadQwen(keep:String?=null):List<String> {
        val stale=loaded().filter {it in qwenTags() && it!=keep}
        stale.forEach {unload(it)}
        if(stale.isNotEmpty()) {
            repeat(20) {if(loaded().none {name->name in stale}) return stale;Thread.sleep(500)}
            throw IllegalStateException("이전 Qwen 모델이 아직 메모리에 있어 동시에 두 모델을 켜지 않도록 중단했습니다. 잠시 후 다시 시도하세요.")
        }
        return stale
    }
    fun probe():Status {
        val tags=SIZES.associateWith {tag(it)}
        return try {
            val version=get("/api/version").path("version").asText().takeIf {it.isNotBlank()}
            val models=get("/api/tags").path("models").map {OllamaModel(it.path("name").asText(),it.path("size").asLong())}.filter {it.name.isNotBlank()}
            val installed=tags.mapValues {(_,tag)->models.any {it.name==tag}}
            val missing=installed.filterValues {!it}.keys.map {"Qwen3 ${it.uppercase()}(${tags.getValue(it)})"}
            Status(true,version,models,loaded(),tags,installed,if(missing.isEmpty())"Ollama가 실행 중이고 14B·30B 모델이 모두 설치되어 있습니다." else "Ollama는 실행 중이지만 ${missing.joinToString(", ")} 모델이 없습니다. ollama pull로 받으세요.")
        } catch(e:Exception) {Status(false,null,emptyList(),emptyList(),tags,tags.mapValues {false},"Ollama에 연결하지 못했습니다. 실행 여부와 OLLAMA_URL을 확인하세요.")}
    }
    fun chat(size:String,prompt:String,think:Boolean=false):Answer {
        check(inference.tryAcquire()) {"다른 로컬 모델 요청이 진행 중입니다."}
        try {return chatLocked(size,prompt,think)} finally {inference.release()}
    }
    private fun chatLocked(size:String,prompt:String,think:Boolean):Answer {
        require(size in SIZES && prompt.isNotBlank() && prompt.length<=6000)
        val model=tag(size)
        unloadQwen(model)
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

    companion object {
        private val inference=java.util.concurrent.Semaphore(1)
        val SIZES=listOf("14b","30b")
        fun tag(size:String):String = System.getenv(if(size=="14b")"OLLAMA_MODEL_14B" else "OLLAMA_MODEL_30B") ?: "qwen3:$size"
        private fun qwenTags()=SIZES.map(::tag)
    }
}

/** One inference request across chat, the file harness and private Google summaries. */
object QwenRequests {
    data class Reply(val answer:LocalLlm.Answer,val runnerName:String?)
    private val slot=java.util.concurrent.Semaphore(1)
    fun chat(size:String,prompt:String,think:Boolean=false,runnerId:String?=null):Reply {
        check(slot.tryAcquire()) {"다른 Qwen 요청이 진행 중입니다."}
        try {
            val remote=RemoteLlm.chat(size,prompt,think,runnerId)
            check(remote!=null || runnerId==null) {"선택한 실행기를 사용할 수 없습니다."}
            return Reply(remote?.answer ?: LocalLlm().chat(size,prompt,think),remote?.runnerName)
        } finally {slot.release()}
    }
}
