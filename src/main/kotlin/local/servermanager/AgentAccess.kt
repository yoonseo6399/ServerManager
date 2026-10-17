package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.*

/** One Discord role that may call the local model. [tier] READ = read-only tools, EDIT = read plus approved partial edits. */
data class AgentRolePolicy(val guildId:String,val roleId:String,val models:Set<String> = setOf("14b"),val tier:String="READ",val maxTurns:Int=AgentAccess.DEFAULT_TURNS,val projects:Set<String> = emptySet())
data class AgentSharedProject(val guildId:String,val name:String,val path:String)
data class AgentAccessData(val roles:List<AgentRolePolicy> = emptyList(),val sharedProjects:List<AgentSharedProject> = emptyList(),
    val turnCap:Int=AgentAccess.MAX_TURNS,val defaultTurns:Int=AgentAccess.DEFAULT_TURNS,val userTurns:Map<String,Int> = emptyMap())
/** What one Discord member may do right now. [actor] is the linked web owner account and exists only for OWNER. */
data class AgentGrant(val kind:String,val models:Set<String>,val tier:String,val maxTurns:Int,val projects:Set<String>,val actor:String?=null) {
    val owner get()=tier==AgentAccess.OWNER
    fun allows(model:String)=model in models
}

/** Pure policy: given the live Discord roles it decides the grant. Model output never reaches this. */
object AgentAccess {
    const val OWNER="OWNER";const val EDIT="EDIT";const val READ="READ"
    const val DEFAULT_TURNS=30;const val MAX_TURNS=100
    private val rank=mapOf(READ to 1,EDIT to 2,OWNER to 3)
    fun tierValid(tier:String)=tier==READ || tier==EDIT
    fun resolve(data:AgentAccessData,guild:String,roles:Set<String>,superAdmin:Boolean,actor:String?=null):AgentGrant? {
        if(superAdmin)return AgentGrant("OWNER",LocalLlm.SIZES.toSet(),OWNER,data.turnCap,data.sharedProjects.filter {it.guildId==guild}.map {it.name}.toSet(),actor)
        val matches=data.roles.filter {it.guildId==guild && it.roleId in roles}
        if(matches.isEmpty())return null
        return AgentGrant("FRIEND",matches.flatMap {it.models}.toSet(),matches.maxBy {rank.getValue(it.tier)}.tier,minOf(matches.maxOf {it.maxTurns},data.turnCap),matches.flatMap {it.projects}.toSet())
    }
    /** Request override, else the member's own setting, else the server default; never above the grant. */
    fun turns(data:AgentAccessData,grant:AgentGrant,guild:String,user:String,requested:Int?):Int =
        (requested ?: data.userTurns["$guild:$user"] ?: data.defaultTurns).coerceIn(1,maxOf(1,minOf(grant.maxTurns,data.turnCap)))
}

/** Own file on purpose: older betas never read it, so a rollback cannot choke on these fields. */
class AgentAccessStore(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("agent-access.json")
    @Volatile private var data=if(file.exists())runCatching {mapper.readValue<AgentAccessData>(file.readText())}.getOrDefault(AgentAccessData()) else AgentAccessData()
    fun view():AgentAccessData=data
    private fun persist(next:AgentAccessData) {GeminiKeyPoolStore(directory).writePrivate(file,mapper.writeValueAsString(next));data=next}
    private fun snowflake(id:String)=id.matches(Regex("[1-9][0-9]{16,19}"))
    @Synchronized fun setRole(policy:AgentRolePolicy):AgentRolePolicy {
        require(snowflake(policy.guildId) && snowflake(policy.roleId) && policy.guildId!=policy.roleId) {"Discord 서버·역할 ID를 확인하세요. @everyone은 허용하지 않습니다."}
        require(policy.models.isNotEmpty() && policy.models.all {it in LocalLlm.SIZES}) {"모델은 14b, 30b 중에서 고르세요."}
        require(AgentAccess.tierValid(policy.tier)) {"권한 단계는 READ 또는 EDIT입니다."}
        require(policy.maxTurns in 1..data.turnCap) {"턴 수는 1~${data.turnCap}입니다."}
        require(policy.projects.size<=20 && policy.projects.all {name->data.sharedProjects.any {it.guildId==policy.guildId && it.name==name}}) {"등록된 공유 프로젝트만 지정할 수 있습니다."}
        require(data.roles.count {it.guildId==policy.guildId && it.roleId!=policy.roleId}<50) {"친구 역할은 서버당 50개까지입니다."}
        persist(data.copy(roles=data.roles.filter {!(it.guildId==policy.guildId && it.roleId==policy.roleId)}+policy));return policy
    }
    @Synchronized fun removeRole(guild:String,role:String):Boolean {
        val next=data.roles.filter {!(it.guildId==guild && it.roleId==role)};if(next.size==data.roles.size)return false
        persist(data.copy(roles=next));return true
    }
    @Synchronized fun addProject(guild:String,name:String,path:Path):AgentSharedProject {
        require(name.matches(Regex("[\\p{L}\\p{N}_-]{1,60}"))) {"프로젝트 이름은 문자·숫자·밑줄·하이픈 1~60자입니다."}
        require(path.isDirectory()) {"존재하는 폴더를 지정하세요."}
        val project=AgentSharedProject(guild,name,path.toRealPath().toString())
        require(data.sharedProjects.count {it.guildId==guild && it.name!=name}<50)
        persist(data.copy(sharedProjects=data.sharedProjects.filter {!(it.guildId==guild && it.name==name)}+project));return project
    }
    /** Removing a project also removes it from every role, so a deleted grant cannot linger. */
    @Synchronized fun removeProject(guild:String,name:String):Boolean {
        if(data.sharedProjects.none {it.guildId==guild && it.name==name})return false
        persist(data.copy(sharedProjects=data.sharedProjects.filter {!(it.guildId==guild && it.name==name)},
            roles=data.roles.map {if(it.guildId==guild)it.copy(projects=it.projects-name) else it}));return true
    }
    fun project(guild:String,name:String)=data.sharedProjects.find {it.guildId==guild && it.name==name}
    /** Lowering the cap also lowers every stored limit that exceeded it. */
    @Synchronized fun setLimits(cap:Int?,default:Int?):AgentAccessData {
        val newCap=cap ?: data.turnCap;require(newCap in 1..AgentAccess.MAX_TURNS) {"턴 상한은 1~${AgentAccess.MAX_TURNS}입니다."}
        val newDefault=(default ?: data.defaultTurns).coerceAtMost(newCap);require(newDefault>=1)
        persist(data.copy(turnCap=newCap,defaultTurns=newDefault,roles=data.roles.map {it.copy(maxTurns=minOf(it.maxTurns,newCap))},userTurns=data.userTurns.mapValues {minOf(it.value,newCap)}));return data
    }
    @Synchronized fun setUserTurns(guild:String,user:String,turns:Int):Int {
        require(turns in 1..data.turnCap) {"턴 수는 1~${data.turnCap}입니다."}
        persist(data.copy(userTurns=data.userTurns+("$guild:$user" to turns)));return turns
    }
}
