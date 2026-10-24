package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.io.path.*

data class ServerArchiveUploadRequest(val filename:String,val size:Long)
data class ServerArchiveUpload(val token:String,val filename:String,val size:Long,val received:Long,val chunkSize:Int=ServerArchiveFormat.CHUNK,val expiresAt:String)
data class ServerArchivePreview(val token:String,val plan:ServerArchivePlan,val expiresAt:String)
data class ServerArchiveImportRequest(val token:String,val name:String,val loader:String,val minecraft:String,val loaderVersion:String,
    val entryJar:String?=null,val requiredJava:Int=21,val port:Int=25565,val memoryMb:Int=2048,val javaPath:String=System.getProperty("java.home")+"/bin/java")
data class ServerArchiveProgress(val phase:String,val files:Int=0,val bytes:Long=0,val totalFiles:Int=0,val totalBytes:Long=0,val message:String="")
private data class ArchiveUploadState(val owner:String,val filename:String,val size:Long,val state:String="UPLOADING",val updatedAt:String=Instant.now().toString())

@Service
class ServerArchiveImporter(private val manager:ServerManager) {
    private val mapper=jacksonObjectMapper()
    private val uploads=manager.root.resolve("archive-uploads").also {it.createDirectories()}
    private val active=mutableSetOf<String>()
    private val jobs=Executors.newSingleThreadExecutor()
    private val ttl=86400L
    private val reserve=128L*1024*1024
    private fun directory(token:String):Path {
        require(runCatching {UUID.fromString(token).toString()==token}.getOrDefault(false)) {"잘못된 업로드 토큰입니다"}
        return uploads.resolve(token)
    }
    private fun state(token:String,owner:String):ArchiveUploadState {
        val dir=directory(token);val file=dir.resolve("upload.json")
        if(!file.isRegularFile() || Files.isSymbolicLink(dir) || Files.isSymbolicLink(file) || file.fileSize()>8192)
            throw ResponseStatusException(HttpStatus.NOT_FOUND,"업로드가 없거나 만료됐습니다. 다시 업로드하세요")
        val state=mapper.readValue(file.readText(),ArchiveUploadState::class.java)
        if(state.owner!=owner)throw ResponseStatusException(HttpStatus.NOT_FOUND,"업로드를 찾을 수 없습니다")
        require(Instant.now().isBefore(Instant.parse(state.updatedAt).plusSeconds(ttl))) {"업로드가 만료됐습니다. 다시 업로드하세요"}
        return state
    }
    private fun save(token:String,state:ArchiveUploadState)=atomicText(directory(token).resolve("upload.json"),mapper.writeValueAsString(state.copy(updatedAt=Instant.now().toString())))
    private fun view(token:String,state:ArchiveUploadState)=ServerArchiveUpload(token,state.filename,state.size,directory(token).resolve("archive.zip").fileSize(),expiresAt=Instant.parse(state.updatedAt).plusSeconds(ttl).toString())
    @Scheduled(fixedDelay=3600000) @Synchronized fun cleanupExpired() {
        uploads.listDirectoryEntries().filter {it.name !in active && runCatching {UUID.fromString(it.name).toString()==it.name}.getOrDefault(false) && it.isDirectory(java.nio.file.LinkOption.NOFOLLOW_LINKS)}.forEach {dir->
            val file=dir.resolve("upload.json").takeIf {it.exists()} ?: dir
            if(!Instant.now().isBefore(Files.getLastModifiedTime(file,java.nio.file.LinkOption.NOFOLLOW_LINKS).toInstant().plusSeconds(ttl)))remove(dir)
        }
    }
    private fun remove(dir:Path) {Files.walk(dir).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}}
    @Synchronized fun create(owner:String,req:ServerArchiveUploadRequest):ServerArchiveUpload {
        cleanupExpired()
        require(owner.isNotBlank() && req.filename.length in 1..255 && req.filename.endsWith(".zip",true) && '/' !in req.filename && '\\' !in req.filename && req.filename.none {it.isISOControl()}) {"서버 폴더를 압축한 .zip 파일을 선택하세요"}
        require(req.size in 1..ServerArchiveFormat.MAX_ARCHIVE) {"ZIP 업로드는 최대 20 GiB입니다"}
        val dirs=uploads.listDirectoryEntries();require(dirs.size<4) {"업로드 보관 한도 4개에 도달했습니다. 기존 업로드를 취소하거나 24시간 후 다시 시도하세요"}
        val outstanding=dirs.sumOf {dir->runCatching {val s=mapper.readValue(dir.resolve("upload.json").readText(),ArchiveUploadState::class.java);maxOf(0L,s.size-dir.resolve("archive.zip").fileSize())}.getOrDefault(0L)}
        require(Files.getFileStore(uploads).usableSpace>=req.size+outstanding+reserve) {"ZIP 업로드를 위한 디스크 여유 공간이 부족합니다"}
        val token=UUID.randomUUID().toString();val dir=directory(token).createDirectories()
        try {dir.resolve("archive.zip").createFile();val state=ArchiveUploadState(owner,req.filename,req.size);save(token,state);return view(token,state)}
        catch(e:Exception){remove(dir);throw e}
    }
    @Synchronized fun status(owner:String,token:String)=view(token,state(token,owner))
    @Synchronized fun append(owner:String,token:String,offset:Long,input:InputStream):ServerArchiveUpload {
        val state=state(token,owner);require(state.state=="UPLOADING") {"이 업로드는 이미 검증되었거나 사용됐습니다"}
        val dir=directory(token);val archive=dir.resolve("archive.zip");val current=archive.fileSize()
        if(offset!=current)throw ResponseStatusException(HttpStatus.CONFLICT,"업로드 위치가 달라졌습니다. 상태를 확인하고 이어서 업로드하세요")
        require(current<state.size) {"업로드가 이미 완료됐습니다"}
        val limit=minOf(ServerArchiveFormat.CHUNK.toLong(),state.size-current)
        require(Files.getFileStore(dir).usableSpace>=limit+reserve) {"디스크 여유 공간이 부족합니다"}
        val part=dir.resolve(".chunk-${UUID.randomUUID()}")
        try {
            val count=input.use {ServerArchiveFormat.copy(it,part,limit)};require(count>0) {"빈 업로드 청크입니다"}
            try {Files.newByteChannel(archive,WRITE).use {channel->channel.position(current);part.inputStream().use {it.copyTo(java.nio.channels.Channels.newOutputStream(channel),64*1024)}}}
            catch(e:Exception){Files.newByteChannel(archive,WRITE).use {it.truncate(current)};throw e}
            save(token,state);return view(token,state(token,owner))
        } finally {part.deleteIfExists()}
    }
    @Synchronized fun preview(owner:String,token:String):ServerArchivePreview {
        val state=state(token,owner);require(state.state in setOf("UPLOADING","READY")) {"이미 가져온 업로드입니다"}
        val archive=directory(token).resolve("archive.zip");require(archive.fileSize()==state.size) {"파일 업로드가 아직 완료되지 않았습니다"}
        val plan=ServerArchiveFormat.inspect(archive,state.filename)
        save(token,state.copy(state="READY"));return ServerArchivePreview(token,plan,Instant.now().plusSeconds(ttl).toString())
    }
    @Synchronized fun cancel(owner:String,token:String) {
        state(token,owner);check(token !in active) {"이미 서버로 가져오는 중입니다. 인스턴스의 완료·실패 상태를 확인하세요"}
        remove(directory(token))
    }
    @Synchronized fun import(owner:String,req:ServerArchiveImportRequest):Map<String,Any?> {
        val state=state(req.token,owner);require(state.state=="READY") {"미리보기를 완료한 사용하지 않은 ZIP을 선택하세요"}
        Launchers.validate(req.loader,req.minecraft,req.loaderVersion)
        require(req.requiredJava in setOf(17,21,25)) {"필요 Java 버전을 선택하세요"}
        val plan=ServerArchiveFormat.inspect(directory(req.token).resolve("archive.zip"),state.filename)
        require(req.entryJar==null || (req.loader !in setOf("Forge","NeoForge") && req.entryJar in plan.jars)) {"압축파일 안의 서버 JAR를 선택하세요"}
        require(Files.getFileStore(uploads).usableSpace>=plan.bytes+reserve) {"압축 해제를 위한 디스크 여유 공간이 부족합니다"}
        save(req.token,state.copy(state="IMPORTING"))
        val row=try {manager.beginArchiveImport(CreateRequest(req.name,req.loaderVersion,req.port,req.memoryMb,req.javaPath,req.loader,req.minecraft),req.token)}
            catch(e:Exception){save(req.token,state);throw e}
        val id=(row["config"] as Instance).id;val r=manager.runtime(id)
        r.archiveImport=ServerArchiveProgress("EXTRACTING",totalFiles=plan.files,totalBytes=plan.bytes,message="ZIP을 검증하며 새 서버 폴더에 압축을 풀고 있습니다")
        active.add(req.token)
        jobs.submit {
            val dir=directory(req.token);val stage=dir.resolve("stage")
            try {
                var reported=0L
                ServerArchiveFormat.extract(dir.resolve("archive.zip"),plan.root,stage) {files,bytes->
                    val now=System.nanoTime();if(now-reported>250_000_000 || files==plan.files){reported=now;r.archiveImport=ServerArchiveProgress("EXTRACTING",files,bytes,plan.files,plan.bytes,"압축 해제·CRC 검증 중")}
                }
                val properties=stage.resolve("server.properties")
                if(properties.exists()) {
                    require(properties.fileSize()<=2L*1024*1024) {"server.properties 크기가 너무 큽니다"}
                    val raw=properties.readBytes()
                    val text=runCatching {Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString()}.getOrElse {raw.toString(Charsets.ISO_8859_1)}
                    val values=java.util.Properties().apply {java.io.StringReader(text).use(::load)}
                    ServerArchiveFormat.relative(values.getProperty("level-name","world"))
                    // Preserve UTF-8 values when the existing runtime writer later reads Java .properties escapes.
                    properties.outputStream().use {values.store(it,"Imported server properties")}
                }
                val metadata=mapOf("loader" to req.loader,"minecraft" to req.minecraft,"loaderVersion" to req.loaderVersion,"requiredJava" to req.requiredJava,"source" to "server-archive")+
                    (req.entryJar?.let {mapOf("entryJar" to it)} ?: emptyMap())
                atomicText(stage.resolve("server-manager-runtime.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata))
                val ready=runCatching {Launchers.arguments(manager.instance(id),stage);true}.getOrDefault(false)
                manager.finishArchiveImport(id,stage,ready)
                r.archiveImport=ServerArchiveProgress("COMPLETE",plan.files,plan.bytes,plan.files,plan.bytes,if(ready)"서버 가져오기 완료. 실행 컴퓨터와 설정을 확인한 뒤 시작하세요" else "파일 가져오기 완료. 서버 실행 파일이 없어 공식 런처 설치가 필요합니다")
                synchronized(this){remove(dir)}
            } catch(e:Exception) {
                synchronized(r){r.state="IMPORT_FAILED";r.archiveImport=r.archiveImport?.copy(phase="FAILED",message=(e.message ?: "압축파일 가져오기 실패").take(500));r.event("error","서버 ZIP 가져오기 실패: ${e.message}. 원본 ZIP을 다시 가져오세요")}
                stage.toFile().deleteRecursively()
            } finally {synchronized(this){active.remove(req.token)}}
        }
        return manager.view(id)
    }
    @PreDestroy fun shutdown(){jobs.shutdownNow();jobs.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)}
}

@RestController
@RequestMapping("/api/server-archives")
class ServerArchiveApi(private val importer:ServerArchiveImporter) {
    private fun <T> noStore(value:T)=ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(value)
    @PostMapping("/uploads") fun create(@RequestBody req:ServerArchiveUploadRequest,auth:org.springframework.security.core.Authentication)=noStore(importer.create(auth.name,req))
    @GetMapping("/uploads/{token}") fun status(@PathVariable token:String,auth:org.springframework.security.core.Authentication)=noStore(importer.status(auth.name,token))
    @PutMapping("/uploads/{token}",consumes=["application/octet-stream"]) fun append(@PathVariable token:String,@RequestParam offset:Long,req:HttpServletRequest,auth:org.springframework.security.core.Authentication)=noStore(importer.append(auth.name,token,offset,req.inputStream))
    @PostMapping("/uploads/{token}/preview") fun preview(@PathVariable token:String,auth:org.springframework.security.core.Authentication)=noStore(importer.preview(auth.name,token))
    @DeleteMapping("/uploads/{token}") fun cancel(@PathVariable token:String,auth:org.springframework.security.core.Authentication){importer.cancel(auth.name,token)}
    @PostMapping("/import") fun import(@RequestBody req:ServerArchiveImportRequest,auth:org.springframework.security.core.Authentication)=noStore(importer.import(auth.name,req))
}
