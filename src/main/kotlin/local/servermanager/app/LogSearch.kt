package local.servermanager

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import java.util.concurrent.*
import java.util.zip.GZIPInputStream
import kotlin.io.path.*

/** Snapshots are scanned on bounded background workers. Only match offsets, never parsed files, are retained. */
class LogSearch(private val directory:Path,private val serverDirectory:Path=directory.resolve("server")) {
    data class Request(val path:String,val query:String="",val errorsOnly:Boolean=false,val filters:LogFilterConfig=LogFilterConfig(enabled=false))
    data class Row(val no:Long,val kind:String,val text:String,val level:String?,val logger:String?)
    data class View(val id:String,val state:String,val scanned:Long,val bytes:Long,val totalBytes:Long,val matches:Long,val retained:Int,val offset:Int,val next:Int?,val rows:List<Row>,val error:String?=null)
    private data class Meta(val level:String?,val logger:String?)
    private class Job(val root:Path,val file:Path,val request:Request,val key:String,val size:Long,val modified:Long) {
        val id=UUID.randomUUID().toString();@Volatile var state="QUEUED";@Volatile var scanned=0L;@Volatile var bytes=0L
        @Volatile var matches=0L;@Volatile var touched=System.currentTimeMillis();@Volatile var error:String?=null
        @Volatile var cancelled=false;var future:Future<*>?=null;var positions=LongArray(3072);var retained=0
        @Synchronized fun add(no:Long,offset:Long,header:Long){matches++;if(retained>=MAX_MATCHES)return;if((retained+1)*3>positions.size)positions=positions.copyOf(positions.size*2);val n=retained++*3;positions[n]=no;positions[n+1]=offset;positions[n+2]=header}
        @Synchronized fun slice(offset:Int,limit:Int):List<LongArray> =(offset until minOf(retained,offset+limit)).map {n->positions.copyOfRange(n*3,n*3+3)}
    }
    fun start(request:Request):View {
        require(request.query.length<=200 && request.filters.rules.keywords.size<=40 && request.filters.rules.mods.size<=40)
        require((request.filters.rules.keywords+request.filters.rules.mods).all {it.length in 1..120 && it.none(Char::isISOControl)})
        val file=LogFiles(directory,serverDirectory).file(request.path)
        val attrs=Files.readAttributes(file,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        val job=Job(directory.toAbsolutePath().normalize(),file,request,attrs.fileKey().toString(),attrs.size(),attrs.lastModifiedTime().toMillis())
        synchronized(jobs){clean();check(jobs.size<16){"다른 로그 검색이 끝난 뒤 다시 시도하세요"};jobs[job.id]=job}
        try {job.future=pool.submit {run(job)}}catch(e:RejectedExecutionException){jobs.remove(job.id);error("로그 검색 작업이 많습니다. 잠시 뒤 다시 시도하세요")}
        return View(job.id,"QUEUED",0,0,job.size,0,0,0,null,emptyList())
    }
    private fun get(id:String):Job=jobs[id]?.also {check(it.root==directory.toAbsolutePath().normalize());it.touched=System.currentTimeMillis()} ?: error("검색이 만료되었습니다. 다시 검색하세요")
    fun cancel(id:String){val job=get(id);job.cancelled=true;job.future?.cancel(true);(job.future as? Runnable)?.let(pool::remove);jobs.remove(id)}
    fun view(id:String,offset:Int=0,limit:Int=100):View {
        require(offset>=0 && limit in 1..100);val job=get(id);valid(job)
        val hits=job.slice(offset,limit);val texts=readPositions(job,hits.flatMap {listOf(it[1],it[2])}.distinct().sorted())
        var budget=0;val rows=mutableListOf<Row>()
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        for(hit in hits){val raw=texts[hit[1]].orEmpty();val text=raw.take(16384);val meta=metadata(texts[hit[2]].orEmpty());val row=Row(hit[0],kind(raw),text,meta.level,meta.logger);val size=mapper.writeValueAsBytes(row).size;if(rows.isNotEmpty() && budget+size>90000)break;budget+=size;rows+=row}
        val retained=synchronized(job){job.retained}
        return View(id,job.state,job.scanned,job.bytes,job.size,job.matches,retained,offset,if(offset+rows.size<retained)offset+rows.size else null,rows,job.error)
    }
    private fun valid(job:Job){val a=Files.readAttributes(job.file,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS);check(!a.isSymbolicLink && a.fileKey().toString()==job.key && a.size()>=job.size && (!job.request.path.endsWith(".gz") || a.size()==job.size && a.lastModifiedTime().toMillis()==job.modified)){"로그 파일이 교체되었습니다. 다시 검색하세요"}}
    private fun run(job:Job) {
        try {
            job.state="SCANNING";valid(job)
            val request=job.request;val needle=request.query.trim().lowercase(Locale.ROOT);var no=0L;var position=0L;var start=0L;var header=0L;var previous=Meta(null,null);var hiddenHeader=false
            val bytes=java.io.ByteArrayOutputStream();val gzip=request.path.endsWith(".gz")
            fun line(){
                val text=bytes.toString(Charsets.UTF_8).removeSuffix("\r");bytes.reset()
                if(LogNoise.dropped(text))return
                no++;val own=metadata(text);val hasHeader=own.level!=null || PREFIX.find(text.trimStart())!=null
                if(hasHeader){previous=own;header=start;hiddenHeader=hidden(text,own,request.filters)}
                val meta=if(hasHeader)own else previous
                if((!hiddenHeader || hasHeader) && !hidden(text,meta,request.filters) && (!request.errorsOnly || meta.level in setOf("WARN","ERROR","FATAL") || kind(text) in setOf("warn","error")) && (needle.isEmpty() || ANSI.replace(text,"").lowercase(Locale.ROOT).contains(needle)))job.add(no,start,header)
                job.scanned=no
            }
            Files.newInputStream(job.file,LinkOption.NOFOLLOW_LINKS).use {raw->
                val bounded=object:java.io.FilterInputStream(raw){var left=job.size;override fun read():Int {if(left<=0)return -1;val b=super.read();if(b>=0)left--;return b};override fun read(b:ByteArray,off:Int,len:Int):Int {if(left<=0)return -1;val n=`in`.read(b,off,minOf(left,len.toLong()).toInt());if(n>0)left-=n;return n}}
                (if(gzip)GZIPInputStream(bounded) else bounded).buffered(65536).use {input->
                    val buffer=ByteArray(65536)
                    while(true){check(!job.cancelled && !Thread.currentThread().isInterrupted){"검색이 취소되었습니다"};val n=input.read(buffer);if(n<0)break
                        for(i in 0 until n){position++;if(buffer[i]==10.toByte()){line();start=position}else {require(bytes.size()<1024*1024){"1 MiB가 넘는 로그 행은 원본 파일로 확인하세요"};bytes.write(buffer[i].toInt())}}
                        job.bytes=if(gzip)job.size-bounded.left else position;require(!gzip || position<=128L*1024*1024){"압축 로그의 펼친 크기는 128 MiB까지 검색할 수 있습니다"}
                    }
                    if(bytes.size()>0)line()
                }
            }
            valid(job);job.bytes=job.size;job.state="COMPLETE"
        }catch(e:Exception){job.error=if(job.cancelled)"검색이 취소되었습니다" else e.message?.take(300);job.state=if(job.cancelled)"CANCELLED" else "FAILED"}
    }
    private fun readPositions(job:Job,offsets:List<Long>):Map<Long,String> {
        if(offsets.isEmpty())return emptyMap()
        val texts=mutableMapOf<Long,String>()
        if(!job.request.path.endsWith(".gz"))java.nio.channels.FileChannel.open(job.file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use {file->
            for(offset in offsets){val buffer=java.nio.ByteBuffer.allocate(minOf(16384L,job.size-offset).toInt());var position=offset;while(buffer.hasRemaining()){val n=file.read(buffer,position);if(n<=0)break;position+=n};val bytes=buffer.array();val length=(0 until buffer.position()).firstOrNull {bytes[it]==10.toByte()} ?: buffer.position();texts[offset]=String(bytes,0,length,Charsets.UTF_8).removeSuffix("\r")}
        }
        else GZIPInputStream(Files.newInputStream(job.file,LinkOption.NOFOLLOW_LINKS)).buffered(65536).use {input->var position=0L;for(offset in offsets){input.skipNBytes(offset-position);position=offset;val bytes=java.io.ByteArrayOutputStream();while(bytes.size()<16384){val b=input.read();if(b<0)break;position++;if(b==10)break;bytes.write(b)};texts[offset]=bytes.toString(Charsets.UTF_8).removeSuffix("\r")}}
        return texts
    }
    private companion object {
        const val MAX_MATCHES=100000
        val jobs=ConcurrentHashMap<String,Job>()
        val pool=ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,ArrayBlockingQueue(8),{r->Thread(r,"log-search").apply {isDaemon=true}})
        fun clean(){val cutoff=System.currentTimeMillis()-120000;jobs.entries.removeIf {(_,job)->if(job.touched<cutoff){job.cancelled=true;job.future?.cancel(true);(job.future as? Runnable)?.let(pool::remove);true}else false}}
        val ANSI=Regex("\u001b\\[[0-?]*[ -/]*[@-~]")
        val PREFIX=Regex("^(?:\\[[^\\]\\r\\n]{1,240}\\]\\s*:?\\s*){1,5}")
        val TAG=Regex("\\[([^\\]\\r\\n]{1,240})\\]");val LEVEL=Regex("(?:^|[ /])(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)$",RegexOption.IGNORE_CASE)
        val TIME=Regex("\\b\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,3})?\\b")
        fun metadata(raw:String):Meta {val text=ANSI.replace(raw,"");val prefix=PREFIX.find(text.trimStart())?.value.orEmpty();val tags=TAG.findAll(prefix).map {it.groupValues[1]}.toList();val index=tags.indexOfFirst {LEVEL.containsMatchIn(it)};return Meta(if(index>=0)LEVEL.find(tags[index])!!.groupValues[1].uppercase() else null,if(index>=0)tags.drop(index+1).firstOrNull {!TIME.containsMatchIn(it)}?.removeSuffix("/") else null)}
        fun kind(text:String)=when {text.contains("ERROR")||text.contains("Exception")||text.trimStart().startsWith("Caused by:")->"error";text.contains("WARN")->"warn";else->"output"}
        fun hidden(text:String,meta:Meta,config:LogFilterConfig):Boolean {if(!config.enabled || config.rules.onlyDebug && meta.level !in setOf("DEBUG","TRACE"))return false;val clean=ANSI.replace(text,"").lowercase(Locale.ROOT);if(config.rules.keywords.any {clean.contains(it.lowercase(Locale.ROOT))})return true;val logger=meta.logger?.lowercase(Locale.ROOT) ?: return false;val tokens=logger.split('.','/',':');return config.rules.mods.any {m->val v=m.lowercase(Locale.ROOT);logger==v||listOf(".","/",":").any {logger.startsWith(v+it)}||v in tokens}}
    }
}
