package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.*
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.http.*
import java.nio.file.*
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Properties
import kotlin.io.path.*

data class PublishedUpdate(val version:String,val name:String,val prerelease:Boolean,val publishedAt:String,val url:String,val installable:Boolean,val reason:String?)
object UpdateReleases {
    fun catalog(bytes:ByteArray,current:String,failed:String?):List<PublishedUpdate> = jacksonObjectMapper().readTree(bytes).let {root->
        require(root.isArray) {"잘못된 릴리즈 목록"}
        root.filter { !it.path("draft").asBoolean() && it.path("tag_name").asText().startsWith("v") && SignedUpdates.eligible(it.path("tag_name").asText().removePrefix("v"),it.path("prerelease").asBoolean(),true) }.map {release->
            val version=release.path("tag_name").asText().removePrefix("v")
            val assets=release.path("assets").map {it.path("name").asText()}.toSet()
            val reason=when {
                SignedUpdates.compare(version,current)<=0->"현재 버전 이하이므로 설치할 수 없습니다"
                version==failed->"시작 검증에 실패한 버전입니다"
                !assets.containsAll(setOf("update.properties","update.sig","server-manager-$version.jar"))->"서명된 업데이트 파일이 없습니다"
                else->null
            }
            PublishedUpdate(version,release.path("name").asText(version).take(200),release.path("prerelease").asBoolean(),release.path("published_at").asText(),"https://github.com/yoonseo6399/ServerManager/releases/tag/v$version",reason==null,reason)
        }.distinctBy {it.version}.sortedWith {a,b->SignedUpdates.compare(b.version,a.version)}
    }
    fun select(releases:List<PublishedUpdate>,version:String,allowPrerelease:Boolean):PublishedUpdate {
        SignedUpdates.compare(version,"0.0.0")
        val release=releases.singleOrNull {it.version==version} ?: error("공개된 릴리즈를 선택하세요")
        require(release.installable) {release.reason ?: "설치할 수 없는 버전입니다"}
        require(!release.prerelease || allowPrerelease) {"베타 설치 동의가 필요합니다"}
        return release
    }
}

object SignedUpdates {
    fun compare(a:String,b:String):Int {
        fun parts(value:String):List<Int> {
            require(value.matches(Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(?:-beta\\.[1-9][0-9]{0,5})?"))) { "잘못된 버전" }
            val core=value.substringBefore('-').split('.').map(String::toInt)
            return core+if("-beta." in value) value.substringAfter("-beta.").toInt() else Int.MAX_VALUE
        }
        return parts(a).zip(parts(b)).map { (x,y)->x.compareTo(y) }.firstOrNull { it!=0 } ?: 0
    }
    fun eligible(version:String,prerelease:Boolean,beta:Boolean):Boolean = runCatching {
        compare(version,"0.0.0")
        (beta || !prerelease) && (beta || "-beta." !in version) && (prerelease == ("-beta." in version))
    }.getOrDefault(false)
    fun manifest(bytes:ByteArray,signature:ByteArray,key:ByteArray):Properties {
        val verifier=Signature.getInstance("Ed25519");verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key)));verifier.update(bytes)
        require(verifier.verify(signature)) { "업데이트 서명을 확인할 수 없습니다" }
        val p=Properties().apply { bytes.inputStream().use { load(it) } };val version=p.getProperty("version","")
        compare(version,"0.0.0");require(p.getProperty("protocol")=="1") { "새 설치 파일로 앱을 업데이트하세요" }
        require(p.getProperty("file")=="server-manager-$version.jar" && p.getProperty("sha256","").matches(Regex("[0-9a-f]{64}")))
        require(p.getProperty("size").toLong() in 1..150L*1024*1024)
        return p
    }
    fun validFile(path:Path,p:Properties):Boolean {
        if(!path.exists()||path.fileSize()!=p.getProperty("size").toLong())return false
        val hash=MessageDigest.getInstance("SHA-256");path.inputStream().use { input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)} }
        return hash.digest().joinToString("") { "%02x".format(it) }==p.getProperty("sha256")
    }
}
@Service
class AppUpdates(private val desktop:DesktopLifecycle) {
    private val mapper=jacksonObjectMapper()
    private val client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build()
    val current=javaClass.getResourceAsStream("/app-version.txt")!!.bufferedReader().use { it.readText().trim() }
    val betaChannel="-beta." in current
    val repository=javaClass.getResourceAsStream("/update-repository.txt")!!.bufferedReader().use { it.readText().trim() }
    private val key=javaClass.getResourceAsStream("/update-public-key.der")!!.use { it.readAllBytes() }
    private val disabled=desktop.home.resolve("updates-disabled")
    @Volatile private var latest:String?=null
    @Volatile private var message="아직 확인하지 않았습니다"
    @Volatile private var checked:String?=null
    @Volatile private var checking=false
    fun status()=mapOf("supported" to desktop.supervised,"enabled" to (desktop.supervised&&!disabled.exists()),"current" to current,"channel" to if(betaChannel) "BETA" else "STABLE","latest" to latest,"message" to message,"checkedAt" to checked,"checking" to checking,"repository" to "https://github.com/$repository","detail" to desktop.updates.snapshot())
    @Synchronized fun enabled(value:Boolean){check(desktop.supervised);if(value){disabled.deleteIfExists()}else{atomicText(disabled,"disabled");desktop.home.resolve("updates/pending.txt").deleteIfExists();desktop.updates.update(UpdateComponents.MANAGER_APP,current,null,"IDLE",message="자동 업데이트 적용을 해제했습니다")}}
    @Scheduled(initialDelay=60000,fixedDelay=21600000) fun periodic(){if(desktop.supervised&&!disabled.exists())checkNow()}
    fun releases():List<PublishedUpdate> = UpdateReleases.catalog(get(URI("https://api.github.com/repos/$repository/releases?per_page=100"),4*1024*1024),current,desktop.home.resolve("failed-update-version.txt").takeIf {it.exists()}?.readText()?.trim())
    @Synchronized fun install(version:String,allowPrerelease:Boolean):Map<String,Any?> {
        check(desktop.supervised) {"수동 업데이트는 설치 앱에서 지원합니다"}
        val release=UpdateReleases.select(releases(),version,allowPrerelease)
        return prepare(release.version)
    }
    @Synchronized fun checkNow():Map<String,Any?> = prepare(null)
    private fun prepare(selectedVersion:String?):Map<String,Any?> {
        check(desktop.supervised) { "자동 업데이트는 설치 앱에서 지원합니다" }
        if(selectedVersion==null){
            val pending=desktop.home.resolve("updates/pending.txt").takeIf {it.exists()}?.readText()?.trim()
            if(pending!=null && runCatching {SignedUpdates.compare(pending,current)>0}.getOrDefault(false)){
                latest=pending;message="$pending 업데이트 적용을 기다리고 있습니다. 대기 중인 버전을 유지합니다. 다른 버전은 릴리즈 목록에서 직접 선택하세요."
                return status()
            }
        }
        if(checking)return status();checking=true
        desktop.updates.update(UpdateComponents.MANAGER_APP,current,null,"CHECKING")
        try {
            val response=mapper.readTree(get(URI("https://api.github.com/repos/$repository/releases"+(if(selectedVersion!=null) "/tags/v$selectedVersion" else if(betaChannel) "?per_page=100" else "/latest")),4*1024*1024))
            val releases=if(betaChannel && selectedVersion==null) response.toList() else listOf(response)
            val release=releases.filter { !it.path("draft").asBoolean() && SignedUpdates.eligible(it.path("tag_name").asText().removePrefix("v"),it.path("prerelease").asBoolean(),betaChannel || selectedVersion!=null) && (selectedVersion==null || it.path("tag_name").asText()=="v$selectedVersion") }
                .maxWithOrNull { a,b->SignedUpdates.compare(a.path("tag_name").asText().removePrefix("v"),b.path("tag_name").asText().removePrefix("v")) }
                ?: run { message="이 채널에 공개된 업데이트가 없습니다";desktop.updates.update(UpdateComponents.MANAGER_APP,current,null,"DONE",message=message);return status() }
            val version=release.path("tag_name").asText().removePrefix("v");SignedUpdates.compare(version,current);latest=version;checked=Instant.now().toString()
            if(SignedUpdates.compare(version,current)<=0){message="최신 버전입니다";desktop.updates.update(UpdateComponents.MANAGER_APP,current,null,"DONE",message=message);return status()}
            if(desktop.home.resolve("failed-update-version.txt").takeIf { it.exists() }?.readText()?.trim()==version){message="이 버전은 시작 검증에 실패해 자동 적용을 중지했습니다. 다음 릴리스를 기다리거나 설치 파일을 확인하세요.";desktop.updates.update(UpdateComponents.MANAGER_APP,current,version,"FAILED",error=message);return status()}
            val base="https://github.com/$repository/releases/download/v$version/"
            desktop.updates.update(UpdateComponents.MANAGER_APP,current,version,"VERIFYING",message="게시자 서명을 확인합니다")
            val manifest=get(URI(base+"update.properties"),65536);val signature=get(URI(base+"update.sig"),1024);val p=SignedUpdates.manifest(manifest,signature,key)
            require(p.getProperty("version")==version)
            val dir=desktop.home.resolve("updates").resolve(version);dir.createDirectories()
            val target=dir.resolve(p.getProperty("file"))
            if(!SignedUpdates.validFile(target,p)){
                val tmp=Files.createTempFile(dir,"download-",".part")
                try { desktop.updates.update(UpdateComponents.MANAGER_APP,current,version,"DOWNLOADING");download(URI(base+p.getProperty("file")),tmp,p.getProperty("size").toLong());desktop.updates.update(UpdateComponents.MANAGER_APP,current,version,"VERIFYING",message="다운로드 파일 무결성을 확인합니다");require(SignedUpdates.validFile(tmp,p)) { "업데이트 파일 무결성 검증 실패" };Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }finally{tmp.deleteIfExists()}
            }
            check(UpdatePackageVerifier.verify(manifest,signature,target,key,version,current) is UpdatePackageVerifier.Result.Ok) {"업데이트 호환성 매니페스트를 확인할 수 없습니다"}
            Files.write(dir.resolve("update.properties"),manifest);Files.write(dir.resolve("update.sig"),signature)
            atomicText(desktop.home.resolve("updates/pending.txt"),version)
            message="업데이트를 받았습니다. 게임 소유 서비스와 전송 상태를 확인한 뒤 관리 앱을 교체합니다."
            desktop.updates.update(UpdateComponents.MANAGER_APP,current,version,"WAITING",waitReason="APPLY_WHEN_SAFE",message=message)
            desktop.restartWhenIdle()
        }catch(e:Exception){checked=Instant.now().toString();message=UpdateRedaction.text(e.message ?: "업데이트 확인 실패");desktop.updates.update(UpdateComponents.MANAGER_APP,current,latest,"FAILED",error=message)}finally{checking=false}
        return status()
    }
    private fun response(uri:URI):HttpResponse<java.io.InputStream> {
        var next=uri
        repeat(5){
            require(next.scheme=="https"&&next.userInfo==null&&next.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")) { "허용되지 않은 업데이트 주소" }
            val r=client.send(HttpRequest.newBuilder(next).timeout(Duration.ofSeconds(120)).header("User-Agent","ServerManager/$current").GET().build(),HttpResponse.BodyHandlers.ofInputStream())
            if(r.statusCode() in listOf(301,302,303,307,308)){r.body().close();next=next.resolve(r.headers().firstValue("Location").orElseThrow());return@repeat}
            if(r.statusCode()!=200){r.body().close();error(if(r.statusCode()==404)"아직 공개된 릴리스가 없습니다" else "GitHub 업데이트 응답: ${r.statusCode()}")};return r
        };error("업데이트 리디렉션 한도 초과")
    }
    private fun get(uri:URI,limit:Int):ByteArray=response(uri).body().use { input->val bytes=input.readNBytes(limit+1);require(bytes.size<=limit);bytes }
    private fun download(uri:URI,target:Path,limit:Long) { response(uri).body().use { input->target.outputStream().use { out->val buffer=ByteArray(65536);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit) { "업데이트 파일 크기 초과" };out.write(buffer,0,n)};require(total==limit) } } }
}
data class UpdatePreference(val enabled:Boolean)
data class UpdateSelection(val version:String,val allowPrerelease:Boolean=false)
@RestController
@RequestMapping("/api/admin/updates")
class UpdateApi(private val updates:AppUpdates) {
    @GetMapping fun status()=updates.status()
    @GetMapping("/releases") fun releases()=updates.releases()
    @PostMapping("/install") fun install(@RequestBody body:UpdateSelection,req:HttpServletRequest):Map<String,Any?> {freshDesktop(req);return updates.install(body.version,body.allowPrerelease)}
    @PutMapping fun enabled(@RequestBody body:UpdatePreference,req:HttpServletRequest):Map<String,Any?> { freshDesktop(req);updates.enabled(body.enabled);return updates.status() }
    @PostMapping("/check") fun check(req:HttpServletRequest):Map<String,Any?> { freshDesktop(req);return updates.checkNow() }
}
