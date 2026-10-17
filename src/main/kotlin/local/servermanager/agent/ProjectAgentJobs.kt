package local.servermanager

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Stop buttons are bound to one owner/channel/run, including routing before the engine starts. */
internal class ProjectAgentJobs:AutoCloseable {
    data class Job(val user:String,val id:String=UUID.randomUUID().toString(),val thread:Thread=Thread.currentThread(),val cancelled:AtomicBoolean=AtomicBoolean())
    private val jobs=ConcurrentHashMap<String,Job>()
    fun begin(channel:String,user:String):Job = Job(user).also {check(jobs.putIfAbsent(channel,it)==null) {"이 채널에서 작업 중입니다. 중지 버튼으로 먼저 중단하세요."}}
    fun stop(channel:String,user:String,id:String?=null) {
        val job=jobs[channel] ?: error("이미 끝난 작업입니다.")
        check(job.user==user && (id==null || job.id==id)) {"본인의 현재 작업만 중단할 수 있습니다."}
        job.cancelled.set(true);if(job.thread!==Thread.currentThread())job.thread.interrupt()
    }
    fun finish(channel:String,job:Job){jobs.remove(channel,job)}
    fun stopUser(user:String){jobs.entries.filter {it.value.user==user}.forEach {runCatching {stop(it.key,user,it.value.id)}}}
    override fun close(){jobs.values.forEach {it.cancelled.set(true);if(it.thread!==Thread.currentThread())it.thread.interrupt()}}
}
