package local.servermanager

/** Verbatim excerpts, never model-written summaries. Exact source/edit/answer tools keep their own paging. */
internal object PiToolOutput {
    const val DEFAULT=2400
    const val MIN=512
    const val MAX=8000
    private val exact=setOf("read","edit","write","read_file","edit_file","write_file","read_result","read_attachment","read_chat_history","ask_user")
    fun limited(name:String)=name !in exact
    fun live(text:String,limit:Int):String {
        if(text.length<=limit)return text
        val note="[실시간 출력 일부 발췌 · 완료 후 원문 파일/보관 ID 제공]\n";val marker="\n[… 중간 생략 …]\n";val n=limit-note.length-marker.length
        return note+text.take(n*2/3)+marker+text.takeLast(n-n*2/3)
    }
    fun preview(text:String,id:String,limit:Int):String {
        val header="[원문 발췌 · 전체 ${text.length}자 · 보관 ID $id · 24시간/채널별 최근20개 보관. read_result {id:\"$id\",start_line:1,lines:40}로 이어 읽으세요. 요약 아님.]\n"
        val marker="\n[… 중간 생략 …]\n"
        val available=(limit-header.length-marker.length).coerceAtLeast(0)
        return header+text.take(available*2/3)+marker+text.takeLast(available-available*2/3)
    }
}
