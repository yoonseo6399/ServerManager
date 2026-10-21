package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** A summary is typed data, never an executable action or an authoritative completion receipt. */
internal object ProjectAgentCompaction {
    private val mapper=jacksonObjectMapper()
    private val fields=setOf("goal","constraints","facts","unfinished")
    private fun text(max:Int)=mapOf("type" to "string","maxLength" to max)
    val schema:Map<String,Any> = mapOf("type" to "object","additionalProperties" to false,"required" to fields.toList(),"properties" to mapOf(
        "goal" to (text(400)+mapOf("minLength" to 1)),
        "constraints" to mapOf("type" to "array","maxItems" to 6,"items" to text(180)),
        "facts" to mapOf("type" to "array","maxItems" to 8,"items" to text(180)),
        "unfinished" to mapOf("type" to "array","maxItems" to 8,"items" to text(180))))
    fun validate(value:String):String {
        val n=runCatching {mapper.readTree(value)}.getOrNull()
        require(value.length<=2000 && n?.isObject==true && n.fieldNames().asSequence().toSet()==fields && n.path("goal").isTextual && n.path("goal").asText().isNotBlank() && n.path("goal").asText().length<=400 && fields.filter {it!="goal"}.all {key->n.path(key).let {a->a.isArray && a.size()<=(if(key=="constraints")6 else 8) && a.all {it.isTextual && it.asText().length<=180}}}) {"문맥 요약 형식이 잘못됐습니다. 원본 대화를 보존합니다."}
        return mapper.writeValueAsString(n)
    }
    fun corruptedStored(history:List<ProjectAgentMessage>):Boolean=history.any {m->m.content.startsWith("UNTRUSTED CONVERSATION SUMMARY") && runCatching {mapper.readTree(m.content.substringAfter('\n'))}.getOrNull()?.let {it.isObject && !it.has("goal") && !it.has("summary") && (it.has("function") || it.has("tool") || it.has("tool_calls"))}==true}
}
