package local.servermanager

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

/** Plain-text rendering of permission data, separated from JDA so it can be unit tested. */
object PermissionText {
    private fun time(millis:Long)=SimpleDateFormat("MM-dd HH:mm").apply {timeZone=TimeZone.getTimeZone("Asia/Seoul")}.format(Date(millis))
    fun list(decls:List<PermissionDecl>,ceiling:PermissionCeiling,status:(PermissionDecl)->String):String {
        if(decls.isEmpty())return "선언된 권한이 없습니다."
        val body=decls.groupBy {it.module}.entries.joinToString("\n") {(module,items)->
            "**$module**\n"+items.joinToString("\n") {d->"- `${d.id}` ${d.label} · ${d.risk}"+(if(d.default==PermissionService.DENY)" · 기본 거부" else "")+" · ${status(d)}"}
        }
        return "위험도 상한: ${ceiling.maxRisk} (관리 컴퓨터의 로컬 설정에서만 변경)"+(if(ceiling.disabled.isEmpty())"" else "\n비활성화: "+ceiling.disabled.sorted().joinToString {"`$it`"})+"\n\n"+body
    }
    fun grants(rules:List<PermissionGrant>,now:Long):String {
        if(rules.isEmpty())return "이 서버에 저장된 권한 규칙이 없습니다."
        return rules.joinToString("\n") {r->"- ${if(r.effect=="DENY")"⛔ 거부" else "✅ 허용"} <@&${r.subject.id}> `${r.permission}`"+
            (r.expiresAt?.let {" · ${if(it<=now)"만료됨" else "~"+time(it)}"} ?: "")}
    }
    fun audit(entries:List<PermissionAuditEntry>):String =
        if(entries.isEmpty())"기록이 없습니다." else entries.joinToString("\n") {"- ${time(it.at)} ${it.actor} ${it.action}: ${it.detail}"}
}

/**
 * `/permission`: the Discord front end of [PermissionService]. Only roles configured as super admin in the management web
 * may use it, and it can never raise the local risk ceiling.
 */
class DiscordPermissionModule(private val service:PermissionService,private val superAdmin:(String,Set<String>)->Boolean,private val clock:()->Long = System::currentTimeMillis) : DiscordModule {
    override val id="permission"
    override val label="권한 관리"
    override val commands=setOf("permission")
    override fun register(jda:JDA,guildId:String) {
        val guild=jda.getGuildById(guildId) ?: return
        val permission=OptionData(OptionType.STRING,"permission","권한 ID 또는 모듈.* (예: agent.use, music.*)",true).setAutoComplete(true)
        val role=OptionData(OptionType.ROLE,"role","대상 역할",true)
        guild.upsertCommand(Commands.slash("permission","기능 권한 배분 (최고 관리자 전용)").addSubcommands(
            SubcommandData("list","선언된 권한과 내 상태 보기"),
            SubcommandData("rules","이 서버의 권한 규칙 보기"),
            SubcommandData("grant","역할에 권한 허용/거부 규칙 저장").addOptions(role,permission,
                OptionData(OptionType.STRING,"effect","기본 허용",false).addChoice("허용","ALLOW").addChoice("거부","DENY"),
                OptionData(OptionType.INTEGER,"minutes","유효 시간(분). 위험도 HIGH 허용은 필수(최대 1440)",false).setRequiredRange(1,PermissionService.MAX_TTL_MINUTES),
                OptionData(OptionType.BOOLEAN,"confirm","위험도 HIGH 허용 확인",false)),
            SubcommandData("revoke","역할의 권한 규칙 삭제").addOptions(role,permission),
            SubcommandData("audit","최근 변경 기록 보기").addOptions(OptionData(OptionType.INTEGER,"count","개수(기본 10)",false).setRequiredRange(1,50)),
            SubcommandData("reset","이 서버의 모든 권한 규칙 삭제"))).queue({}, {})
    }
    override fun onAutoComplete(event:CommandAutoCompleteInteractionEvent) {
        val query=event.focusedOption.value
        val choices=(service.declarations().map {it.id}+service.declarations().map {it.module+".*"}.distinct())
            .filter {it.contains(query,true)}.take(25).map {Command.Choice(it,it)}
        event.replyChoices(choices).queue({}, {})
    }
    override fun onCommand(event:SlashCommandInteractionEvent) {
        val guild=event.guild ?: run {event.reply("서버 안에서만 사용할 수 있습니다.").setEphemeral(true).queue({}, {});return}
        event.deferReply(true).queue({hook->
            guild.retrieveMemberById(event.user.id).useCache(false).queue({member->
                val roles=member.roles.map {it.id}.toSet()
                val admin=superAdmin(guild.id,roles)
                val text=try {run(event.subcommandName,guild.id,event.user.id,roles,admin) {name->event.getOption(name)}}
                    catch(e:IllegalArgumentException) {"실패: ${e.message}"} catch(e:IllegalStateException) {"실패: ${e.message}"}
                hook.editOriginal(text.take(1900)).setAllowedMentions(emptyList()).queue({}, {})
            },{hook.editOriginal("현재 서버 역할을 확인하지 못했습니다. 잠시 후 다시 시도하세요.").queue({}, {})})
        },{})
    }

    /** Pure command logic over a tiny option accessor; every state change goes through [PermissionService]. */
    internal fun run(sub:String?,guildId:String,user:String,roles:Set<String>,admin:Boolean,option:(String)->net.dv8tion.jda.api.interactions.commands.OptionMapping?):String {
        check(admin) {"최고 관리자 역할만 /permission을 사용할 수 있습니다."}
        val actor="discord:$user"
        return when(sub) {
            "list" -> PermissionText.list(service.declarations(),service.ceiling()) {d->
                service.check(guildId,roles,d.id,admin).let {if(it.allowed==true)"내 상태: 허용" else if(it.allowed==false)"내 상태: 거부" else "내 상태: 모듈 기본"}}
            "rules" -> PermissionText.grants(service.grants(guildId),clock())
            "grant" -> service.grant(guildId,actor,admin,option("role")!!.asRole.id,option("permission")!!.asString,option("effect")?.asString ?: "ALLOW",option("minutes")?.asLong,option("confirm")?.asBoolean ?: false)
            "revoke" -> service.revoke(guildId,actor,admin,option("role")!!.asRole.id,option("permission")!!.asString).let {if(it>0)"규칙 ${it}개를 삭제했습니다." else "삭제할 규칙이 없습니다."}
            "audit" -> PermissionText.audit(service.audit(guildId,option("count")?.asInt ?: 10))
            "reset" -> "규칙 ${service.reset(guildId,actor,admin)}개를 모두 삭제했습니다. 이후 각 모듈의 기본 정책만 적용됩니다."
            else -> error("알 수 없는 하위 명령입니다.")
        }
    }
}
