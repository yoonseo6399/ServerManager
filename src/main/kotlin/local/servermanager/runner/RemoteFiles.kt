package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Service
import java.io.InputStream
import java.io.OutputStream

@Service
class RemoteFiles(private val manager:ServerManager) {
    private val mapper=jacksonObjectMapper()
    fun remote(id:String)=manager.instance(id).runnerId!=null
    fun filesRemote(id:String)=manager.instance(id).runnerId!=null
    fun available(id:String)=manager.liveFilesAvailable(id)
    private fun call(id:String,op:String,path:String="",offset:Long=0,data:String?=null,revision:String?=null,uploadId:String?=null,to:String="")=
        manager.remoteWorkspace(id,WorkspaceRequest(op,manager.instance(id).copy(),path,to,offset,data,revision,uploadId))
    fun mods(id:String):ModInventorySnapshot {
        val mods=mutableListOf<InstalledMod>();var offset=0L;var revision:String?=null
        do {
            val page=call(id,"MOD_SCAN",offset=offset,revision=revision);val current=page.path("revision").asText()
            check(revision==null || revision==current);revision=current
            mods+=page.path("mods").map {mapper.treeToValue(it,InstalledMod::class.java)};check(mods.size<=2000)
            val next=page.path("next");if(next.isNull || next.isMissingNode)break
            check(next.asLong()>offset);offset=next.asLong()
        }while(true)
        return ModInventorySnapshot(revision ?: error("모드 목록이 없습니다"),mods,emptyList())
    }
    fun toggleMods(id:String,request:ModSelection)=manager.withRemoteFiles(id) {call(id,"MOD_TOGGLE",data=mapper.writeValueAsString(request));Unit}
    fun logFiles(id:String):List<LogFiles.Entry> = call(id,"LOG_LIST").map {mapper.treeToValue(it,LogFiles.Entry::class.java)}
    fun logPage(id:String,path:String,offset:Long,revision:String?):LogFiles.Page=mapper.treeToValue(call(id,"LOG_PAGE",path,offset,revision=revision),LogFiles.Page::class.java)
    fun logWindow(id:String,path:String,from:Long,limit:Int,query:String?):LogFiles.Window {
        require(from>=1 && limit in 1..500);val rows=mutableListOf<LogFiles.Row>();var offset=from;var last:LogFiles.Window
        do {
            last=mapper.treeToValue(call(id,"LOG_WINDOW",path,offset,data=minOf(100,limit-rows.size).toString(),to=query.orEmpty()),LogFiles.Window::class.java)
            rows+=last.lines
            val next=last.next ?: break
            check(next>offset);offset=next
        }while(rows.size<limit && (query.isNullOrBlank() || rows.isNotEmpty()))
        return LogFiles.Window(rows,last.total,rows.firstOrNull()?.no ?: 0,rows.lastOrNull()?.no ?: 0,last.next)
    }
    fun files(id:String,path:String):List<FileEntry> {
        val result=mutableListOf<FileEntry>();var offset=0L
        do {val page=call(id,"LIST",path,offset);result+=page.path("entries").map {mapper.treeToValue(it,FileEntry::class.java)};check(result.size<=100000);val next=page.path("next");if(next.isNull || next.isMissingNode)break;check(next.asLong()>offset);offset=next.asLong()}while(true)
        return result.sortedWith(compareBy<FileEntry>{!it.directory}.thenBy {it.name})
    }
    fun size(id:String,path:String):Long=call(id,"STAT",path).path("size").asLong().also {require(it>=0)}
    fun download(id:String,path:String,out:OutputStream,limit:Long=Long.MAX_VALUE,log:Boolean=false) {
        val stat=call(id,if(log)"LOG_STAT" else "STAT",path);val size=stat.path("size").asLong(-1);require(size in 0..limit) {"파일 크기 제한을 초과했습니다"};val revision=stat.path("revision").asText();var offset=0L
        while(offset<size){val part=call(id,if(log)"LOG_READ" else "READ",path,offset,revision=revision);val bytes=java.util.Base64.getDecoder().decode(part.path("data").asText());check(bytes.isNotEmpty() && bytes.size<=RunnerWorkspace.CHUNK && bytes.size<=size-offset);out.write(bytes);offset+=bytes.size}
    }
    fun text(id:String,path:String):Map<String,String> {
        val out=java.io.ByteArrayOutputStream();download(id,path,out,2L*1024*1024);val bytes=out.toByteArray();require(!bytes.contains(0)) {"바이너리 파일은 다운로드하세요"}
        return mapOf("content" to EditableText.decode(bytes),"revision" to java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
    }
    fun write(id:String,path:String,input:InputStream,revision:String?=null,limit:Long=RunnerWorkspace.MAX)=manager.withRemoteFiles(id) {
        val upload=call(id,"WRITE_BEGIN",path,revision=revision).path("uploadId").asText();var offset=0L
        try {while(true){val bytes=input.readNBytes(RunnerWorkspace.CHUNK);if(bytes.isEmpty())break;require(offset+bytes.size<=limit) {"파일 크기 제한을 초과했습니다"};call(id,"WRITE_CHUNK",path,offset,java.util.Base64.getEncoder().encodeToString(bytes),uploadId=upload);offset+=bytes.size};call(id,"WRITE_COMMIT",path,uploadId=upload);Unit}
        catch(e:Exception){runCatching {call(id,"WRITE_ABORT",path,uploadId=upload)};throw e}
    }
    fun change(id:String,op:String,path:String,to:String="")=manager.withRemoteFiles(id) {call(id,op,path,to=to);Unit}
}
