package local.servermanager

/** Due requests are durably claimed before dispatch. A crash may omit a run, never silently duplicate it. */
internal class ProjectAgentScheduleDispatcher(private val schedules:ProjectAgentSchedules,private val connected:()->Boolean,
    private val busy:(String)->Boolean,private val submit:((()->Unit)->Unit),private val execute:(ProjectAgentSchedule)->Boolean,
    private val failed:(ProjectAgentSchedule,Exception)->Unit={_,_->}) {
    fun tick() {
        if(!connected())return
        schedules.due().forEach {task->
            if(busy(task.channel))return@forEach
            val claimed=schedules.claim(task.id) ?: return@forEach
            try {submit {
                try {val success=execute(claimed);schedules.finish(claimed.id,if(success)"DONE"else "FAILED",if(success)""else "작업이 중단됐습니다. 채널 결과를 확인하세요.")}
                catch(e:Exception){schedules.finish(claimed.id,"FAILED",(e.message ?: "예약 실행 오류").take(500));failed(claimed,e)}
            }}catch(e:Exception){schedules.finish(claimed.id,"FAILED","작업 실행기를 시작하지 못했습니다.");failed(claimed,e)}
        }
    }
}
