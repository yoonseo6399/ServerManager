package local.servermanager

import jakarta.annotation.PreDestroy
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

data class FolderDownloadRequest(val path:String,val source:String="current")
data class FolderDownloadStatus(val token:String,val phase:String,val message:String,val files:Int=0,val completed:Int=0,val bytes:Long=0,val totalBytes:Long=0,val source:String="current",val copiedAt:String?=null,val zipBytes:Long=0,val downloadedBytes:Long=0,val downloadState:String="WAITING")

@Service
class FolderDownloads(private val manager:ServerManager,private val transfers:RunnerTransfer) {
    private data class Job(val instance:String,val file:Path,val filename:String,val status:FolderDownloadStatus,val created:Long=System.currentTimeMillis())
    private val remote=RemoteFiles(manager)
    private val directory=manager.root.resolve("folder-downloads").also {it.createDirectories()}
    private val executor=Executors.newFixedThreadPool(2)
    private val jobs=ConcurrentHashMap<String,Job>()
    @Synchronized fun create(id:String,path:String,source:String="current"):FolderDownloadStatus {
        manager.instance(id)
        require(source in setOf("current","snapshot")) {"다운로드 원본을 선택하세요"}
        val useRemote=source=="current" && remote.remote(id)
        val snapshotMarker=manager.root.resolve(id).resolve("latest-snapshot.json")
        var snapshotStamp:String?=null
        fun local(relative:String):Path {
            if(source!="snapshot")return manager.path(id,relative)
            val base=manager.originalServerDir(id)
            require(base.isDirectory(NOFOLLOW_LINKS) && !Files.isSymbolicLink(base)) {"중앙 복사본이 없습니다"}
            val target=if(relative.isEmpty())base else FileInventory.safePath(base,relative)
            var parent:Path?=target
            while(parent!=null && parent.startsWith(base)){require(!Files.isSymbolicLink(parent)) {"심볼릭 링크는 다운로드할 수 없습니다"};parent=parent.parent}
            check(!manager.runtime(id).recoveringFiles) {"중앙 복사본을 반영 중입니다. 잠시 후 다시 시도하세요"}
            return target
        }
        require(path.isEmpty() || PackFormat.relative(path)==path) {"폴더 경로를 확인하세요"}
        check(jobs.values.count {it.status.phase=="WORKING"}<8) {"진행 중인 압축 작업이 많습니다. 잠시 후 다시 시도하세요"}
        val token=UUID.randomUUID().toString();val name=(path.substringAfterLast('/').ifBlank {manager.instance(id).name}).replace(Regex("[^\\p{L}\\p{N}._ -]"),"_").take(100)+".zip"
        var initial=FolderDownloadStatus(token,"WORKING","폴더 파일 목록을 확인합니다",source=source)
        jobs[token]=Job(id,directory.resolve("$token.zip"),name,initial)
        executor.submit {try {
          fun build() {
            if(source=="snapshot") {
                snapshotStamp=if(snapshotMarker.isRegularFile())snapshotMarker.readText() else null
                val copiedAt=snapshotStamp?.let {runCatching {com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(it).path("completedAt").asText().takeIf(String::isNotBlank)}.getOrNull()}
                initial=initial.copy(copiedAt=copiedAt)
            }
            val entries=mutableListOf<FileEntry>();var total=0L
            fun list(relative:String,depth:Int=0) {
                require(depth<64) {"폴더가 너무 깊습니다"}
                val rows=if(useRemote)remote.files(id,relative) else {
                    val folder=local(relative);require(folder.isDirectory(NOFOLLOW_LINKS)) {"다운로드할 폴더가 없습니다"}
                    Files.list(folder).use {paths->paths.filter {!Files.isSymbolicLink(it)}.map {file->FileEntry(file.name,listOf(relative,file.name).filter {it.isNotEmpty()}.joinToString("/"),file.isDirectory(NOFOLLOW_LINKS),if(file.isDirectory(NOFOLLOW_LINKS))0 else file.fileSize())}.toList()}
                }
                for(entry in rows){require(entries.size<100000) {"압축 항목은 100,000개까지 지원합니다"};entries+=entry
                    if(entry.directory)list(entry.path,depth+1) else {total+=entry.size;require(total<=32L*1024*1024*1024) {"압축 전 폴더 크기는 32 GiB까지 지원합니다"}}
                }
            }
            list(path)
            check(Files.getFileStore(directory).usableSpace>total+128L*1024*1024) {"ZIP을 준비할 디스크 공간이 부족합니다"}
            var done=0;var bytes=0L;val files=entries.count {!it.directory};var last=0L
            fun progress(message:String,force:Boolean=false){val now=System.nanoTime();if(force||now-last>100_000_000){last=now;jobs.computeIfPresent(token){_,job->job.copy(status=initial.copy(message=message,files=files,completed=done,bytes=bytes,totalBytes=total))}}}
            ZipOutputStream(jobs.getValue(token).file.outputStream().buffered()).use {zip->
                entries.forEach {entry->
                    val relative=if(path.isEmpty())entry.path else entry.path.removePrefix("$path/")
                    require(PackFormat.relative(relative)==relative)
                    zip.putNextEntry(ZipEntry(relative+if(entry.directory)"/" else ""))
                    if(!entry.directory) {
                        val counting=object:OutputStream(){override fun write(value:Int){check(bytes<total) {"압축 중 파일 크기가 변경되었습니다"};zip.write(value);bytes++;progress(entry.path)};override fun write(buffer:ByteArray,off:Int,len:Int){check(bytes+len<=total) {"압축 중 파일 크기가 변경되었습니다"};zip.write(buffer,off,len);bytes+=len;progress(entry.path)}}
                        if(useRemote)remote.download(id,entry.path,counting,32L*1024*1024*1024)
                        else {
                            val file=local(entry.path);val stamp=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes::class.java,NOFOLLOW_LINKS)
                            check(stamp.isRegularFile && stamp.size()==entry.size) {"파일이 변경되었습니다. 서버를 종료하고 다시 시도하세요: ${entry.path}"}
                            Files.newInputStream(file,NOFOLLOW_LINKS).use {it.copyTo(counting)}
                            val after=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes::class.java,NOFOLLOW_LINKS)
                            check(after.size()==stamp.size() && after.lastModifiedTime()==stamp.lastModifiedTime()) {"압축 중 파일이 변경되었습니다. 서버를 종료하고 다시 시도하세요: ${entry.path}"}
                        }
                        done++;progress(entry.path,true)
                    }
                    zip.closeEntry()
                }
            }
            if(source=="snapshot")check((if(snapshotMarker.isRegularFile())snapshotMarker.readText() else null)==snapshotStamp) {"압축 중 중앙 복사본이 갱신됐습니다. 다시 요청하세요"}
            jobs.computeIfPresent(token){_,job->job.copy(status=initial.copy(phase="READY",message="ZIP 준비 완료",files=files,completed=done,bytes=bytes,totalBytes=total,zipBytes=job.file.fileSize()))}
          }
          if(source=="snapshot")transfers.withSnapshotRead(id,::build) else build()
        } catch(e:Exception){jobs.computeIfPresent(token){_,job->job.file.deleteIfExists();job.copy(status=initial.copy(phase="FAILED",message=e.message ?: "폴더 압축 실패"))}}}
        return initial
    }
    private fun job(id:String,token:String):Job {manager.instance(id);return jobs[token]?.also {require(it.instance==id) {"다른 서버의 압축 파일입니다"}} ?: error("압축 파일이 만료되었습니다. 다시 요청하세요")}
    fun status(id:String,token:String)=job(id,token).status
    fun download(id:String,token:String):ResponseEntity<StreamingResponseBody> {
        val job=job(id,token);check(job.status.phase=="READY" && job.file.isRegularFile()) {"압축이 끝날 때까지 기다리세요"}
        val body=StreamingResponseBody {out->
            var sent=0L;var last=0L
            jobs.computeIfPresent(token){_,current->current.copy(status=current.status.copy(downloadState="SENDING",downloadedBytes=0))}
            try {
                job.file.inputStream().buffered().use {input->val buffer=ByteArray(64*1024);while(true){val count=input.read(buffer);if(count<0)break;out.write(buffer,0,count);sent+=count
                    val now=System.nanoTime();if(now-last>100_000_000){last=now;jobs.computeIfPresent(token){_,current->current.copy(status=current.status.copy(downloadedBytes=sent))}}
                }}
                out.flush();jobs.computeIfPresent(token){_,current->current.copy(status=current.status.copy(downloadState="COMPLETE",downloadedBytes=sent))}
            } catch(e:Exception){jobs.computeIfPresent(token){_,current->current.copy(status=current.status.copy(downloadState="FAILED",downloadedBytes=sent,message="ZIP 전송이 끊겼습니다. 다운로드를 다시 시도하세요."))};throw e}
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).contentLength(job.file.fileSize())
            .header(HttpHeaders.CONTENT_DISPOSITION,org.springframework.http.ContentDisposition.attachment().filename(job.filename,Charsets.UTF_8).build().toString())
            .cacheControl(org.springframework.http.CacheControl.noStore()).body(body)
    }
    @Scheduled(fixedDelay=600000) fun cleanup() {
        val cutoff=System.currentTimeMillis()-3600000
        jobs.entries.removeIf {(_,job)->if(job.status.phase!="WORKING" && job.status.downloadState!="SENDING" && job.created<cutoff){job.file.deleteIfExists();true}else false}
        directory.listDirectoryEntries("*.zip").filter {file->jobs.values.none {it.file==file} && Files.getLastModifiedTime(file).toMillis()<cutoff}.forEach {it.deleteIfExists()}
    }
    @PreDestroy fun close(){executor.shutdownNow()}
}
@RestController
@RequestMapping("/api/instances/{id}/folder-downloads")
class FolderDownloadApi(private val downloads:FolderDownloads) {
    @PostMapping fun create(@PathVariable id:String,@RequestBody req:FolderDownloadRequest)=downloads.create(id,req.path,req.source)
    @GetMapping("/{token}") fun status(@PathVariable id:String,@PathVariable token:String)=downloads.status(id,token)
    @GetMapping("/{token}/download") fun download(@PathVariable id:String,@PathVariable token:String,request:jakarta.servlet.http.HttpServletRequest):ResponseEntity<StreamingResponseBody> {
        val response=downloads.download(id,token);val stream=response.body!!
        return ResponseEntity.status(response.statusCode).headers(response.headers).body(StreamingResponseBody {out->
            // Large worlds can take longer than the servlet's default async request timeout.
            if(request.isAsyncStarted)request.asyncContext.timeout=0
            stream.writeTo(out)
        })
    }
}
