package local.servermanager

/** Validated on both sides of the runner protocol; model text cannot enlarge the execution window. */
data class LocalContextBudget(val window:Int=8192,val output:Int=4096) {
    init {require(window in setOf(8192,16384) && output in 512..4096 && window-output>=4096) {"실행 창은 8192/16384, 출력 예약은 512~4096 토큰입니다."}}
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val input:Int get()=minOf(if(window==8192)3600 else 11000,window-output-256)
    @get:com.fasterxml.jackson.annotation.JsonIgnore
    val warning:String get()=if(window==16384)"16K는 메모리 압력·스왑이 증가할 수 있습니다. 현재 Mac의 전체 16K 입력은 미검증입니다." else ""
    fun helper()=copy(output=1024)
    companion object {val DEFAULT=LocalContextBudget()}
}
