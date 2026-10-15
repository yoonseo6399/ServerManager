package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.*

/** Private AES-GCM envelope. No generated secret-bearing toString, no secrets in REST views. */
class GoogleSecretStore(private val root:Path) {
    private fun file(id:String):Path {
        var cursor=root.toAbsolutePath().normalize();while(cursor.parent!=null){require(!java.nio.file.Files.isSymbolicLink(cursor));cursor=cursor.parent}
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")));return root.resolve("$id.enc") }
    @Synchronized fun read(id:String):String? {
        val path=file(id);require(!java.nio.file.Files.isSymbolicLink(root) && !java.nio.file.Files.isSymbolicLink(root.resolve("key.bin")) && !java.nio.file.Files.isSymbolicLink(path))
        if(!path.exists())return null
        val data=path.readBytes();require(data.size in 29..65536)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(root.resolve("key.bin").readBytes(),"AES"),GCMParameterSpec(128,data.copyOfRange(0,12)))
        return cipher.doFinal(data.copyOfRange(12,data.size)).toString(Charsets.UTF_8)
    }
    @Synchronized fun save(id:String,value:String) {
        require(value.toByteArray().size<=60000);val path=file(id)
        require(!java.nio.file.Files.isSymbolicLink(root) && !java.nio.file.Files.isSymbolicLink(root.resolve("key.bin")) && !java.nio.file.Files.isSymbolicLink(path))
        root.createDirectories();
        java.nio.file.Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        val key=root.resolve("key.bin");require(!java.nio.file.Files.isSymbolicLink(key))
        if(!key.exists()) {key.writeBytes(ByteArray(32).also {SecureRandom().nextBytes(it)});java.nio.file.Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}
        val iv=ByteArray(12).also {SecureRandom().nextBytes(it)};val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key.readBytes(),"AES"),GCMParameterSpec(128,iv))
        val tmp=java.nio.file.Files.createTempFile(root,".google-",".tmp",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
        try {tmp.writeBytes(iv+cipher.doFinal(value.toByteArray()));java.nio.file.Files.move(tmp,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)}finally{tmp.deleteIfExists()}
    }
    @Synchronized fun remove(id:String){file(id).deleteIfExists()}
}
private class GoogleCeremony(val user:String,val state:String,val verifier:String,val clientId:String,val at:Long)
class GoogleClientInput(val clientId:String,val clientSecret:String?=null)
class GoogleQuery(val query:String="",val from:String="",val until:String="")

/** Only fixed Google hosts and read-only API paths are exposed. Content is untrusted data. */
class GoogleReadonlyService(root:Path,private val transport:(HttpRequest)->JsonNode={request ->
    val response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build().send(request,HttpResponse.BodyHandlers.ofInputStream())
    response.body().use {stream ->
        val bytes=stream.readNBytes(1_000_001);check(bytes.size<=1_000_000) {"Google 응답 크기 제한 초과"}
        check(response.statusCode() in 200..299) {"Google 연결 오류 (${response.statusCode()}). 연결 권한을 확인하거나 다시 연결하세요."}
        if(bytes.isEmpty())jacksonObjectMapper().createObjectNode() else jacksonObjectMapper().readTree(bytes)
    }
}) {
    private val mapper=jacksonObjectMapper();private val secrets=GoogleSecretStore(root)
    companion object {
        val scopes=setOf("https://www.googleapis.com/auth/gmail.readonly","https://www.googleapis.com/auth/calendar.events.readonly")
        fun encode(s:String)=URLEncoder.encode(s,Charsets.UTF_8)
    }
    fun config():JsonNode?=secrets.read("client")?.let {mapper.readTree(it)}
    @Synchronized fun configure(body:GoogleClientInput) {
        require(body.clientId.matches(Regex("[A-Za-z0-9._-]{10,200}\\.apps\\.googleusercontent\\.com"))) {"Google 웹 애플리케이션 클라이언트 ID를 확인하세요."}
        val secret=body.clientSecret?.takeIf {it.isNotBlank()} ?: config()?.path("secret")?.asText()
        require(!secret.isNullOrBlank() && secret.length<=512 && secret.none {it.isWhitespace()})
        secrets.save("client",mapper.writeValueAsString(mapOf("id" to body.clientId,"secret" to secret)))
    }
    private fun userKey(user:String)="user-"+Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(user.toByteArray()))
    fun connected(user:String)=secrets.read(userKey(user))?.let {mapper.readTree(it).path("clientId").asText()==config()?.path("id")?.asText()}==true
    private fun form(url:String,fields:Map<String,String>):JsonNode=transport(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(fields.entries.joinToString("&"){encode(it.key)+"="+encode(it.value)})).build())
    private fun tokenRequest(fields:Map<String,String>):JsonNode {
        val client=config() ?: error("Google 앱 연결 설정이 필요합니다.")
        return form("https://oauth2.googleapis.com/token",fields+mapOf("client_id" to client.path("id").asText(),"client_secret" to client.path("secret").asText()))
    }
    fun authorize(state:String,verifier:String,redirect:String):String {
        val client=config() ?: error("소유자가 Google 앱을 먼저 설정해야 합니다.")
        val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return "https://accounts.google.com/o/oauth2/v2/auth?"+mapOf("client_id" to client.path("id").asText(),"redirect_uri" to redirect,"response_type" to "code","scope" to scopes.joinToString(" "),"state" to state,"access_type" to "offline","prompt" to "consent","code_challenge" to challenge,"code_challenge_method" to "S256").entries.joinToString("&"){encode(it.key)+"="+encode(it.value)}
    }
    @Synchronized fun finish(user:String,code:String,verifier:String,redirect:String) {
        require(code.length in 1..4096)
        val token=tokenRequest(mapOf("code" to code,"code_verifier" to verifier,"redirect_uri" to redirect,"grant_type" to "authorization_code"))
        val granted=token.path("scope").asText().split(' ').toSet()
        check(granted==scopes) {"Gmail과 캘린더의 읽기 권한만 모두 허용해야 합니다."}
        check(token.path("access_token").asText().isNotBlank()) {"Google 액세스 토큰이 없습니다."}
        check(token.path("refresh_token").asText().isNotBlank()) {"오프라인 연결 권한이 없습니다. 다시 연결하세요."}
        secrets.save(userKey(user),mapper.writeValueAsString(mapOf("clientId" to config()!!.path("id").asText(),"refresh" to token.path("refresh_token").asText(),"access" to token.path("access_token").asText(),"expires" to System.currentTimeMillis()+token.path("expires_in").asLong(3600)*1000)))
    }
    @Synchronized private fun access(user:String):String {
        val token=secrets.read(userKey(user))?.let {mapper.readTree(it)} ?: error("Google 계정을 연결하세요.")
        check(token.path("clientId").asText()==config()?.path("id")?.asText()) {"Google 앱 설정이 바뀌었습니다. 다시 연결하세요."}
        if(token.path("expires").asLong()>System.currentTimeMillis()+60000)return token.path("access").asText()
        val next=tokenRequest(mapOf("refresh_token" to token.path("refresh").asText(),"grant_type" to "refresh_token"))
        check(next.path("access_token").asText().isNotBlank())
        if(next.has("scope"))check(next.path("scope").asText().split(' ').toSet()==scopes) {"Google 읽기 권한을 다시 연결하세요."}
        secrets.save(userKey(user),mapper.writeValueAsString(mapOf("clientId" to token.path("clientId").asText(),"refresh" to token.path("refresh").asText(),"access" to next.path("access_token").asText(),"expires" to System.currentTimeMillis()+next.path("expires_in").asLong(3600)*1000)))
        return next.path("access_token").asText()
    }
    @Synchronized fun revoke(user:String):Boolean {
        val token=secrets.read(userKey(user))?.let {mapper.readTree(it)} ?: return true
        secrets.remove(userKey(user))
        return try {form("https://oauth2.googleapis.com/revoke",mapOf("token" to token.path("refresh").asText()));true} catch(_:Exception) {false}
    }
    private fun get(user:String,url:String)=transport(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer ${access(user)}").GET().build())
    fun search(user:String,query:String):JsonNode {require(query.length<=500);return get(user,"https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=20&q="+encode(query))}
    fun message(user:String,id:String):JsonNode {require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")));return get(user,"https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=full")}
    fun events(user:String,from:String,until:String):JsonNode {
        val begin=Instant.parse(from);val end=Instant.parse(until);require(end>begin && Duration.between(begin,end).toDays()<=93)
        return get(user,"https://www.googleapis.com/calendar/v3/calendars/primary/events?singleEvents=true&orderBy=startTime&maxResults=100&timeMin=${encode(from)}&timeMax=${encode(until)}")
    }
}
@RestController
class GoogleReadonlyApi(private val service:GoogleReadonlyService,private val auth:AuthStore,private val settings:SecuritySettings) {
    private fun user()=auth.account(SecurityContextHolder.getContext().authentication.name)?.takeIf {it.enabled}?.id ?: error("로그인이 필요합니다.")
    private fun redirect():String {
        val origin=URI(settings.origin);require(origin.scheme=="https" || (origin.scheme=="http" && origin.host in setOf("localhost","127.0.0.1")))
        return settings.origin.trimEnd('/')+"/api/google/callback"
    }
    @GetMapping("/api/admin/google-client") fun client()=ResponseEntity.ok().header("Cache-Control","no-store").body(mapOf("clientId" to service.config()?.path("id")?.asText(),"configured" to (service.config()!=null),"redirectUri" to redirect()))
    @PutMapping("/api/admin/google-client") fun configure(@RequestBody body:GoogleClientInput,request:HttpServletRequest):Map<String,Boolean>{freshDesktop(request);service.configure(body);return mapOf("saved" to true)}
    @GetMapping("/api/google/connection") fun connection()=ResponseEntity.ok().header("Cache-Control","no-store").body(mapOf("connected" to service.connected(user()),"configured" to (service.config()!=null)))
    @PostMapping("/api/google/connect") fun connect(request:HttpServletRequest):Map<String,String> {
        freshDesktop(request);val encoder=Base64.getUrlEncoder().withoutPadding();fun random()=encoder.encodeToString(ByteArray(32).also {SecureRandom().nextBytes(it)})
        val state=random();val verifier=random();request.session.setAttribute("google.ceremony",GoogleCeremony(user(),state,verifier,service.config()!!.path("id").asText(),System.currentTimeMillis()))
        return mapOf("url" to service.authorize(state,verifier,redirect()))
    }
    // Public landing only: establish a same-origin document before accessing the Strict session cookie.
    @GetMapping("/api/google/callback") fun landing(@RequestParam state:String,@RequestParam(required=false) code:String?):ResponseEntity<String> {
        require(state.length<=200 && (code==null || code.length<=4096))
        val target="/api/google/callback-complete?state="+GoogleReadonlyService.encode(state)+"&code="+GoogleReadonlyService.encode(code.orEmpty())
        val html="<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\"><meta http-equiv=\"refresh\" content=\"0;url="+target.replace("&","&amp;")+"\"></head><body>Google 연결을 확인하고 있습니다.</body></html>"
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").header("Content-Type","text/html;charset=UTF-8").header("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'; base-uri 'none'").body(html)
    }
    @GetMapping("/api/google/callback-complete") fun callback(@RequestParam state:String,@RequestParam(required=false) code:String?,request:HttpServletRequest):ResponseEntity<String> {
        val ceremony=request.getSession(false)?.getAttribute("google.ceremony") as? GoogleCeremony ?: error("연결 요청이 만료되었습니다.")
        request.session.removeAttribute("google.ceremony")
        require(ceremony.user==user() && ceremony.clientId==service.config()?.path("id")?.asText() && System.currentTimeMillis()-ceremony.at in 0..300000 && MessageDigest.isEqual(ceremony.state.toByteArray(),state.toByteArray())) {"연결 요청을 다시 시작하세요."}
        service.finish(user(),code ?: error("Google 연결이 취소되었습니다."),ceremony.verifier,redirect())
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").body("Google 읽기 전용 연결이 완료되었습니다. 관리 웹으로 돌아가세요.")
    }
    @DeleteMapping("/api/google/connection") fun revoke(request:HttpServletRequest):Map<String,Boolean>{freshDesktop(request);return mapOf("removed" to true,"remoteRevoked" to service.revoke(user()))}
    @PostMapping("/api/google/gmail/search") fun search(@RequestBody body:GoogleQuery)=ResponseEntity.ok().header("Cache-Control","no-store").body(service.search(user(),body.query))
    @GetMapping("/api/google/gmail/{id}") fun message(@PathVariable id:String)=ResponseEntity.ok().header("Cache-Control","no-store").body(service.message(user(),id))
    @PostMapping("/api/google/calendar/events") fun events(@RequestBody body:GoogleQuery)=ResponseEntity.ok().header("Cache-Control","no-store").body(service.events(user(),body.from,body.until))
}

@org.springframework.context.annotation.Configuration
class GoogleReadonlyConfiguration {
    @org.springframework.context.annotation.Bean
    fun googleReadonlyService(manager:ServerManager)=GoogleReadonlyService(manager.root.resolve("security/google"))
}
