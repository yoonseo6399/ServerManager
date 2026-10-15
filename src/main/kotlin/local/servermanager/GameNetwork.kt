package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.*
import org.springframework.http.ResponseEntity
import java.net.IDN
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.*

data class GameNetworkConfig(val enabled:Boolean=false,val port:Int=6399,@get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT) val sableUdpEnabled:Boolean=false,val baseDomain:String="",val targetHost:String="",
    val relayRunnerId:String?=null,val defaultInstanceId:String?=null,val defaultHostname:String="",val labels:Map<String,String> = emptyMap(),val cloudflareEnabled:Boolean=false,val zoneId:String="")
data class GameNetworkView(val config:GameNetworkConfig,val revision:Long,val tokenConfigured:Boolean)
data class GameNetworkSave(val config:GameNetworkConfig,val revision:Long,val token:String?=null,val clearToken:Boolean=false)
private data class GameNetworkDisk(val config:GameNetworkConfig=GameNetworkConfig(),val revision:Long=0,val encryptedToken:String?=null,val installationId:String=UUID.randomUUID().toString())

object GameDomains {
    fun host(value:String):String {
        require(value.length in 1..253 && !value.contains('\u0000')) { "DNS 호스트 이름을 입력하세요" }
        val ascii=IDN.toASCII(value.trimEnd('.'),IDN.USE_STD3_ASCII_RULES).lowercase()
        require(ascii.length<=253 && ascii.split('.').all { it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) }) { "DNS 호스트 이름을 확인하세요" }
        return ascii
    }
    fun hostname(config:GameNetworkConfig,id:String):String=host("${config.labels[id] ?: "s-${id.take(8)}"}.${config.baseDomain}")
    fun resolve(config:GameNetworkConfig,ids:Set<String>,hostname:String):String? {
        val resolvedHost=runCatching { host(hostname.substringBefore('\u0000')) }.getOrNull() ?: return null
        if((config.defaultHostname.isNotBlank() && resolvedHost==host(config.defaultHostname)) || (config.targetHost.isNotBlank() && resolvedHost==host(config.targetHost)))return config.defaultInstanceId?.takeIf { it in ids }
        return ids.filter { GameDomains.hostname(config,it)==resolvedHost }.singleOrNull()
    }
    fun validate(config:GameNetworkConfig,ids:Set<String>) {
        require(config.relayRunnerId==null || (config.enabled && !config.sableUdpEnabled && runCatching {UUID.fromString(config.relayRunnerId)}.isSuccess)) {"실행기 TCP 중계에는 공유 중계가 필요하며 Sable UDP와 함께 사용할 수 없습니다"}
        require(!config.sableUdpEnabled || config.enabled) {"Sable UDP 중계에는 공유 TCP 중계가 필요합니다"}
        require(config.port in 1024..65535)
        if(config.baseDomain.isNotEmpty())host(config.baseDomain)
        if(config.targetHost.isNotEmpty())host(config.targetHost)
        if(config.defaultHostname.isNotEmpty())host(config.defaultHostname)
        require(!config.enabled || config.baseDomain.isNotBlank()) { "게임 하위 도메인의 기본 주소를 입력하세요" }
        require(config.defaultInstanceId==null || config.defaultInstanceId in ids)
        require(config.labels.keys.all { it in ids }) { "삭제됐거나 없는 서버의 주소 설정입니다" }
        config.labels.values.forEach { require(host(it)==it && !it.contains('.')) { "하위 도메인 이름은 영문 소문자·숫자·하이픈으로 입력하세요" } }
        if(config.baseDomain.isNotEmpty())require(ids.map { hostname(config,it) }.distinct().size==ids.size) { "서버 하위 도메인은 서로 달라야 합니다" }
        if(config.defaultHostname.isNotBlank())require(ids.none { it!=config.defaultInstanceId && hostname(config,it)==host(config.defaultHostname) }) { "기존 주소가 다른 서버의 하위 도메인과 겹칩니다" }
        require(!config.cloudflareEnabled || (config.enabled && config.targetHost.isNotBlank() && config.zoneId.matches(Regex("[a-f0-9]{32}")))) { "Cloudflare Zone ID와 중계 컴퓨터의 DNS only 호스트를 입력하세요" }
        if(config.targetHost.isNotEmpty()) {
            require(!ids.any { hostname(config,it)==host(config.targetHost) } && host(config.baseDomain)!=host(config.targetHost)) { "중계 대상 호스트가 생성할 주소와 같으면 DNS가 순환합니다" }
        }
    }
}

class GameNetworkStore(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("settings.json")
    private val key=directory.resolve("token.key")
    private var disk:GameNetworkDisk
    init {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(file) && !Files.isSymbolicLink(key))
        directory.createDirectories();Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        disk=if(file.exists())mapper.readValue(file.readText()) else GameNetworkDisk()
    }
    @Synchronized fun view()=GameNetworkView(disk.config,disk.revision,disk.encryptedToken!=null)
    @Synchronized fun config()=disk.config
    @Synchronized fun ownerTag()="ServerManager:${disk.installationId}:"
    @Synchronized fun removeInstance(id:String,ids:Set<String>) {
        if(id !in disk.config.labels && disk.config.defaultInstanceId!=id)return
        val next=disk.config.copy(labels=disk.config.labels-id,defaultInstanceId=disk.config.defaultInstanceId?.takeUnless {it==id})
        save(GameNetworkSave(next,disk.revision),ids)
    }
    @Synchronized fun token():String? {
        val encrypted=disk.encryptedToken ?: return null
        val bytes=Base64.getDecoder().decode(encrypted);val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key.readBytes(),"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
    }
    private fun privateWrite(path:Path,bytes:ByteArray) {
        val tmp=Files.createTempFile(directory,".network-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try { tmp.writeBytes(bytes);Files.move(tmp,path,ATOMIC_MOVE,REPLACE_EXISTING) } finally { tmp.deleteIfExists() }
    }
    @Synchronized fun save(request:GameNetworkSave,ids:Set<String>):GameNetworkView {
        check(request.revision==disk.revision) { "네트워크 설정이 다른 화면에서 변경되었습니다" }
        GameDomains.validate(request.config,ids)
        require(!(request.clearToken && !request.token.isNullOrBlank()))
        var encrypted=if(request.clearToken)null else disk.encryptedToken
        request.token?.takeIf { it.isNotBlank() }?.let { token ->
            require(token.length in 10..512 && token.matches(Regex("[A-Za-z0-9_.-]+"))) { "Cloudflare API 토큰 형식을 확인하세요" }
            if(!key.exists())privateWrite(key,ByteArray(32).also { SecureRandom().nextBytes(it) })
            val iv=ByteArray(12).also { SecureRandom().nextBytes(it) };val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key.readBytes(),"AES"),GCMParameterSpec(128,iv))
            encrypted=Base64.getEncoder().encodeToString(iv+cipher.doFinal(token.toByteArray()))
        }
        require(!request.config.cloudflareEnabled || encrypted!=null) { "Cloudflare DNS 편집 API 토큰을 입력하세요" }
        val next=disk.copy(config=request.config,revision=disk.revision+1,encryptedToken=encrypted)
        privateWrite(file,mapper.writeValueAsBytes(next));disk=next;return view()
    }
}

class CloudflareDns(private val token:String,private val zone:String,private val request:((String,String,JsonNode?)->JsonNode)?=null) {
    private val mapper=jacksonObjectMapper()
    private val client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build()
    private fun call(method:String,path:String,body:JsonNode?=null):JsonNode {
        if(request!=null)return request.invoke(method,path,body)
        val builder=HttpRequest.newBuilder(URI("https://api.cloudflare.com/client/v4/zones/$zone/dns_records$path"))
            .timeout(java.time.Duration.ofSeconds(20)).header("Authorization","Bearer $token").header("Content-Type","application/json")
        val response=client.send(builder.method(method,body?.let { HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(it)) } ?: HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofInputStream())
        val bytes=response.body().use { it.readNBytes(1024*1024+1) }
        check(response.statusCode() in 200..299 && bytes.size<=1024*1024) { "Cloudflare DNS HTTP ${response.statusCode()}. 토큰의 Zone DNS 편집 권한을 확인하세요" }
        return mapper.readTree(bytes).also { check(it.path("success").asBoolean()) { "Cloudflare DNS 요청이 거부됐습니다" } }
    }
    fun upsert(name:String,type:String,content:String?,data:Map<String,Any>?=null,tag:String):String {
        val records=call("GET","?name="+URLEncoder.encode(name,Charsets.UTF_8)+"&per_page=100").path("result")
        require(records.size()<=1) { "여러 DNS 레코드가 이미 있습니다: $name. 직접 확인하세요" }
        val wanted=mapper.valueToTree<JsonNode>(buildMap<String,Any> { put("name",name);put("type",type);put("ttl",1);put("comment",tag);if(content!=null){put("content",content);put("proxied",false)};if(data!=null)put("data",data) })
        val old=records.firstOrNull()
        if(old!=null) {
            val equal=old.path("type").asText()==type && (if(content!=null) old.path("content").asText().trimEnd('.')==content.trimEnd('.') && !old.path("proxied").asBoolean() else data!!.all { (k,v)->old.path("data").path(k).asText().trimEnd('.')==v.toString().trimEnd('.') })
            if(equal)return old.path("id").asText()
            check(old.path("comment").asText()==tag) { "기존 사용자 DNS를 덮어쓰지 않았습니다: $name" }
            return call("PUT","/"+old.path("id").asText(),wanted).path("result").path("id").asText()
        }
        return call("POST","",wanted).path("result").path("id").asText()
    }
    fun prune(names:Set<String>,ownerTag:String,allowed:()->Boolean={true}) {
        val stale=mutableListOf<String>()
        val owned=Regex(Regex.escape(ownerTag)+"[a-f0-9-]{36}:(?:A|SRV)")
        var page=1
        do {
            check(allowed()) { "설정 변경으로 DNS 정리를 중단했습니다" }
            val response=call("GET","?per_page=100&page=$page")
            response.path("result").filter { owned.matches(it.path("comment").asText()) && it.path("type").asText() in setOf("A","SRV") && it.path("name").asText() !in names }.forEach { stale.add(it.path("id").asText()) }
            val pages=response.path("result_info").path("total_pages").asInt(1);require(pages<=100) { "DNS 레코드가 너무 많습니다" };page++
        } while(page<=pages)
        stale.forEach {id->check(allowed()) { "설정 변경으로 DNS 정리를 중단했습니다" };call("DELETE","/$id")}
    }
}

@Service
class GameNetwork(private val manager:ServerManager,private val runners:RunnerRegistry?=null) {
    val store=GameNetworkStore(manager.root.resolve("security/game-network"))
    @Volatile final var dnsMessage="Cloudflare 자동 DNS 미설정";private set
    @Volatile private var lastSnapshot=""
    @Volatile private var lastAttempt=0L
    @Volatile var relayMessage="중앙 Mac 중계"
    fun config()=store.config()
    fun ids()=manager.list().map { (it["config"] as Instance).id }.toSet()
    fun address(id:String):String?=config().takeIf { it.enabled }?.let { GameDomains.hostname(it,id) }
    fun resolve(host:String):String?=GameDomains.resolve(config(),ids(),host)
    fun removeInstance(id:String) {store.removeInstance(id,ids());lastSnapshot="";lastAttempt=0}
    @Scheduled(fixedDelay=10000) fun periodicDns()=synchronizeDns()
    @Synchronized fun synchronizeDns(force:Boolean=false) {
        val view=store.view();val config=view.config;val revision=view.revision
        if(!config.cloudflareEnabled){dnsMessage="Cloudflare 자동 DNS 꺼짐";return}
        val ids=ids()
        if(!force && System.currentTimeMillis()-lastAttempt<60000)return
        lastAttempt=System.currentTimeMillis()
        try {
            val client=CloudflareDns(store.token() ?: error("Cloudflare 토큰이 없습니다"),config.zoneId)
            val address=java.net.InetAddress.getAllByName(config.targetHost).firstOrNull { it is java.net.Inet4Address && !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isSiteLocalAddress && !it.isLinkLocalAddress }?.hostAddress ?: error("중계 컴퓨터의 공개 IPv4 DNS only 호스트를 확인하세요")
            val snapshot=revision.toString()+ids.sorted().joinToString()+address
            if(!force && snapshot==lastSnapshot)return
            val names=ids.associateWith { GameDomains.hostname(config,it) }
            names.forEach { (id,name)->
                check(store.view().revision==revision) { "설정 변경으로 이전 DNS 작업을 중단했습니다" }
                client.upsert(name,"A",address,tag=store.ownerTag()+id+":A")
                check(store.view().revision==revision) { "설정 변경으로 이전 DNS 작업을 중단했습니다" }
                client.upsert("_minecraft._tcp.$name","SRV",null,mapOf("priority" to 0,"weight" to 5,"port" to config.port,"target" to name),store.ownerTag()+id+":SRV")
            }
            check(store.view().revision==revision) { "설정 변경으로 이전 DNS 작업을 중단했습니다" }
            client.prune(names.values.flatMap {listOf(it,"_minecraft._tcp.$it")}.toSet(),store.ownerTag()) {store.view().revision==revision}
            lastSnapshot=snapshot;dnsMessage="${names.size}개 게임 주소의 DNS·SRV 확인 완료"
        } catch(e:Exception){dnsMessage=e.message ?: "Cloudflare DNS 동기화 실패"}
    }
    fun save(request:GameNetworkSave):GameNetworkView=manager.withIdle {
        if(!request.config.enabled)require(manager.list().map {(it["config"] as Instance).port}.distinct().size==manager.list().size) { "독립 포트 모드로 바꾸려면 서버마다 다른 내부 포트를 지정하세요" }
        request.config.relayRunnerId?.let {id->require(runners?.active(id)!=null && runners.tcpRelaySupported(id)) {"중계 수신을 허용한 온라인 실행기를 선택하세요"}}
        store.save(request,ids())
    }.also { lastSnapshot="";lastAttempt=0 }
    fun domain(id:String,label:String):GameNetworkView=manager.withStopped(id) {
        val view=store.view()
        val labels=view.config.labels.filterKeys { it in ids() }.toMutableMap()
        if(label.isBlank())labels.remove(id) else labels[id]=label
        store.save(GameNetworkSave(view.config.copy(labels=labels),view.revision),ids())
    }.also {lastSnapshot="";lastAttempt=0}
}

@RestController
@RequestMapping("/api/admin/game-network")
class GameNetworkApi(private val network:GameNetwork) {
    @GetMapping fun get()=ResponseEntity.ok().header("Cache-Control","no-store").body(mapOf("settings" to network.store.view(),"dnsMessage" to network.dnsMessage,"relayMessage" to network.relayMessage))
    @PutMapping fun save(@RequestBody request:GameNetworkSave,req:HttpServletRequest):GameNetworkView { freshDesktop(req);return network.save(request) }
}

@RestController
class GameAddressesApi(private val network:GameNetwork,private val accounts:AuthStore) {
    @GetMapping("/api/game-addresses") fun addresses(authentication:org.springframework.security.core.Authentication)=mapOf("enabled" to network.config().enabled,"port" to network.config().port,"baseDomain" to network.config().baseDomain,"dnsAutomatic" to network.config().cloudflareEnabled,"labels" to network.config().labels.filterKeys { accounts.allowed(authentication.name,it,false) },"hosts" to network.ids().filter { accounts.allowed(authentication.name,it,false) }.associateWith { network.address(it) })
    data class Domain(val label:String)
    @PutMapping("/api/instances/{id}/game-domain") fun domain(@PathVariable id:String,@RequestBody request:Domain,req:HttpServletRequest):Map<String,String?> {freshDesktop(req);network.domain(id,request.label);return mapOf("hostname" to network.address(id))}
}
