package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Recognize explicit tool intent only. Never derive a command from prose or skip the normal approval gate. */
internal object ProjectAgentReply {
    private val mapper=jacksonObjectMapper()
    fun parse(text:String):JsonNode? = runCatching {
        val node=mapper.readTree(text) as? ObjectNode ?: return null
        if(!node.path("message").isTextual)return null
        val alias=node.hasNonNull("tool_name") || node.hasNonNull("arguments")
        if(node.has("tool") && alias)return null // contradictory/ambiguous shapes require a model retry
        if(!node.has("tool")){
            if(!node.path("tool_name").isTextual || !node.path("arguments").isObject)return null
            val tool=mapper.createObjectNode().put("name",node.path("tool_name").asText())
            tool.set<JsonNode>("arguments",node.path("arguments"));node.set<JsonNode>("tool",tool)
        }
        if(node.hasNonNull("tool")){
            val tool=node.path("tool")
            if(!tool.isObject || tool.path("name").asText() !in ProjectAgentTools.names || !tool.path("arguments").isObject)return null
        }
        node
    }.getOrNull()
}
