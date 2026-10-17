package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import jakarta.annotation.PreDestroy
import kotlin.io.path.*

data class PowerPolicy(val enabled:Boolean=false,val minutes:Int=30)
data class PowerStatus(val config:PowerPolicy,val status:String="OFF",val emptySeconds:Long=0,val remainingSeconds:Long?=null,val lastStopReason:String?=null)
private data class PowerStopRecord(val reason:String="",val at:String="")

/** Only contiguous, distinct, fresh zero-player samples advance the clock. */
class EmptyPlayerTimer {
    private var since:Long?=null
    private var lastAt:Long?=null
    private var lastSample:String?=null
    private var identity:String?=null
    var status="OFF";private set
    var emptySeconds=0L;private set
    @Synchronized fun reset(reason:String) {since=null;lastAt=null;lastSample=null;identity=null;emptySeconds=0;status=reason}
    @Synchronized fun observe(policy:PowerPolicy,state:String,diagnostic:Boolean,identity:String,sample:PlayerSample?,wall:Instant,now:Long):Boolean {
        if(!policy.enabled){reset("OFF");return false}
        if(state!="RUNNING"||diagnostic){reset(if(diagnostic)"DIAGNOSTIC" else "WAITING");return false}
        val age=sample?.let {runCatching {java.time.Duration.between(Instant.parse(it.sampledAt),wall).toMillis()}.getOrNull()}
        if(sample==null||age==null||age !in 0..25000||sample.count<0){reset("UNKNOWN");return false}
        if(sample.count>0){reset("OCCUPIED");return false}
        if(this.identity!=identity || lastAt?.let {now<it||now-it>25000}==true)reset("EMPTY")
        this.identity=identity
        // Repeated reads of one cached observation never establish continuous emptiness.
        if(lastSample==sample.sampledAt)return false
        lastAt=now;lastSample=sample.sampledAt
        if(since==null)since=now
        emptySeconds=maxOf(0L,(now-since!!)/1000);status="EMPTY"
        return emptySeconds>=policy.minutes*60L
    }
    @Synchronized fun snapshot(policy:PowerPolicy,reason:String?)=PowerStatus(policy,status,emptySeconds,if(status=="EMPTY")maxOf(0L,policy.minutes*60L-emptySeconds) else null,reason)
}

@Service
class PowerSaving(private val manager:ServerManager) {
    private val mapper=jacksonObjectMapper()
    private val policies=ConcurrentHashMap<String,PowerPolicy>()
    private val timers=ConcurrentHashMap<String,EmptyPlayerTimer>()
    private val checks=ConcurrentHashMap.newKeySet<String>()
    private val lastStops=ConcurrentHashMap<String,PowerStopRecord>()
    private val executor=Executors.newCachedThreadPool()
    private val subscription=manager.onSignal {event->if(event.kind=="stop") {
        manager.withStopped(event.instanceId) {
            val record=PowerStopRecord(manager.runtime(event.instanceId).stopReason ?: "OTHER",Instant.now().toString())
            // Decision history, never parsed console/stack-trace output.
            atomicText(manager.root.resolve(event.instanceId).resolve("power-saving-last-stop.json"),mapper.writeValueAsString(record))
            lastStops[event.instanceId]=record
        }
    }}
    init {manager.attachPowerStatus {id->status(id)}}
    private fun policy(id:String)=policies.computeIfAbsent(id) {
        val file=manager.root.resolve(id).resolve("power-saving.json")
        if(file.exists())mapper.readValue<PowerPolicy>(file.readText()).also {require(it.minutes in 30..60)} else PowerPolicy()
    }
    fun status(id:String):PowerStatus {
        val r=manager.runtime(id);val config=policy(id)
        val stop=lastStops.computeIfAbsent(id){
            val file=manager.root.resolve(id).resolve("power-saving-last-stop.json")
            if(file.exists())mapper.readValue<PowerStopRecord>(file.readText()) else PowerStopRecord()
        }
        val result=timers.computeIfAbsent(id){EmptyPlayerTimer().also {it.reset(if(config.enabled)"WAITING" else "OFF")}}.snapshot(config,stop.reason.ifBlank {null})
        return if(r.idleCheckPending)result.copy(status="VERIFYING",remainingSeconds=null) else result
    }
    fun save(id:String,config:PowerPolicy):PowerStatus {
        require(config.minutes in 30..60) {"무인 종료 대기 시간은 30~60분입니다"}
        val runtime=manager.runtime(id)
        synchronized(runtime) {
            check(!runtime.idleCheckPending) {"종료 직전 인원 확인 중입니다. 잠시 후 다시 시도하세요"}
            atomicText(manager.root.resolve(id).resolve("power-saving.json"),mapper.writeValueAsString(config))
            runtime.activityVersion++
            policies[id]=config;timers.computeIfAbsent(id){EmptyPlayerTimer()}.reset(if(config.enabled)"WAITING" else "OFF")
        }
        return status(id)
    }
    @Scheduled(fixedDelay=2000) fun tick() {
        val views=manager.list();val ids=views.map {(it["config"] as Instance).id}.toSet()
        timers.keys.removeIf {it !in ids};policies.keys.removeIf {it !in ids};lastStops.keys.removeIf {it !in ids}
        views.forEach {view->
            val instance=view["config"] as Instance;val id=instance.id;val r=manager.runtime(id)
            val timer=timers.computeIfAbsent(id){EmptyPlayerTimer()};val config=policy(id)
            val identity="${view["pid"]}:${view["activityVersion"]}:${instance.runnerId}"
            val due=timer.observe(config,view["state"].toString(),view["diagnosticActive"]==true,identity,view["players"] as? PlayerSample,Instant.now(),System.nanoTime()/1_000_000)
            if(r.idleCheckPending) {manager.expireIdleCheck(id);return@forEach}
            if(due && checks.add(id))executor.submit {
                try {
                    if(policy(id)==config && manager.stopIfEmpty(id,view["activityVersion"] as Long))timer.reset("STOP_REQUESTED")
                    else timer.reset("VERIFY_FAILED")
                } catch(_:Exception){timer.reset("VERIFY_FAILED")} finally {checks.remove(id)}
            }
        }
    }
    @PreDestroy fun close(){subscription.close();executor.shutdownNow()}
}

@RestController
@RequestMapping("/api/instances/{id}/power-saving")
class PowerSavingApi(private val power:PowerSaving) {
    @GetMapping fun get(@PathVariable id:String)=power.status(id)
    @PutMapping fun save(@PathVariable id:String,@RequestBody config:PowerPolicy)=power.save(id,config)
}
