package local.servermanager

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.io.path.*

/** Lines from mods that flood the console. They are neither stored nor shown, and are skipped when reading older files. */
object LogNoise {
    private val marker="[MunitionRackDebug]".toByteArray()
    fun dropped(line:String)=line.contains("[MunitionRackDebug]")
    /** Mod tags sit right after the logger prefix, so only the head of a line is inspected. */
    fun dropped(bytes:ByteArray,length:Int):Boolean {
        val end=minOf(length,512)-marker.size
        var i=0
        while(i<=end){var j=0;while(j<marker.size && bytes[i+j]==marker[j])j++;if(j==marker.size)return true;i++}
        return false
    }
}

/** Raw files only. Neither parsed logs nor indexes are written to disk. */
class LogFiles(private val directory: Path,private val serverDirectory:Path=directory.resolve("server")) {
    data class Entry(val path:String,val name:String,val size:Long,val modifiedAt:String,val compressed:Boolean)
    data class Line(val seq:Long,val time:String?,val kind:String,val text:String)
    /** [no] counts only lines that are not dropped as noise, starting at 1. */
    data class Row(val no:Long,val kind:String,val text:String)
    data class Window(val lines:List<Row>,val total:Long,val first:Long,val last:Long,val next:Long?)
    data class Page(val events:List<Line>,val nextOffset:Long?,val revision:String,val bytesRead:Long)
    private fun allowed(name:String)=name=="console.log" || Regex("server/logs/[^/]+\\.log(?:\\.gz)?").matches(name) || Regex("server/crash-reports/[^/]+\\.txt").matches(name)
    private fun resolve(name:String):Path {
        require(allowed(name) && !name.contains('\\')) { "지원하지 않는 로그 파일입니다" }
        val base=(if(name.startsWith("server/"))serverDirectory else directory).toAbsolutePath().normalize();val p=base.resolve(name.removePrefix(if(name.startsWith("server/"))"server/" else "")).normalize()
        require(p.startsWith(base)) { "잘못된 로그 경로입니다" }
        var part:Path?=p
        while(part!=null && part.startsWith(base)){require(!Files.isSymbolicLink(part)) { "로그의 심볼릭 링크는 지원하지 않습니다" };part=part.parent}
        require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) { "로그 파일이 없습니다" }
        return p
    }
    fun file(name:String):Path=resolve(name)
    fun list():List<Entry> {
        val names=mutableListOf("console.log")
        for(sub in listOf("server/logs","server/crash-reports")) {
            val folder=serverDirectory.resolve(sub.removePrefix("server/"))
            if(Files.isSymbolicLink(serverDirectory) || Files.isSymbolicLink(folder) || !Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))continue
            Files.list(folder).use { paths->paths.forEach { names.add("$sub/${it.fileName}") } }
        }
        return names.filter(::allowed).mapNotNull { name->runCatching { val p=resolve(name);Entry(name,name,p.fileSize(),p.getLastModifiedTime().toInstant().toString(),name.endsWith(".gz")) }.getOrNull() }.sortedByDescending { it.modifiedAt }
    }
    fun page(name:String,offset:Long=0,limit:Int=300,expectedRevision:String?=null):Page {
        require(offset>=0 && limit in 1..500) { "로그 조회 범위를 확인하세요" }
        val p=resolve(name);val attrs=Files.readAttributes(p,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        val revision="${attrs.fileKey()}:${attrs.size()}:${attrs.lastModifiedTime()}"
        check(expectedRevision==null || revision==expectedRevision) { "로그 파일이 변경되었습니다. 다시 불러오세요." }
        val gzip=name.endsWith(".gz")
        require(!gzip || offset<128L*1024*1024) { "압축 로그는 펼친 크기 128 MiB까지 조회할 수 있습니다" }
        require(gzip || offset<=attrs.size()) { "로그 커서가 파일 범위를 벗어났습니다" }
        val channel=Files.newByteChannel(p,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))
        if(!gzip)channel.position(offset)
        val raw=java.nio.channels.Channels.newInputStream(channel)
        val input=try { if(gzip)GZIPInputStream(raw) else raw }catch(e:Exception){raw.close();throw e}
        input.buffered().use { stream->
            if(gzip){var skipped=0L;while(skipped<offset){val n=stream.skip(offset-skipped);require(n>0) { "로그 커서가 파일 범위를 벗어났습니다" };skipped+=n}}
            val events=mutableListOf<Line>();var position=offset;var eof=false
            while(events.size<limit && position-offset<2*1024*1024) {
                val start=position;val bytes=ByteArrayOutputStream()
                while(true){val b=stream.read();if(b<0){eof=true;break};position++;require(!gzip || position<=128L*1024*1024) { "압축 로그는 펼친 크기 128 MiB까지 조회할 수 있습니다" };if(b==10)break;require(bytes.size()<1024*1024) { "1 MiB를 넘는 로그 행은 원본 파일로 확인하세요" };bytes.write(b)}
                if(position==start)break
                val text=bytes.toString(Charsets.UTF_8).removeSuffix("\r")
                val kind=when { text.contains("ERROR")||text.contains("Exception")||text.startsWith("Caused by:")->"error";text.contains("WARN")->"warn";else->"output" }
                events.add(Line(start+1,null,kind,text))
                if(eof)break
            }
            // Peek only to distinguish an exact page boundary from EOF.
            if(!eof)eof=stream.read()<0
            return Page(events,if(eof)null else position,revision,position-offset)
        }
    }
    private class Index(val fileKey:String) {val offsets=ArrayList<Long>();var scanned=0L;var lines=0L;var tail=false;var done=false}
    private fun kindOf(text:String)=when { text.contains("ERROR")||text.contains("Exception")||text.startsWith("Caused by:")->"error";text.contains("WARN")->"warn";else->"output" }
    /** Streams lines from [start]. [onLine] gets the line's start offset, the offset after it, its (possibly truncated) bytes, and whether a newline ended it; return false to stop. */
    private fun scan(p:Path,gzip:Boolean,start:Long,onLine:(Long,Long,ByteArray,Int,Boolean)->Boolean) {
        val channel=Files.newByteChannel(p,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))
        if(!gzip)channel.position(start)
        val raw=java.nio.channels.Channels.newInputStream(channel)
        val input=try { if(gzip)GZIPInputStream(raw) else raw }catch(e:Exception){raw.close();throw e}
        input.use { stream->
            if(gzip){var skipped=0L;while(skipped<start){val n=stream.skip(start-skipped);require(n>0) { "로그 커서가 파일 범위를 벗어났습니다" };skipped+=n}}
            val buffer=ByteArray(64*1024);val line=ByteArray(MAX_LINE);var length=0;var lineStart=start;var position=start
            while(true) {
                val n=stream.read(buffer);if(n<0)break
                for(i in 0 until n) {
                    val b=buffer[i];position++
                    require(!gzip || position<=128L*1024*1024) { "압축 로그는 펼친 크기 128 MiB까지 조회할 수 있습니다" }
                    if(b==10.toByte()){
                        val end=if(length>0 && length<=MAX_LINE && line[length-1]==13.toByte())length-1 else length
                        if(!onLine(lineStart,position,line,end,true))return
                        lineStart=position;length=0
                    } else if(length<MAX_LINE)line[length++]=b
                }
            }
            if(position>lineStart){val end=if(length>0 && line[length-1]==13.toByte())length-1 else length;onLine(lineStart,position,line,end,false)}
        }
    }
    /** Sparse line index kept in memory only. Plain logs only ever grow, so the scan resumes where the last one stopped. */
    private fun index(p:Path,gzip:Boolean,attrs:BasicFileAttributes):Index {
        val key=p.toAbsolutePath().toString()
        val fileKey=if(gzip)"${attrs.size()}:${attrs.lastModifiedTime()}" else "${attrs.fileKey()}"
        if(indexes.size>16)indexes.clear()
        val index=indexes.compute(key) { _,old-> if(old!=null && old.fileKey==fileKey && (gzip || old.scanned<=attrs.size())) old else Index(fileKey) }!!
        synchronized(index) {
            if(gzip && index.done)return index
            index.tail=false
            try {scan(p,gzip,index.scanned) { start,end,bytes,length,complete->
                val noise=LogNoise.dropped(bytes,length)
                if(!complete){index.tail=!noise;return@scan true}
                if(!noise){if(index.lines%STEP==0L)index.offsets.add(start);index.lines++}
                index.scanned=end
                true
            }} catch(e:Exception){indexes.remove(key);throw e}
            if(gzip)index.done=true
        }
        return index
    }
    /**
     * Line-numbered view of a whole file. With [query], lines from [from] on are searched server-side (case-insensitive) and only matches return;
     * [Window.next] says where to continue, so callers can keep loading while the user scrolls.
     */
    fun window(name:String,from:Long,limit:Int,query:String?=null):Window {
        require(from>=1 && limit in 1..500 && (query?.length ?: 0)<=200) { "로그 조회 범위를 확인하세요" }
        val p=resolve(name);val gzip=name.endsWith(".gz")
        val attrs=Files.readAttributes(p,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        val index=index(p,gzip,attrs);val total:Long;val offsets:List<Long>
        synchronized(index){total=index.lines+(if(index.tail)1 else 0);offsets=ArrayList(index.offsets)}
        val needle=query?.trim()?.lowercase()?.takeIf {it.isNotEmpty()}
        if(from>total)return Window(emptyList(),total,0,0,null)
        val slot=((from-1)/STEP).toInt().coerceAtMost((offsets.size-1).coerceAtLeast(0))
        var no=slot*STEP;val rows=ArrayList<Row>();var next:Long?=null;val deadline=System.nanoTime()+3_000_000_000L
        scan(p,gzip,offsets.getOrElse(slot){0L}) { _,_,bytes,length,_->
            if(LogNoise.dropped(bytes,length))return@scan true
            no++
            if(no<from)return@scan true
            if(rows.size>=limit){next=no;return@scan false}
            val text=String(bytes,0,length,Charsets.UTF_8)
            if(needle==null || text.lowercase().contains(needle))rows.add(Row(no,kindOf(text),text))
            if(needle!=null && System.nanoTime()>deadline){next=no+1;return@scan false}
            true
        }
        if(next!=null && next!!>total)next=null
        return Window(rows,total,rows.firstOrNull()?.no ?: 0,rows.lastOrNull()?.no ?: 0,next)
    }
    private companion object {const val STEP=1000L;const val MAX_LINE=16*1024;val indexes=java.util.concurrent.ConcurrentHashMap<String,Index>()}
}
