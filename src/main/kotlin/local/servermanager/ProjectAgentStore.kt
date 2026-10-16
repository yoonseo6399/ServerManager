package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.*

data class ProjectAgentPreferences(val model:String?=null,val think:Boolean=true,val explicitPermission:Boolean=true,val revision:Long=0)
data class ProjectAgentProject(val name:String,val path:String)
data class ProjectAgentMessage(val role:String,val content:String)
data class ProjectAgentChannel(val guild:String,val channel:String,val user:String,val project:String?,val home:String,val history:List<ProjectAgentMessage> = emptyList())
data class ProjectAgentData(val preferences:Map<String,ProjectAgentPreferences> = emptyMap(),val projects:Map<String,List<ProjectAgentProject>> = emptyMap(),val channels:Map<String,ProjectAgentChannel> = emptyMap())

/** Only human Discord commands change settings/projects. Model text is never configuration. */
class ProjectAgentStore(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("project-agent.json")
    private var data=if(file.exists())mapper.readValue<ProjectAgentData>(file.readText()) else ProjectAgentData()
    private fun key(guild:String,user:String)="$guild:$user"
    private fun persist(next:ProjectAgentData) {GeminiKeyPoolStore(directory).writePrivate(file,mapper.writeValueAsString(next));data=next}
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
    @Synchronized fun channel(id:String)=data.channels[id]
    @Synchronized fun addChannel(value:ProjectAgentChannel) {
        require(data.channels.size<500);persist(data.copy(channels=data.channels+(value.channel to value)))
    }
    @Synchronized fun history(channel:String,messages:List<ProjectAgentMessage>) {
        val previous=data.channels[channel] ?: error("등록된 에이전트 채널이 아닙니다.")
        persist(data.copy(channels=data.channels+(channel to previous.copy(history=ProjectAgentContext.compact(messages)))))
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
