package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import kotlin.io.path.*

object UpdateComponents {
    const val MANAGER_APP="MANAGER_APP"
    const val RUNNER_CONTROL="RUNNER_CONTROL"
    const val GAME_HOST="GAME_HOST"
    val all=listOf(MANAGER_APP,RUNNER_CONTROL,GAME_HOST)
}
object UpdateStages {
    val all=setOf("IDLE","CHECKING","DOWNLOADING","VERIFYING","WAITING","APPLYING","RECONNECTING","DONE","FAILED")
}
/** One row of the update status API. Never contains a token, URL with credentials, or an exception body. */
data class ComponentUpdate(val component:String,val current:String,val target:String?,val stage:String="IDLE",val waitReason:String?=null,
    val error:String?=null,val updatedAt:String="",val jobId:String?=null,val waitDetail:String?=null)
data class UpdateLogEntry(val seq:Long,val time:String,val jobId:String?,val component:String,val stage:String,val message:String)
data class UpdateDetail(val components:List<ComponentUpdate>,val log:List<UpdateLogEntry>,val hostMode:HostModeState?=null,val protocol:Int=1)

object UpdateRedaction {
    private val patterns=listOf(
        Regex("(?i)bearer\\s+\\S+"),
        Regex("(?i)(authorization|token|secret|password|passwd|api[_-]?key|signature)\\s*[=:]\\s*\\S+"),
        Regex("[A-Za-z0-9_-]{40,}"),
        Regex("(?i)https?://[^\\s/@]+:[^\\s/@]+@"))
    fun text(value:String):String {
        var out=value.take(300)
        patterns.forEach {out=out.replace(it,"[redacted]")}
        return out
    }
}

/**
 * Durable, restart-safe record of what each component is doing. The log lives next to the update payloads so a controller
 * that reconnects after an update can still report what happened while it was gone.
 */
class UpdateCoordinator(private val home:Path,private val clock:()->Instant={Instant.now()},private val maxLog:Int=300) {
    private val mapper=jacksonObjectMapper()
    private val dir=home.resolve("updates")
    private val logFile=dir.resolve("progress.log")
    private val stateFile=dir.resolve("components.json")
    private val components=linkedMapOf<String,ComponentUpdate>()
    private var seq=0L
    private var jobCounter=0L
    private val log=ArrayDeque<UpdateLogEntry>()
    @Volatile var onChange:(UpdateDetail)->Unit={}
    init {
        dir.createDirectories()
        runCatching {
            if(stateFile.exists())mapper.readValue(stateFile.readText(),Array<ComponentUpdate>::class.java).forEach {components[it.component]=it}
            if(logFile.exists())logFile.readLines().takeLast(maxLog).mapNotNull {runCatching {mapper.readValue(it,UpdateLogEntry::class.java)}.getOrNull()}.forEach {log.addLast(it);seq=maxOf(seq,it.seq)}
            jobCounter=log.mapNotNull {it.jobId?.substringAfterLast('-')?.toLongOrNull()}.maxOrNull() ?: 0
        }
    }
    @Synchronized fun jobFor(component:String,target:String?):String? {
        val existing=components[component]
        if(target==null)return null
        if(existing?.target==target && existing.jobId!=null && existing.stage !in setOf("DONE","FAILED"))return existing.jobId
        return "job-${component.lowercase().replace('_','-')}-${target}-${++jobCounter}"
    }
    /** Records a transition. [current] is the running version, [target] the version being moved to. */
    @Synchronized fun update(component:String,current:String,target:String?,stage:String,waitReason:String?=null,error:String?=null,message:String?=null,waitDetail:String?=null) {
        require(component in UpdateComponents.all && stage in UpdateStages.all)
        val previous=components[component]
        val job=jobFor(component,target)
        val now=clock().toString()
        val safeError=error?.let {UpdateRedaction.text(it)}
        val next=ComponentUpdate(component,current,target,stage,waitReason?.take(60),safeError,now,job,waitDetail?.let {UpdateRedaction.text(it)}?.take(120))
        components[component]=next
        // Only changes are logged so heartbeats do not flood the history.
        if(previous?.stage!=stage || previous.waitReason!=next.waitReason || previous.waitDetail!=next.waitDetail || previous.error!=next.error || previous.target!=target || previous.current!=current)
            append(UpdateLogEntry(++seq,now,job,component,stage,UpdateRedaction.text(message ?: describe(stage,waitReason,safeError,current,target))))
        persist()
        onChange(snapshot())
    }
    private fun describe(stage:String,reason:String?,error:String?,current:String,target:String?)=when(stage) {
        "WAITING" -> "대기: ${reason ?: "조건 확인 중"}"
        "FAILED" -> "실패: ${error ?: "원인 미상"}"
        "DONE" -> "완료: $current"
        else -> "$stage ${target ?: current}"
    }
    private fun append(entry:UpdateLogEntry) {
        log.addLast(entry);while(log.size>maxLog)log.removeFirst()
        runCatching {
            logFile.parent.createDirectories()
            if(log.size>=maxLog && logFile.exists() && logFile.fileSize()>256*1024)logFile.writeText(log.joinToString("\n"){mapper.writeValueAsString(it)}+"\n")
            else Files.writeString(logFile,mapper.writeValueAsString(entry)+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
        }
    }
    private fun persist() {
        val tmp=Files.createTempFile(dir,".components-",".tmp")
        try {tmp.writeText(mapper.writeValueAsString(components.values.toList()));Files.move(tmp,stateFile,ATOMIC_MOVE,REPLACE_EXISTING)} finally {tmp.deleteIfExists()}
    }
    @Synchronized fun snapshot(hostMode:HostModeState?=null)=UpdateDetail(UpdateComponents.all.map {components[it] ?: ComponentUpdate(it,"unknown",null)},log.toList(),hostMode)
    @Synchronized fun logSince(after:Long)=log.filter {it.seq>after}
    @Synchronized fun current(component:String)=components[component]
    /** Only call with a version from the currently authenticated host connection, never the endpoint file alone. */
    @Synchronized fun reconcileHost(running:String,expected:String):Boolean {
        if(running!=expected)return false
        val row=components[UpdateComponents.GAME_HOST]
        if(row?.target!=null && row.target!=expected)return false
        if(row?.current==running && row.target==null && row.stage=="DONE" && row.error==null)return false
        update(UpdateComponents.GAME_HOST,running,null,"DONE",message="상주 서비스 연결 확인: $running")
        return true
    }
    /** After a restart: a pending update whose target is now the running version is complete; anything else keeps its state. */
    @Synchronized fun reconcileStart(component:String,running:String,failedVersion:String?) {
        val c=components[component] ?: return
        when {
            c.target!=null && c.target==running && c.stage !in setOf("DONE") -> update(component,running,c.target,"DONE",message="재시작 후 새 버전이 시작됐습니다: $running")
            c.target!=null && failedVersion==c.target && c.stage!="FAILED" -> update(component,running,c.target,"FAILED",error="새 버전 시작 확인에 실패해 $running 로 되돌렸습니다",message="롤백: ${c.target} 시작 실패 → $running")
            c.target!=null && SignedUpdates.compare(c.target,running)<=0 && c.stage in setOf("APPLYING","RECONNECTING") -> update(component,running,null,"DONE")
        }
    }
}

/**
 * Decides whether a component may be replaced right now. Pure function of facts, so every refusal has a stated reason and the
 * dirty/receipt/lease protections are never bypassed: a mid-transfer update only ever pauses at a checkpoint.
 */
data class UpdateFacts(
    val hostedGamesAlive:Int=0,
    val legacyGamesAlive:Int=0,
    val hostProtocol:Int?=null,
    val hostMinCompatible:Int=1,
    val controllerProtocol:Int=GameHostProtocol.VERSION,
    val targetMinHost:Int=1,
    val targetMaxHost:Int=1,
    val transferActive:Boolean=false,
    val transferResumable:Boolean=true,
    val dirtyWithoutProcess:Boolean=false,
    val preparing:Boolean=false,
    val relayStreamsInController:Int=0,
    val relayStreamsInHost:Int=0,
    val targetHostProtocolDiffers:Boolean=false)
data class UpdateDecision(val apply:Boolean,val waitReason:String?=null,val pauseTransfers:Boolean=false)
object UpdatePolicy {
    fun decide(component:String,f:UpdateFacts):UpdateDecision {
        val gamesAlive=f.hostedGamesAlive+f.legacyGamesAlive
        return when(component) {
            UpdateComponents.GAME_HOST -> when {
                gamesAlive>0 -> UpdateDecision(false,"GAME_RUNNING")
                f.relayStreamsInHost>0 -> UpdateDecision(false,"RELAY_STREAMS_ACTIVE")
                f.preparing -> UpdateDecision(false,"SERVER_PREPARING")
                else -> UpdateDecision(true)
            }
            else -> when {
                f.legacyGamesAlive>0 -> UpdateDecision(false,"LEGACY_GAME_RUNNING")
                f.hostedGamesAlive>0 && f.hostProtocol==null -> UpdateDecision(false,"HOST_UNREACHABLE")
                f.hostedGamesAlive>0 && !HostCompatibility.controlUpdate(f.hostProtocol!!,f.hostMinCompatible,f.controllerProtocol,f.targetMinHost,f.targetMaxHost).allowed -> UpdateDecision(false,"HOST_PROTOCOL_INCOMPATIBLE")
                f.relayStreamsInController>0 -> UpdateDecision(false,"RELAY_STREAMS_ACTIVE")
                f.preparing -> UpdateDecision(false,"SERVER_PREPARING")
                f.transferActive && !f.transferResumable -> UpdateDecision(false,"TRANSFER_NOT_RESUMABLE")
                f.transferActive -> UpdateDecision(true,pauseTransfers=true)
                f.dirtyWithoutProcess -> UpdateDecision(false,"DIRTY_HANDOFF_PENDING")
                else -> UpdateDecision(true)
            }
        }
    }
}

/**
 * Start confirmation and rollback for a signed package. The control layer calls [confirm] only after it has reached the
 * manager and re-attached to the host; otherwise the next start rolls back to the previous version.
 * File names match RunnerBootstrap (active.txt, previous.txt, failed-update-version.txt) so both sides agree.
 */
class ActivationGuard(private val home:Path) {
    private val updates=home.resolve("updates")
    private val pending=updates.resolve("activation.json")
    private val mapper=jacksonObjectMapper()
    data class Pending(val component:String,val from:String,val to:String,val attempts:Int,val deadlineMillis:Long)
    private fun text(path:Path)=path.takeIf {it.exists()}?.readText()?.trim()
    private fun atomic(path:Path,value:String){path.parent.createDirectories();val t=Files.createTempFile(path.parent,".w-",".tmp");try {t.writeText(value);Files.move(t,path,ATOMIC_MOVE,REPLACE_EXISTING)} finally {t.deleteIfExists()}}
    fun begin(component:String,from:String,to:String,deadlineMillis:Long,now:Long=System.currentTimeMillis()) {
        // active.txt/previous.txt belong to the supervisor (RunnerBootstrap); this journal only records what must be confirmed.
        atomic(pending,mapper.writeValueAsString(Pending(component,from,to,(read()?.takeIf {it.to==to}?.attempts ?: 0)+1,now+deadlineMillis)))
    }
    fun read():Pending?=runCatching {mapper.readValue(pending.readText(),Pending::class.java)}.getOrNull()
    fun confirm(running:String):Boolean {
        val p=read() ?: return false
        if(p.to!=running)return false
        pending.deleteIfExists();return true
    }
    sealed interface Outcome {
        data object Nothing:Outcome
        data class Confirmed(val version:String):Outcome
        data class RolledBack(val failed:String,val restored:String,val reason:String):Outcome
        data class Waiting(val version:String,val remainingMillis:Long):Outcome
    }
    /** Evaluated at every control-layer start. [running] is the version that actually started. */
    fun check(running:String,now:Long=System.currentTimeMillis(),maxAttempts:Int=2):Outcome {
        val p=read() ?: return Outcome.Nothing
        return when {
            p.to==running && now<=p.deadlineMillis -> Outcome.Waiting(running,p.deadlineMillis-now)
            p.to==running -> rollback(p,"시작 확인 제한 시간을 넘겼습니다")
            p.attempts>=maxAttempts -> rollback(p,"새 버전이 ${p.attempts}번 시작에 실패했습니다")
            else -> rollback(p,"실행된 버전($running)이 목표 버전(${p.to})과 다릅니다")
        }
    }
    private fun rollback(p:Pending,reason:String):Outcome {
        atomic(home.resolve("failed-update-version.txt"),p.to)
        atomic(updates.resolve("active.txt"),p.from)
        if(updates.resolve("rollback-active.txt").exists())atomic(updates.resolve("rollback-active.txt"),p.from)
        updates.resolve("pending.txt").deleteIfExists();pending.deleteIfExists()
        return Outcome.RolledBack(p.to,p.from,reason)
    }
}

/** Verifies a staged update package against the pinned key before it may become pending. */
object UpdatePackageVerifier {
    sealed interface Result {
        data class Ok(val version:String,val minHost:Int,val maxHost:Int,val hostProtocol:Int):Result
        data class Rejected(val reason:String):Result
    }
    fun verify(manifest:ByteArray,signature:ByteArray,jar:Path,key:ByteArray,expectedVersion:String,currentVersion:String,allowRollback:Boolean=false):Result {
        val props=try {SignedUpdates.manifest(manifest,signature,key)} catch(e:Exception){return Result.Rejected("SIGNATURE_INVALID")}
        val version=props.getProperty("version")
        if(version!=expectedVersion)return Result.Rejected("VERSION_MISMATCH")
        val comparison=runCatching {SignedUpdates.compare(version,currentVersion)}.getOrDefault(0)
        if(comparison<=0 && !(comparison<0 && allowRollback && UpdateRollback.stableTarget(version)))return Result.Rejected("NOT_NEWER")
        if(!SignedUpdates.validFile(jar,props))return Result.Rejected("PACKAGE_HASH_MISMATCH")
        val minHost=props.getProperty("controller.minHost","1").toIntOrNull() ?: return Result.Rejected("MANIFEST_INVALID")
        val maxHost=props.getProperty("controller.maxHost",minHost.toString()).toIntOrNull() ?: return Result.Rejected("MANIFEST_INVALID")
        val hostProtocol=props.getProperty("host.protocol",maxHost.toString()).toIntOrNull() ?: return Result.Rejected("MANIFEST_INVALID")
        if(minHost<1 || maxHost<minHost)return Result.Rejected("MANIFEST_INVALID")
        return Result.Ok(version,minHost,maxHost,hostProtocol)
    }
}
