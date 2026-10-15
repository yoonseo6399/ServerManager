package local.servermanager

import java.util.concurrent.*

/** Bounded, single-flight HTTP transactions. Polling never waits for the file transaction lock. */
class TransferTasks:AutoCloseable {
    data class Key(val instance:String,val runner:String,val context:String,val operation:String,val request:String)
    private val monitor=Any()
    private val jobs=linkedMapOf<Key,Future<Any>>()
    private val pool=ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,ArrayBlockingQueue(8),{r->Thread(r,"transfer-transaction").apply {isDaemon=true}})
    fun busy(instance:String)=synchronized(monitor){jobs.any {(k,v)->k.instance==instance&&!v.isDone}}
    @Suppress("UNCHECKED_CAST")
    fun <T:Any> poll(key:Key,consume:Boolean=false,work:()->T):T? {
        val future=synchronized(monitor) {
            jobs[key] ?: run {
                if(jobs.any {(k,v)->k.instance==key.instance&&!v.isDone})return null
                if(jobs.size>=8) {val old=jobs.entries.firstOrNull {it.value.isDone} ?: return null;jobs.remove(old.key)}
                val job=FutureTask<Any>{work()}
                try {pool.execute(job)}catch(_:RejectedExecutionException){return null}
                jobs[key]=job;job
            }
        }
        if(!future.isDone)return null
        return try {(future.get() as T).also {if(consume)synchronized(monitor){jobs.remove(key,future)}}}
        catch(e:ExecutionException){synchronized(monitor){jobs.remove(key,future)};throw e.cause ?: e}
    }
    override fun close(){pool.shutdownNow();synchronized(monitor){jobs.clear()}}
}
