package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Ollama /api/chat function registration. Tool calls remain proposals until the engine authorizes them. */
internal object ProjectAgentNativeTools {
    const val MARKER="OLLAMA_NATIVE_TOOLS_V1"
    private val mapper=jacksonObjectMapper()
    private val core=setOf("list_files","read_file","search","edit_file","write_file","sh")
    fun active(messages:List<ProjectAgentMessage>)=messages.firstOrNull()?.takeIf {it.role=="system"}?.content?.lineSequence()?.any {it==MARKER}==true
    fun tier(system:String)=when {system.contains("Requester access level: READ")->AgentAccess.READ;system.contains("Requester access level: EDIT")->AgentAccess.EDIT;else->AgentAccess.OWNER}
    fun names(system:String):Set<String> {
        val allowed=ProjectAgentTools.allowed(tier(system))
        val extra=Regex("(?m)^NATIVE_EXTRA_TOOLS=([a-z_,]*)$").findAll(system).lastOrNull()?.groupValues?.get(1)?.split(',').orEmpty()
        return (core+extra).intersect(allowed)
    }
    private fun str()=mapOf("type" to "string")
    private fun integer(min:Int=1,max:Int=400)=mapOf("type" to "integer","minimum" to min,"maximum" to max)
    private fun bool()=mapOf("type" to "boolean")
    private fun obj(properties:Map<String,Any>,vararg required:String):Map<String,Any> = mapOf("type" to "object","properties" to properties,"required" to required.toList(),"additionalProperties" to false)
    private fun schema(name:String):Map<String,Any> = when(name) {
        "read_file"->obj(mapOf("path" to str(),"start_line" to integer(1,Int.MAX_VALUE),"lines" to integer(),"summarize" to bool()),"path")
        "list_files"->obj(mapOf("path" to str(),"limit" to integer(1,300),"summarize" to bool()))
        "search"->obj(mapOf("pattern" to str(),"path" to str(),"glob" to str(),"files" to bool(),"type" to mapOf("type" to "string","enum" to listOf("kotlin","kt","kts","java","ts","tsx","js","py","md","txt","text","json","css","rust","go","yaml","html","sh")),"context" to integer(0,10),"max" to integer(1,200),"ignore_case" to bool(),"fixed" to bool()))
        "edit_file"->obj(mapOf("path" to str(),"find" to str(),"replace" to str(),"replace_all" to bool(),"start_line" to integer(1,Int.MAX_VALUE),"end_line" to integer(1,Int.MAX_VALUE),"insert_after_line" to integer(0,Int.MAX_VALUE)),"path","replace")
        "write_file"->obj(mapOf("path" to str(),"content" to str()),"path","content")
        "sh"->obj(mapOf("command" to str()),"command")
        "delete_path","upload_file"->obj(mapOf("path" to str()),"path")
        "find_files"->obj(mapOf("path" to str(),"pattern" to str()),"pattern")
        "search_text"->obj(mapOf("path" to str(),"text" to str()),"text")
        "run_command","build","test"->obj(mapOf("command" to str(),"cwd" to str(),"timeout_seconds" to integer(1,600)),"command")
        "git"->obj(mapOf("args" to mapOf("type" to "array","items" to str()),"cwd" to str()),"args")
        "make_new_project"->obj(mapOf("name" to str(),"path" to str(),"repo" to str()),"name")
        "read_result"->obj(mapOf("id" to str(),"start_line" to integer(1,Int.MAX_VALUE),"lines" to integer()),"id")
        "list_attachments"->obj(emptyMap())
        "read_attachment"->obj(mapOf("message_id" to str(),"attachment_id" to str(),"start_line" to integer(1,Int.MAX_VALUE),"lines" to integer()),"message_id","attachment_id")
        "save_attachment"->obj(mapOf("message_id" to str(),"attachment_id" to str(),"path" to str()),"message_id","attachment_id","path")
        else->error("Unregistered agent function: $name")
    }
    private fun description(name:String)=when(name) {
        "search"->"Find numbered source matches, or files:true to list names. Use before reading an unknown location. Omit type for arbitrary files. files:true is ONLY for name listings, never combine it with pattern."
        "read_file"->"Read exact current UTF-8 source, required before edits. Returns verbatim text; reread after editing."
        "edit_file"->"Change only a requested portion, preserving unrelated bytes. Use exact find/replace OR line range OR insert_after_line; read first."
        "write_file"->"Create a new UTF-8 file; existing-file partial edits must use edit_file."
        "sh"->"Restricted read-only project commands, not an OS shell. Use for git diff/status, ls, grep, sed -n; cannot write."
        else->"${name.replace('_',' ')} inside the granted permissions. Execution requires backend validation and any required human approval."
    }
    fun definitions(system:String):List<Map<String,Any>> {
        fun fn(name:String,description:String,parameters:Map<String,Any>)=mapOf<String,Any>("type" to "function","function" to mapOf("name" to name,"description" to description,"parameters" to parameters))
        val available=ProjectAgentTools.allowed(tier(system)).sorted()
        val requirement=obj(mapOf("id" to str(),"status" to mapOf("type" to "string","enum" to listOf("done","blocked")),"evidence_ids" to mapOf("type" to "array","items" to str()),"evidence" to str()),"id","status","evidence_ids","evidence")
        return names(system).sorted().map {fn(it,description(it),schema(it))}+listOf(
            fn("select_tools","Select up to two additional functions for the next step, replacing the previous selection. This grants no permission. Other available functions: ${available.filter {it !in core}.joinToString(", ")}",obj(mapOf("names" to mapOf("type" to "array","items" to mapOf("type" to "string","enum" to available),"maxItems" to 2),"plan" to mapOf("type" to "array","items" to str(),"maxItems" to 12)),"names")),
            fn("report_task","Report verified completion, a real blocker, or a necessary question. Close each requirement using matching actual receipt IDs; never invent execution.",obj(mapOf("message" to str(),"status" to mapOf("type" to "string","enum" to listOf("completed","blocked","clarify")),"requirements" to mapOf("type" to "array","items" to requirement,"maxItems" to 16)),"message","status","requirements")))
    }
    fun overhead(system:String)=ProjectAgentContext.estimatedTokens(mapper.writeValueAsString(definitions(system)))
    fun wire(messages:List<ProjectAgentMessage>):List<JsonNode> = messages.map {message->
        message.native?.let {mapper.readTree(it)} ?: mapper.valueToTree(mapOf("role" to message.role,"content" to message.content))
    }
    fun assistant(content:String,calls:List<JsonNode>,thinking:String=""):String=mapper.writeValueAsString(mapOf("role" to "assistant","content" to content,"tool_calls" to calls)+if(thinking.isNotEmpty())mapOf("thinking" to thinking)else emptyMap())
    fun result(name:String,text:String):String=mapper.writeValueAsString(mapOf("role" to "tool","tool_name" to name,"content" to text))
    fun callName(native:String?):String?=native?.let {runCatching {mapper.readTree(it).path("tool_calls").firstOrNull()?.path("function")?.path("name")?.asText()}.getOrNull()}
    /** No fallback parses prose/JSON content as a tool call. Only provider message.tool_calls authorizes a proposal. */
    fun reply(content:String,calls:List<JsonNode>):String {
        if(calls.size>1)return mapper.writeValueAsString(mapOf("message" to "한 번에 도구 하나만 요청하세요. 이 응답의 도구는 실행하지 않았습니다.","status" to "needs_tool","tool" to null))
        val fn=calls.singleOrNull()?.path("function") ?: return mapper.writeValueAsString(mapOf("message" to content.ifBlank {"빈 도구 응답입니다. 실제 함수를 요청하거나 확인한 결과를 보고해야 합니다."},"status" to if(content.isBlank())"needs_tool"else "completed","tool" to null))
        val name=fn.path("name").asText();val args=fn.path("arguments")
        require(args.isObject) {"Ollama function arguments must be an object"}
        return when(name) {
            "report_task"->mapper.writeValueAsString(mapOf("message" to args.path("message").asText(),"status" to args.path("status").asText(),"requirements" to args.path("requirements"),"tool" to null))
            "select_tools"->mapper.writeValueAsString(mapOf("message" to "다음 단계의 도구를 선택합니다.","status" to "needs_tool","select_tools" to args.path("names"),"plan" to args.path("plan"),"tool" to null))
            else->mapper.writeValueAsString(mapOf("message" to content.ifBlank {"$name 도구를 요청합니다."},"status" to "needs_tool","tool" to mapOf("name" to name,"arguments" to args)))
        }
    }
}
