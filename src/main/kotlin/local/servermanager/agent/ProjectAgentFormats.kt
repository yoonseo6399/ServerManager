package local.servermanager

/** Provider-level JSON grammar; semantic policy and explicit approval are still enforced separately. */
internal object ProjectAgentFormats {
    fun forMessages(messages:List<ProjectAgentMessage>):Any {
        val system=messages.firstOrNull()?.takeIf {it.role=="system"}?.content.orEmpty()
        if(system.startsWith("You are 루오노타르")) {
            val tier=when {system.contains("Requester access level: READ")->AgentAccess.READ;system.contains("Requester access level: EDIT")->AgentAccess.EDIT;else->AgentAccess.OWNER}
            fun string()=mapOf("type" to "string")
            val tool=mapOf("type" to "object","required" to listOf("name","arguments"),"additionalProperties" to false,
                "properties" to mapOf("name" to mapOf("type" to "string","enum" to ProjectAgentTools.allowed(tier).sorted()),"arguments" to mapOf("type" to "object")))
            val requirement=mapOf("type" to "object","required" to listOf("id","status","evidence"),
                "properties" to mapOf("id" to string(),"status" to mapOf("type" to "string","enum" to listOf("done","blocked")),"evidence" to string(),"evidence_ids" to mapOf("type" to "array","items" to string())))
            return mapOf("type" to "object","required" to listOf("message","status","tool"),"additionalProperties" to false,
                "properties" to mapOf("message" to string(),"status" to mapOf("type" to "string","enum" to listOf("completed","needs_tool","clarify","blocked")),
                    "tool" to mapOf("anyOf" to listOf(mapOf("type" to "null"),tool)),"plan" to mapOf("type" to "array","items" to string(),"maxItems" to 12),
                    "requirements" to mapOf("type" to "array","items" to requirement,"maxItems" to 16)))
        }
        if(system.startsWith("Context compressor"))return ProjectAgentCompaction.schema
        if(system.startsWith("Tool-result summarizer"))return mapOf("type" to "object","required" to listOf("summary"),"additionalProperties" to false,"properties" to mapOf("summary" to mapOf("type" to "string","minLength" to 1,"maxLength" to 1000)))
        if(!system.startsWith("Independent command-risk reviewer"))return "json"
        fun choice(vararg values:String)=mapOf("type" to "string","enum" to values.toList())
        return mapOf("type" to "object","additionalProperties" to false,
            "required" to listOf("action","risk","projectScope","reason"),
            "properties" to mapOf("action" to choice("ALLOW","ASK","DENY"),"risk" to choice("LOW","MEDIUM","HIGH"),
                "projectScope" to choice("INSIDE","OUTSIDE","UNKNOWN"),"reason" to mapOf("type" to "string","minLength" to 1,"maxLength" to 1000)))
    }
}
