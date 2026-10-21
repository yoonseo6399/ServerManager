package local.servermanager

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs on the inference host, including while waiting for model loading/prefill. */
internal class LlmMemoryGuard(private val allowed:Boolean,private val sample:()->LlmMemory={LlmMemory.sample()}) {
    fun check() {if(Thread.currentThread().isInterrupted)throw InterruptedException();if(!allowed){val memory=sample();if(Thread.currentThread().isInterrupted)throw InterruptedException();check(memory.pressure!="위험") {"$MARKER 메모리 압력이 위험 수준이어서 모델 호출을 중단했습니다. ${memory.compact()}"}}}
    fun <T> await(pending:CompletableFuture<T>,seconds:Long):T {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds)
        while(true){check();val left=deadline-System.nanoTime();if(left<=0)throw TimeoutException();try{return pending.get(minOf(left,TimeUnit.SECONDS.toNanos(1)),TimeUnit.NANOSECONDS)}catch(_:TimeoutException){if(pending.isDone)return pending.get()}}
    }
    companion object {const val MARKER="MEMORY_PRESSURE_CRITICAL:";fun reason(error:Throwable):String?=generateSequence(error){it.cause}.mapNotNull {it.message}.firstOrNull {MARKER in it}}
}
