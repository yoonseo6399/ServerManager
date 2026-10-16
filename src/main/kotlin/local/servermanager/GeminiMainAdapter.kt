package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

/** Translate the coordinator's Responses envelope without changing tool execution semantics.
 * Preserve complete Gemini parts (including thought signatures) in private replay metadata. */
class GeminiMainAdapter(private val delegate:AgentModel):AgentModel {
    private val mapper=jacksonObjectMapper()
    override fun generate(request:JsonNode):JsonNode {
        val contents=mapper.createArrayNode()
        val calls=mutableMapOf<String,Pair<String,String?>>()
        val replayed=mutableSetOf<String>()
        request.path("input").forEach {item ->
            val type=item.path("type").asText()
            when {
                item.has("geminiParts") -> {
                    val group=item.path("geminiGroup").asText()
                    if(replayed.add(group))contents.add(mapper.createObjectNode().put("role","model").set<JsonNode>("parts",item.path("geminiParts")))
                    if(type=="function_call")calls[item.path("call_id").asText()]=item.path("name").asText() to item.path("geminiCallId").takeIf {it.isTextual}?.asText()
                }
                type=="function_call_output" -> {
                    val id=item.path("call_id").asText();val callInfo=calls[id] ?: error("도구 응답에 대응하는 호출이 없습니다.")
                    val functionResponse=linkedMapOf<String,Any>("name" to callInfo.first,"response" to mapOf("result" to item.path("output").asText()))
                    callInfo.second?.let {functionResponse["id"]=it}
                    val part=mapper.valueToTree<JsonNode>(mapOf("functionResponse" to functionResponse))
                    val last=contents.lastOrNull()
                    if(last?.path("role")?.asText()=="user" && last.path("parts").firstOrNull()?.has("functionResponse")==true)
                        (last.path("parts") as com.fasterxml.jackson.databind.node.ArrayNode).add(part)
                    else contents.add(mapper.createObjectNode().put("role","user").set<JsonNode>("parts",mapper.createArrayNode().add(part)))
                }
                item.path("role").asText()=="user" -> contents.add(mapper.valueToTree<JsonNode>(mapOf("role" to "user","parts" to listOf(mapOf("text" to if(item.path("content").isArray)item.path("content").map {it.path("text").asText()}.joinToString("\n") else item.path("content").asText())))))
                else -> error("Gemini 대화 이력 형식을 확인하세요.")
            }
        }
        val translated=mapper.createObjectNode()
        translated.set<JsonNode>("contents",contents)
        translated.set<JsonNode>("systemInstruction",mapper.valueToTree(mapOf("parts" to listOf(mapOf("text" to request.path("instructions").asText())))))
        val declarations=request.path("tools").map {tool -> mapOf("name" to tool.path("name").asText(),"description" to tool.path("description").asText(),"parametersJsonSchema" to tool.path("parameters"))}
        if(declarations.isNotEmpty())translated.set<JsonNode>("tools",mapper.valueToTree(listOf(mapOf("functionDeclarations" to declarations))))
        translated.set<JsonNode>("generationConfig",mapper.valueToTree(mapOf("maxOutputTokens" to request.path("max_output_tokens").asInt(8192))))
        val response=delegate.generate(translated)
        val candidate=response.path("candidates").firstOrNull() ?: error("Gemini가 응답 후보를 반환하지 않았습니다.")
        check(candidate.path("finishReason").asText()=="STOP") { "Gemini 응답이 완료되지 않았습니다. 제한 또는 차단 사유를 확인하세요." }
        val parts=candidate.path("content").path("parts")
        val group=UUID.randomUUID().toString();val output=mapper.createArrayNode()
        parts.forEach {part ->
            if(part.has("functionCall")) {
                val call=part.path("functionCall")
                output.add(mapper.createObjectNode().put("type","function_call").put("name",call.path("name").asText()).put("call_id",UUID.randomUUID().toString()).put("arguments",call.path("args").toString()).also {node -> if(call.has("id"))node.put("geminiCallId",call.path("id").asText())})
            } else if(part.has("text") && !part.path("thought").asBoolean()) {
                output.add(mapper.valueToTree<JsonNode>(mapOf("type" to "message","role" to "assistant","content" to listOf(mapOf("type" to "output_text","text" to part.path("text").asText())))))
            }
        }
        check(output.size()>0) { "Gemini가 사용 가능한 텍스트나 도구 호출을 반환하지 않았습니다." }
        output.forEach {(it as com.fasterxml.jackson.databind.node.ObjectNode).put("geminiGroup",group).set<JsonNode>("geminiParts",parts)}
        return mapper.createObjectNode().put("status","completed").also {
            it.set<JsonNode>("output",output)
            it.set<JsonNode>("usage",mapper.valueToTree(mapOf("total_tokens" to response.path("usageMetadata").path("totalTokenCount").asLong())))
        }
    }
}
