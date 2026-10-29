package local.servermanager

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A file commit is acknowledged before slow recovery-copy work. Coalesce adjacent edits per server. */
internal class DeferredWorkspaceEdits(private val delayMs:Long=1500,private val warning:(String,Exception)->Unit={_,_->}):AutoCloseable {
    private val executor=Executors.newScheduledThreadPool(2)
    private data class Pending(val version:Long,val action:()->Boolean,var future:ScheduledFuture<*>?=null)
    private val pending=mutableMapOf<String,Pending>()
    private var serial=0L
    @Synchronized fun mark(id:String,action:()->Boolean) {
        if(executor.isShutdown)return
        pending.remove(id)?.future?.cancel(false)
        val value=Pending(++serial,action);pending[id]=value;schedule(id,value)
    }
    private fun schedule(id:String,value:Pending) {
        value.future=executor.schedule({
            val done=try{value.action()}catch(e:Exception){warning(id,e);false}
            synchronized(this){
                if(pending[id]===value){if(done)pending.remove(id) else if(!executor.isShutdown)schedule(id,value)}
            }
        },delayMs,TimeUnit.MILLISECONDS)
    }
    @Synchronized override fun close(){executor.shutdownNow();pending.clear()}
}
