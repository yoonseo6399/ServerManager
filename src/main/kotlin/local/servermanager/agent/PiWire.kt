package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Private provider envelope. It is data carried through the consented runner, never an instruction/tool loop. */
internal object PiWire {
    const val PREFIX="SERVERMANAGER_PI_PROVIDER_V1\n"
    private val mapper=jacksonObjectMapper()
    fun active(messages:List<ProjectAgentMessage>)=messages.size==1 && messages[0].role=="system" && messages[0].content.startsWith(PREFIX)
    fun decode(messages:List<ProjectAgentMessage>):JsonNode? {
        if(!active(messages))return null
        val raw=messages[0].content.removePrefix(PREFIX);require(raw.length<=180000) {"Pi 요청 크기 한도입니다. 문맥을 압축하세요."}
        val body=mapper.readTree(raw)
        require(body.isObject && body.path("messages").isArray && body.path("messages").size() in 1..256)
        require(!body.has("tools") || body.path("tools").isArray && body.path("tools").size()<=32)
        body.path("messages").forEach {m->require(m.path("role").asText() in setOf("system","developer","user","assistant","tool")) {"Pi 메시지 역할이 지원되지 않습니다: ${m.path("role").asText()}"};if(m.path("role").asText()=="developer")(m as ObjectNode).put("role","system");if(m.path("content").isArray){val blocks=m.path("content");require(blocks.all {it.path("type").asText()=="text" && it.path("text").isTextual}) {"현재 Pi 연결은 텍스트 파일만 지원합니다."};(m as ObjectNode).put("content",blocks.joinToString("\n"){it.path("text").asText()})};require(m.path("content").isTextual || m.path("content").isNull || !m.has("content"))}
        body.path("tools").forEach {t->require(t.path("type").asText()=="function" && t.path("function").path("name").asText().matches(Regex("[a-zA-Z0-9_]{1,64}"))) {"Pi 공급자에는 공식 function 도구만 등록할 수 있습니다: ${t.path("type").asText()}"}}
        return body
    }
    fun ollamaMessages(body:JsonNode):List<JsonNode> {
        val names=mutableMapOf<String,String>()
        return body.path("messages").map {original->
            val m=original.deepCopy<ObjectNode>()
            m.path("tool_calls").forEach {c->val call=c as ObjectNode;val fn=call.path("function") as ObjectNode;names[call.path("id").asText()]=fn.path("name").asText();if(fn.path("arguments").isTextual)fn.set<JsonNode>("arguments",mapper.readTree(fn.path("arguments").asText()))}
            if(m.path("role").asText()=="tool") {m.put("tool_name",names[m.path("tool_call_id").asText()] ?: error("Pi 도구 결과의 호출 ID가 일치하지 않습니다."));m.remove("tool_call_id")}
            if(m.has("reasoning_content")){m.set<JsonNode>("thinking",m.remove("reasoning_content"))}
            if(!m.path("content").isTextual)m.put("content","")
            m
        }
    }
    fun assistant(text:String,calls:List<JsonNode>,thinking:String=""):String {
        val values=calls.mapIndexed {index,c->
            val fn=c.path("function");mapOf("id" to c.path("id").asText().ifBlank {"pi_call_${java.util.UUID.randomUUID()}_$index"},"type" to "function","function" to mapOf("name" to fn.path("name").asText(),"arguments" to (if(fn.path("arguments").isTextual)fn.path("arguments").asText()else mapper.writeValueAsString(fn.path("arguments")))))
        }
        return mapper.writeValueAsString(mapOf("role" to "assistant","content" to text,"reasoning_content" to thinking,"tool_calls" to values))
    }
}
