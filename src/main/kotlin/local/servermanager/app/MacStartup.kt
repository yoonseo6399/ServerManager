package local.servermanager

import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.*

class StartupSettings(private val home:Path,private val app:Path?,private val data:Path,private val mac:Boolean) {
    private val file=home.resolve("Library/LaunchAgents/local.servermanager.plist")
    private fun launcher()=app?.resolve("Contents/MacOS/ServerManager")
    fun status()=mapOf("supported" to (mac && launcher()?.isExecutable()==true),"enabled" to file.exists(),"requiresLogin" to true,"dataDirectory" to data.toString())
    @Synchronized fun setEnabled(enabled:Boolean):Map<String,Any> {
        check(mac && launcher()?.isExecutable()==true) { "자동 시작은 설치된 macOS 앱에서 설정할 수 있습니다" }
        if(enabled) {
            file.parent.createDirectories()
            val temp=Files.createTempFile(file.parent,"servermanager-",".plist")
            try {
                temp.writeText(plist(launcher()!!,data));Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"))
                Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.deleteIfExists() }
        } else file.deleteIfExists()
        return status()
    }
    companion object {
        private fun xml(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
        fun plist(launcher:Path,data:Path):String="""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>local.servermanager</string>
<key>ProgramArguments</key><array><string>${xml(launcher.toString())}</string><string>--service</string></array>
<key>EnvironmentVariables</key><dict><key>SERVER_MANAGER_HOME</key><string>${xml(System.getenv("SERVER_MANAGER_HOME") ?: System.getProperty("user.home")+"/Library/Application Support/ServerManager")}</string></dict>
<key>RunAtLoad</key><true/>
<key>ProcessType</key><string>Background</string>
<key>ExitTimeOut</key><integer>90</integer>
</dict></plist>
"""
    }
}
@Service
class MacStartup(manager:ServerManager):StartupSettingsProvider {
    override val settings=StartupSettings(Path.of(System.getProperty("user.home")),System.getenv("SERVER_MANAGER_APP_PATH")?.let { Path.of(it) },manager.root,System.getProperty("os.name").startsWith("Mac"))
}
interface StartupSettingsProvider { val settings:StartupSettings }
data class StartupRequest(val enabled:Boolean)
@RestController
@RequestMapping("/api/admin/startup")
class StartupApi(private val startup:MacStartup) {
    @GetMapping fun status()=startup.settings.status()
    @PutMapping fun update(@RequestBody body:StartupRequest,request:HttpServletRequest):Map<String,Any> {
        val at=request.getSession(false)?.getAttribute("sm.verified") as? Long ?: 0
        if(System.currentTimeMillis()-at>300000)throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"패스키로 다시 인증한 뒤 자동 시작 설정을 변경하세요")
        return startup.settings.setEnabled(body.enabled)
    }
}
