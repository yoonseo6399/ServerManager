package local.servermanager

import java.io.IOException
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Shared, finite budget; includes writes already in progress. No game bytes are logged. */
internal class RelayByteBudget(private val maximum:Long=16L*1024*1024) {
    private var used=0L
    @Synchronized fun acquire(size:Int):Boolean {if(used+size>maximum)return false;used+=size;return true}
    @Synchronized fun release(size:Int){used-=size;check(used>=0)}
    @Synchronized fun usedBytes()=used
}

internal object RelayIoWatchdog {
    private val checks=ConcurrentHashMap<Any,()->Unit>()
    private val timer=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"relay-write-deadlines").apply {isDaemon=true}}
    init {timer.scheduleWithFixedDelay({checks.values.forEach {runCatching(it)}},100,100,TimeUnit.MILLISECONDS)}
    fun register(key:Any,check:()->Unit){checks[key]=check}
    fun remove(key:Any){checks.remove(key)}
}

/** One player/backend gets one bounded writer. The tunnel reader never writes to an endpoint socket. */
internal class RelaySocketWriter(
    private val socket:Socket, private val budget:RelayByteBudget,
    private val events:RelayEvents,private val context:Map<String,Any?>,
    private val onFailure:(String)->Unit,
    private val maxBytes:Int=512*1024,private val writeTimeoutMillis:Long=10_000
):AutoCloseable {
    private val lock=Object()
    private class Chunk(capacity:Int){val bytes=ByteArray(capacity);var size=0}
    private val queue=ArrayDeque<Chunk>()
    private var pendingBytes=0
    private val open=AtomicBoolean(true)
    private var finishReason:String?=null
    private val started=System.nanoTime()
    private val bytesRead=AtomicLong();private val bytesWritten=AtomicLong()
    @Volatile private var lastRead=started
    @Volatile private var lastWrite=started
    @Volatile private var writingSince=0L
    private val worker=Thread.ofVirtual().name("relay-endpoint-writer").unstarted {run()}
    init {
        RelayIoWatchdog.register(this) {
            val since=writingSince
            if(since!=0L && elapsed(since)>=writeTimeoutMillis)fail("ENDPOINT_WRITE_TIMEOUT")
        }
        worker.start()
    }
    fun read(count:Int){bytesRead.addAndGet(count.toLong());lastRead=System.nanoTime()}
    fun offer(bytes:ByteArray):Boolean {
        var rejection:Map<String,Any?>?=null
        val accepted=synchronized(lock) {
            if(!open.get() || finishReason!=null)return false
            if(bytes.isEmpty())return true
            val cause=if(pendingBytes+bytes.size>maxBytes)"STREAM_BYTE_LIMIT" else if(!budget.acquire(bytes.size))"GLOBAL_BYTE_LIMIT" else null
            if(cause!=null) {
                rejection=mapOf("limitCause" to cause,"queuedBytes" to pendingBytes,"queuedChunks" to queue.size,
                    "incomingBytes" to bytes.size,"limitBytes" to maxBytes,"lastWriteAgeMs" to elapsed(lastWrite))
                false
            } else {
                // TCP segmentation and protocol frame count are not measures of congestion.
                // Coalesce small fragments, reserving only their bytes, without blocking the shared receiver.
                var offset=0
                while(offset<bytes.size) {
                    val tail=queue.peekLast()?.takeIf {it.size<it.bytes.size}
                        ?: Chunk(minOf(32*1024,maxBytes)).also {queue.addLast(it)}
                    val count=minOf(bytes.size-offset,tail.bytes.size-tail.size)
                    bytes.copyInto(tail.bytes,tail.size,offset,offset+count);tail.size+=count;offset+=count
                }
                pendingBytes+=bytes.size;lock.notifyAll();true
            }
        }
        if(!accepted){events.emit("endpoint_queue_rejected",context+(rejection ?: emptyMap()));fail("ENDPOINT_BACKPRESSURE")}
        return accepted
    }
    /** CLOSE follows accepted DATA, even when the receive loop reads them in one burst. */
    fun finish(reason:String){synchronized(lock){if(finishReason==null)finishReason=reason;lock.notifyAll()}}
    private fun run() {
        try {
            while(open.get()) {
                val item=synchronized(lock) {
                    while(open.get() && queue.isEmpty() && finishReason==null)lock.wait()
                    if(!open.get())return
                    if(queue.isEmpty()){close(finishReason ?: "EOF");return}
                    queue.removeFirst()
                }
                try {
                    writingSince=System.nanoTime()
                    socket.getOutputStream().write(item.bytes,0,item.size);socket.getOutputStream().flush()
                    lastWrite=System.nanoTime();bytesWritten.addAndGet(item.size.toLong())
                    if(elapsed(writingSince)>=1000)events.emit("endpoint_slow_write",context+mapOf("elapsedMs" to elapsed(writingSince),"bytes" to item.size))
                } finally {writingSince=0;synchronized(lock){pendingBytes-=item.size};budget.release(item.size)}
            }
        } catch(_:InterruptedException) {if(open.get())fail("WRITER_INTERRUPTED")}
        catch(e:Exception){if(open.get())fail("ENDPOINT_WRITE_${e.javaClass.simpleName}")}
    }
    private fun fail(reason:String){if(close(reason))onFailure(reason)}
    fun close(reason:String):Boolean {
        if(!open.compareAndSet(true,false))return false
        RelayIoWatchdog.remove(this)
        val queued=synchronized(lock) {
            val size=pendingBytes
            val chunks=queue.size
            while(queue.isNotEmpty()){val item=queue.removeFirst();pendingBytes-=item.size;budget.release(item.size)}
            lock.notifyAll();size to chunks
        }
        runCatching {socket.close()}
        worker.interrupt()
        events.emit("stream_closed",context+mapOf("reason" to reason,"durationMs" to elapsed(started),"bytesFromEndpoint" to bytesRead.get(),
            "bytesToEndpoint" to bytesWritten.get(),"queuedBytes" to queued.first,"queuedChunks" to queued.second,"lastReadAgeMs" to elapsed(lastRead),"lastWriteAgeMs" to elapsed(lastWrite)))
        return true
    }
    override fun close(){close("SHUTDOWN")}
    private fun elapsed(since:Long)=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-since)
}

/** Fair, bounded tunnel send queue. FIFO within a stream; round-robin between streams. */
internal class RelayFrameWriter(
    private val write:(SecureChannel.Frame)->Unit,private val abort:(String)->Unit,
    private val events:RelayEvents,private val context:Map<String,Any?>,
    private val byteLimit:Int=4*1024*1024,private val streamLimit:Int=512*1024,private val writeTimeoutMillis:Long=10_000
):AutoCloseable {
    private val lock=Object()
    private val queues=LinkedHashMap<Int,ArrayDeque<SecureChannel.Frame>>()
    private val streamBytes=HashMap<Int,Int>()
    private var bytes=0;private var frames=0;private var controls=0
    private val open=AtomicBoolean(true)
    @Volatile private var writingSince=0L
    private var terminal:String?=null
    private var afterDrain:(()->Unit)?=null
    private val droppedDatagrams=AtomicLong()
    private val worker=Thread.ofVirtual().name("relay-tunnel-writer").unstarted {run()}
    init {
        RelayIoWatchdog.register(this) {
            val since=writingSince
            if(since!=0L && TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-since)>=writeTimeoutMillis)fail("TUNNEL_WRITE_TIMEOUT")
        }
        worker.start()
    }
    fun send(frame:SecureChannel.Frame,waitForCapacity:Boolean=false) {
        var failure:String?=null
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(writeTimeoutMillis)
        synchronized(lock) {
            while(waitForCapacity && open.get() && (frames>=512 || bytes+frame.payload.size>byteLimit || (streamBytes[frame.stream] ?: 0)+frame.payload.size>streamLimit)) {
                val remaining=deadline-System.nanoTime()
                if(remaining<=0)break
                TimeUnit.NANOSECONDS.timedWait(lock,remaining)
            }
            if(!open.get() || afterDrain!=null)throw IOException(terminal ?: "TUNNEL_CLOSED")
            val control=frame.type!=SecureChannel.DATA && frame.type !in setOf(SecureChannel.UDP_UP,SecureChannel.UDP_DOWN,SecureChannel.VOICE_UP,SecureChannel.VOICE_DOWN)
            if(control && controls>=256)failure="TUNNEL_CONTROL_BACKPRESSURE"
            else if(!control && (frames>=512 || bytes+frame.payload.size>byteLimit || (streamBytes[frame.stream] ?: 0)+frame.payload.size>streamLimit))failure="TUNNEL_STREAM_BACKPRESSURE"
            else {
                queues.getOrPut(frame.stream){ArrayDeque()}.addLast(frame)
                frames++;bytes+=frame.payload.size;streamBytes[frame.stream]=(streamBytes[frame.stream] ?: 0)+frame.payload.size
                if(control)controls++
                lock.notifyAll()
            }
        }
        failure?.let {
            if(frame.type in setOf(SecureChannel.UDP_UP,SecureChannel.UDP_DOWN,SecureChannel.VOICE_UP,SecureChannel.VOICE_DOWN)) {
                val count=droppedDatagrams.incrementAndGet()
                if(count==1L || count%100==0L)events.emit("tunnel_datagram_dropped",context+mapOf("count" to count,"reason" to it))
                return
            }
            events.emit("tunnel_backpressure",context+mapOf("stream" to frame.stream,"reason" to it))
            if(it=="TUNNEL_CONTROL_BACKPRESSURE")fail(it)
            throw IOException(it)
        }
    }
    private fun run() {
        try {
            while(open.get()) {
                val item=synchronized(lock) {
                    while(open.get() && queues.isEmpty() && afterDrain==null)lock.wait()
                    if(!open.get())return
                    if(queues.isEmpty()){afterDrain?.invoke();return}
                    val entry=queues.entries.iterator().next();val id=entry.key;val q=entry.value
                    val frame=q.removeFirst();queues.remove(id);if(q.isNotEmpty())queues[id]=q
                    frame
                }
                try {
                    writingSince=System.nanoTime();write(item)
                    val ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-writingSince)
                    if(ms>=1000)events.emit("tunnel_slow_write",context+mapOf("elapsedMs" to ms,"stream" to item.stream,"bytes" to item.payload.size))
                } finally {
                    writingSince=0
                    synchronized(lock) {
                        frames--;bytes-=item.payload.size
                        val remaining=(streamBytes[item.stream] ?: 0)-item.payload.size
                        if(remaining==0)streamBytes.remove(item.stream)else streamBytes[item.stream]=remaining
                        lock.notifyAll()
                        if(item.type!=SecureChannel.DATA && item.type !in setOf(SecureChannel.UDP_UP,SecureChannel.UDP_DOWN,SecureChannel.VOICE_UP,SecureChannel.VOICE_DOWN))controls--
                    }
                }
            }
        }catch(_:InterruptedException){if(open.get())fail("TUNNEL_WRITER_INTERRUPTED")}
        catch(e:Exception){if(open.get())fail("TUNNEL_WRITE_${e.javaClass.simpleName}")}
    }
    fun snapshot():Map<String,Any?> =synchronized(lock){mapOf("queuedBytes" to bytes,"queuedFrames" to frames,"queuedStreams" to streamBytes.size,
        "droppedDatagrams" to droppedDatagrams.get(),"writeAgeMs" to writingSince.takeIf {it!=0L}?.let {TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-it)})}
    fun finish(after:()->Unit){synchronized(lock){afterDrain=after;lock.notifyAll()}}
    private fun fail(reason:String){if(stop(reason)){events.emit("tunnel_write_failed",context+mapOf("reason" to reason));abort(reason)}}
    private fun stop(reason:String):Boolean {
        if(!open.compareAndSet(true,false))return false
        RelayIoWatchdog.remove(this)
        synchronized(lock){terminal=reason;queues.clear();lock.notifyAll()}
        worker.interrupt();return true
    }
    override fun close(){stop("TUNNEL_CLOSED")}
}
