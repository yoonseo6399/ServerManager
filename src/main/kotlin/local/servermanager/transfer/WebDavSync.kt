package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.io.path.*

data class CloudFile(val sha256:String,val size:Long)
data class CloudManifest(val generation:String,val previous:String?,val files:Map<String,CloudFile>,val directories:Set<String>,val createdAt:String)
data class WebDavSyncStatus(val enabled:Boolean,val inProgress:Boolean,val lastSuccess:String?,val lastError:String?,val generation:String?)

/** A private content-addressed object store on an ordinary WebDAV folder. The manifest is committed last. */
class WebDavClient(config:WebDavConfig,password:String) {
    private val base=config.url.trimEnd('/')+"/"
    private val authorization="Basic "+Base64.getEncoder().encodeToString("${config.username}:$password".toByteArray())
    private val client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build()
    private fun url(path:String):URI=URI.create(base+path)
    private fun request(method:String,path:String,body:HttpRequest.BodyPublisher=HttpRequest.BodyPublishers.noBody(),headers:Map<String,String> = emptyMap()):HttpRequest {
        val builder=HttpRequest.newBuilder(url(path)).timeout(Duration.ofMinutes(30)).header("Authorization",authorization)
        headers.forEach { (key,value)->builder.header(key,value) }
        return builder.method(method,body).build()
    }
    fun collection(path:String) {
        val response=client.send(request("MKCOL",path),HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in setOf(201,405)) { "WebDAV 폴더 준비 실패: HTTP ${response.statusCode()}" }
    }
    fun get(path:String):Pair<ByteArray,String?>? {
        val response=client.send(request("GET",path),HttpResponse.BodyHandlers.ofByteArray())
        if(response.statusCode()==404) return null
        check(response.statusCode()==200) { "WebDAV 파일 읽기 실패: HTTP ${response.statusCode()}" }
        return response.body() to response.headers().firstValue("ETag").orElse(null)
    }
    fun exists(path:String):Boolean {
        val response=client.send(request("HEAD",path),HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in setOf(200,404)) { "WebDAV 파일 확인 실패: HTTP ${response.statusCode()}" }
        return response.statusCode()==200
    }
    fun upload(path:String,file:Path) {
        val response=client.send(request("PUT",path,HttpRequest.BodyPublishers.ofFile(file)),HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in setOf(200,201,204)) { "WebDAV 파일 업로드 실패: HTTP ${response.statusCode()}" }
    }
    fun commit(path:String,bytes:ByteArray,etag:String?) {
        val condition=if(etag==null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to etag)
        val response=client.send(request("PUT",path,HttpRequest.BodyPublishers.ofByteArray(bytes),condition+mapOf("Content-Type" to "application/json")),HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in setOf(200,201,204)) {
            if(response.statusCode()==409 || response.statusCode()==412) "WebDAV에 다른 버전이 있습니다. 동기화를 중단했습니다"
            else "WebDAV 매니페스트 저장 실패: HTTP ${response.statusCode()}"
        }
    }
    fun download(path:String,target:Path) {
        val response=client.send(request("GET",path),HttpResponse.BodyHandlers.ofFile(target))
        check(response.statusCode()==200) { "WebDAV 파일 다운로드 실패: HTTP ${response.statusCode()}" }
    }
}

@Service
class WebDavSync(private val manager:ServerManager,private val settings:WebDavSettings) {
    private val mapper=jacksonObjectMapper()
    private val executor=Executors.newSingleThreadExecutor()
    private val running=mutableSetOf<String>()
    private val successes=mutableMapOf<String,String>()
    private val errors=mutableMapOf<String,String>()
    private val subscription=manager.onSignal { signal -> if(signal.kind in setOf("stop","import","snapshot")) {
        if(!manager.backgroundPending(signal.instanceId) && manager.instance(signal.instanceId).syncEnabled() && !manager.runtime(signal.instanceId).diagnosticActive && settings.view().config.enabled) runCatching { schedule(signal.instanceId) }
        else manager.remoteCloudReady(signal.instanceId)
    } }
    private fun stateFile(id:String)=manager.root.resolve(id).resolve("webdav-sync.json")
    @Synchronized fun status(id:String):WebDavSyncStatus {
        manager.instance(id)
        return WebDavSyncStatus(settings.view().config.enabled && manager.instance(id).syncEnabled(),id in running,successes[id],errors[id],readState(id))
    }
    @Synchronized fun schedule(id:String) {
        check(manager.instance(id).syncEnabled()) {"서버 파일 동기화가 꺼져 있습니다"}
        require(settings.view().config.enabled) { "WebDAV 동기화가 꺼져 있습니다" }
        if(!running.add(id)) return
        executor.submit {
            try { syncNow(id);synchronized(this) { successes[id]=Instant.now().toString();errors.remove(id) };manager.remoteCloudReady(id) }
            catch(e:Exception) { synchronized(this) { errors[id]=e.message?.take(300) ?: "동기화 실패" } }
            finally { synchronized(this) { running.remove(id) } }
        }
    }
    fun syncNow(id:String):CloudManifest {
        check(manager.instance(id).syncEnabled()) {"서버 파일 동기화가 꺼져 있습니다"}
        val runtime=manager.runtime(id)
        val directory=synchronized(runtime) {
            check(!runtime.diagnosticActive && !runtime.modBusy && !runtime.cloudSyncActive) {"모드 검사·변경 중에는 운영 클라우드에 동기화하지 않습니다"}
            runtime.cloudSyncActive=true;manager.serverDir(id)
        }
        try {return syncFiles(id,directory)}finally {runtime.cloudSyncActive=false}
    }
    private fun syncFiles(id:String,directory:Path):CloudManifest {
        val state=manager.view(id)["state"].toString()
        check(state !in setOf("RUNNING","STARTING","STOPPING","INSTALLING","IMPORTING")) { "서버가 완전히 종료된 뒤 동기화할 수 있습니다" }
        val (config,password)=settings.credentials() ?: error("WebDAV 설정이 없습니다")
        val client=WebDavClient(config,password)
        val base="instances/$id"
        client.collection("instances")
        client.collection(base)
        client.collection("$base/objects")
        val remote=client.get("$base/manifest.json")
        val remoteManifest=remote?.first?.let { mapper.readValue<CloudManifest>(it) }
        val localGeneration=readState(id)
        val (files,dirs)=collect(directory)
        if(remoteManifest!=null && remoteManifest.generation!=localGeneration && remoteManifest.previous==localGeneration && remoteManifest.files==files && remoteManifest.directories==dirs) {
            writeState(id,remoteManifest.generation)
            return remoteManifest
        }
        check(remoteManifest?.generation==localGeneration) { "WebDAV 버전이 로컬 기록과 달라 자동 덮어쓰기를 중단했습니다" }
        if(remoteManifest!=null) check(remote?.second!=null) { "WebDAV 서버가 ETag를 제공하지 않아 안전하게 갱신할 수 없습니다" }
        if(remoteManifest!=null && remoteManifest.files==files && remoteManifest.directories==dirs) return remoteManifest
        val changed=files.filter { (path,file)->remoteManifest?.files?.get(path)!=file }
        changed.forEach { (path,file) ->
            val blob="$base/objects/${file.sha256}"
            if(!client.exists(blob)) client.upload(blob,directory.resolve(path))
        }
        val manifest=CloudManifest(UUID.randomUUID().toString(),localGeneration,files,dirs,Instant.now().toString())
        client.commit("$base/manifest.json",mapper.writeValueAsBytes(manifest),remote?.second)
        writeState(id,manifest.generation)
        return manifest
    }
    fun restoreIntoEmptyDirectory(id:String,target:Path):CloudManifest {
        manager.instance(id)
        require(!target.exists() || Files.list(target).use { it.findAny().isEmpty }) { "복원 대상 폴더가 비어 있어야 합니다" }
        val (config,password)=settings.credentials() ?: error("WebDAV 설정이 없습니다")
        val client=WebDavClient(config,password)
        val manifest=client.get("instances/$id/manifest.json")?.first?.let { mapper.readValue<CloudManifest>(it) } ?: error("클라우드에 서버 데이터가 없습니다")
        target.createDirectories()
        manifest.directories.forEach { FileInventory.safePath(target,it).createDirectories() }
        manifest.files.forEach { (relative,file) ->
            val path=FileInventory.safePath(target,relative)
            path.parent.createDirectories()
            val temp=Files.createTempFile(path.parent,".restore-",".tmp")
            try {
                client.download("instances/$id/objects/${file.sha256}",temp)
                check(Files.size(temp)==file.size && FileInventory.digest(temp)==file.sha256) { "복원 파일 해시가 다릅니다" }
                Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING)
            } finally { temp.deleteIfExists() }
        }
        return manifest
    }
    fun collect(base:Path):Pair<Map<String,CloudFile>,Set<String>> = FileInventory.collect(base)
    private fun readState(id:String):String?=stateFile(id).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }
    private fun writeState(id:String,generation:String) {
        val path=stateFile(id)
        val tmp=Files.createTempFile(path.parent,".sync-",".tmp")
        try { tmp.writeText(generation+"\n");Files.move(tmp,path,ATOMIC_MOVE,REPLACE_EXISTING) } finally { tmp.deleteIfExists() }
    }
    @PreDestroy fun close() { subscription.close();executor.shutdown() }
}
