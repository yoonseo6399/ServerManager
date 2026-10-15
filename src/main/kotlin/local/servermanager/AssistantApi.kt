package local.servermanager

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

class AssistantCreate(val purpose:String="",val rootId:String="",val files:List<String> = emptyList())
class AssistantApproval(val token:String="",val state:String="")
@RestController
@RequestMapping("/api/admin/assistant")
class AssistantApi(private val harness:AssistantHarness,private val identity:AssistantIdentity,private val google:AssistantGoogle,manager:ServerManager) {
    private val qwen=QwenSettings(manager.root.resolve("security/agent"))
    private fun <T> errors(action:()->T):T=try {action()} catch(e:IllegalArgumentException) {throw ResponseStatusException(HttpStatus.BAD_REQUEST,e.message)} catch(e:IllegalStateException) {throw ResponseStatusException(HttpStatus.CONFLICT,e.message)}
    @PostMapping("/google") fun google(@RequestBody body:AssistantGoogleRequest,auth:Authentication)=errors {ResponseEntity.ok().header("Cache-Control","no-store").body(google.summarize(auth.name,body))}
    @GetMapping fun view(auth:Authentication)=errors {ResponseEntity.ok().header("Cache-Control","no-store").body(mapOf("policy" to harness.engine.policy(auth.name),"tasks" to harness.engine.list(auth.name),"aider" to mapOf("available" to false,"message" to "Aider는 자동 설치되지 않습니다. 현재는 기본 구조화 편집 엔진을 사용합니다.")))}
    @PutMapping("/policy") fun save(@RequestBody body:AssistantPolicy,auth:Authentication,request:HttpServletRequest)=errors {freshDesktop(request);harness.engine.savePolicy(auth.name,body)}
    @PostMapping("/tasks") fun create(@RequestBody body:AssistantCreate,auth:Authentication,request:HttpServletRequest)=errors {freshDesktop(request);harness.create(auth.name,body.purpose,body.rootId,body.files)}
    @GetMapping("/tasks/{id}") fun task(@PathVariable id:String,auth:Authentication)=errors {ResponseEntity.ok().header("Cache-Control","no-store").body(harness.engine.view(auth.name,id))}
    @PostMapping("/tasks/{id}/approve") fun approve(@PathVariable id:String,@RequestBody body:AssistantApproval,auth:Authentication,request:HttpServletRequest)=errors {freshDesktop(request);harness.engine.approve(auth.name,id,body.token,body.state)}
    @PostMapping("/tasks/{id}/cancel") fun cancel(@PathVariable id:String,auth:Authentication)=errors {harness.engine.cancel(auth.name,id)}
    @DeleteMapping("/tasks/{id}") fun forget(@PathVariable id:String,auth:Authentication)=errors {harness.engine.forget(auth.name,id);mapOf("removed" to true)}
    @PostMapping("/discord-link") fun link(auth:Authentication,request:HttpServletRequest)=errors {freshDesktop(request);ResponseEntity.ok().header("Cache-Control","no-store").body(mapOf("code" to identity.code(auth.name),"seconds" to 300))}
    @DeleteMapping("/discord-link") fun unlink(auth:Authentication,request:HttpServletRequest)=errors {freshDesktop(request);identity.revoke(auth.name);mapOf("removed" to true)}
}
