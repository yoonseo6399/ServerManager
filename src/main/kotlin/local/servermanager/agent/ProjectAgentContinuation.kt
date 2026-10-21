package local.servermanager

/** Carry original human intent for short follow-ups; this never grants or reuses an approval. */
internal object ProjectAgentContinuation {
    fun matches(text:String):Boolean {
        val t=text.trim()
        return t.matches(Regex("[?？!！.。]+")) || Regex("^(?:이어서|계속|방금|그거|그것|그냥|다시|아까|왜(?:요|안|\\s)|continue|resume)(?:.*)$",RegexOption.IGNORE_CASE).matches(t) && t.length<=200
    }
}
