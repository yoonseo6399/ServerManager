package local.servermanager

/** Validated on both sides of the runner protocol; model text cannot enlarge the execution window. */
data class LocalContextBudget(val window:Int=8192,val output:Int=4096,@get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) val allowCriticalMemory:Boolean=false,val requestTimeoutSeconds:Int=1200,@get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) val experimentalKvCompression:Boolean=false) {
    init {require(requestTimeoutSeconds in 60..14400) {"모델 요청 제한은 1~240분입니다."};require(window in WINDOWS && output in 512..4096 && window-output>=4096) {"실행 창은 8192/16384/32768/65536, 출력 예약은 512~4096 토큰입니다."}}
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val input:Int get()=minOf(when(window){8192->3600;16384->11000;else->window-output-256},window-output-256)
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val warning:String get()=if(window>=16384)"${window/1024}K는 실험 설정입니다. 실제 실행 가능성은 미검증이며 메모리 압력·스왑이 증가할 수 있습니다. 압력·스왑 증가량을 확인하세요. 실행창과 실제 보존 문맥은 다릅니다." else ""
    fun helper()=copy(output=1024)
    companion object {val WINDOWS=setOf(8192,16384,32768,65536);val DEFAULT=LocalContextBudget()}
}
