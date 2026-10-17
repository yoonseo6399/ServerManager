package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.read
import kotlin.io.path.*

data class AutoRestartPolicy(val enabled:Boolean=false,val includeManual:Boolean=false)
data class AutoRestartStatus(val config:AutoRestartPolicy,val status:String,val remainingSeconds:Long?=null,val attempts:Int=0,val message:String?=null)

/** Persisted attempt times prevent resetting the crash-loop budget by restarting the manager. */
class RestartBudget(times:List<Long> = emptyList()) {
    private val attempts=times.toMutableList()
    fun recent(now:Long):List<Long> {attempts.removeIf {it<now-600000};return attempts.toList()}
    fun delay(now:Long)=15L shl recent(now).size.coerceAtMost(2)
    fun reserve(now:Long):Boolean {if(recent(now).size>=3)return false;attempts.add(now);return true}
}

@Service
class AutoRestart(private val manager:ServerManager,private val desktop:DesktopLifecycle,private val cloud:WebDavSync) {
    private data class Pending(val due:Long,val activity:Long)
    private val mapper=jacksonObjectMapper()
    private val policies=ConcurrentHashMap<String,AutoRestartPolicy>()
    private val budgets=ConcurrentHashMap<String,RestartBudget>()
    private val pending=ConcurrentHashMap<String,Pending>()
    private val messages=ConcurrentHashMap<String,String>()
    private val subscription=manager.onSignal {signal->when(signal.kind) {
        "start"->messages.remove(signal.instanceId)
        "stop"->{
            val id=signal.instanceId;val exit=signal.exit
            val policy=policy(id)
            if(!manager.runtime(id).diagnosticActive && exit!=null&&eligible(policy,exit)) {
                val now=System.currentTimeMillis()
                val budget=budget(id)
                val delay=synchronized(budget) {if(budget.recent(now).size>=3)null else budget.delay(now)}
                if(delay==null)messages[id]="10분 동안 3회 재시작하여 자동 재시작을 중단했습니다. 원인을 확인한 뒤 직접 시작하세요."
                else pending[id]=Pending(now+delay*1000,manager.runtime(id).activityVersion)
            }
        }
    }}
    init {manager.attachAutoRestartStatus(::status)}
    private fun policy(id:String)=policies.computeIfAbsent(id) {
        val path=manager.root.resolve(id).resolve("auto-restart.json")
        if(path.exists())mapper.readValue(path.readText()) else AutoRestartPolicy()
    }
    private fun budget(id:String)=budgets.computeIfAbsent(id) {
        val path=manager.root.resolve(id).resolve("auto-restart-attempts.json")
        RestartBudget(if(path.exists())mapper.readValue<List<Long>>(path.readText()).takeLast(3) else emptyList())
    }
    fun status(id:String):AutoRestartStatus {
        val r=manager.runtime(id);val policy=policy(id);val p=pending[id];val now=System.currentTimeMillis()
        val budget=budget(id);val attempts=synchronized(budget){budget.recent(now).size}
        return AutoRestartStatus(policy,when {
            !policy.enabled->"OFF"
            messages.containsKey(id)->"PAUSED"
            p==null->"WAITING"
            r.diagnosticActive->"DIAGNOSTIC"
            r.state !in setOf("STOPPED","FAILED")||!r.remoteCloudReady||(manager.instance(id).runnerId!=null&&!r.remoteWorkerReady)->"DATA"
            else->"COUNTDOWN"
        },p?.let {maxOf(0,(it.due-now+999)/1000)},attempts,messages[id])
    }
    fun save(id:String,policy:AutoRestartPolicy):AutoRestartStatus {
        val r=manager.runtime(id)
        synchronized(r) {
            atomicText(manager.root.resolve(id).resolve("auto-restart.json"),mapper.writeValueAsString(policy))
            policies[id]=policy;pending.remove(id);messages.remove(id)
        }
        return status(id)
    }
    fun cancel(id:String):AutoRestartStatus {
        val r=manager.runtime(id);synchronized(r){pending.remove(id);messages[id]="자동 재시작 대기를 취소했습니다."};return status(id)
    }
    @Scheduled(fixedDelay=1000) fun tick() {tick(System.currentTimeMillis())}
    internal fun tick(now:Long) {
        pending.forEach {(id,p)->
            if(now<p.due)return@forEach
            desktop.gate.read {
                if(desktop.stopping||desktop.home.resolve("updates/pending.txt").exists()||desktop.home.resolve("pending-data-location.txt").exists())return@read
                runCatching {
                    manager.withStopped(id) {
                        val r=manager.runtime(id)
                        if(pending[id]!=p)return@withStopped
                        if(!policy(id).enabled||r.activityVersion!=p.activity){pending.remove(id,p);return@withStopped}
                        if(r.diagnosticActive||!r.remoteCloudReady||(manager.instance(id).runnerId!=null&&!r.remoteWorkerReady))return@withStopped
                        val sync=cloud.status(id)
                        if(sync.enabled&&(sync.inProgress||sync.lastError!=null))return@withStopped
                        val b=budget(id)
                        val allowed=synchronized(b) {
                            if(!b.reserve(now))false else {
                                atomicText(manager.root.resolve(id).resolve("auto-restart-attempts.json"),mapper.writeValueAsString(b.recent(now)));true
                            }
                        }
                        pending.remove(id,p)
                        if(!allowed){messages[id]="10분 동안 3회 재시작하여 자동 재시작을 중단했습니다.";return@withStopped}
                        try {manager.startAs(id,StartActor("AUTO","자동 재시작"))} catch(_:Exception) {messages[id]="자동 재시작에 실패했습니다. 실행기 연결·메모리·설치 상태를 확인하고 직접 시작하세요.";manager.automaticRestartError(id,messages.getValue(id))}
                    }
                }.onFailure {
                    if(runCatching {manager.runtime(id).activityVersion!=p.activity}.getOrDefault(true))pending.remove(id,p)
                }
            }
        }
    }
    @PreDestroy fun close(){subscription.close();pending.clear()}
    companion object {
        fun eligible(policy:AutoRestartPolicy,exit:ServerExit)=policy.enabled && exit.reason !in setOf("IDLE","RESTART","SHUTDOWN") && (!exit.requested||policy.includeManual)
    }
}

@RestController
@RequestMapping("/api/instances/{id}/auto-restart")
class AutoRestartApi(private val restart:AutoRestart) {
    @GetMapping fun get(@PathVariable id:String)=restart.status(id)
    @PutMapping fun save(@PathVariable id:String,@RequestBody policy:AutoRestartPolicy)=restart.save(id,policy)
    @PostMapping("/cancel") fun cancel(@PathVariable id:String)=restart.cancel(id)
}
