package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** OpenAI chat-completions transport for the pinned Strata backend, retaining the same permission engine. */
internal class StrataLlm(private val endpoint:URI=StrataRuntime.endpoint,private val context:()->Int={StrataRuntime.start()},private val memory:()->LlmMemory={StrataRuntime.memory()}) {
    private val mapper=jacksonObjectMapper()
    init {require(endpoint.scheme=="http" && endpoint.host in setOf("localhost","127.0.0.1","[::1]","::1") && endpoint.rawUserInfo==null && endpoint.rawQuery==null && endpoint.rawFragment==null && endpoint.path in setOf("","/"))}
    internal fun wire(messages:List<ProjectAgentMessage>):List<JsonNode> {
        var counter=0;val pending=mutableMapOf<String,String>()
        return ProjectAgentNativeTools.wire(messages).map {node->
            val out=node.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            if(out.has("thinking")){out.set<JsonNode>("reasoning_content",out.remove("thinking"))}
            if(out.path("role").asText()=="assistant")out.path("tool_calls").forEach {call->
                val c=call as com.fasterxml.jackson.databind.node.ObjectNode;val fn=c.path("function") as com.fasterxml.jackson.databind.node.ObjectNode
                val id=c.path("id").asText().ifBlank {"sm_call_${counter++}"};c.put("id",id);c.put("type","function")
                pending[fn.path("name").asText()]=id
                if(!fn.path("arguments").isTextual)fn.put("arguments",mapper.writeValueAsString(fn.path("arguments")))
            }
            if(out.path("role").asText()=="tool"){
                val name=out.path("tool_name").asText();out.remove("tool_name")
                out.put("tool_call_id",pending.remove(name) ?: error("Strata 도구 결과에 대응하는 호출이 없습니다."))
            }
            out
        }
    }
    fun conversation(messages:List<ProjectAgentMessage>,think:Boolean,budget:LocalContextBudget,progress:(QwenProgress)->Unit={},plain:Boolean=false):LocalLlm.Answer {
        // Reject before starting a 66GB offloaded model, rather than silently shrinking a user's window.
        require(budget.window==8192) {"Flash-Next는 현재 검증한 8K 실행 창만 지원합니다. /agent context에서 8192를 선택하세요."}
        val maximum=context();require(budget.window<=maximum) {"Strata 실행 창보다 큰 문맥입니다."}
        val pi=PiWire.decode(messages)
        val native=ProjectAgentNativeTools.active(messages)
        val body=linkedMapOf<String,Any>("model" to StrataRuntime.MODEL,"messages" to (pi?.path("messages") ?: wire(messages)),"stream" to true,"stream_options" to mapOf("include_usage" to true),"max_tokens" to budget.output,"temperature" to 0,
            "chat_template_kwargs" to mapOf("enable_thinking" to think,"preserve_thinking" to true))
        if(pi!=null)body["tools"]=pi.path("tools")
        else if(native){body["tools"]=ProjectAgentNativeTools.definitions(messages.first().content);body["parallel_tool_calls"]=false}
        else if(!plain){val schema=ProjectAgentFormats.forMessages(messages);body["response_format"]=if(schema is String)mapOf("type" to "json_object")else mapOf("type" to "json_schema","json_schema" to mapOf("name" to "agent_response","strict" to true,"schema" to schema))}
        val request=HttpRequest.newBuilder(endpoint.resolve("/v1/chat/completions")).timeout(Duration.ofMinutes(20)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build()
        val start=System.nanoTime();val opened=java.util.concurrent.atomic.AtomicReference<java.io.InputStream?>();val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
        val headers=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().sendAsync(request,HttpResponse.BodyHandlers.ofInputStream())
        headers.whenComplete {r,_->if(cancelled.get())runCatching {r?.body()?.close()}}
        val pending=headers.thenApplyAsync {response->response.body().use {stream->
            opened.set(stream);check(!cancelled.get());check(response.statusCode()==200) {"Strata HTTP ${response.statusCode()} · 실행 컴퓨터의 Strata 로그를 확인하세요."}
            val text=StringBuilder();val thinking=StringBuilder();val calls=sortedMapOf<Int,Triple<StringBuilder,StringBuilder,StringBuilder>>()
            var count=0;var last=0L;var finish="";var tokens=0L;var input:Long?=null;var rate:Double?=null
            stream.bufferedReader().use {reader->while(true){
                val line=reader.readLine() ?: break;count+=line.length;check(count<=524288) {"Strata 응답 크기 제한입니다."};check(!cancelled.get())
                if(!line.startsWith("data:"))continue
                val data=line.substringAfter(':').trim();if(data=="[DONE]")break
                val chunk=mapper.readTree(data);check(!chunk.has("error")) {"Strata 생성 실패"}
                val choice=chunk.path("choices").firstOrNull();val delta=choice?.path("delta")
                text.append(delta?.path("content")?.asText("").orEmpty());thinking.append(delta?.path("reasoning_content")?.asText("").orEmpty())
                delta?.path("tool_calls")?.forEach {part->
                    val index=part.path("index").asInt();require(index in 0..15)
                    val v=calls.getOrPut(index){Triple(StringBuilder(),StringBuilder(),StringBuilder())}
                    v.first.append(part.path("id").asText(""));v.second.append(part.path("function").path("name").asText(""));v.third.append(part.path("function").path("arguments").asText(""))
                }
                choice?.get("finish_reason")?.takeUnless {it.isNull}?.let {finish=it.asText()}
                chunk.get("usage")?.let {tokens=it.path("completion_tokens").asLong();input=it.get("prompt_tokens")?.asLong()}
                chunk.path("timings").get("predicted_per_second")?.asDouble()?.takeIf {it.isFinite() && it>0}?.let {rate=it}
                if(System.nanoTime()-last>TimeUnit.MILLISECONDS.toNanos(250)){progress(QwenProgress(thinking.toString(),if(native||plain||pi!=null)text.toString()else QwenVisibleText.message(text.toString())).bounded());last=System.nanoTime()}
            }}
            check(finish.isNotEmpty()) {"Strata 응답이 완료되지 않았습니다."}
            val functions=calls.values.map {v->mapper.valueToTree<JsonNode>(mapOf("id" to v.first.toString(),"type" to "function","function" to mapOf("name" to v.second.toString(),"arguments" to mapper.readTree(v.third.toString()))))}
            progress(QwenProgress(thinking.toString(),if(native||plain||pi!=null)text.toString()else QwenVisibleText.message(text.toString())).bounded())
            LocalLlm.Answer(if(pi!=null)text.toString() else if(native)ProjectAgentNativeTools.reply(text.toString(),functions)else text.toString(),finish=="length",(System.nanoTime()-start)/1e9,tokens,thinking.takeLast(4000).toString(),input,maximum,
                if(pi!=null)PiWire.assistant(text.toString(),functions,thinking.toString()) else if(native && functions.size==1)ProjectAgentNativeTools.assistant(text.toString(),functions,thinking.toString())else null,rate,memory())
        }}
        try {return pending.get(1205,TimeUnit.SECONDS)}catch(e:Exception){
            cancelled.set(true);headers.cancel(true);pending.cancel(true);runCatching {opened.get()?.close()};runCatching {headers.getNow(null)?.body()?.close()}
            if(e is InterruptedException)Thread.currentThread().interrupt()
            val message=generateSequence<Throwable>(e){it.cause}.firstOrNull {it is IllegalStateException || it is IllegalArgumentException}?.message
            throw IllegalStateException(if(e is InterruptedException)"Flash-Next 생성을 중단했습니다."else message ?: "Flash-Next 대화 실패 또는 20분 제한 초과입니다.",e)
        }
    }
}
