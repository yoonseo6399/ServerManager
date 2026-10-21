package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.*

data class ProjectAgentPreferences(val model:String?=null,val think:Boolean=true,val explicitPermission:Boolean=true,val revision:Long=0)
data class ProjectAgentProject(val name:String,val path:String)
data class ProjectAgentMessage(val role:String,val content:String,@get:com.fasterxml.jackson.annotation.JsonIgnore val native:String?=null)
data class ProjectAgentChannel(val guild:String,val channel:String,val user:String,val project:String?,val home:String,val history:List<ProjectAgentMessage> = emptyList(),val contextLimit:Int=4500,val contextEpoch:Long=0,val executionWindow:Int=8192,val outputTokens:Int=2048)
data class ProjectAgentData(val preferences:Map<String,ProjectAgentPreferences> = emptyMap(),val projects:Map<String,List<ProjectAgentProject>> = emptyMap(),val channels:Map<String,ProjectAgentChannel> = emptyMap(),val gradleConsent:Map<String,Set<String>> = emptyMap())

private data class ProjectAgentProfiles(val windows:Map<String,Int> = emptyMap(),val outputs:Map<String,Int> = emptyMap(),val limits:Map<String,Int> = emptyMap(),val epochs:Map<String,Long> = emptyMap())

private data class ProjectAgentMetadata(val limits:Map<String,Int> = emptyMap(),val epochs:Map<String,Long> = emptyMap(),val gradleConsent:Map<String,Set<String>> = emptyMap())

/** Only human Discord commands change settings/projects. Model text is never configuration. */
class ProjectAgentStore(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("project-agent.json")
    private val metadataFile=directory.resolve("project-agent-context.json")
    private val profilesFile=directory.resolve("project-agent-profiles.json")
    private val profiles=if(profilesFile.exists())mapper.readValue<ProjectAgentProfiles>(profilesFile.readText())else ProjectAgentProfiles()
    private val metadata=if(metadataFile.exists())mapper.readValue<ProjectAgentMetadata>(metadataFile.readText())else ProjectAgentMetadata()
    private var data=(if(file.exists())mapper.readValue<ProjectAgentData>(file.readText()) else ProjectAgentData()).let {loaded->loaded.copy(gradleConsent=metadata.gradleConsent,channels=loaded.channels.mapValues {(id,channel)->channel.copy(contextLimit=(if(profiles.epochs[id]==metadata.epochs[id])profiles.limits[id]else null) ?: metadata.limits[id] ?: 2867,contextEpoch=metadata.epochs[id] ?: 0,executionWindow=profiles.windows[id] ?: 8192,outputTokens=profiles.outputs[id] ?: 4096)})}
    internal val profileDirectory:Path get()=directory
    @Synchronized internal fun piState(channel:ProjectAgentChannel):Path {
        val key=java.security.MessageDigest.getInstance("SHA-256").digest("${channel.guild}:${channel.channel}:${channel.user}:${channel.home}".toByteArray()).joinToString(""){"%02x".format(it)}
        val root=directory.resolve("pi-sessions").resolve(key);root.createDirectories()
        val generation=root.resolve("generation");if(!generation.exists())generation.writeText("initial")
        val value=generation.readText();require(value.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        return root.resolve(value).also {it.createDirectories()}
    }
    private fun key(guild:String,user:String)="$guild:$user"
    private val plansFile=directory.resolve("project-agent-plans.json")
    private var plans:Map<String,ProjectAgentTaskPlan> = if(plansFile.exists())mapper.readValue<Map<String,ProjectAgentTaskPlan>>(plansFile.readText()).filterKeys {profiles.epochs[it]==metadata.epochs[it]}else emptyMap()
    @Synchronized fun taskPlan(channel:String)=plans[channel]
    @Synchronized fun savePlan(channel:String,value:ProjectAgentTaskPlan,epoch:Long) {
        check(data.channels[channel]?.contextEpoch==epoch) {"문맥 설정이 바뀌어 계획 저장을 중단했습니다."}
        require(value.request.length<=12000 && value.steps.size<=12 && value.steps.all {it.length<=300} && value.completed.size<=100)
        val next=(plans+(channel to value)).entries.sortedByDescending {it.value.updated}.take(500).associate {it.toPair()}
        GeminiKeyPoolStore(directory).writePrivate(plansFile,mapper.writeValueAsString(next));plans=next
    }
    private val notesFile=directory.resolve("project-agent-notes.json")
    private var notes:Map<String,ProjectAgentNotes> = runCatching {if(notesFile.exists())mapper.readValue<Map<String,ProjectAgentNotes>>(notesFile.readText()) else emptyMap()}.getOrDefault(emptyMap())
    /** Short per-channel carry-over (last request/result, touched files, unfinished items). Separate file so older releases never see it. */
    @Synchronized fun notes(channel:String):ProjectAgentNotes?=notes[channel]
    @Synchronized fun saveNotes(channel:String,value:ProjectAgentNotes) {
        val next=(notes+(channel to value)).entries.sortedByDescending {it.value.updated}.take(500).associate {it.key to it.value}
        GeminiKeyPoolStore(directory).writePrivate(notesFile,mapper.writeValueAsString(next));notes=next
    }
    @Synchronized fun clearNotes(channel:String) {
        if(channel in plans){val next=plans-channel;GeminiKeyPoolStore(directory).writePrivate(plansFile,mapper.writeValueAsString(next));plans=next}
        if(channel !in notes)return
        val next=notes-channel;GeminiKeyPoolStore(directory).writePrivate(notesFile,mapper.writeValueAsString(next));notes=next
    }
    private fun persist(next:ProjectAgentData) {
        // Keep the legacy file readable by older beta releases after an application rollback.
        val primary=mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(next)
        primary.remove("gradleConsent");primary.path("channels").elements().forEach {node->(node as com.fasterxml.jackson.databind.node.ObjectNode).remove(listOf("contextLimit","contextEpoch","executionWindow","outputTokens"))}
        val writer=GeminiKeyPoolStore(directory)
        writer.writePrivate(file,mapper.writeValueAsString(primary))
        writer.writePrivate(metadataFile,mapper.writeValueAsString(ProjectAgentMetadata(next.channels.mapValues {it.value.contextLimit.coerceAtMost(3584)},next.channels.mapValues {it.value.contextEpoch},next.gradleConsent)))
        writer.writePrivate(profilesFile,mapper.writeValueAsString(ProjectAgentProfiles(next.channels.mapValues {it.value.executionWindow},next.channels.mapValues {it.value.outputTokens},next.channels.mapValues {it.value.contextLimit},next.channels.mapValues {it.value.contextEpoch})))
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
    @Synchronized fun context(channel:String,user:String,reset:Boolean=false,limit:Int?=null,window:Int?=null,output:Int?=null):ProjectAgentChannel {
        val previous=data.channels[channel] ?: error("에이전트 채널이 아닙니다.");check(previous.user==user)
        val budget=LocalContextBudget(window ?: previous.executionWindow,output ?: previous.outputTokens)
        val input=budget.window-budget.output-256
        require(limit==null || limit in 1024..input-16) {"압축 기준은 1024~${input-16} 토큰입니다. 실행 창·출력 예약에 맞춰 설정하세요."}
        val next=previous.copy(history=if(reset)emptyList() else previous.history,contextLimit=limit ?: (if(window!=null && window!=previous.executionWindow)(input*0.8).toInt()else previous.contextLimit.coerceAtMost(input-16)),contextEpoch=previous.contextEpoch+1,executionWindow=budget.window,outputTokens=budget.output)
        if(reset){val oldState=piState(previous);oldState.parent.resolve("generation").writeText(java.util.UUID.randomUUID().toString())}
        persist(data.copy(channels=data.channels+(channel to next)))
        if(reset)runCatching {clearNotes(channel)}
        return next
    }
}

object ProjectAgentContext {
    /** Conservative budget for an 8K context with room left for Thinking and the final JSON. */
    /** Input budget for default callers; channel profiles pass their validated budget explicitly. */
    val budget:Int get()=LocalContextBudget.DEFAULT.input
    fun messageTokens(message:ProjectAgentMessage)=estimatedTokens(message.native ?: message.content)
    fun estimatedTokens(text:String):Int=(text.count {it.code<128}+2)/3+text.count {it.code>=128}*2
    fun forModel(system:ProjectAgentMessage,history:List<ProjectAgentMessage>,requester:ProjectAgentMessage?=null,inputBudget:Int=budget):List<ProjectAgentMessage> {
        var remaining=inputBudget-estimatedTokens(system.content)-estimatedTokens(requester?.content.orEmpty());require(remaining>100) {"고정 요청·계획이 입력 예산을 초과했습니다. 범위를 줄이거나 16K 프로필을 선택하세요."}
        val selected=mutableListOf<ProjectAgentMessage>()
        for(message in history.asReversed()) {
            if(remaining<100 || selected.size>=16)break
            var text=message.content
            if(messageTokens(message)>remaining) {
                var chars=text.length
                while(chars>0 && estimatedTokens(text.take(chars))>remaining-90)chars=(chars*0.8).toInt()
                text=text.take(chars)+"\n[PARTIAL CONTEXT: 일부 생략. 필요한 파일/출력 범위를 도구로 다시 읽으세요. 전체를 확인했다고 주장하지 마세요.]"
            }
            val bounded=message.copy(content=text,native=if(text==message.content && messageTokens(message)<=remaining)message.native else null)
            selected.add(bounded);remaining-=messageTokens(bounded)
        }
        val ordered=selected.asReversed().toMutableList()
        // Never replay an orphan native call/result when budgeting or compression removed its partner.
        for(i in ordered.indices) {
            val message=ordered[i];val name=ProjectAgentNativeTools.callName(message.native)
            if(name!=null && (i+1>=ordered.size || !ordered[i+1].native.orEmpty().contains("\"role\":\"tool\"")))ordered[i]=message.copy(native=null)
            if(message.native.orEmpty().contains("\"role\":\"tool\"") && (i==0 || ProjectAgentNativeTools.callName(ordered[i-1].native)==null))ordered[i]=message.copy(native=null)
        }
        return listOf(system)+listOfNotNull(requester)+ordered
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
