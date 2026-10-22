package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.*
import java.util.UUID
import kotlin.io.path.*

data class WorkspaceRequest(val op:String,val config:Instance,val path:String="",val to:String="",val offset:Long=0,val data:String?=null,val revision:String?=null,val uploadId:String?=null,@get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) val mutationToken:String?=null)

/** All paths are resolved against a locally granted folder, never a path supplied by the manager. */
class RunnerWorkspace(private val base:Path,private val history:Path,private val logDirectory:Path=base.parent,private val beforeMutation:(String?)->Unit={},private val stopped:()->Unit) {
    companion object {const val CHUNK=64*1024;const val MAX=256L*1024*1024}
    private val mapper=jacksonObjectMapper()
    private data class Upload(val path:String,val temporary:Path,val revision:String?,var bytes:Long=0,val at:Long=System.currentTimeMillis())
    private val uploads=mutableMapOf<String,Upload>()
    private var modListing:ModInventorySnapshot?=null
    private fun path(relative:String):Path {
        require(relative.length<=4096 && !relative.contains('\\') && !relative.contains('\u0000') && !relative.contains(':')) {"잘못된 경로입니다"}
        val root=base.toRealPath();require(!Files.isSymbolicLink(base))
        val result=if(relative.isEmpty())root else FileInventory.safePath(root,relative)
        var ancestor:Path?=result
        while(ancestor!=null && ancestor.startsWith(root)){require(!Files.isSymbolicLink(ancestor)) {"심볼릭 링크는 지원하지 않습니다"};if(ancestor.exists())require(ancestor.toRealPath().startsWith(root)) {"서버 밖으로 연결되는 경로입니다"};ancestor=ancestor.parent}
        return result
    }
    private fun archive(p:Path):Path? {
        require(p!=base.toRealPath()) {"서버 루트는 변경할 수 없습니다"}
        if(p.exists()){history.createDirectories();require(!Files.isSymbolicLink(history));val dest=history.resolve(UUID.randomUUID().toString()+"-"+p.name);Files.move(p,dest,StandardCopyOption.ATOMIC_MOVE);return dest};return null
    }
    private data class Journal(val path:String,val temporary:String,val backup:String?,val size:Long,val sha256:String)
    @Synchronized fun recover() {
        if(!history.exists())return
        require(!Files.isSymbolicLink(history))
        for(journal in history.listDirectoryEntries(".write-*.json")) {
            stopped();require(!Files.isSymbolicLink(journal));val record=mapper.readValue<Journal>(journal.readText());val target=path(record.path)
            require(record.temporary.startsWith(".remote-upload-") && '/' !in record.temporary && '\\' !in record.temporary && record.size in 0..MAX)
            val temp=history.resolve(record.temporary);require(!Files.isSymbolicLink(temp))
            val backup=record.backup?.let {require('/' !in it && '\\' !in it && it.startsWith("saved-"));history.resolve(it).also {p->require(!Files.isSymbolicLink(p))}}
            when {
                !target.exists() && backup?.exists()==true -> {Files.move(backup,target,StandardCopyOption.ATOMIC_MOVE);temp.deleteIfExists()}
                target.exists() && !temp.exists() -> check(target.isRegularFile() && target.fileSize()==record.size && FileInventory.digest(target)==record.sha256) {"파일 반영 후 외부 변경이 있습니다. 실행기 복구 보관함을 확인하세요"}
                target.exists() && temp.exists() && backup?.exists()!=true -> temp.deleteIfExists()
                !target.exists() && backup?.exists()!=true -> temp.deleteIfExists()
                else -> error("중단된 파일 작업을 확인하세요. 원본과 복구 파일을 보존했습니다")
            }
            journal.deleteIfExists()
        }
    }
    private fun commit(upload:Upload,target:Path) {
        history.createDirectories();require(!Files.isSymbolicLink(history));val backup=if(target.exists())"saved-${UUID.randomUUID()}-${target.name}" else null
        val journal=history.resolve(".write-${UUID.randomUUID()}.json");val record=Journal(upload.path,upload.temporary.name,backup,upload.bytes,FileInventory.digest(upload.temporary))
        java.nio.channels.FileChannel.open(journal,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use {channel->val bytes=java.nio.ByteBuffer.wrap(mapper.writeValueAsBytes(record));while(bytes.hasRemaining())channel.write(bytes);channel.force(true)}
        try {if(backup!=null)Files.move(target,history.resolve(backup),StandardCopyOption.ATOMIC_MOVE);Files.move(upload.temporary,target,StandardCopyOption.ATOMIC_MOVE);journal.deleteIfExists()}
        catch(e:Exception){recover();throw e}
    }
    private fun revision(p:Path)="${p.fileSize()}:${Files.getLastModifiedTime(p).toMillis()}"
    fun matches(directory:Path)=base==directory
    @Synchronized fun busy()=uploads.values.any {System.currentTimeMillis()-it.at<=15*60*1000}
    @Synchronized fun execute(req:WorkspaceRequest):Any {
        val readOnly=req.op in setOf("LIST","STAT","READ","LOG_LIST","LOG_PAGE","LOG_WINDOW","MOD_SCAN")
        if(!readOnly){stopped();beforeMutation(req.mutationToken)}
        uploads.entries.removeIf { (_,u)->if(System.currentTimeMillis()-u.at>15*60*1000){u.temporary.deleteIfExists();true}else false }
        if(!readOnly)recover()
        val p=path(req.path)
        return when(req.op) {
            "MOD_SCAN" -> {
                val snapshot=if(req.offset==0L)ModInventory.scan(path("mods")).also {modListing=it} else modListing ?: error("모드 목록을 다시 불러오세요")
                require(req.offset in 0..snapshot.mods.size.toLong())
                check(req.revision==null || req.revision==snapshot.revision) {"모드 목록이 변경되었습니다"}
                val end=minOf(snapshot.mods.size,req.offset.toInt()+50)
                mapOf("revision" to snapshot.revision,"mods" to snapshot.mods.subList(req.offset.toInt(),end),"next" to if(end<snapshot.mods.size)end else null)
            }
            "MOD_TOGGLE" -> {
                val selection=mapper.readValue<ModSelection>(req.data ?: error("변경할 모드를 선택하세요"))
                val directory=path("mods");val snapshot=ModInventory.scan(directory)
                check(selection.revision==snapshot.revision) {"모드 파일이 변경되었습니다. 다시 불러오세요"}
                require(selection.files.isNotEmpty() && selection.files.all {f->snapshot.mods.any {it.filename==f && it.enabled!=selection.enabled}})
                val changes=selection.files.associateWith {if(selection.enabled)it.dropLast(9) else "$it.disabled"}
                require(changes.values.none {to->snapshot.mods.any {it.filename.equals(to,true)}}) {"같은 이름의 모드가 이미 있습니다"}
                ModFiles.rename(directory,changes,history.resolve("mod-backups"));modListing=null;emptyMap<String,String>()
            }
            "LOG_LIST" -> LogFiles(logDirectory,base).list()
            "LOG_PAGE" -> LogFiles(logDirectory,base).page(req.path,req.offset,100,req.revision)
            "LOG_WINDOW" -> {
                val window=LogFiles(logDirectory,base).window(req.path,req.offset,(req.data?.toIntOrNull() ?: 300).coerceIn(1,100),req.to.takeIf {it.isNotEmpty()})
                var budget=0;val rows=window.lines.takeWhile {row->budget+=mapper.writeValueAsBytes(row).size;budget<=120000}
                window.copy(lines=rows,first=rows.firstOrNull()?.no ?: 0,last=rows.lastOrNull()?.no ?: 0,next=if(rows.size<window.lines.size)(rows.lastOrNull()?.no ?: req.offset-1)+1 else window.next)
            }
            "LIST" -> {
                require(p.isDirectory());require(req.offset in 0..100000)
                val entries=Files.list(p).use {it.filter {f->!Files.isSymbolicLink(f) && !f.name.startsWith(".remote-save-")}.sorted().limit(100001).toList()};require(entries.size<=100000) {"폴더의 파일이 너무 많습니다"}
                val page=mutableListOf<FileEntry>();var budget=0;var index=req.offset.toInt();require(index<=entries.size)
                while(index<entries.size && page.size<100){val f=entries[index];val relative=base.toRealPath().relativize(f).toString().replace('\\','/');val size=relative.toByteArray().size+f.name.toByteArray().size+100;if(budget+size>64000 && page.isNotEmpty())break;budget+=size;page+=FileEntry(f.name,relative,f.isDirectory(),if(f.isDirectory())0 else f.fileSize());index++}
                mapOf("entries" to page,"next" to if(index<entries.size)index else null)
            }
            "STAT" -> {require(p.isRegularFile());mapOf("size" to p.fileSize(),"revision" to revision(p))}
            "READ" -> {
                require(p.isRegularFile() && req.offset in 0..p.fileSize());val rev=revision(p);check(req.revision==null || req.revision==rev) {"파일이 변경되었습니다. 다시 열어주세요"}
                val bytes=p.inputStream().use {it.skipNBytes(req.offset);it.readNBytes(CHUNK)};check(revision(p)==rev) {"파일이 변경되었습니다. 다시 다운로드하세요"}
                mapOf("data" to java.util.Base64.getEncoder().encodeToString(bytes),"revision" to rev)
            }
            "MKDIR" -> {p.createDirectories();emptyMap<String,String>()}
            "MOVE" -> {require(p!=base.toRealPath());val to=path(req.to);require(!to.exists() && to!=base.toRealPath());Files.move(p,to,StandardCopyOption.ATOMIC_MOVE);emptyMap<String,String>()}
            "DELETE" -> {require(p.exists());archive(p);emptyMap<String,String>()}
            "WRITE_BEGIN" -> {
                require(p!=base.toRealPath() && !p.isDirectory());check(uploads.size<4) {"진행 중인 업로드가 너무 많습니다"}
                if(req.revision!=null)check(p.isRegularFile() && FileInventory.digest(p)==req.revision) {"파일이 변경되었습니다. 다시 열어주세요"}
                p.parent.createDirectories();history.createDirectories();require(!Files.isSymbolicLink(history) && Files.getFileStore(history)==Files.getFileStore(p.parent)) {"파일 보관함은 원본과 같은 볼륨에 있어야 합니다"};val id=UUID.randomUUID().toString();uploads[id]=Upload(req.path,Files.createTempFile(history,".remote-upload-",".tmp"),req.revision);mapOf("uploadId" to id)
            }
            "WRITE_CHUNK" -> {
                val upload=uploads[req.uploadId] ?: error("업로드가 만료되었습니다");require(upload.path==req.path && req.offset==upload.bytes)
                require(req.data!=null && req.data.length<=90000);val bytes=java.util.Base64.getDecoder().decode(req.data);require(bytes.size<=CHUNK && upload.bytes+bytes.size<=MAX)
                Files.newOutputStream(upload.temporary,StandardOpenOption.APPEND).use {it.write(bytes)};upload.bytes+=bytes.size;mapOf("bytes" to upload.bytes)
            }
            "WRITE_COMMIT" -> {
                val upload=uploads[req.uploadId] ?: error("업로드가 만료되었습니다");require(upload.path==req.path)
                if(upload.revision!=null)check(p.isRegularFile() && FileInventory.digest(p)==upload.revision) {"파일이 변경되었습니다. 다시 열어주세요"}
                commit(upload,p)
                uploads.remove(req.uploadId);emptyMap<String,String>()
            }
            "WRITE_ABORT" -> {uploads.remove(req.uploadId)?.temporary?.deleteIfExists();emptyMap<String,String>()}
            else -> error("지원하지 않는 파일 작업입니다")
        }
    }
}
