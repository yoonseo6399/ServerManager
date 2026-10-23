package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.*

enum class PermissionRisk {LOW,MEDIUM,HIGH}

/** A capability a module can ask the server manager to gate. Risk is fixed in code; only the local ceiling can allow more. */
data class PermissionDecl(val id:String,val label:String,val risk:PermissionRisk,val module:String,val default:String=PermissionService.LEGACY)

/** Who a rule applies to. Platform-neutral so a web user or another chat platform can be added without a data migration. */
data class PermissionSubject(val platform:String="discord",val kind:String="role",val id:String="")
data class PermissionGrant(val guildId:String="",val subject:PermissionSubject=PermissionSubject(),val permission:String="",val effect:String="ALLOW",
    val expiresAt:Long?=null,val by:String="",val at:Long=0)
/** Set only by the machine owner (local file or management web). Discord commands can never raise it. */
data class PermissionCeiling(val maxRisk:PermissionRisk=PermissionRisk.MEDIUM,val disabled:Set<String> = emptySet())
/** [allowed] is null when nothing is configured: the caller then keeps its own legacy check. */
data class PermissionDecision(val allowed:Boolean?,val reason:String)
data class PermissionAuditEntry(val at:Long=0,val guildId:String="",val actor:String="",val action:String="",val detail:String="")

private data class PermissionGrantFile(val grants:List<PermissionGrant> = emptyList())

/**
 * Policy store and decision point for module permissions. It decides; modules and the server manager enforce.
 * Order of evaluation: undeclared -> deny, above the local ceiling -> deny, super admin -> allow, explicit deny wins over
 * explicit allow, nothing configured -> default (LEGACY = ask the module's own check, DENY = refuse until granted).
 */
class PermissionService(private val directory:Path,private val clock:()->Long = System::currentTimeMillis) {
    companion object {
        const val LEGACY="LEGACY";const val DENY="DENY"
        const val MAX_GRANTS=500;const val MAX_AUDIT=1000;const val MAX_TTL_MINUTES=43200L;const val HIGH_TTL_MINUTES=1440L
        val ID=Regex("[a-z][a-z0-9_]{0,30}\\.[a-z][a-z0-9_]{0,30}")
        private val ROLE=Regex("\\d{5,25}")
    }
    private val mapper=jacksonObjectMapper()
    private val grantsFile=directory.resolve("permission-grants.json")
    private val ceilingFile=directory.resolve("permission-ceiling.json")
    private val auditFile=directory.resolve("permission-audit.jsonl")
    private val declared=LinkedHashMap<String,PermissionDecl>()
    private val loadErrors=linkedSetOf<String>()
    private inline fun <reified T> load(path:Path,default:()->T):T = try {
        // notExists is false for inaccessible paths: those must fail closed too.
        if(Files.notExists(path))default() else mapper.readValue<T>(path.readText())
    } catch(e:Exception) {loadErrors.add(path.fileName.toString());default()}
    private var grants:List<PermissionGrant> = load(grantsFile) {PermissionGrantFile()}.grants
    private var ceiling:PermissionCeiling = load(ceilingFile) {PermissionCeiling()}
    @Synchronized fun storageError():String? = if(loadErrors.isEmpty())null else
        "권한 설정을 읽을 수 없어 실행을 차단했습니다: ${loadErrors.joinToString()}. 정상 백업을 복원한 뒤 다시 시작하세요."
    private fun requireHealthy() {check(loadErrors.isEmpty()) {storageError()!!}}


    // ---- Declarations -------------------------------------------------------------------------------------------
    @Synchronized fun declare(decls:List<PermissionDecl>) {
        for(d in decls) {
            require(ID.matches(d.id) && d.id.substringBefore('.')==d.module) {"권한 ID는 '모듈.동작' 형식이고 모듈 이름과 같아야 합니다: ${d.id}"}
            require(d.default==LEGACY || d.default==DENY) {"기본 정책은 LEGACY 또는 DENY여야 합니다: ${d.id}"}
            require(d.label.isNotBlank() && d.label.length<=80) {"권한 설명은 1~80자여야 합니다: ${d.id}"}
            val old=declared[d.id]
            require(old==null || old==d) {"이미 다른 내용으로 선언된 권한입니다: ${d.id}"}
            declared[d.id]=d
        }
    }
    @Synchronized fun declarations():List<PermissionDecl> = declared.values.toList()
    @Synchronized fun declaration(id:String):PermissionDecl? = declared[id]

    // ---- Ceiling (machine owner only) -----------------------------------------------------------------------------
    @Synchronized fun ceiling():PermissionCeiling = ceiling
    /** Not reachable from Discord commands. Called from local configuration code or a management-web endpoint. */
    @Synchronized fun setCeiling(next:PermissionCeiling,actor:String="local") {
        requireHealthy()
        persist(ceilingFile,mapper.writeValueAsString(next));ceiling=next
        audit("*",actor,"ceiling","maxRisk=${next.maxRisk} disabled=${next.disabled.sorted().joinToString(",")}")
    }

    // ---- Decision -------------------------------------------------------------------------------------------------
    private fun matches(rule:String,permission:String)=rule==permission || (rule.endsWith(".*") && permission.startsWith(rule.removeSuffix("*")))
    @Synchronized fun check(guildId:String,roles:Set<String>,permission:String,superAdmin:Boolean=false):PermissionDecision {
        storageError()?.let {return PermissionDecision(false,it)}
        val decl=declared[permission] ?: return PermissionDecision(false,"선언되지 않은 권한입니다")
        if(decl.risk.ordinal>ceiling.maxRisk.ordinal)return PermissionDecision(false,"위험도 상한(${ceiling.maxRisk})을 넘는 권한입니다. 상한은 관리 컴퓨터의 로컬 설정에서만 올릴 수 있습니다")
        if(permission in ceiling.disabled)return PermissionDecision(false,"관리 컴퓨터의 로컬 설정에서 비활성화된 권한입니다")
        if(superAdmin)return PermissionDecision(true,"최고 관리자")
        val now=clock()
        val active=grants.filter {it.guildId==guildId && it.subject.platform=="discord" && it.subject.kind=="role" && it.subject.id in roles &&
            matches(it.permission,permission) && (it.expiresAt==null || it.expiresAt>now)}
        if(active.any {it.effect=="DENY"})return PermissionDecision(false,"거부 규칙이 적용됩니다")
        if(active.any {it.effect=="ALLOW"})return PermissionDecision(true,"허용 규칙이 적용됩니다")
        return if(decl.default==DENY)PermissionDecision(false,"허용 규칙이 없습니다(기본 거부)") else PermissionDecision(null,"설정된 규칙이 없습니다")
    }

    // ---- Changes (super admin only; HIGH needs confirmation and a short lifetime) -------------------------------------
    private fun requireAdmin(actorIsSuperAdmin:Boolean) {check(actorIsSuperAdmin) {"최고 관리자 역할만 권한 규칙을 바꿀 수 있습니다."}}
    @Synchronized fun grant(guildId:String,actor:String,actorIsSuperAdmin:Boolean,roleId:String,permission:String,effect:String="ALLOW",ttlMinutes:Long?=null,confirmHigh:Boolean=false):String {
        requireHealthy()
        requireAdmin(actorIsSuperAdmin)
        require(ROLE.matches(roleId)) {"역할 ID가 올바르지 않습니다."}
        require(effect=="ALLOW" || effect=="DENY") {"효과는 ALLOW 또는 DENY여야 합니다."}
        require(ttlMinutes==null || ttlMinutes in 1..MAX_TTL_MINUTES) {"유효 시간은 1~$MAX_TTL_MINUTES 분이어야 합니다."}
        val wildcard=permission.endsWith(".*")
        val targets=if(wildcard)declared.values.filter {matches(permission,it.id)} else listOfNotNull(declared[permission])
        require(targets.isNotEmpty()) {"선언되지 않은 권한입니다: $permission. /permission list로 확인하세요."}
        if(effect=="ALLOW") {
            val tooHigh=targets.filter {it.risk.ordinal>ceiling.maxRisk.ordinal}
            require(wildcard || tooHigh.isEmpty()) {"${permission}의 위험도(${targets.first().risk})가 로컬 상한(${ceiling.maxRisk})을 넘어 허용할 수 없습니다. 관리 컴퓨터의 로컬 설정에서 상한을 올려야 합니다."}
            if(targets.any {it.risk==PermissionRisk.HIGH}) {
                require(confirmHigh) {"위험도 HIGH 권한입니다. confirm:true로 의도를 확인하세요."}
                require(ttlMinutes!=null && ttlMinutes<=HIGH_TTL_MINUTES) {"위험도 HIGH 허용은 $HIGH_TTL_MINUTES 분(24시간) 이하의 유효 시간이 필요합니다."}
            }
        }
        val subject=PermissionSubject("discord","role",roleId)
        val rule=PermissionGrant(guildId,subject,permission,effect,ttlMinutes?.let {clock()+it*60_000},actor,clock())
        val next=grants.filterNot {it.guildId==guildId && it.subject==subject && it.permission==permission}+rule
        require(next.size<=MAX_GRANTS) {"권한 규칙이 너무 많습니다. 오래된 규칙을 정리하세요."}
        persist(grantsFile,mapper.writeValueAsString(PermissionGrantFile(next)));grants=next
        audit(guildId,actor,"grant","$effect role=$roleId $permission ttl=${ttlMinutes ?: "-"}")
        return "$effect 규칙을 저장했습니다: <@&$roleId> · $permission"+(ttlMinutes?.let {" · $it 분 후 만료"} ?: "")
    }
    @Synchronized fun revoke(guildId:String,actor:String,actorIsSuperAdmin:Boolean,roleId:String,permission:String):Int {
        requireHealthy()
        requireAdmin(actorIsSuperAdmin)
        val next=grants.filterNot {it.guildId==guildId && it.subject.id==roleId && it.permission==permission}
        val removed=grants.size-next.size
        if(removed>0){persist(grantsFile,mapper.writeValueAsString(PermissionGrantFile(next)));grants=next}
        audit(guildId,actor,"revoke","role=$roleId $permission removed=$removed");return removed
    }
    /** Kill switch: removes every rule of one guild. */
    @Synchronized fun reset(guildId:String,actor:String,actorIsSuperAdmin:Boolean):Int {
        requireHealthy()
        requireAdmin(actorIsSuperAdmin)
        val next=grants.filterNot {it.guildId==guildId};val removed=grants.size-next.size
        if(removed>0){persist(grantsFile,mapper.writeValueAsString(PermissionGrantFile(next)));grants=next}
        audit(guildId,actor,"reset","removed=$removed");return removed
    }
    @Synchronized fun grants(guildId:String):List<PermissionGrant> = grants.filter {it.guildId==guildId}

    // ---- Audit ----------------------------------------------------------------------------------------------------
    @Synchronized private fun audit(guildId:String,actor:String,action:String,detail:String) {
        directory.createDirectories()
        val line=mapper.writeValueAsString(PermissionAuditEntry(clock(),guildId,actor.take(60),action,detail.take(300)))
        Files.writeString(auditFile,line+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
        val lines=auditFile.readLines()
        if(lines.size>MAX_AUDIT+200)persist(auditFile,lines.takeLast(MAX_AUDIT).joinToString("\n",postfix="\n"))
    }
    @Synchronized fun audit(guildId:String,limit:Int=20):List<PermissionAuditEntry> {
        if(!auditFile.exists())return emptyList()
        return auditFile.readLines().mapNotNull {runCatching {mapper.readValue<PermissionAuditEntry>(it)}.getOrNull()}
            .filter {it.guildId==guildId || it.guildId=="*"}.takeLast(limit.coerceIn(1,100)).reversed()
    }

    private fun persist(path:Path,text:String) {
        directory.createDirectories()
        val temp=Files.createTempFile(directory,path.fileName.toString(),".tmp")
        temp.writeText(text)
        runCatching {Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"))}
        Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING)
    }
}
