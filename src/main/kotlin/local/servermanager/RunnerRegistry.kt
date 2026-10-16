package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.io.path.*

data class RunnerDevice(val id:String,val name:String,val platform:String,val registeredBy:String,val publicKey:String,
    val createdAt:String,var version:String="unknown",var enabled:Boolean=true,var allowedInstanceIds:Set<String> = emptySet())
data class RunnerView(val id:String,val name:String,val platform:String,val registeredBy:String,val createdAt:String,
    val version:String,val enabled:Boolean,val allowedInstanceIds:Set<String>,val online:Boolean,val lastSeen:String?,val update:RunnerUpdateStatus?=null,val remoteUpdateSupported:Boolean=false,val registeredByName:String="",val canGrant:Boolean=false,val tcpRelaySupported:Boolean=false)
data class RunnerPairRequest(val code:String,val publicKey:String,val platform:String,val version:String)
data class RunnerEnrollmentRequest(val name:String)
private data class PendingRunner(val codeHash:String,val userId:String,val name:String,val expiresAt:Long)

@Service
class RunnerRegistry(private val manager:ServerManager,private val auth:AuthStore) {
    private val mapper=jacksonObjectMapper()
    private val random=SecureRandom()
    private val directory=manager.root.resolve("security/runners")
    private val file=directory.resolve("devices.json")
    private val devices:MutableMap<String,RunnerDevice>
    private val pending=mutableMapOf<String,PendingRunner>()
    private val heartbeat=mutableMapOf<String,Long>()
    private val nonces=mutableMapOf<String,Long>()
    private val updates=mutableMapOf<String,RunnerUpdateStatus>()
    private val tcpRelays=mutableSetOf<String>()
    private val updateDetails=mutableMapOf<String,UpdateDetail>()
    @Synchronized fun updateDetail(id:String,detail:UpdateDetail){if(active(id)!=null)updateDetails[id]=detail}
    @Synchronized fun updateDetailFor(id:String)=updateDetails[id]
    @Synchronized fun tcpRelayCapability(id:String,enabled:Boolean) {if(enabled && active(id)!=null)tcpRelays.add(id) else tcpRelays.remove(id)}
    @Synchronized fun tcpRelaySupported(id:String)=id in tcpRelays && online(id)
    init {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(file))
        directory.createDirectories()
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        devices=if(file.exists()) mapper.readValue(file.readText()) else mutableMapOf()
    }
    private fun token():String=ByteArray(32).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun hash(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun persist() {
        val tmp=Files.createTempFile(directory,".devices-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try { tmp.writeBytes(mapper.writeValueAsBytes(devices));Files.move(tmp,file,ATOMIC_MOVE,REPLACE_EXISTING) }
        finally { tmp.deleteIfExists() }
    }
    @Synchronized fun enroll(userId:String,name:String):String {
        require(auth.account(userId)?.enabled==true)
        require(name.isNotBlank() && name.length<=80) { "실행기 이름은 1~80자입니다" }
        val now=System.currentTimeMillis()
        pending.entries.removeIf { it.value.expiresAt<now }
        require(pending.values.count { it.userId==userId }<3) { "진행 중인 실행기 등록은 3개까지입니다" }
        val code=token()
        pending[hash(code)]=PendingRunner(hash(code),userId,name,now+5*60_000)
        return code
    }
    @Synchronized fun pair(request:RunnerPairRequest):RunnerDevice {
        require(request.platform in setOf("MAC","WINDOWS")) { "Mac 또는 Windows 실행기를 사용하세요" }
        require(request.version.length in 1..80 && request.version.matches(Regex("[A-Za-z0-9_.-]+")))
        val bytes=runCatching { Base64.getUrlDecoder().decode(request.publicKey) }.getOrNull()
        require(bytes!=null && bytes.size in 40..150) { "기기 공개키를 확인하세요" }
        require(runCatching { KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(bytes)) }.isSuccess) { "기기 공개키를 확인하세요" }
        val invite=pending.remove(hash(request.code)) ?: throw IllegalArgumentException("등록 코드가 없거나 이미 사용되었습니다")
        require(invite.expiresAt>=System.currentTimeMillis() && auth.account(invite.userId)?.enabled==true) { "등록 코드가 만료되었습니다" }
        val device=RunnerDevice(UUID.randomUUID().toString(),invite.name,request.platform,invite.userId,request.publicKey,Instant.now().toString(),request.version)
        devices[device.id]=device;persist();return device
    }
    @Synchronized fun device(id:String):RunnerDevice?=devices[id]?.copy(allowedInstanceIds=devices[id]!!.allowedInstanceIds.toSet())
    @Synchronized fun active(id:String):RunnerDevice?=device(id)?.takeIf { it.enabled && auth.account(it.registeredBy)?.enabled==true }
    fun verify(id:String,message:ByteArray,signature:String):Boolean {
        val device=active(id) ?: return false
        return runCatching {
            val key=KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(device.publicKey)))
            Signature.getInstance("Ed25519").apply { initVerify(key);update(message) }.verify(Base64.getUrlDecoder().decode(signature))
        }.getOrDefault(false)
    }
    fun acceptConnection(id:String,timestamp:Long,nonce:String,signature:String):Boolean=acceptPurpose(id,timestamp,nonce,signature,"sm-runner-v1")
    fun acceptUdpConnection(id:String,timestamp:Long,nonce:String,signature:String):Boolean=acceptPurpose(id,timestamp,nonce,signature,"sm-runner-udp-v1")
    @Synchronized private fun acceptPurpose(id:String,timestamp:Long,nonce:String,signature:String,purpose:String):Boolean {
        val now=System.currentTimeMillis()
        nonces.entries.removeIf { it.value<now-120000 }
        if(nonces.size>=10000)return false
        if(kotlin.math.abs(now-timestamp)>60000 || nonce.length !in 20..100 || !nonce.matches(Regex("[A-Za-z0-9_-]+")) || nonces.containsKey("$id:$nonce")) return false
        val challenge="$purpose\n$id\n$timestamp\n$nonce\n".toByteArray()
        if(!verify(id,challenge,signature)) return false
        nonces["$id:$nonce"]=now
        return true
    }
    @Synchronized fun seen(id:String,version:String) {
        if(active(id)==null)return
        heartbeat[id]=System.currentTimeMillis()
        devices[id]?.let { if(it.version!=version && version.length in 1..80) { it.version=version;updates.remove(id);persist() } }
    }
    @Synchronized fun disconnected(id:String) { heartbeat.remove(id);tcpRelays.remove(id) }
    @Synchronized fun online(id:String)=active(id)!=null && (heartbeat[id]?.let { System.currentTimeMillis()-it<45_000 } ?: false)
    @Synchronized fun views(userId:String):List<RunnerView> {
        val user=auth.account(userId) ?: return emptyList()
        return devices.values.filter { user.managesAllServers() || it.registeredBy==userId || it.allowedInstanceIds.any { id -> user.permissions[id]=="ADMIN" } }
            .map { RunnerView(it.id,it.name,it.platform,it.registeredBy,it.createdAt,it.version,it.enabled,it.allowedInstanceIds,online(it.id),heartbeat[it.id]?.let { ms->Instant.ofEpochMilli(ms).toString() },updates[it.id],remoteUpdateSupported(it.id),auth.account(it.registeredBy)?.displayName ?: "삭제된 계정",(it.registeredBy==userId || user.managesAllServers()),tcpRelaySupported(it.id)) }
    }
    @Synchronized fun connected(id:String){updates.remove(id)}
    @Synchronized fun remoteUpdateSupported(id:String)=active(id)!=null && updates[id]?.phase?.let {it!="UNSUPPORTED"}==true
    @Synchronized fun updateStatus(id:String,status:RunnerUpdateStatus){if(active(id)!=null)updates[id]=status.validated()}
    @Synchronized fun requestUpdate(userId:String,id:String,pause:Boolean) {
        val user=auth.account(userId);require(user?.enabled==true && (user.owner || devices[id]?.registeredBy==userId)) {"기기 등록자 또는 소유자만 실행기를 업데이트할 수 있습니다"}
        check(active(id)!=null) {"폐기된 실행기입니다"}
        check(online(id)) {"실행기를 온라인으로 연결하세요"}
        check(updates[id]?.phase!="UNSUPPORTED") {"서명 업데이트를 지원하는 설치판으로 실행하세요"}
        check(remoteUpdateSupported(id)) {"이 실행기는 최초 한 번 자동 업데이트 또는 새 설치판 적용이 필요합니다 (0.4.4 이상)"}
        if(pause)check(manager.list().none {(it["config"] as Instance).runnerId==id && it["diagnosticActive"]==true}) {"모드 검사를 마친 뒤 전송 중지 업데이트를 요청하세요"}
        updates[id]=RunnerUpdateStatus("REQUESTED")
    }
    @Synchronized fun allow(userId:String,id:String,instanceId:String,allowed:Boolean) {
        val device=devices[id] ?: error("실행기를 찾을 수 없습니다")
        val user=auth.account(userId)
        require(user?.enabled==true && (device.registeredBy==userId || user.managesAllServers())) { "기기 등록자·전체 관리자·소유자만 서버별 사용을 허용할 수 있습니다" }
        manager.instance(instanceId)
        require(auth.allowed(userId,instanceId,false)) { "접근할 수 있는 서버만 실행기에 허용할 수 있습니다" }
        if(!allowed && manager.instance(instanceId).runnerId==id)
            check(manager.view(instanceId)["state"] in setOf("NEW","STOPPED","FAILED","IMPORT_FAILED")) { "서버가 완전히 종료된 뒤 실행기 허용을 해제하세요" }
        device.allowedInstanceIds=if(allowed) device.allowedInstanceIds+instanceId else device.allowedInstanceIds-instanceId
        persist()
    }
    @Synchronized fun revoke(userId:String,id:String) {
        val device=devices[id] ?: error("실행기를 찾을 수 없습니다")
        require(device.registeredBy==userId || auth.account(userId)?.owner==true) { "등록자 또는 소유자만 실행기를 폐기할 수 있습니다" }
        check(manager.list().none { row ->
            val instance=row["config"] as Instance
            instance.runnerId==id && row["state"] !in setOf("NEW","STOPPED","FAILED","IMPORT_FAILED")
        }) { "이 실행기에서 서버가 실행 중이거나 상태 확인이 필요합니다" }
        device.enabled=false;heartbeat.remove(id);persist()
    }
    @Synchronized fun removeInstance(id:String) {
        devices.values.forEach { it.allowedInstanceIds=it.allowedInstanceIds-id }
        persist()
    }
    @Synchronized fun canAssign(userId:String,instanceId:String,runnerId:String):Boolean {
        val user=auth.account(userId) ?: return false
        return (user.managesAllServers() || user.permissions[instanceId]=="ADMIN") && active(runnerId)?.allowedInstanceIds?.contains(instanceId)==true
    }
}

@RestController
@RequestMapping("/api/runners")
class RunnerApi(private val registry:RunnerRegistry,private val auth:AuthStore,private val audit:AuditLog,private val gateway:RunnerGateway) {
    private fun fresh(req:HttpServletRequest) {
        val verified=req.getSession(false)?.getAttribute("sm.verified") as? Long ?: 0
        if(System.currentTimeMillis()-verified>300000) throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"패스키로 다시 인증한 뒤 진행하세요")
    }
    @GetMapping fun list(authentication:Authentication)=registry.views(authentication.name)
    @PostMapping("/{id}/update") fun update(@PathVariable id:String,@RequestBody request:RunnerUpdateRequest,authentication:Authentication,req:HttpServletRequest) {
        fresh(req);registry.requestUpdate(authentication.name,id,request.pauseTransfer)
        try {check(gateway.requestUpdate(id,request.pauseTransfer)) {"실행기 연결이 끊겼습니다. 다시 요청하세요"}}
        catch(e:Exception){registry.updateStatus(id,RunnerUpdateStatus("FAILED"));throw e}
        audit.append(authentication.name,"runner signed update requested $id",200,"web")
    }
    @PostMapping("/enroll") fun enroll(@RequestBody request:RunnerEnrollmentRequest,authentication:Authentication,req:HttpServletRequest):Map<String,Any> {
        fresh(req);val code=registry.enroll(authentication.name,request.name)
        audit.append(authentication.name,"runner enroll",200,"web")
        return mapOf("code" to code,"expiresInSeconds" to 300)
    }
    @PostMapping("/pair") fun pair(@RequestBody request:RunnerPairRequest):Map<String,String> {
        val device=registry.pair(request)
        audit.append(device.registeredBy,"runner paired ${device.id}",200,"runner")
        return mapOf("id" to device.id,"name" to device.name)
    }
    @PutMapping("/{id}/allow/{instanceId}") fun allow(@PathVariable id:String,@PathVariable instanceId:String,authentication:Authentication,req:HttpServletRequest) {
        fresh(req);registry.allow(authentication.name,id,instanceId,true)
    }
    @DeleteMapping("/{id}/allow/{instanceId}") fun disallow(@PathVariable id:String,@PathVariable instanceId:String,authentication:Authentication,req:HttpServletRequest) {
        fresh(req);registry.allow(authentication.name,id,instanceId,false)
    }
    // Leave /connect exclusively to the WebSocket handler. An unconstrained
    // MVC DELETE /{id} also matches GET /connect and returns 405 before upgrade.
    @DeleteMapping("/{id:[a-f0-9-]{36}}") fun revoke(@PathVariable id:String,authentication:Authentication,req:HttpServletRequest) {
        fresh(req);registry.revoke(authentication.name,id)
    }
}

/** Read-only update progress for the management web: per component current/target, stage, wait reason, error and a durable log. */
@RestController
@RequestMapping("/api/runners")
class RunnerUpdateStatusApi(private val registry:RunnerRegistry) {
    @GetMapping("/{id:[a-f0-9-]{36}}/update-status")
    fun status(@PathVariable id:String,@RequestParam(defaultValue="0") after:Long,authentication:Authentication):Map<String,Any?> {
        // Same visibility rule as the runner list: only runners the caller may see.
        if(registry.views(authentication.name).none {it.id==id})throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val detail=registry.updateDetailFor(id)
        return mapOf("available" to (detail!=null),
            "components" to (detail?.components ?: emptyList()),
            "log" to (detail?.log?.filter {it.seq>after} ?: emptyList()),
            "hostMode" to detail?.hostMode,
            "note" to if(detail==null)"실행기가 아직 상세 업데이트 상태를 보고하지 않았습니다 (구버전이거나 오프라인)" else null)
    }
}
