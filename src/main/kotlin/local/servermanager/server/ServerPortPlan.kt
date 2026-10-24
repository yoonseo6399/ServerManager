package local.servermanager

/** Select a stable internal port; never move another server or change a public relay port. */
object ServerPortPlan {
    fun available(preferred:Int,reserved:Set<Int>):Int {
        require(preferred in 1024..65535) {"포트 범위는 1024~65535입니다"}
        for(offset in 0 until 64512) {
            val candidate=1024+(preferred-1024+offset)%64512
            if(candidate !in reserved)return candidate
        }
        error("이 실행기에서 사용할 수 있는 내부 게임 포트가 없습니다")
    }
}
