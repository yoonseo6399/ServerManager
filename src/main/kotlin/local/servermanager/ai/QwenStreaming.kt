package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class QwenUsage(val input:Long?=null,val output:Long?=null,val estimatedContext:Int=0,val modelContext:Int?=null,val purpose:String="main",val helperInput:Long=0,val helperOutput:Long=0,val helperCalls:Int=0,val executionWindow:Int=8192,val inputBudget:Int=3600,val outputReserved:Int=4096)

/** Snapshots, not token deltas: a slow Discord edit may skip intermediate snapshots without losing text. */
data class QwenProgress(val thinking:String="",val text:String="") {
    fun bounded()=QwenProgress(thinking.takeLast(1500),text.take(3500))
}

internal object QwenVisibleText {
    private val mapper=jacksonObjectMapper()
    /** Decode only a top-level message string. Tool arguments and raw partial JSON never reach the main reply. */
    fun message(json:String):String {
        var i=0
        fun space(){while(i<json.length && json[i].isWhitespace())i++}
        fun string(partial:Boolean):String? {
            if(i>=json.length || json[i++]!='"')return null
            val start=i-1;var escaped=false
            while(i<json.length){val c=json[i++];if(c=='"' && !escaped)return runCatching {mapper.readTree(json.substring(start,i)).asText()}.getOrNull();escaped=if(escaped)false else c=='\\'}
            if(!partial)return null
            // Finish only complete escapes. A split unicode escape stays invisible until its next chunk.
            var end=json.length
            while(end>start+1){val candidate=json.substring(start,end)+"\"";val result=runCatching {mapper.readTree(candidate).asText()}.getOrNull();if(result!=null)return result;end--}
            return ""
        }
        space();if(i>=json.length || json[i++]!='{')return ""
        while(i<json.length){
            space();val key=string(false) ?: return "";space();if(i>=json.length || json[i++]!=':')return "";space()
            if(key=="message")return string(true).orEmpty()
            var depth=0;var quoted=false;var escaped=false
            while(i<json.length){val c=json[i]
                if(quoted){if(c=='"' && !escaped)quoted=false;escaped=if(escaped)false else c=='\\'}
                else if(c=='"')quoted=true else if(c=='{' || c=='[')depth++ else if(c=='}' || c==']'){if(depth==0)return "";depth--} else if(c==',' && depth==0){i++;break}
                i++
            }
        }
        return ""
    }
}

/** At most one Discord edit in flight; final output cannot be overwritten by stale Thinking edits. */
internal class CoalescedMessageEdits<T>(private val send:(T,()->Unit)->Unit,
    private val scheduler:ScheduledExecutorService=shared,private val intervalMillis:Long=750) {
    private var next:T?=null;private var inFlight=false;private var scheduled=false;private var terminal=false;private var last=0L
    @Synchronized fun offer(value:T,finished:Boolean=false) {
        if(terminal)return
        next=value;terminal=finished;schedule()
    }
    @Synchronized private fun schedule(){if(!scheduled && !inFlight && next!=null){scheduled=true;val wait=(intervalMillis-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-last)).coerceAtLeast(0);scheduler.schedule(::drain,wait,TimeUnit.MILLISECONDS)}}
    private fun drain(){
        val value=synchronized(this){scheduled=false;if(inFlight)return;val v=next ?: return;next=null;inFlight=true;last=System.nanoTime();v}
        val complete={synchronized(this){inFlight=false;schedule()};Unit}
        try {send(value,complete)}catch(_:Exception){complete()}
    }
    companion object {private val shared=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"qwen-discord-edits").apply {isDaemon=true}}}
}
