package local.servermanager

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity

class SaveMainAgentSettings(val config:MainAgentConfig,val key:String?=null)
@RestController
@RequestMapping("/api/admin/main-agent")
class MainAgentSettingsApi(manager:ServerManager) {
    private val settings=MainAgentSettings(manager.root.resolve("security/agent"))
    @GetMapping fun view()=ResponseEntity.ok().header("Cache-Control","no-store").body(mapOf("config" to settings.config(),"keyConfigured" to !settings.key().isNullOrBlank(),"keys" to settings.keyOptions(),"ready" to settings.ready(settings.config())))
    @PutMapping fun save(@RequestBody body:SaveMainAgentSettings,request:HttpServletRequest):Map<String,Boolean> {
        freshDesktop(request);settings.save(body.config,body.key);return mapOf("saved" to true)
    }
}
