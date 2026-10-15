package local.servermanager

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity

// No generated toString: request carries a secret.
class SaveAgentPoolKey(val id:String,val project:String,val key:String?=null,val rpm:Int=5,val tpm:Long=250000,val rpd:Int=20,val enabled:Boolean=true)

@RestController
@RequestMapping("/api/admin/agent-keys")
class AgentKeyPoolApi(manager:ServerManager) {
    private val store=GeminiKeyPoolStore(manager.root.resolve("security/agent"),{System.getenv("GEMINI_API_KEY")?.takeIf {it.isNotBlank()} ?: AgentApiKeyStore(manager.root.resolve("security/agent")).read()})
    private val model=System.getenv("LUONNOTAR_AGENT_MODEL") ?: "gemini-3.8-flash"
    @GetMapping fun list()=ResponseEntity.ok().header("Cache-Control","no-store").body(mapOf("keys" to store.configs(),"usage" to GeminiQuotaTracker(store,model).status(store.configs()),"localUsageOnly" to true))
    @PutMapping fun save(@RequestBody body:SaveAgentPoolKey,request:HttpServletRequest):Map<String,Boolean> {
        freshDesktop(request)
        val secret=body.key?.takeIf {it.isNotBlank()} ?: store.existingSecret(body.id)
        require(!secret.isNullOrBlank()) { "새 키의 API 키 값을 입력하세요." }
        store.upsert(PoolKeyConfig(body.id,body.project,body.rpm,body.tpm,body.rpd,body.enabled),secret)
        return mapOf("saved" to true)
    }
    @DeleteMapping("/{id}") fun remove(@PathVariable id:String,request:HttpServletRequest):Map<String,Boolean> {
        freshDesktop(request);store.remove(id);return mapOf("removed" to true)
    }
}
