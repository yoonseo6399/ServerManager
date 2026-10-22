package local.servermanager

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import org.springframework.security.core.Authentication
import java.nio.file.Path
import java.util.UUID
object ServiceBoot {val id=UUID.randomUUID().toString()}

@Component
class DesktopRuntime {
    val supervised=System.getenv("SERVER_MANAGER_SUPERVISED")=="1"
    val home:Path=Path.of(System.getenv("SERVER_MANAGER_HOME") ?: System.getProperty("user.home")+"/Library/Application Support/ServerManager")
}
data class ServiceRestartStatus(val supported:Boolean,val bootId:String,val stage:String,val canRestart:Boolean,val reason:String?=null,val message:String="")
data class RunnerRestartStatus(val stage:String,val message:String,val requestId:String,val requestedAt:Long,val previousBootId:String?=null)
fun restartReason(reason:String?)=when(reason) {
    null -> "재시작할 수 있습니다"
    "UNSUPERVISED" -> "설치 앱의 실행기를 통해 시작해야 원격 재시작할 수 있습니다"
    "UPDATE_PENDING" -> "대기 중인 업데이트나 저장 위치 변경을 먼저 완료하세요"
    "RELAY_STREAMS_ACTIVE" -> "제어 프로그램이 중계하는 플레이어 연결이 남아 있습니다"
    "LEGACY_GAME_RUNNING" -> "상주 서비스가 관리하지 않는 게임이 실행 중입니다"
    "HOST_UNREACHABLE" -> "게임 상주 서비스 연결을 확인할 수 없습니다"
    "SERVER_PREPARING" -> "서버 시작·종료·설치 또는 모드 작업을 마친 뒤 다시 시도하세요"
    "TRANSFER_ACTIVE","DIRTY_HANDOFF_PENDING" -> "파일 전송·인계가 끝난 뒤 다시 시도하세요"
    "HOST_REPLACING" -> "상주 서비스 교체가 진행 중입니다"
    "BUSY" -> "이미 재시작을 요청했습니다"
    else -> "지금은 재시작할 수 없습니다: $reason"
}

@RestController
@RequestMapping("/api/admin/service-restart")
class ServiceRestartApi(private val desktop:DesktopLifecycle,private val audit:AuditLog) {
    @GetMapping fun status()=desktop.serviceRestartStatus()
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) fun restart(authentication:Authentication,req:HttpServletRequest):ServiceRestartStatus {
        freshDesktop(req)
        val value=desktop.requestServiceRestart()
        audit.append(authentication.name,"manager service restart requested",202,"web")
        return value
    }
}
