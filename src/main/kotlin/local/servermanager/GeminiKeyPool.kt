package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

data class PoolKeyConfig(val id:String,val project:String,val rpm:Int=5,val tpm:Long=250000,val rpd:Int=20,val enabled:Boolean=true)
// Secret-bearing objects intentionally have no generated toString().
class PoolCredential(val config:PoolKeyConfig,val secret:String)

class GeminiKeyPoolStore(val directory:Path,private val legacy:()->String?={AgentApiKeyStore(directory).read()}) {
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("key-pool.json")
    fun configs():List<PoolKeyConfig> {
        check(!Files.isSymbolicLink(file))
        val list:List<PoolKeyConfig> = if(file.exists())mapper.readValue(file.readText()) else if(!legacy().isNullOrBlank())listOf(PoolKeyConfig("default","default")) else emptyList()
        validate(list);return list
    }
    private fun validate(list:List<PoolKeyConfig>) {
        require(list.size<=32 && list.distinctBy {it.id}.size==list.size) { "API 키는 중복 없이 최대 32개까지 등록하세요." }
        list.forEach {c ->
            require(c.id.matches(Regex("[A-Za-z0-9_-]{1,40}")) && c.project.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "키 이름과 프로젝트 ID는 영문·숫자·밑줄·하이픈을 사용하세요." }
            require(c.rpm in 1..100000 && c.tpm in 1..1_000_000_000 && c.rpd in 1..100_000_000) { "RPM·입력 TPM·RPD는 양수로 설정하세요." }
        }
        list.groupBy {it.project}.values.forEach {group ->
            require(group.map {Triple(it.rpm,it.tpm,it.rpd)}.distinct().size==1) { "같은 프로젝트의 모든 키는 동일한 한도를 설정하세요." }
        }
    }
    fun credentials():List<PoolCredential> = configs().filter {it.enabled}.mapNotNull {c ->
        val secret=existingSecret(c.id)
        secret?.takeIf {it.isNotBlank()}?.let {PoolCredential(c,it)}
    }
    fun existingSecret(id:String):String? {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        return if(id=="default")legacy() else AgentApiKeyStore(directory.resolve("keys").resolve(id)).read()
    }
    fun upsert(config:PoolKeyConfig,secret:String) {
        val next=configs().filterNot {it.id==config.id}.map {if(it.project==config.project)it.copy(rpm=config.rpm,tpm=config.tpm,rpd=config.rpd) else it}+config
        validate(next)
        require(configs().none {it.id!=config.id && existingSecret(it.id)==secret}) { "같은 API 키가 이미 다른 이름으로 등록되어 있습니다." }
        if(config.id=="default")AgentApiKeyStore(directory).save(secret)
        else AgentApiKeyStore(directory.resolve("keys").resolve(config.id)).save(secret)
        writePrivate(file,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(next))
    }
    fun remove(id:String) {
        val before=configs();require(before.any {it.id==id}) { "등록되지 않은 키 이름입니다." }
        writePrivate(file,mapper.writeValueAsString(before.filterNot {it.id==id}))
    }
    fun redact(text:String):String = credentials().fold(text) {value,c -> value.replace(c.secret,"[redacted]")}
    internal fun writePrivate(path:Path,text:String) {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(path))
        directory.createDirectories();Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        atomicText(path,text);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"))
    }
}

data class PoolMinute(var id:String="",var at:Long=0,var input:Long=0)
data class PoolProjectUsage(var day:String="",var requests:Int=0,var totalTokens:Long=0,var inputTokens:Long=0,var cooldown:Long=0,val minute:MutableList<PoolMinute> = mutableListOf())
data class PoolLedger(val projects:MutableMap<String,PoolProjectUsage> = linkedMapOf(),val disabledKeys:MutableMap<String,Long> = linkedMapOf(),val lastKeys:MutableMap<String,Long> = linkedMapOf(),var selections:Long=0)
class PoolLease(val credential:PoolCredential,val group:String,val reservation:String)
class PoolDecision(val lease:PoolLease?,val waitMs:Long,val reason:String)

/** Reservations persist before HTTP; all keys in a project/model share the same counters. */
class GeminiQuotaTracker(private val store:GeminiKeyPoolStore,private val model:String,private val now:()->Long={System.currentTimeMillis()}) {
    private val mapper=jacksonObjectMapper()
    private val state=store.directory.resolve("key-usage.json")
    companion object {private val monitors=ConcurrentHashMap<Path,Any>();private val zone=ZoneId.of("America/Los_Angeles")}
    private fun day(at:Long)=Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    private fun <T> locked(block:(PoolLedger)->T):T = synchronized(monitors.computeIfAbsent(store.directory.toAbsolutePath().normalize()){Any()}) {
        store.directory.createDirectories()
        val lockPath=store.directory.resolve("key-usage.lock")
        require(!Files.isSymbolicLink(lockPath) && !Files.isSymbolicLink(state))
        FileChannel.open(lockPath,CREATE,WRITE).use {channel -> channel.lock().use {
            Files.setPosixFilePermissions(lockPath,PosixFilePermissions.fromString("rw-------"))
            val ledger:PoolLedger=if(state.exists())mapper.readValue(state.readText()) else PoolLedger()
            val result=block(ledger)
            store.writePrivate(state,mapper.writeValueAsString(ledger));result
        } }
    }
    private fun usage(ledger:PoolLedger,group:String,at:Long):PoolProjectUsage {
        val u=ledger.projects.getOrPut(group){PoolProjectUsage()}
        if(u.day!=day(at)) {u.day=day(at);u.requests=0;u.totalTokens=0;u.inputTokens=0}
        u.minute.removeIf {at-it.at>=60000}
        return u
    }
    fun reserve(credentials:List<PoolCredential>,estimate:Long):PoolDecision=locked {ledger ->
        val at=now();var earliest=Long.MAX_VALUE;val reasons=linkedSetOf<String>()
        val candidates=credentials.sortedWith(compareBy<PoolCredential> {c ->
            val u=usage(ledger,"${c.config.project}/$model",at)
            maxOf(u.requests.toDouble()/c.config.rpd,u.minute.size.toDouble()/c.config.rpm,u.minute.sumOf {it.input}.toDouble()/c.config.tpm)
        }.thenBy {ledger.lastKeys[it.config.id] ?: 0L})
        for(c in candidates) {
            val cfg=c.config;val group="${cfg.project}/$model";val u=usage(ledger,group,at)
            val disabled=ledger.disabledKeys[cfg.id] ?: 0L
            if(disabled>at) {earliest=minOf(earliest,disabled);reasons.add("${cfg.id}: 인증 대기");continue}
            if(u.requests>=cfg.rpd) {reasons.add("${cfg.project}: RPD 소진");continue}
            if(estimate>cfg.tpm) {reasons.add("${cfg.project}: 요청 크기가 입력 TPM 초과");continue}
            if(u.cooldown>at) {earliest=minOf(earliest,u.cooldown);reasons.add("${cfg.project}: 서버 제한 대기");continue}
            if(u.minute.size>=cfg.rpm || u.minute.sumOf {it.input}+estimate>cfg.tpm) {
                earliest=minOf(earliest,(u.minute.minOfOrNull {it.at} ?: at)+60000)
                reasons.add("${cfg.project}: RPM/입력 TPM 대기");continue
            }
            val id=UUID.randomUUID().toString()
            u.requests++;u.minute.add(PoolMinute(id,at,estimate))
            ledger.lastKeys[cfg.id]=++ledger.selections
            return@locked PoolDecision(PoolLease(c,group,id),0,"")
        }
        PoolDecision(null,if(earliest==Long.MAX_VALUE)Long.MAX_VALUE else (earliest-at).coerceAtLeast(1),reasons.joinToString(" · ").ifBlank { "사용 가능한 API 키가 없습니다." })
    }
    fun complete(lease:PoolLease,response:JsonNode)=locked {ledger ->
        val u=usage(ledger,lease.group,now());val meta=response.path("usageMetadata")
        val actual=meta.path("promptTokenCount").takeIf {it.isNumber}?.asLong()
        if(actual!=null)u.minute.find {it.id==lease.reservation}?.input=actual
        u.inputTokens+=actual ?: 0;u.totalTokens+=meta.path("totalTokenCount").asLong()
    }
    fun rejected(lease:PoolLease,error:GeminiApiException)=locked {ledger ->
        val u=usage(ledger,lease.group,now())
        if(error.status==429) {
            if(error.daily)u.requests=maxOf(u.requests,lease.credential.config.rpd)
            u.cooldown=now()+error.retryMs.coerceIn(1000,3600000)
        } else if(error.status in listOf(401,403))ledger.disabledKeys[lease.credential.config.id]=now()+3600000
        else if(error.status in listOf(500,502,503,504))u.cooldown=now()+minOf(error.retryMs,5000).coerceAtLeast(1000)
    }
    fun status(configs:List<PoolKeyConfig>):String=locked {ledger ->
        configs.groupBy {it.project}.entries.joinToString("\n") {(project,keys) ->
            val cfg=keys.first();val u=usage(ledger,"$project/$model",now())
            "$project · 키 ${keys.count {it.enabled}}개 · RPM ${u.minute.size}/${cfg.rpm} · 입력 TPM ${u.minute.sumOf {it.input}}/${cfg.tpm} · RPD ${u.requests}/${cfg.rpd} · 오늘 사용 토큰 ${u.totalTokens}"
        }.ifBlank { "등록된 API 키가 없습니다." }
    }
}

class PooledGeminiModel(private val store:GeminiKeyPoolStore,private val model:String,private val tracker:GeminiQuotaTracker,
    private val notify:(String)->Unit={},private val factory:(String,String)->AgentModel={key,name->GeminiAgentModel(key,name)},
    private val now:()->Long={System.currentTimeMillis()},private val sleep:(Long)->Unit={Thread.sleep(it)},
    private val credentialFilter:()->List<PoolCredential> = {store.credentials()}):AgentModel {
    override fun generate(request:JsonNode):JsonNode {
        // Conservative UTF-8 byte estimate, reconciled to provider promptTokenCount after completion.
        val estimate=request.toString().toByteArray(Charsets.UTF_8).size.toLong()+256
        val deadline=now()+65000
        var attempts=0;var reportedWait=false;var lastFailure:GeminiApiException?=null
        while(attempts<4) {
            check(!Thread.currentThread().isInterrupted) { "작업이 중지되었습니다." }
            val credentials=credentialFilter()
            val decision=tracker.reserve(credentials,estimate)
            val lease=decision.lease
            if(lease==null) {
                if(decision.waitMs==Long.MAX_VALUE || now()+decision.waitMs>deadline)throw IllegalStateException("API 키 한도 대기: ${decision.reason}. /agent-keys에서 사용량을 확인하세요.")
                if(!reportedWait) {notify("${decision.reason} · 잠시 후 다시 선택합니다.");reportedWait=true}
                sleep(minOf(decision.waitMs,1000));continue
            }
            attempts++
            notify("API 요청 #$attempts · 키 ${lease.credential.config.id} · 프로젝트 ${lease.credential.config.project} · 요청 예약 완료")
            try {
                val response=factory(lease.credential.secret,model).generate(request)
                tracker.complete(lease,response);return response
            } catch(e:GeminiApiException) {
                lastFailure=e
                tracker.rejected(lease,e)
                if(e.status !in listOf(401,403,429,500,502,503,504))throw e
                if(e.status in listOf(500,502,503,504)) {
                    if(attempts<4) {
                        val backoff=(2000L shl (attempts-1))+java.util.concurrent.ThreadLocalRandom.current().nextLong(501)
                        val delay=if(e.retryAfterProvided)maxOf(backoff,e.retryMs) else backoff
                        if(delay>deadline-now())throw IllegalStateException("Gemini HTTP ${e.status}: 모델 $model 제공자 일시 오류입니다. 권장 재시도 시간이 작업의 대기 한도를 넘습니다. 나중에 다시 시도하세요.")
                        notify("모델 $model: HTTP ${e.status} · 제공자 일시 오류 · ${delay}ms 후 재시도 ($attempts/4). 키 변경만으로 모델 혼잡을 해결할 수는 없습니다.")
                        sleep(delay)
                    }
                } else notify("키 ${lease.credential.config.id}: HTTP ${e.status} · 사용 가능한 다음 키를 확인합니다.")
            }
        }
        if(lastFailure?.status in listOf(500,502,503,504))throw IllegalStateException("Gemini HTTP ${lastFailure!!.status}: 모델 $model 제공자 일시 오류로 4회 요청이 실패했습니다. API 키 오류로 판정한 것이 아닙니다. 잠시 후 재시도하거나 다른 모델을 사용하세요.")
        throw IllegalStateException("API 키 전환 4회 한도에 도달했습니다. /agent-keys에서 한도와 키 설정을 확인하세요.")
    }
}
