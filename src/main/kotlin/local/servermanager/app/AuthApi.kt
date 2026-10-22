package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.yubico.webauthn.*
import com.yubico.webauthn.data.*
import com.yubico.webauthn.data.ByteArray as WebBytes
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

data class RegistrationStart(val token:String?=null)
data class RegistrationFinish(val credential:JsonNode,val label:String="패스키")
data class LoginStart(val reauthenticate:Boolean=false)
data class LoginFinish(val credential:JsonNode)
data class InviteRequest(val name:String,val permissions:Map<String,String> = emptyMap(),val role:AccountRole=AccountRole.MEMBER)
data class AccessRequest(val enabled:Boolean,val permissions:Map<String,String>,val role:AccountRole?=null)
private data class RegistrationCeremony(val request:String,val userId:String,val invitationHash:String?,val at:Long)
private data class LoginCeremony(val request:String,val expectedUser:String?,val at:Long)

@RestController
@RequestMapping("/api")
class AuthApi(private val store:AuthStore,private val settings:SecuritySettings,private val contexts:HttpSessionSecurityContextRepository,private val csrf:HttpSessionCsrfTokenRepository,private val audit:AuditLog,private val manager:ServerManager) {
    private val mapper=jacksonObjectMapper()
    private val rp=RelyingParty.builder().identity(RelyingPartyIdentity.builder().id(settings.rpId).name("Server Manager").build())
        .credentialRepository(store).origins(setOf(settings.origin)).allowOriginPort(false).allowOriginSubdomain(false).build()

    private fun current():Account?=SecurityContextHolder.getContext().authentication?.name?.let { store.account(it) }?.takeIf { it.enabled }
    private fun signedIn():Account=current() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"로그인이 필요합니다")
    private fun fresh(req:HttpServletRequest) {
        signedIn()
        val at=req.getSession(false)?.getAttribute("sm.verified") as? Long ?: 0
        if(System.currentTimeMillis()-at>5*60*1000) throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"패스키로 다시 인증한 뒤 진행하세요")
    }
    private fun establish(user:Account,req:HttpServletRequest,res:HttpServletResponse) {
        req.getSession(true);req.changeSessionId()
        val session=req.getSession();session.maxInactiveInterval=30*60
        session.setAttribute("sm.user",user.id);session.setAttribute("sm.epoch",user.epoch);session.setAttribute("sm.started",System.currentTimeMillis());session.setAttribute("sm.verified",System.currentTimeMillis())
        val context=SecurityContextHolder.createEmptyContext()
        context.authentication=UsernamePasswordAuthenticationToken.authenticated(user.id,null,listOf(SimpleGrantedAuthority(if(user.owner)"ROLE_OWNER" else if(user.role==AccountRole.ADMIN)"ROLE_ADMIN" else "ROLE_MEMBER")))
        SecurityContextHolder.setContext(context);contexts.saveContext(context,req,res);csrf.saveToken(null,req,res)
    }
    @GetMapping("/auth/session") fun session(req:HttpServletRequest):Map<String,Any?> {
        val user=current();val token=req.getAttribute(CsrfToken::class.java.name) as CsrfToken
        return mapOf("bootId" to ServiceBoot.id,"appVersion" to javaClass.getResourceAsStream("/app-version.txt")!!.bufferedReader().use { it.readText().trim() },"user" to user?.let { mapOf("id" to it.id,"name" to it.displayName,"owner" to it.owner,"administrator" to (it.role==AccountRole.ADMIN),"role" to it.role,"permissions" to it.instancePermissions()) },"csrf" to token.token,"publicOrigin" to settings.publicOrigin,"origin" to settings.origin,"setupRequired" to !store.hasOwnerCredential())
    }
    @PostMapping("/auth/register/options") fun registration(@RequestBody body:RegistrationStart,req:HttpServletRequest):JsonNode {
        val invitation=body.token?.let { store.invitation(it.trim()) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"초대 코드가 잘못되었거나 만료되었습니다") }
        val user=if(invitation!=null) store.account(invitation.userId)!! else { fresh(req);signedIn() }
        val request=rp.startRegistration(StartRegistrationOptions.builder().user(UserIdentity.builder().name(user.id).displayName(user.displayName).id(WebBytes.fromBase64Url(user.handle)).build())
            .authenticatorSelection(AuthenticatorSelectionCriteria.builder().residentKey(ResidentKeyRequirement.REQUIRED).userVerification(UserVerificationRequirement.REQUIRED).build()).timeout(120000).build())
        req.getSession(true).setAttribute("sm.registration",RegistrationCeremony(request.toJson(),user.id,invitation?.hash,System.currentTimeMillis()))
        return mapper.readTree(request.toCredentialsCreateJson())
    }
    @PostMapping("/auth/register/finish") fun register(@RequestBody body:RegistrationFinish,req:HttpServletRequest,res:HttpServletResponse):Map<String,Boolean> {
        val session=req.getSession(false) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val ceremony=synchronized(session) { val saved=session.getAttribute("sm.registration") as? RegistrationCeremony;session.removeAttribute("sm.registration");saved } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST,"등록 요청을 다시 시작하세요")
        require(System.currentTimeMillis()-ceremony.at<180000) { "패스키 등록 요청이 만료되었습니다" }
        require(body.label.isNotBlank() && body.label.length<=80)
        if(ceremony.invitationHash==null) { fresh(req);require(signedIn().id==ceremony.userId) }
        val result=try {
            rp.finishRegistration(FinishRegistrationOptions.builder().request(PublicKeyCredentialCreationOptions.fromJson(ceremony.request)).response(PublicKeyCredential.parseRegistrationResponseJson(body.credential.toString())).build())
        } catch(e:Exception) { throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"패스키 등록 검증에 실패했습니다") }
        require(result.isUserVerified) { "사용자 확인이 필요합니다" }
        store.register(ceremony.userId,ceremony.invitationHash,Passkey(result.keyId.id.base64Url,ceremony.userId,result.publicKeyCose.base64Url,result.signatureCount,body.label,Instant.now().toString()))
        establish(store.account(ceremony.userId)!!,req,res)
        return mapOf("authenticated" to true)
    }
    @PostMapping("/auth/login/options") fun login(@RequestBody body:LoginStart,req:HttpServletRequest):JsonNode {
        val expected=if(body.reauthenticate)signedIn().id else null
        val builder=StartAssertionOptions.builder().userVerification(UserVerificationRequirement.REQUIRED).timeout(120000)
        if(expected!=null)builder.username(expected)
        val request=rp.startAssertion(builder.build())
        req.getSession(true).setAttribute("sm.assertion",LoginCeremony(request.toJson(),expected,System.currentTimeMillis()))
        return mapper.readTree(request.toCredentialsGetJson())
    }
    @PostMapping("/auth/login/finish") fun finishLogin(@RequestBody body:LoginFinish,req:HttpServletRequest,res:HttpServletResponse):Map<String,Boolean> {
        val session=req.getSession(false) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val ceremony=synchronized(session) { val saved=session.getAttribute("sm.assertion") as? LoginCeremony;session.removeAttribute("sm.assertion");saved } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST,"로그인을 다시 시작하세요")
        require(System.currentTimeMillis()-ceremony.at<180000) { "로그인 요청이 만료되었습니다" }
        val result=try { rp.finishAssertion(FinishAssertionOptions.builder().request(AssertionRequest.fromJson(ceremony.request)).response(PublicKeyCredential.parseAssertionResponseJson(body.credential.toString())).build()) }
        catch(e:Exception) { throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"패스키 인증에 실패했습니다") }
        if(!result.isSuccess || !result.isUserVerified || (ceremony.expectedUser!=null && ceremony.expectedUser!=result.username)) throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"패스키 인증에 실패했습니다")
        val user=store.account(result.username)?.takeIf { it.enabled } ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        store.updateCounter(result.credentialId.base64Url,result.signatureCount)
        establish(user,req,res);return mapOf("authenticated" to true)
    }
    @PostMapping("/auth/logout") fun logout(req:HttpServletRequest,res:HttpServletResponse) {
        req.getSession(false)?.invalidate();SecurityContextHolder.clearContext();csrf.saveToken(null,req,res)
    }
    @GetMapping("/auth/credentials") fun credentials()=store.credentialList(signedIn().id)
    @DeleteMapping("/auth/credentials/{id}") fun removeCredential(@PathVariable id:String,req:HttpServletRequest) { fresh(req);store.removeKey(signedIn().id,id) }
    @PostMapping("/auth/revoke-sessions") fun revoke(req:HttpServletRequest) { fresh(req);store.revokeSessions(signedIn().id) }
    @GetMapping("/admin/users") fun users()=store.accounts().map { mapOf("id" to it.id,"name" to it.displayName,"owner" to it.owner,"role" to it.role,"enabled" to it.enabled,"permissions" to it.instancePermissions(),"registered" to store.credentialList(it.id).isNotEmpty()) }
    @PostMapping("/admin/invitations") fun invite(@RequestBody body:InviteRequest,req:HttpServletRequest):Map<String,String> {
        fresh(req);body.permissions.keys.forEach { manager.instance(it) }
        return mapOf("token" to store.invite(body.name,body.permissions,body.role),"expiresIn" to "24h")
    }
    @PutMapping("/admin/users/{id}") fun update(@PathVariable id:String,@RequestBody body:AccessRequest,req:HttpServletRequest) { fresh(req);body.permissions.keys.forEach { manager.instance(it) };store.updateUser(id,body.enabled,body.permissions,body.role) }
    @GetMapping("/admin/audit") fun audit()=audit.recent()
}
