package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Small nonblocking events only: never payloads, tickets, keys, or raw exception messages. */
fun interface RelayEvents {
    fun emit(event:String,fields:Map<String,Any?>)
    companion object {val NONE=RelayEvents {_,_->}}
}

internal fun relayError(e:Throwable?):String? {
    if(e==null)return null
    val types=mutableListOf<String>();var current:Throwable?=e
    repeat(5){val c=current ?: return@repeat;types.add(c.javaClass.simpleName);current=c.cause?.takeIf {it!==c}}
    return types.joinToString(" > ")
}

class RelayFileLog(private val directory:Path,private val maxBytes:Long=5L*1024*1024,private val backups:Int=4,private val name:String="relay"):RelayEvents,AutoCloseable {
    private val mapper=jacksonObjectMapper()
    private val queue=ArrayBlockingQueue<Map<String,Any?>>(512)
    private val dropped=AtomicLong()
    @Volatile private var open=true
    private val worker=Thread({run()},"relay-diagnostic-log").apply {isDaemon=true;start()}
    override fun emit(event:String,fields:Map<String,Any?>) {
        if(open && !queue.offer(fields+mapOf("at" to Instant.now().toString(),"event" to event,"pid" to ProcessHandle.current().pid())))dropped.incrementAndGet()
    }
    private fun run() {
        while(open || queue.isNotEmpty()) {
            val item=try {queue.poll(100,TimeUnit.MILLISECONDS)}catch(_:InterruptedException){null} ?: continue
            try {
                val lost=dropped.getAndSet(0)
                append(item+(if(lost>0)mapOf("droppedLogEvents" to lost)else emptyMap()))
            }catch(e:Exception){
                dropped.incrementAndGet()
                // At most one line per failed batch, no credentials from filesystem/exception messages.
                if(dropped.get()==1L)System.err.println("${Instant.now()} relay_log_unavailable ${relayError(e)}")
            }
        }
    }
    private fun append(item:Map<String,Any?>) {
        Files.createDirectories(directory)
        val path=directory.resolve("$name.jsonl")
        if(Files.exists(path) && Files.size(path)>=maxBytes) {
            for(i in backups downTo 1) {
                val source=if(i==1)path else directory.resolve("$name.${i-1}.jsonl")
                if(Files.exists(source))Files.move(source,directory.resolve("$name.$i.jsonl"),REPLACE_EXISTING)
            }
        }
        if(!Files.exists(path)) {
            if(!System.getProperty("os.name").startsWith("Windows"))Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            else Files.createFile(path)
        }
        Files.writeString(path,mapper.writeValueAsString(item)+"\n",APPEND)
    }
    override fun close(){open=false;worker.join(1000)}
}
