package local.servermanager

/** Provider-level JSON grammar; semantic policy and explicit approval are still enforced separately. */
internal object ProjectAgentFormats {
    fun forMessages(messages:List<ProjectAgentMessage>):Any {
        val system=messages.firstOrNull()?.takeIf {it.role=="system"}?.content.orEmpty()
        if(!system.startsWith("Independent command-risk reviewer"))return "json"
        fun choice(vararg values:String)=mapOf("type" to "string","enum" to values.toList())
        return mapOf("type" to "object","additionalProperties" to false,
            "required" to listOf("action","risk","projectScope","reason"),
            "properties" to mapOf("action" to choice("ALLOW","ASK","DENY"),"risk" to choice("LOW","MEDIUM","HIGH"),
                "projectScope" to choice("INSIDE","OUTSIDE","UNKNOWN"),"reason" to mapOf("type" to "string","minLength" to 1,"maxLength" to 1000)))
    }
}
