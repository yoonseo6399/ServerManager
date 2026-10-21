package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** [runnerId] null means automatic: the first opted-in runner, else the manager computer. [model] is the default for /local-llm. */
data class QwenConfig(val runnerId:String?=null,val model:String="14b",val requestTimeoutMinutes:Int=20)

class QwenSettings(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("qwen.json")
    private val executionFile=directory.resolve("qwen-execution.json")
    fun config():QwenConfig = runCatching {if(file.exists())mapper.readValue<QwenConfig>(file.readText()) else QwenConfig()}.getOrDefault(QwenConfig()).takeIf {valid(it)}.let {base->(base ?: QwenConfig()).copy(requestTimeoutMinutes=runCatching {if(executionFile.exists())mapper.readTree(executionFile.readText()).path("requestTimeoutMinutes").asInt(20).also {require(it in 1..240)}else 20}.getOrDefault(20))}
    fun valid(c:QwenConfig)=c.requestTimeoutMinutes in 1..240 && c.model in LocalLlm.SIZES && (c.runnerId==null || c.runnerId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
    fun save(c:QwenConfig) {
        require(valid(c)) {"로컬 모델·실행기와 추론 요청 제한(1~240분)을 확인하세요."}
        GeminiKeyPoolStore(directory).writePrivate(file,mapper.writeValueAsString(mapOf("runnerId" to c.runnerId,"model" to c.model)))
        GeminiKeyPoolStore(directory).writePrivate(executionFile,mapper.writeValueAsString(mapOf("requestTimeoutMinutes" to c.requestTimeoutMinutes)))
    }
}

data class QwenRunnerView(val id:String,val name:String,val online:Boolean,val capability:String)
class SaveQwenSettings(val runnerId:String?=null,val model:String="14b",val requestTimeoutMinutes:Int=20)
class QwenRunnerRequest(val runnerId:String="")

@RestController
@RequestMapping("/api/admin/qwen")
class QwenSettingsApi(private val manager:ServerManager,private val registry:RunnerRegistry,private val gateway:RunnerGateway) {
    private val settings=QwenSettings(manager.root.resolve("security/agent"))
    private fun runners(user:String)=registry.views(user).filter {it.enabled}.map {QwenRunnerView(it.id,it.name,it.online,gateway.llmCapability(it.id))}
    private fun view(user:String)=ResponseEntity.ok().header("Cache-Control","no-store").body(mapOf("config" to settings.config(),"runners" to runners(user),"agentEngine" to PiRuntime(manager.root.resolve("security/agent")).status(),"models" to LocalModels.all.map {mapOf("id" to it.id,"label" to it.label,"supportsThinking" to it.supportsThinking,"backend" to it.backend)}))
    private fun target(user:String,id:String):String {
        if(runners(user).none {it.id==id}) throw ResponseStatusException(HttpStatus.NOT_FOUND,"실행기를 찾을 수 없습니다.")
        return id
    }
    private fun <T> remote(block:()->T):T = try {block()} catch(e:IllegalStateException) {throw ResponseStatusException(HttpStatus.CONFLICT,e.message ?: "실행기 요청 실패")}

    @GetMapping fun get(authentication:Authentication)=view(authentication.name)

    /** Saving a different model also unloads the others on the chosen runner so two Qwen models are never resident at once. */
    @PutMapping fun save(@RequestBody body:SaveQwenSettings,request:HttpServletRequest,authentication:Authentication):Map<String,Any?> {
        freshDesktop(request)
        body.runnerId?.let {target(authentication.name,it)}
        val next=QwenConfig(body.runnerId?.takeIf {it.isNotBlank()},body.model,body.requestTimeoutMinutes)
        if(!settings.valid(next)) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"로컬 모델·실행기와 추론 요청 제한(1~240분)을 확인하세요.")
        val before=settings.config()
        settings.save(next)
        val unloaded=if(before.model!=next.model||before.runnerId!=next.runnerId) before.runnerId?.takeIf {gateway.llmReady(it)}?.let {runCatching {gateway.llmAdmin(it,"unload")}.isSuccess} else null
        return mapOf("saved" to true,"unloaded" to unloaded)
    }

    private fun admin(user:String,id:String,op:String):Any = if(id.isBlank())when(op){"probe"->LocalLlm().probe();"unload"->LocalLlm().unloadAll();"install-strata"->StrataRuntime.install();else->error("Unknown LLM operation")}else gateway.llmAdmin(target(user,id),op)
    @PostMapping("/probe") fun probe(@RequestBody body:QwenRunnerRequest,authentication:Authentication)=remote {admin(authentication.name,body.runnerId,"probe")}
    @PostMapping("/strata/install") fun installStrata(@RequestBody body:QwenRunnerRequest,request:HttpServletRequest,authentication:Authentication):Any {
        freshDesktop(request)
        return remote {admin(authentication.name,body.runnerId,"install-strata")}
    }

    @PostMapping("/unload") fun unload(@RequestBody body:QwenRunnerRequest,request:HttpServletRequest,authentication:Authentication):Any {
        freshDesktop(request)
        return remote {admin(authentication.name,body.runnerId,"unload")}
    }
}
