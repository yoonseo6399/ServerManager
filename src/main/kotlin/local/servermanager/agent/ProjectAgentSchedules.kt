package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.io.path.*

data class ProjectAgentSchedule(val id:String,val guild:String,val channel:String,val user:String,val epoch:Long,val revision:Long,val at:Instant,val title:String,val prompt:String,val state:String="PENDING",val detail:String="",val updated:Instant=Instant.now())

/** Durable one-shot requests, not a way to pre-approve future tools. Never replay a claimed task after restart. */
internal class ProjectAgentSchedules(private val directory:Path,private val clock:()->Instant={Instant.now()}) {
    private val mapper=jacksonObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val file=directory.resolve("project-agent-schedules.json")
    private var items:List<ProjectAgentSchedule> = if(file.exists())mapper.readValue(file.readText())else emptyList()
    init {if(items.any {it.state=="RUNNING"})save(items.map {if(it.state=="RUNNING")it.copy(state="INTERRUPTED",detail="앱 재시작: 중복 실행 방지를 위해 자동 재실행하지 않습니다.",updated=clock())else it})}
    private fun save(next:List<ProjectAgentSchedule>) {GeminiKeyPoolStore(directory).writePrivate(file,mapper.writeValueAsString(next));items=next}
    @Synchronized fun list(guild:String,user:String)=items.filter {it.guild==guild && it.user==user}.sortedByDescending {it.at}.take(100)
    @Synchronized fun create(session:ProjectAgentChannel,revision:Long,at:String,title:String,prompt:String):ProjectAgentSchedule {
        val whenAt=OffsetDateTime.parse(at).toInstant();require(whenAt.isAfter(clock()) && whenAt.isBefore(clock().plusSeconds(366L*86400))) {"예약 시각은 UTC 오프셋을 포함한 향후 1년 이내 시각이어야 합니다."}
        require(title.isNotBlank() && title.length<=100 && prompt.isNotBlank() && prompt.length<=6000)
        check(items.count {it.guild==session.guild && it.user==session.user && it.state=="PENDING"}<20) {"대기 예약은 사용자당 최대 20개입니다."}
        val value=ProjectAgentSchedule(UUID.randomUUID().toString(),session.guild,session.channel,session.user,session.contextEpoch,revision,whenAt,title,prompt,updated=clock())
        save((items.filter {it.state in setOf("PENDING","RUNNING")}+items.filter {it.state !in setOf("PENDING","RUNNING")}.takeLast(200)+value));return value
    }
    @Synchronized fun cancel(guild:String,user:String,id:String):ProjectAgentSchedule {
        val value=items.find {it.id==id && it.guild==guild && it.user==user} ?: error("본인의 예약을 찾지 못했습니다.")
        check(value.state=="PENDING") {"대기 중인 예약만 취소할 수 있습니다. 실행 중이면 /agent stop을 사용하세요."}
        return value.copy(state="CANCELLED",updated=clock()).also {next->save(items.map {if(it.id==id)next else it})}
    }
    @Synchronized fun due():List<ProjectAgentSchedule> = items.filter {it.state=="PENDING" && !it.at.isAfter(clock())}
    @Synchronized fun claim(id:String):ProjectAgentSchedule? {
        val old=items.find {it.id==id && it.state=="PENDING" && !it.at.isAfter(clock())} ?: return null
        if(old.at.isBefore(clock().minusSeconds(900))){finish(id,"MISSED","15분 이상 지연되어 실행하지 않았습니다.");return null}
        return old.copy(state="RUNNING",updated=clock()).also {next->save(items.map {if(it.id==id)next else it})}
    }
    @Synchronized fun finish(id:String,state:String,detail:String="") {require(state in setOf("DONE","FAILED","CANCELLED","MISSED"));save(items.map {if(it.id==id)it.copy(state=state,detail=detail.take(500),updated=clock())else it})}
}
