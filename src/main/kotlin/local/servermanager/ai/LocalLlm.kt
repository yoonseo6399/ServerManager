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
    @Volatile var modelLimitProvider:((String,String?)->Int?)?=null
    @Volatile var conversationProvider:((String,List<ProjectAgentMessage>,Boolean,String?,(QwenProgress)->Unit)->Result?)?=null
    @Volatile var budgetConversationProvider:((String,List<ProjectAgentMessage>,Boolean,String?,LocalContextBudget,(QwenProgress)->Unit)->Result?)?=null
    /** [runnerId] pins one runner; the provider throws when it is offline or has not opted in. */
    fun chat(size:String,prompt:String,think:Boolean,runnerId:String?=null):Result?=provider?.invoke(size,prompt,LocalModels.thinking(size,think),runnerId)
}

/** Plain chat only: no project files, tool execution, or provider fallback. */
class LocalLlm(private val endpoint:URI=URI(System.getenv("OLLAMA_URL") ?: "http://127.0.0.1:11434"),private val trace:((String,String)->Unit)?=null) {
    private val mapper=jacksonObjectMapper()
    init {
        require(endpoint.scheme=="http" && endpoint.host in setOf("localhost","127.0.0.1","[::1]","::1") && endpoint.rawUserInfo==null && endpoint.rawQuery==null && endpoint.rawFragment==null && endpoint.path in setOf("","/")) {"OLLAMA_URL은 로컬 Ollama의 HTTP 주소여야 합니다."}
    }
    data class OllamaModel(val name:String,val sizeBytes:Long)
    data class Status(val reachable:Boolean,val version:String?,val models:List<OllamaModel>,val loaded:List<String>,val tags:Map<String,String>,val installed:Map<String,Boolean>,val message:String,val modelLimits:Map<String,Int> = emptyMap(),val memory:LlmMemory?=null,val strata:StrataRuntime.Status?=null,val turboQuant:TurboQuantRuntime.Status?=null)
    data class Answer(val text:String,val limited:Boolean,val seconds:Double,val tokens:Long,val thinking:String="",val inputTokens:Long?=null,val modelContext:Int?=null,@get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) val nativeMessage:String?=null,val tokensPerSecond:Double?=null,val memory:LlmMemory?=null)
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
    /** Only recognized numeric memory diagnostics may leave Ollama; never echo arbitrary response/input text. */
    private fun error(status:Int,bytes:ByteArray):String {
        val reason=runCatching {mapper.readTree(bytes).path("error").asText()}.getOrDefault("")
        val memory=Regex("model requires ([0-9.]+) (GiB|GB) but only ([0-9.]+) (GiB|GB) are available(?: \\(after [0-9.]+ (?:MiB|GiB) overhead\\))?").matchEntire(reason)
        return "Ollama HTTP $status"+(memory?.let {" · 모델 메모리 부족: 필요 ${it.groupValues[1]} ${it.groupValues[2]}, 사용 가능 ${it.groupValues[3]} ${it.groupValues[4]}"} ?: "")
    }
    /** Names of models currently held in memory, or empty when Ollama cannot be asked. */
    fun loaded():List<String> = runCatching {get("/api/ps").path("models").map {it.path("name").asText()}.filter {it.isNotBlank()}}.getOrDefault(emptyList())
    fun unload(model:String) {
        val body=mapper.writeValueAsString(mapOf("model" to model,"keep_alive" to 0,"stream" to false))
        val request=HttpRequest.newBuilder(endpoint.resolve("/api/generate")).timeout(Duration.ofSeconds(30))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        check(client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200) {"Ollama가 모델 언로드를 거부했습니다."}
    }
    /** Unloads managed local models other than [keep] (all of them when null) and waits until Ollama reports them gone. */
    fun unloadQwen(keep:String?=null):List<String> {
        val stale=loaded().filter {it in qwenTags() && it!=keep}
        stale.forEach {unload(it)}
        if(stale.isNotEmpty()) {
            repeat(20) {if(loaded().none {name->name in stale}) return stale;Thread.sleep(500)}
            throw IllegalStateException("이전 로컬 모델이 아직 메모리에 있어 동시에 두 모델을 켜지 않도록 중단했습니다. 잠시 후 다시 시도하세요.")
        }
        return stale
    }
    fun modelContext(size:String):Int? {
        if(LocalModels.get(size).backend=="strata")return 16384
        val cacheKey=endpoint.toString()+tag(size)
        modelMetadata[cacheKey]?.takeIf {System.currentTimeMillis()-it.first<300_000}?.let {return it.second}
        return runCatching {
        val request=HttpRequest.newBuilder(endpoint.resolve("/api/show")).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("model" to tag(size))))).build()
        val response=client.send(request,HttpResponse.BodyHandlers.ofInputStream())
        response.body().use {stream->check(response.statusCode()==200);val bytes=stream.readNBytes(1_048_577);check(bytes.size<=1_048_576)
            mapper.readTree(bytes).path("model_info").properties().firstOrNull {it.key.endsWith(".context_length")}?.value?.asInt()?.takeIf {it>0}}
    }.getOrNull().also {modelMetadata[cacheKey]=System.currentTimeMillis() to it}
    }
    private fun modelFile(size:String):java.nio.file.Path {
        val request=HttpRequest.newBuilder(endpoint.resolve("/api/show")).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("model" to tag(size))))).build()
        val response=client.send(request,HttpResponse.BodyHandlers.ofInputStream())
        val raw=response.body().use {it.readNBytes(1_048_577)};check(response.statusCode()==200 && raw.size<=1_048_576) {"Ollama 모델 경로를 확인하지 못했습니다."}
        val from=mapper.readTree(raw).path("modelfile").asText().lineSequence().firstOrNull {it.startsWith("FROM ")}?.removePrefix("FROM ")?.trim()?.trim('"') ?: error("GGUF 경로가 없습니다.")
        val path=java.nio.file.Path.of(from);require(path.isAbsolute && java.nio.file.Files.isRegularFile(path)) {"로컬 GGUF 모델 파일을 찾을 수 없습니다."}
        check(java.nio.file.Files.newInputStream(path).use {it.readNBytes(4)}.contentEquals("GGUF".toByteArray())) {"TurboQuant에는 로컬 GGUF가 필요합니다."}
        return path.toRealPath()
    }
    private fun memory():LlmMemory = runCatching {
        val rows=get("/api/ps").path("models");val sizes=rows.mapNotNull {it.get("size")?.asLong()}
        if(rows.isEmpty || sizes.isNotEmpty())LlmMemory.sample(sizes.sum(),"Ollama 전체 할당")else LlmMemory.sample()
    }.getOrElse {LlmMemory.sample()}
    fun unloadAll():Status {
        check(inference.tryAcquire()) {"다른 로컬 모델 요청이 진행 중입니다."}
        try {TurboQuantRuntime.stop();StrataRuntime.stop();unloadQwen();return probe()}finally {inference.release()}
    }
    fun probe():Status {
        val tags=SIZES.associateWith {tag(it)};val strata=StrataRuntime.status()
        val version=runCatching {get("/api/version").path("version").asText().takeIf {it.isNotBlank()}}.getOrNull()
        val models=runCatching {get("/api/tags").path("models").map {OllamaModel(it.path("name").asText(),it.path("size").asLong())}.filter {it.name.isNotBlank()}}.getOrDefault(emptyList())
        val installed=tags.mapValues {(id,tag)->if(LocalModels.get(id).backend=="strata")strata.installed else models.any {it.name==tag}}
        val missing=installed.filter {(id,ok)->!ok && LocalModels.get(id).backend=="ollama"}.keys.map {LocalModels.label(it)}
        val loaded=loaded()+(if(strata.running)listOf(StrataRuntime.MODEL)else emptyList())+(if(TurboQuantRuntime.status().running)listOf(tag("qwen38-27b"))else emptyList())
        return Status(version!=null || strata.running,version,models,loaded,tags,installed,if(version!=null)"Ollama 실행 중"+(if(missing.isEmpty())" · Ollama 모델 모두 설치됨"else " · 미설치: ${missing.joinToString()}")+" · Flash-Next는 별도 Strata 엔진을 사용합니다."else "Ollama에 연결하지 못했습니다. Flash-Next 상태는 아래에서 확인하세요.",
            tags.keys.mapNotNull {key->if(installed[key]==true)modelContext(key)?.let {key to it}else null}.toMap(),if(strata.running)StrataRuntime.memory()else if(TurboQuantRuntime.status().running)TurboQuantRuntime.memory()else memory(),strata,TurboQuantRuntime.status())
    }
    fun chat(size:String,prompt:String,think:Boolean=false):Answer {
        check(inference.tryAcquire()) {"다른 로컬 모델 요청이 진행 중입니다."}
        try {return chatLocked(size,prompt,think)} finally {inference.release()}
    }
    private fun chatLocked(size:String,prompt:String,think:Boolean):Answer {
        TurboQuantRuntime.stop()
        require(size in SIZES && prompt.isNotBlank() && prompt.length<=6000)
        if(LocalModels.get(size).backend=="strata") {unloadQwen();return StrataLlm().conversation(listOf(ProjectAgentMessage("user",prompt)),think,LocalContextBudget(output=2048),plain=true)}
        StrataRuntime.stop()
        val model=tag(size)
        unloadQwen(model)
        val body=mapper.writeValueAsString(mapOf("model" to model,"messages" to listOf(mapOf("role" to "user","content" to prompt)),
            "think" to LocalModels.thinking(size,think),"stream" to false,"keep_alive" to 0,"options" to mapOf("num_ctx" to 8192,"num_predict" to 2048)))
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
        check(response.statusCode()==200) {error(response.statusCode(),bytes.toByteArray())}
        val result=mapper.readTree(bytes.toByteArray())
        check(result.path("done").asBoolean() && !result.has("error")) {"Ollama가 완료된 응답을 반환하지 않았습니다."}
        val text=result.path("message").path("content").asText()
        check(text.isNotBlank()) {"최종 답변이 없습니다. 출력 한도 또는 모델 상태를 확인하세요."}
        return Answer(text,result.path("done_reason").asText()=="length",(System.nanoTime()-start)/1e9,result.path("eval_count").asLong(),inputTokens=result.get("prompt_eval_count")?.asLong(),tokensPerSecond=LlmMemory.rate(result.path("eval_count").asLong(),result.get("eval_duration")?.asLong()),memory=memory())
    }

    /** A bounded conversation; reviewers pass a fresh two-message list with no acting-agent context. */
    fun conversation(size:String,messages:List<ProjectAgentMessage>,think:Boolean=true,progress:(QwenProgress)->Unit={}):Answer = conversationWithBudget(size,messages,think,LocalContextBudget.DEFAULT,progress)
    fun conversationWithBudget(size:String,messages:List<ProjectAgentMessage>,think:Boolean,budget:LocalContextBudget,progress:(QwenProgress)->Unit={}):Answer {
        val pi=PiWire.decode(messages)
        require(size in SIZES && messages.size in 1..32 && messages.all {it.role in setOf("system","user","assistant")} && messages.sumOf {it.content.length}<=(if(pi!=null)PiWire.MAX_REQUEST_BYTES else 140000))
        val native=ProjectAgentNativeTools.active(messages)
        val overhead=if(native)ProjectAgentNativeTools.overhead(messages.first().content)else 0
        require(pi!=null || messages.sumOf {ProjectAgentContext.messageTokens(it)}+overhead<=budget.input) {"설정된 입력 예산(${budget.input})보다 문맥이 큽니다. 파일·변경을 작은 구간으로 나눠 요청하세요."}
        check(inference.tryAcquire()) {"다른 로컬 모델 요청이 진행 중입니다."}
        try {
            val memoryGuard=LlmMemoryGuard(budget.allowCriticalMemory);memoryGuard.check()
            if(budget.experimentalKvCompression && size=="qwen38-27b") {
                check(TurboQuantRuntime.status().ready) {"TurboQuant 엔진 준비가 필요합니다. 관리웹에서 설치/지원 상태를 확인하세요."}
                val file=modelFile(size);StrataRuntime.stop();unloadQwen()
                return StrataLlm(endpoint=TurboQuantRuntime.endpoint,context={TurboQuantRuntime.start(file,budget.window,tag(size),budget.allowCriticalMemory)},memory={TurboQuantRuntime.memory()},model=tag(size),backend="TurboQuant",allowedWindows=LocalContextBudget.WINDOWS,authorization={TurboQuantRuntime.authorization()},modelMaximum={modelContext(size)}).conversation(messages,think,budget,progress)
            }
            TurboQuantRuntime.stop()
            if(LocalModels.get(size).backend=="strata") {unloadQwen();return StrataLlm().conversation(messages,think,budget,progress)}
            StrataRuntime.stop()
            unloadQwen(tag(size))
            val body=mapper.writeValueAsString(mapOf("model" to tag(size),"messages" to (if(pi!=null)PiWire.ollamaMessages(pi) else if(native)ProjectAgentNativeTools.wire(messages)else messages),"think" to LocalModels.thinking(size,think),"stream" to true,
                "keep_alive" to "1m","options" to (mapOf("num_ctx" to budget.window,"num_predict" to budget.output)+if(pi!=null)mapOf("temperature" to pi.path("temperature").asDouble(0.0).coerceIn(0.0,1.0))else if(messages.firstOrNull()?.content?.let {it.startsWith("Independent command-risk reviewer") || it.startsWith("Independent request-aware command-risk reviewer")}==true)mapOf("temperature" to 0)else emptyMap()))+
                if(pi!=null)mapOf("tools" to pi.path("tools")) else if(native)mapOf("tools" to ProjectAgentNativeTools.definitions(messages.first().content))else mapOf("format" to ProjectAgentFormats.forMessages(messages)))
            trace?.invoke("request",body)
            val request=HttpRequest.newBuilder(endpoint.resolve("/api/chat")).timeout(Duration.ofSeconds(budget.requestTimeoutSeconds.toLong())).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val start=System.nanoTime();val opened=java.util.concurrent.atomic.AtomicReference<java.io.InputStream?>()
            val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
            val headers=client.sendAsync(request,HttpResponse.BodyHandlers.ofInputStream())
            headers.whenComplete {response,_->if(cancelled.get())runCatching {response?.body()?.close()}}
            val pending=headers.thenApplyAsync {response->
                response.body().use {stream->
                    opened.set(stream)
                    check(!cancelled.get()) {"Ollama 생성을 중단했습니다."}
                    check(response.statusCode()==200) {error(response.statusCode(),stream.readNBytes(4096))}
                    val bounded=object:java.io.FilterInputStream(stream){var count=0
                        override fun read():Int=super.read().also {if(it>=0){count++;check(count<=524288)}}
                        override fun read(b:ByteArray,off:Int,len:Int):Int=super.read(b,off,len).also {if(it>0){count+=it;check(count<=524288)}}
                    }
                    val content=StringBuilder();val thinking=StringBuilder();var last=0L;var done=false;var limited=false;var tokens=0L;var inputTokens:Long?=null;var duration:Long?=null;val calls=mutableListOf<com.fasterxml.jackson.databind.JsonNode>()
                    bounded.bufferedReader().use {reader->while(true){
                        val line=reader.readLine() ?: break;trace?.invoke("chunk",line);val chunk=mapper.readTree(line);check(!chunk.has("error")) {"Ollama가 요청을 완료하지 못했습니다."}
                        content.append(chunk.path("message").path("content").asText());thinking.append(chunk.path("message").path("thinking").asText())
                        chunk.path("message").path("tool_calls").takeIf {it.isArray}?.forEach {call->calls.add(call);check(calls.size<=16)}
                        check(!cancelled.get()) {"Ollama 생성을 중단했습니다."}
                        if(System.nanoTime()-last>TimeUnit.MILLISECONDS.toNanos(250)){progress(QwenProgress(thinking.toString(),(if(native || pi!=null)content.toString()else QwenVisibleText.message(content.toString()))).bounded());last=System.nanoTime()}
                        if(chunk.path("done").asBoolean()){done=true;limited=chunk.path("done_reason").asText()=="length";tokens=chunk.path("eval_count").asLong();inputTokens=chunk.get("prompt_eval_count")?.asLong();duration=chunk.get("eval_duration")?.asLong();break}
                    }}
                    progress(QwenProgress(thinking.toString(),(if(native || pi!=null)content.toString()else QwenVisibleText.message(content.toString()))).bounded())
                    check(done && (native || pi!=null || content.isNotBlank())) {"Ollama 답변이 완료되지 않았습니다. 문맥·출력 한도를 확인하세요."}
                    val text=if(pi!=null)content.toString() else if(native)ProjectAgentNativeTools.reply(content.toString(),calls)else content.toString()
                    Answer(text,limited,(System.nanoTime()-start)/1e9,tokens,thinking.takeLast(4000).toString(),inputTokens,modelContext(size),if(pi!=null)PiWire.assistant(content.toString(),calls,thinking.toString()) else if(native && calls.size==1)ProjectAgentNativeTools.assistant(content.toString(),calls,thinking.toString())else null,LlmMemory.rate(tokens,duration),memory())
                }
            }
            return try {memoryGuard.await(pending,budget.requestTimeoutSeconds.toLong()+5)} catch(e:Exception){cancelled.set(true);headers.cancel(true);pending.cancel(true);runCatching {opened.get()?.close()};runCatching {headers.getNow(null)?.body()?.close()};if(e is InterruptedException)Thread.currentThread().interrupt();val failure=generateSequence<Throwable>(e){it.cause}.firstOrNull {it is IllegalStateException && it.message.orEmpty().startsWith("Ollama HTTP ")};throw IllegalStateException(when {LlmMemoryGuard.reason(e)!=null->LlmMemoryGuard.reason(e);e is InterruptedException->"Ollama 생성을 중단했습니다.";failure!=null->failure.message;else->"Ollama 대화 실패 또는 설정한 ${budget.requestTimeoutSeconds/60}분 제한 초과입니다."},e)}
        }finally {inference.release()}
    }

    companion object {
        private val modelMetadata=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,Int?>>()
        private val inference=java.util.concurrent.Semaphore(1)
        val SIZES=LocalModels.all.map {it.id}
        fun tag(size:String):String = LocalModels.tag(size)
        private fun qwenTags()=SIZES.map(::tag)
    }
}

/** One inference request across chat, the file harness and private Google summaries. */
object QwenRequests {
    data class Reply(val answer:LocalLlm.Answer,val runnerName:String?)
    private val slot=java.util.concurrent.Semaphore(1)
    fun conversation(size:String,messages:List<ProjectAgentMessage>,think:Boolean=true,runnerId:String?=null,progress:(QwenProgress)->Unit={}):Reply = conversationWithBudget(size,messages,think,runnerId,LocalContextBudget.DEFAULT,progress)
    fun conversationWithBudget(size:String,messages:List<ProjectAgentMessage>,think:Boolean,runnerId:String?,budget:LocalContextBudget,progress:(QwenProgress)->Unit={}):Reply {
        check(slot.tryAcquire()) {"다른 Qwen 요청이 진행 중입니다."}
        try {
            val remote=RemoteLlm.budgetConversationProvider?.invoke(size,messages,LocalModels.thinking(size,think),runnerId,budget,progress)
                ?: if(RemoteLlm.budgetConversationProvider==null && budget.window==8192 && !budget.experimentalKvCompression)RemoteLlm.conversationProvider?.invoke(size,messages,LocalModels.thinking(size,think),runnerId,progress)else null
            check(remote!=null || runnerId==null) {"선택한 실행기가 프로젝트 에이전트 대화를 지원하지 않습니다. 실행기를 업데이트하세요."}
            return Reply(remote?.answer ?: LocalLlm().conversationWithBudget(size,messages,think,budget,progress),remote?.runnerName)
        }finally {slot.release()}
    }
    fun chat(size:String,prompt:String,think:Boolean=false,runnerId:String?=null):Reply {
        check(slot.tryAcquire()) {"다른 Qwen 요청이 진행 중입니다."}
        try {
            val remote=RemoteLlm.chat(size,prompt,think,runnerId)
            check(remote!=null || runnerId==null) {"선택한 실행기를 사용할 수 없습니다."}
            return Reply(remote?.answer ?: LocalLlm().chat(size,prompt,think),remote?.runnerName)
        } finally {slot.release()}
    }
}
