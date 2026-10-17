package local.servermanager

internal object ProjectAgentCompletion {
    private val promise=Regex("(?i)(확인|찾|읽|열|수정|생성|실행|검사|빌드|테스트|검색|저장|업로드|살펴|조사|고쳐|해결|불러|복제|설치)[^.!?\\n]{0,28}(볼게|드릴게|할게|하겠|보겠|드리겠|겠습니다)|(?:I(?:'ll| will)|let me)\\s+(?:read|check|find|run|build|test|fix|create|inspect|search|open|upload)")
    private val action=Regex("(?i)(파일|폴더|디렉토리|프로젝트|빌드|테스트|명령|서버|file|folder|build|test|[\\p{L}\\p{N}_-]+\\.[a-z0-9]{1,8})[^\\n]{0,100}(읽|확인|찾|수정|생성|만들|삭제|실행|내용|열어|보여|검사|검색|해줘|해주|돌려|read|check|find|run|fix|create)")
    fun needsContinuation(request:String,message:String,status:String,tools:Int):Boolean {
        if(promise.containsMatchIn(message))return true
        if(status=="needs_tool")return true
        if(status in setOf("clarify","blocked"))return false
        val honest=Regex("할 수 없|실행하지 않|실행하지 못|권한.*필요|승인.*필요|알려.*주세요|어느.*[?？]|어떤.*[?？]").containsMatchIn(message)
        return tools==0 && action.containsMatchIn(request) && !honest && !Regex("방법|어떻게|설명|예시").containsMatchIn(request)
    }
}

internal object ProjectAgentRawResult {
    /** Exact prefix, never semantic summarization. Omitted bytes must be obtained with a focused read. */
    fun fit(text:String,limit:Int=1100):String {
        if(ProjectAgentContext.estimatedTokens(text)<=limit)return text
        val out=StringBuilder()
        for(line in text.lineSequence()){
            if(ProjectAgentContext.estimatedTokens(out.toString()+line+"\n")>limit)break
            out.append(line).append('\n')
        }
        val partial=out.isEmpty()
        if(partial){var n=minOf(text.length,limit);while(n>0 && ProjectAgentContext.estimatedTokens(text.take(n))>limit)n--;out.append(text.take(n))}
        val next=Regex("(?m)^([0-9]+):").findAll(out).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.plus(if(partial)0 else 1)
        return out.toString()+"\n[원문 범위 제한: 요약하지 않음. "+(next?.let {"read_file/read_attachment start_line:$it 로 이어 읽으세요."} ?: "범위를 좁혀 다시 조회하세요.")+"]"
    }
}
