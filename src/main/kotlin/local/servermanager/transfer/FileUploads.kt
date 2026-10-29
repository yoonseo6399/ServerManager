package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.io.InputStream
import java.nio.file.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.io.path.*

data class FileUploadRequest(val filename:String,val size:Long,val path:String="",val clientId:String,val documentId:String="")
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(value=["cancellable"],allowGetters=true)
data class FileUploadStatus(val token:String,val instanceId:String,val filename:String,val path:String,val size:Long,val clientId:String,val documentId:String="",
    val phase:String="RECEIVING",val received:Long=0,val transferred:Long=0,val message:String="파일을 받는 중",val updatedAt:String=Instant.now().toString(),val chunkSize:Int=1024*1024,val revision:Long=0) {
    val cancellable:Boolean get()=phase in setOf("RECEIVING","QUEUED","TRANSFERRING")
}
internal data class SavedFileUpload(val owner:String,val runnerId:String?,val status:FileUploadStatus,val dismissed:Boolean=false)
private class UploadCancelled:RuntimeException("업로드가 취소되었습니다")

/** Disk-backed status outlives browser requests. Only a completed staged file may enter the writer. */
@Service
class FileUploads(private val manager:ServerManager,private val remote:RemoteFiles,private val accounts:AuthStore) {
    private val mapper=jacksonObjectMapper()
    private val directory=manager.root.resolve("file-uploads").also {it.createDirectories();require(!Files.isSymbolicLink(it))}
    private val jobs=linkedMapOf<String,SavedFileUpload>()
    private val workers=Executors.newFixedThreadPool(2)
    private val running=mutableSetOf<String>()
    private var closing=false
    companion object {const val MAX=256L*1024*1024;private val TERMINAL=setOf("COMPLETE","CANCELLED","FAILED")}
    init {
        directory.listDirectoryEntries().filter {it.isDirectory(LinkOption.NOFOLLOW_LINKS) && validToken(it.name)}.forEach {dir->
            runCatching {
                require(!Files.isSymbolicLink(dir));val file=dir.resolve("status.json");require(file.isRegularFile(LinkOption.NOFOLLOW_LINKS) && file.fileSize()<16384)
                val saved=mapper.readValue(file.readText(),SavedFileUpload::class.java);require(saved.status.token==dir.name)
                val s=saved.status
                val recovered=if(s.phase in TERMINAL)s else s.copy(phase="FAILED",message=if(s.phase=="APPLYING")"관리웹이 재시작되었습니다. 파일 반영 여부를 확인한 뒤 다시 업로드하세요" else "관리웹 재시작으로 업로드가 중단되었습니다. 다시 업로드하세요",updatedAt=Instant.now().toString())
                jobs[s.token]=saved.copy(status=recovered);persist(s.token);payload(s.token).deleteIfExists()
            }
        }
    }
    private fun validToken(token:String)=runCatching {UUID.fromString(token).toString()==token}.getOrDefault(false)
    private fun payload(token:String)=directory.resolve(token).resolve("content.part")
    private fun persist(token:String){val job=jobs.getValue(token);atomicText(directory.resolve(token).resolve("status.json"),mapper.writeValueAsString(job))}
    private fun change(token:String,update:(FileUploadStatus)->FileUploadStatus):FileUploadStatus {
        val saved=jobs.getValue(token);val status=update(saved.status).copy(updatedAt=Instant.now().toString(),revision=saved.status.revision+1);jobs[token]=saved.copy(status=status);persist(token);return status
    }
    private fun access(owner:String,id:String){if(!accounts.allowed(owner,id,true))throw ResponseStatusException(HttpStatus.FORBIDDEN,"파일 변경 권한이 없습니다");manager.instance(id)}
    private fun owned(owner:String,id:String,token:String):SavedFileUpload {
        access(owner,id);val job=jobs[token]
        if(job==null || job.owner!=owner || job.status.instanceId!=id)throw ResponseStatusException(HttpStatus.NOT_FOUND,"업로드를 찾을 수 없습니다")
        return job
    }
    @Synchronized fun list(owner:String,id:String):List<FileUploadStatus> {access(owner,id);return jobs.values.filter {it.owner==owner && it.status.instanceId==id && !it.dismissed}.map {it.status}}
    @Synchronized fun status(owner:String,id:String,token:String)=owned(owner,id,token).status
    @Synchronized fun create(owner:String,id:String,req:FileUploadRequest):FileUploadStatus {
        access(owner,id);cleanup()
        require(req.filename.length in 1..255 && req.filename !in setOf(".","..") && req.filename.none {it.isISOControl()||it=='/'||it=='\\'}) {"파일 이름을 확인하세요"}
        require(req.path.isEmpty() || PackFormat.relative(req.path)==req.path) {"업로드 폴더 경로를 확인하세요"}
        require(req.size in 0..MAX) {"파일 업로드는 최대 256 MiB입니다"};require(validToken(req.clientId) && (req.documentId.isEmpty() || validToken(req.documentId))) {"업로드 탭 식별자가 올바르지 않습니다"}
        val active=jobs.values.filter {it.status.phase !in TERMINAL}
        check(active.size<32 && active.count {it.owner==owner}<16) {"진행 중인 업로드가 많습니다. 완료하거나 취소한 뒤 다시 시도하세요"}
        val reserved=active.sumOf {it.status.size}
        check(Files.getFileStore(directory).usableSpace>req.size+reserved+128L*1024*1024) {"업로드 임시 공간이 부족합니다"}
        val token=UUID.randomUUID().toString();val dir=directory.resolve(token).createDirectory();dir.resolve("content.part").createFile()
        val status=FileUploadStatus(token,id,req.filename,req.path,req.size,req.clientId,documentId=req.documentId)
        jobs[token]=SavedFileUpload(owner,manager.instance(id).runnerId,status);persist(token);return status
    }
    /** One bounded HTTP body is read before taking the registry lock, so cancel never waits on the network. */
    fun append(owner:String,id:String,token:String,offset:Long,input:InputStream):FileUploadStatus {
        synchronized(this){check(owned(owner,id,token).status.phase=="RECEIVING") {"이 업로드는 파일을 받을 수 없습니다"}}
        val bytes=input.readNBytes(1024*1024+1);require(bytes.isNotEmpty() && bytes.size<=1024*1024) {"업로드 조각은 1 MiB 이하여야 합니다"}
        synchronized(this){
            val s=owned(owner,id,token).status;check(s.phase=="RECEIVING") {"업로드가 취소되었거나 이미 접수됐습니다"}
            if(offset!=s.received)throw ResponseStatusException(HttpStatus.CONFLICT,"업로드 위치가 달라졌습니다. 현재 상태를 확인하세요")
            require(bytes.size<=s.size-s.received) {"선언한 파일 크기를 초과했습니다"}
            val file=payload(token);require(!Files.isSymbolicLink(file))
            Files.newByteChannel(file,StandardOpenOption.WRITE).use {channel->
                channel.truncate(offset);channel.position(offset)
                try {val b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b)}catch(e:Exception){channel.truncate(offset);throw e}
            }
            return change(token){it.copy(received=offset+bytes.size)}
        }
    }
    @Synchronized fun complete(owner:String,id:String,token:String):FileUploadStatus {
        val s=owned(owner,id,token).status
        if(s.phase!="RECEIVING")return s // Safe after a lost acknowledgement; never apply twice.
        check(s.received==s.size && payload(token).fileSize()==s.size) {"파일을 모두 받을 때까지 기다리세요"}
        val result=change(token){it.copy(phase="QUEUED",message="실행기 반영 대기 중")};dispatch();return result
    }
    @Synchronized fun cancel(owner:String,id:String,token:String):FileUploadStatus {
        val s=owned(owner,id,token).status
        check(s.phase!="APPLYING") {"파일을 반영 중이라 취소할 수 없습니다. 완료 상태를 확인하세요"}
        if(s.phase in TERMINAL)return s
        val result=change(token){it.copy(phase="CANCELLED",message="업로드 취소됨 · 기존 파일 보존")}
        if(id !in running || s.phase!="TRANSFERRING")payload(token).deleteIfExists()
        return result
    }
    @Synchronized fun dismiss(owner:String,id:String,token:String) {
        check(owned(owner,id,token).status.phase in TERMINAL) {"진행 중인 업로드는 먼저 취소하세요"}
        jobs[token]=jobs.getValue(token).copy(dismissed=true);persist(token)
    }
    @Synchronized private fun dispatch() {
        if(closing)return
        for(saved in jobs.values.toList()) {
            val s=saved.status;if(s.phase!="QUEUED" || s.instanceId in running || running.size>=2)continue
            running.add(s.instanceId);change(s.token){it.copy(phase="TRANSFERRING",message="실행기로 파일 전송 중")}
            workers.submit {run(s.token)}
        }
    }
    private fun run(token:String) {
        val saved=synchronized(this){jobs.getValue(token)};val id=saved.status.instanceId
        fun checkAllowed() {access(saved.owner,id);check(manager.instance(id).runnerId==saved.runnerId) {"실행 컴퓨터가 변경되었습니다. 업로드를 다시 시작하세요"}}
        fun progress(bytes:Long) {checkAllowed();synchronized(this){if(closing || jobs.getValue(token).status.phase=="CANCELLED")throw UploadCancelled();change(token){it.copy(transferred=bytes)}}}
        fun commit() {checkAllowed();synchronized(this){if(closing || jobs.getValue(token).status.phase=="CANCELLED")throw UploadCancelled();change(token){it.copy(phase="APPLYING",message="파일 반영 중 · 이 단계는 취소할 수 없습니다")}}}
        try {
            progress(0);val path=listOf(saved.status.path,saved.status.filename).filter {it.isNotEmpty()}.joinToString("/")
            payload(token).inputStream().use {input->
                if(remote.filesRemote(id))remote.write(id,path,input,onProgress=::progress,beforeCommit=::commit)
                else manager.writeStream(id,path,input,onProgress=::progress,beforeCommit=::commit)
            }
            synchronized(this){change(token){it.copy(phase="COMPLETE",transferred=it.size,message="업로드 완료 · 다음 서버 시작부터 적용")}}
        } catch(e:Exception) {
            synchronized(this){if(jobs.getValue(token).status.phase!="CANCELLED")change(token){it.copy(phase="FAILED",message=((if(it.phase=="APPLYING")"반영 결과를 확인하지 못했습니다. 파일을 확인하세요: " else "")+(e.message ?: "파일 업로드 실패")).take(500))}}
        } finally {
            synchronized(this){payload(token).deleteIfExists();running.remove(id);dispatch()}
        }
    }
    @Scheduled(fixedDelay=60000) @Synchronized fun cleanup() {
        val now=Instant.now()
        jobs.values.toList().forEach {saved->
            val s=saved.status;val age=java.time.Duration.between(Instant.parse(s.updatedAt),now).seconds
            if(s.phase=="RECEIVING" && age>900){change(s.token){it.copy(phase="CANCELLED",message="전송이 중단되어 업로드를 취소했습니다")};payload(s.token).deleteIfExists()}
            if(s.phase in TERMINAL && age>86400 && s.instanceId !in running){directory.resolve(s.token).toFile().deleteRecursively();jobs.remove(s.token)}
        }
    }
    @PreDestroy fun close(){synchronized(this){closing=true};workers.shutdownNow();workers.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)}
}

@RestController
@RequestMapping("/api/instances/{id}/file-uploads")
class FileUploadApi(private val uploads:FileUploads) {
    private fun <T> reply(value:T,accepted:Boolean=false)=ResponseEntity.status(if(accepted)202 else 200).cacheControl(CacheControl.noStore()).body(value)
    @GetMapping fun list(@PathVariable id:String,auth:org.springframework.security.core.Authentication)=reply(uploads.list(auth.name,id))
    @GetMapping("/{token}") fun status(@PathVariable id:String,@PathVariable token:String,auth:org.springframework.security.core.Authentication)=reply(uploads.status(auth.name,id,token))
    @PostMapping fun create(@PathVariable id:String,@RequestBody req:FileUploadRequest,auth:org.springframework.security.core.Authentication)=reply(uploads.create(auth.name,id,req))
    @PutMapping("/{token}/content",consumes=["application/octet-stream"]) fun append(@PathVariable id:String,@PathVariable token:String,@RequestParam offset:Long,req:HttpServletRequest,auth:org.springframework.security.core.Authentication)=reply(uploads.append(auth.name,id,token,offset,req.inputStream))
    @PostMapping("/{token}/complete") fun complete(@PathVariable id:String,@PathVariable token:String,auth:org.springframework.security.core.Authentication)=reply(uploads.complete(auth.name,id,token),true)
    @PostMapping("/{token}/cancel") fun cancel(@PathVariable id:String,@PathVariable token:String,auth:org.springframework.security.core.Authentication)=reply(uploads.cancel(auth.name,id,token))
    @DeleteMapping("/{token}") fun dismiss(@PathVariable id:String,@PathVariable token:String,auth:org.springframework.security.core.Authentication){uploads.dismiss(auth.name,id,token)}
}
