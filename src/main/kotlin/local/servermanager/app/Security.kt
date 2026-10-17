package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.io.path.*

class SecuritySettings(val publicOrigin:String,val port:Int,val bind:String) {
    val origin=publicOrigin.ifBlank { "http://localhost:$port" }.trimEnd('/')
    val uri=URI(origin)
    val secure=uri.scheme=="https"
    val rpId=uri.host ?: error("올바른 공개 URL을 입력하세요")
    init {
        require(uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path.isNullOrEmpty()) { "공개 URL에는 경로나 인증 정보를 넣을 수 없습니다" }
        require(secure || (uri.scheme=="http" && rpId=="localhost")) { "외부 공개 URL은 HTTPS여야 합니다" }
        require(rpId=="localhost" || (!rpId.matches(Regex("[0-9.]+")) && !rpId.contains(':'))) { "패스키에는 IP 주소 대신 도메인을 사용하세요" }
        require(bind in listOf("127.0.0.1","::1","localhost") || (secure && publicOrigin.isNotBlank())) { "공개 URL 설정 전에는 루프백 주소에서만 실행할 수 있습니다" }
    }
}

class AuditLog(private val file:Path) {
    private val mapper=jacksonObjectMapper()
    @Synchronized fun append(actor:String,action:String,status:Int,client:String) {
        file.parent.createDirectories()
        // No request bodies, tokens, cookies or query strings are recorded.
        val line=mapper.writeValueAsString(mapOf("at" to Instant.now().toString(),"actor" to actor,"action" to action,"status" to status,"client" to client))
        if(file.exists() && file.fileSize()>10*1024*1024) Files.move(file,file.resolveSibling("audit.previous.jsonl"),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(file,line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND)
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"))
    }
    @Synchronized fun recent():List<Any> = if(file.exists()) file.useLines { it.toList().takeLast(300).map { line->mapper.readValue(line,Map::class.java) } }.reversed() else emptyList()
}

class RequestGuard(private val settings:SecuritySettings,private val token:String,private val audit:AuditLog):OncePerRequestFilter() {
    private data class Bucket(var from:Long,var count:Int)
    private val attempts=mutableMapOf<String,Bucket>()
    @Synchronized private fun allowed(ip:String):Boolean {
        val now=System.currentTimeMillis();attempts.entries.removeIf { now-it.value.from>60000 }
        if(!attempts.containsKey(ip)&&attempts.size>=10000)return false
        val bucket=attempts.getOrPut(ip) { Bucket(now,0) };return ++bucket.count<=40
    }
    override fun doFilterInternal(req:HttpServletRequest,res:HttpServletResponse,chain:FilterChain) {
        val remote=req.remoteAddr
        val local=remote in listOf("127.0.0.1","::1","0:0:0:0:0:0:0:1")
        val proxyOk=MessageDigest.isEqual((req.getHeader("X-ServerManager-Proxy") ?: "").toByteArray(),token.toByteArray())
        if(!local && !proxyOk) { reject(res,403,"리버스 프록시 인증이 필요합니다");return }
        val client=if(proxyOk) req.getHeader("X-Real-IP")?.takeIf { it.length<=45 && it.matches(Regex("[0-9a-fA-F:.]+")) } ?: remote else remote
        req.setAttribute("sm.client",client)
        val unsafe=req.method !in listOf("GET","HEAD","OPTIONS")
        val origin=req.getHeader("Origin")
        if(unsafe && origin!=null && origin!=settings.origin) { reject(res,403,"허용되지 않은 요청 출처입니다");return }
        if(unsafe && req.getHeader("Sec-Fetch-Site")=="cross-site") { reject(res,403,"다른 사이트에서의 요청은 허용하지 않습니다");return }
        if((req.requestURI.startsWith("/api/auth/") || req.requestURI=="/api/runners/pair") && unsafe && !allowed(client)) { res.setHeader("Retry-After","60");reject(res,429,"시도가 너무 많습니다. 1분 후 다시 시도하세요");return }
        try { chain.doFilter(req,res) } finally {
            if(unsafe && req.requestURI.startsWith("/api/")) {
                val actor=req.getSession(false)?.getAttribute("sm.user")?.toString() ?: "anonymous"
                audit.append(actor,req.method+" "+req.requestURI.take(300),res.status,client)
            }
        }
    }
    private fun reject(res:HttpServletResponse,status:Int,message:String) { res.status=status;res.contentType="application/json";res.characterEncoding="UTF-8";res.writer.write(jacksonObjectMapper().writeValueAsString(mapOf("message" to message))) }
}

class SessionGuard(private val store:AuthStore):OncePerRequestFilter() {
    override fun doFilterInternal(req:HttpServletRequest,res:HttpServletResponse,chain:FilterChain) {
        val auth=SecurityContextHolder.getContext().authentication
        if(auth!=null && auth.isAuthenticated && auth !is AnonymousAuthenticationToken) {
            val account=store.account(auth.name);val session=req.getSession(false)
            val started=session?.getAttribute("sm.started") as? Long ?: 0
            if(account?.enabled!=true || session?.getAttribute("sm.epoch")!=account.epoch || System.currentTimeMillis()-started>8*60*60*1000L) {
                session?.invalidate();SecurityContextHolder.clearContext()
            }
        }
        chain.doFilter(req,res)
    }
}

@Configuration
class SecurityConfig {
    @Bean fun settings(@Value("\${server-manager.public-origin:}") origin:String,@Value("\${server.port:8787}") port:Int,@Value("\${server.address:127.0.0.1}") bind:String)=SecuritySettings(origin,port,bind)
    @Bean(destroyMethod="close") fun authStore(manager:ServerManager)=AuthStore(manager.root.resolve("security"))
    @Bean fun audit(manager:ServerManager)=AuditLog(manager.root.resolve("security/audit.jsonl"))
    @Bean fun contexts()=HttpSessionSecurityContextRepository()
    @Bean fun csrfRepository()=HttpSessionCsrfTokenRepository()
    @Bean fun cookies(settings:SecuritySettings)=org.springframework.boot.web.servlet.ServletContextInitializer { context->context.sessionCookieConfig.isHttpOnly=true;context.sessionCookieConfig.isSecure=settings.secure }
    @Bean fun security(http:HttpSecurity,store:AuthStore,settings:SecuritySettings,audit:AuditLog,contexts:HttpSessionSecurityContextRepository,csrf:HttpSessionCsrfTokenRepository,manager:ServerManager):SecurityFilterChain {
        val tokenFile=manager.root.resolve("security/proxy-token.txt")
        if(!tokenFile.exists()) { val bytes=ByteArray(32);SecureRandom().nextBytes(bytes);Files.writeString(tokenFile,Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));Files.setPosixFilePermissions(tokenFile,PosixFilePermissions.fromString("rw-------")) }
        val token=tokenFile.readText().trim();require(token.length>=32)
        http.csrf { it.csrfTokenRepository(csrf).csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler()).ignoringRequestMatchers("/api/runners/pair","/api/runners/transfer/**") }
        http.securityContext { it.securityContextRepository(contexts) }
        http.requestCache { it.disable() }
        http.formLogin { it.disable() };http.httpBasic { it.disable() };http.logout { it.disable() }
        http.exceptionHandling { it.authenticationEntryPoint { _,res,_->res.status=401;res.contentType="application/json";res.characterEncoding="UTF-8";res.writer.write("{\"message\":\"로그인이 필요합니다\"}") };it.accessDeniedHandler { _,res,_->res.status=403;res.contentType="application/json";res.characterEncoding="UTF-8";res.writer.write("{\"message\":\"권한이 없거나 요청 검증에 실패했습니다\"}") } }
        http.headers { it.contentSecurityPolicy { c->c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https://cdn.modrinth.com; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'") };it.frameOptions { f->f.deny() };it.referrerPolicy { p->p.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) };if(settings.secure)it.httpStrictTransportSecurity { h->h.requestMatcher { true }.includeSubDomains(false).maxAgeInSeconds(31536000) } }
        http.authorizeHttpRequests { auth->
            auth.requestMatchers("/","/index.html","/assets/**","/favicon.ico","/api/auth/session","/api/google/callback","/api/auth/login/**","/api/auth/register/**","/api/runners/pair","/api/runners/connect","/api/runners/transfer/**").permitAll()
            auth.requestMatchers("/api/admin/**").access { authentication,_->AuthorizationDecision(store.account(authentication.get().name)?.let { it.owner && it.enabled }==true) }
            auth.requestMatchers("/api/instances/**").access { authentication,ctx->
                val user=store.account(authentication.get().name)
                val parts=ctx.request.requestURI.removePrefix("/api/instances").trim('/').split('/').filter { it.isNotEmpty() }
                val write=ctx.request.method !in listOf("GET","HEAD")
                AuthorizationDecision(user?.enabled==true && (if(parts.isEmpty()) !write||user.managesAllServers() else if(parts.size==1 && ctx.request.method=="DELETE") user.managesAllServers() else store.allowed(user.id,parts[0],write)))
            }
            auth.requestMatchers("/api/mrpacks/**").access { authentication,_->AuthorizationDecision(store.account(authentication.get().name)?.let { it.managesAllServers() && it.enabled }==true) }
            auth.requestMatchers("/api/**").authenticated()
            auth.anyRequest().denyAll()
        }
        http.addFilterBefore(RequestGuard(settings,token,audit),CsrfFilter::class.java)
        http.addFilterBefore(SessionGuard(store),AnonymousAuthenticationFilter::class.java)
        return http.build()
    }
}
