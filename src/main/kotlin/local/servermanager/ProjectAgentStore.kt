package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.*

data class ProjectAgentPreferences(val model:String?=null,val think:Boolean=true,val explicitPermission:Boolean=true,val revision:Long=0)
data class ProjectAgentProject(val name:String,val path:String)
data class ProjectAgentMessage(val role:String,val content:String)
data class ProjectAgentChannel(val guild:String,val channel:String,val user:String,val project:String?,val home:String,val history:List<ProjectAgentMessage> = emptyList(),val contextLimit:Int=2867,val contextEpoch:Long=0)
data class ProjectAgentData(val preferences:Map<String,ProjectAgentPreferences> = emptyMap(),val projects:Map<String,List<ProjectAgentProject>> = emptyMap(),val channels:Map<String,ProjectAgentChannel> = emptyMap(),val gradleConsent:Map<String,Set<String>> = emptyMap())

private data class ProjectAgentMetadata(val limits:Map<String,Int> = emptyMap(),val epochs:Map<String,Long> = emptyMap(),val gradleConsent:Map<String,Set<String>> = emptyMap())

/** Only human Discord commands change settings/projects. Model text is never configuration. */
class ProjectAgentStore(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("project-agent.json")
    private val metadataFile=directory.resolve("project-agent-context.json")
    private val metadata=if(metadataFile.exists())mapper.readValue<ProjectAgentMetadata>(metadataFile.readText())else ProjectAgentMetadata()
    private var data=(if(file.exists())mapper.readValue<ProjectAgentData>(file.readText()) else ProjectAgentData()).let {loaded->loaded.copy(gradleConsent=metadata.gradleConsent,channels=loaded.channels.mapValues {(id,channel)->channel.copy(contextLimit=metadata.limits[id] ?: 2867,contextEpoch=metadata.epochs[id] ?: 0)})}
    private fun key(guild:String,user:String)="$guild:$user"
    private fun persist(next:ProjectAgentData) {
        // Keep the legacy file readable by older beta releases after an application rollback.
        val primary=mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(next)
        primary.remove("gradleConsent");primary.path("channels").elements().forEach {node->(node as com.fasterxml.jackson.databind.node.ObjectNode).remove(listOf("contextLimit","contextEpoch"))}
        val writer=GeminiKeyPoolStore(directory)
        writer.writePrivate(file,mapper.writeValueAsString(primary))
        writer.writePrivate(metadataFile,mapper.writeValueAsString(ProjectAgentMetadata(next.channels.mapValues {it.value.contextLimit},next.channels.mapValues {it.value.contextEpoch},next.gradleConsent)))
        data=next
    }
    @Synchronized fun preferences(guild:String,user:String)=data.preferences[key(guild,user)] ?: ProjectAgentPreferences()
    @Synchronized fun settings(guild:String,user:String,model:String?,think:Boolean?,explicit:Boolean?):ProjectAgentPreferences {
        require(model==null || model in LocalLlm.SIZES)
        val old=preferences(guild,user);val next=old.copy(model=model ?: old.model,think=think ?: old.think,explicitPermission=explicit ?: old.explicitPermission,revision=old.revision+1)
        persist(data.copy(preferences=data.preferences+(key(guild,user) to next)));return next
    }
    @Synchronized fun projects(guild:String,user:String)=data.projects[key(guild,user)].orEmpty()
    @Synchronized fun register(guild:String,user:String,name:String,path:Path):ProjectAgentProject {
        require(name.matches(Regex("[\\p{L}\\p{N}_-]{1,60}"))) {"프로젝트 이름은 문자·숫자·밑줄·하이픈 1~60자로 입력하세요."}
        require(path.isDirectory()) {"존재하는 프로젝트 폴더를 선택하세요."}
        val project=ProjectAgentProject(name,path.toRealPath().toString());val previous=projects(guild,user)
        require(previous.size<100 || previous.any {it.name==name})
        persist(data.copy(projects=data.projects+(key(guild,user) to (previous.filter {it.name!=name}+project))));return project
    }
    @Synchronized fun gradleTrusted(guild:String,user:String,fingerprint:String)=fingerprint in data.gradleConsent[key(guild,user)].orEmpty()
    @Synchronized fun trustGradle(guild:String,user:String,fingerprint:String){persist(data.copy(gradleConsent=data.gradleConsent+(key(guild,user) to (data.gradleConsent[key(guild,user)].orEmpty().toList().takeLast(99).toSet()+fingerprint))))}
    @Synchronized fun channel(id:String)=data.channels[id]
    @Synchronized fun channelsOf(guild:String,user:String)=data.channels.values.count {it.guild==guild && it.user==user}
    @Synchronized fun addChannel(value:ProjectAgentChannel) {
        require(data.channels.size<500);persist(data.copy(channels=data.channels+(value.channel to value)))
    }
    @Synchronized fun history(channel:String,messages:List<ProjectAgentMessage>,epoch:Long?=null) {
        val previous=data.channels[channel] ?: error("등록된 에이전트 채널이 아닙니다.")
        check(epoch==null || previous.contextEpoch==epoch) {"문맥 설정이 변경되어 이전 작업을 중단했습니다."}
        require(messages.size<=256 && messages.sumOf {it.content.length}<=262144) {"문맥 저장 한도입니다. 압축 또는 초기화하세요."}
        persist(data.copy(channels=data.channels+(channel to previous.copy(history=messages.toList()))))
    }
    @Synchronized fun context(channel:String,user:String,reset:Boolean=false,limit:Int?=null):ProjectAgentChannel {
        val previous=data.channels[channel] ?: error("에이전트 채널이 아닙니다.");check(previous.user==user)
        require(limit==null || limit in 1024..3584) {"압축 기준은 1024~3584 토큰입니다. 실행 창 8192 중 출력·안전 여유를 제외한 범위입니다."}
        val next=previous.copy(history=if(reset)emptyList() else previous.history,contextLimit=limit ?: previous.contextLimit,contextEpoch=previous.contextEpoch+1)
        persist(data.copy(channels=data.channels+(channel to next)));return next
    }
}

object ProjectAgentContext {
    /** Conservative budget for an 8K context with room left for Thinking and the final JSON. */
    fun estimatedTokens(text:String):Int=(text.count {it.code<128}+2)/3+text.count {it.code>=128}*2
    fun forModel(system:ProjectAgentMessage,history:List<ProjectAgentMessage>,requester:ProjectAgentMessage?=null):List<ProjectAgentMessage> {
        var remaining=3600-estimatedTokens(system.content)-estimatedTokens(requester?.content.orEmpty());require(remaining>500)
        val selected=mutableListOf<ProjectAgentMessage>()
        for(message in history.asReversed()) {
            if(remaining<100 || selected.size>=16)break
            var text=message.content
            if(estimatedTokens(text)>remaining) {
                var chars=text.length
                while(chars>0 && estimatedTokens(text.take(chars))>remaining-30)chars=(chars*0.8).toInt()
                text=text.take(chars)+"\n[이전 문맥 일부 생략]"
            }
            selected.add(message.copy(content=text));remaining-=estimatedTokens(text)
        }
        return listOf(system)+listOfNotNull(requester)+selected.asReversed()
    }
    /** Keep whole recent exchanges. Old output is deliberately not promoted into a system instruction. */
    fun compact(messages:List<ProjectAgentMessage>):List<ProjectAgentMessage> {
        val chosen=mutableListOf<ProjectAgentMessage>();var size=0
        for(message in messages.asReversed()) {
            val bounded=message.copy(content=message.content.take(6000))
            if(chosen.size>=16 || size+bounded.content.length>16000)break
            chosen.add(bounded);size+=bounded.content.length
        }
        return chosen.asReversed()
    }
    fun summary(output:String,limit:Int=2400):String = if(output.length<=limit)output else
        output.take(limit/2)+"\n[출력 일부 생략: 전체 ${output.length}자. 전체 기록은 도구 스레드 첨부 파일 참조]\n"+output.takeLast(limit/2)
}
