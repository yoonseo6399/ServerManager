package local.servermanager

/** Stable IDs are persisted and sent over the runner protocol; old 14b/30b IDs remain valid. */
object LocalModels {
    data class Model(val id:String,val label:String,val tag:String,val supportsThinking:Boolean,val environment:String)
    val all=listOf(
        Model("14b","Qwen3 14B","qwen3:14b",true,"OLLAMA_MODEL_14B"),
        Model("30b","Qwen3 30B","qwen3:30b",true,"OLLAMA_MODEL_30B"),
        Model("qwen3-coder-30b","Qwen3-Coder 30B","qwen3-coder:30b",false,"OLLAMA_MODEL_CODER_30B"),
        Model("gemma4-12b","Gemma 4 12B","gemma4:12b",true,"OLLAMA_MODEL_GEMMA4_12B"),
        Model("gemma4-31b","Gemma 4 31B","gemma4:31b",true,"OLLAMA_MODEL_GEMMA4_31B"))
    fun get(id:String)=all.find {it.id==id} ?: throw IllegalArgumentException("지원하지 않는 로컬 모델입니다. 관리 앱과 추론 실행기를 업데이트하세요.")
    fun label(id:String)=get(id).label
    fun tag(id:String)=get(id).let {System.getenv(it.environment)?.takeIf(String::isNotBlank) ?: it.tag}
    fun thinking(id:String,requested:Boolean)=get(id).supportsThinking && requested
}
