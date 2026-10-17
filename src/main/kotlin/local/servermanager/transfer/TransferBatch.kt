package local.servermanager

import java.io.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Bounded framing, not an archive: paths are validated against the manifest before any writes. */
data class TransferPart(val path:String,val offset:Long,val bytes:ByteArray)
object TransferBatch {
    const val MAX_BYTES=4*1024*1024
    const val MAX_FILES=64
    const val SMALL_FILE=256*1024
    const val PARALLEL=4
    fun encode(parts:List<TransferPart>):ByteArray {
        require(parts.size in 1..MAX_FILES)
        val out=ByteArrayOutputStream()
        DataOutputStream(out).use {d->
            d.writeInt(0x534d4231);d.writeInt(parts.size)
            parts.forEach {p->
                val path=p.path.toByteArray(Charsets.UTF_8);require(path.size in 1..4096 && p.offset>=0 && p.bytes.size<=SMALL_FILE)
                d.writeInt(path.size);d.write(path);d.writeLong(p.offset);d.writeInt(p.bytes.size);d.write(p.bytes)
                require(out.size()<=MAX_BYTES)
            }
        };return out.toByteArray()
    }
    fun decode(bytes:ByteArray):List<TransferPart> {
        require(bytes.size in 8..MAX_BYTES)
        val input=DataInputStream(ByteArrayInputStream(bytes));require(input.readInt()==0x534d4231)
        val count=input.readInt();require(count in 1..MAX_FILES)
        val result=(1..count).map {
            val length=input.readInt();require(length in 1..4096 && length<=input.available())
            val pathBytes=input.readNBytes(length);val path=pathBytes.toString(Charsets.UTF_8);require(path.toByteArray(Charsets.UTF_8).contentEquals(pathBytes))
            val offset=input.readLong();val size=input.readInt();require(offset>=0 && size in 0..SMALL_FILE && size<=input.available())
            TransferPart(path,offset,input.readNBytes(size))
        }
        require(input.available()==0 && result.map {it.path}.distinct().size==count)
        return result
    }
    fun groups(paths:Collection<String>,size:(String)->Long):List<List<String>> {
        val groups=mutableListOf<List<String>>();var small=mutableListOf<String>();var bytes=8L
        fun flush(){if(small.isNotEmpty()){groups.add(small);small=mutableListOf();bytes=8}}
        paths.forEach {p->
            val n=size(p);require(n>=0);val framed=n+p.toByteArray(Charsets.UTF_8).size+16
            if(n>SMALL_FILE){flush();groups.add(listOf(p))}
            else {if(small.size==MAX_FILES || bytes+framed>MAX_BYTES)flush();small.add(p);bytes+=framed}
        };flush();return groups
    }
    /** At most four active tasks; no per-file threads or unbounded futures. Drain workers before retry/commit/update. */
    fun <T> parallel(items:List<T>,action:(T)->Unit) {
        if(items.isEmpty())return
        val pool=Executors.newFixedThreadPool(minOf(PARALLEL,items.size));val next=java.util.concurrent.atomic.AtomicInteger();val failed=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        try {
            val futures=(1..minOf(PARALLEL,items.size)).map {pool.submit(Callable {
                while(failed.get()==null){val n=next.getAndIncrement();if(n>=items.size)break
                    try {action(items[n])}catch(t:Throwable){failed.compareAndSet(null,t);break}
                }
            })}
            futures.forEach {it.get()};failed.get()?.let {throw it}
        } finally {pool.shutdown();while(!pool.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS)){} }
    }
}
