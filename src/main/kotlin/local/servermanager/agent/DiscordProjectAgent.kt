package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.Category
import net.dv8tion.jda.api.requests.restaction.ChannelAction
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.utils.FileUpload
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

/** One private channel is one bounded, persistent conversation. Only its linked owner may execute/approve. */
class DiscordProjectAgent(private val directory:Path,private val identity:AssistantIdentity,private val config:()->DiscordConfig,
    private val current:()->JDA?,private val roots:(String)->List<AssistantRoot> = {emptyList()}) :AutoCloseable {
    private val store=ProjectAgentStore(directory)
    private val qwen=QwenSettings(directory)
    private val access=AgentAccessStore(directory)
    private val audience=ProjectAgentRouting(directory)
    private val worker=Executors.newVirtualThreadPerTaskExecutor()
    private val routing=ConcurrentHashMap.newKeySet<String>()
    private val seededChannels=ConcurrentHashMap.newKeySet<String>()
    private val jobs=ProjectAgentJobs()
    private data class MemoryApproval(val channel:String,val user:String,val epoch:Long,val revision:Long,val until:java.time.Instant=java.time.Instant.now().plusSeconds(600),val result:java.util.concurrent.CompletableFuture<Boolean> = java.util.concurrent.CompletableFuture())
    private val filePreviews=ProjectAgentFilePreviews()
    private val memoryApprovals=ConcurrentHashMap<String,MemoryApproval>()
    private val schedules=ProjectAgentSchedules(directory)
    private val historyGrants=ProjectAgentHistoryGrants(directory)
    private val personalHistory=DiscordProjectAgentHistory(current,historyGrants) {source->check(authorize(current() ?: error("연결 없음"),source.guild,source.owner).owner) {"정보 제공 서버의 출처 관리자 권한이 철회됐습니다."}}
    private fun personalServices(session:ProjectAgentChannel)=ProjectAgentPersonalServices(session,schedules,personalHistory,{store.preferences(session.guild,session.user)}) {name->
        val grant=privateAccess(current() ?: error("Discord 연결이 없습니다."),session)
        if(name=="schedule_task")check(grant.owner) {"작업 예약은 인증된 최고 관리자만 사용할 수 있습니다."}
    }
    private val engine=ProjectAgentEngine(store,{session->ProjectAgentTools(directory.resolve("project-agent-logs"),DiscordProjectAgentFiles({current()?.getTextChannelById(session.channel) ?: error("채널이 없습니다.")},{privateAccess(current() ?: error("연결 없음"),session)}),session.channel,personalServices(session)) {name,path->store.register(session.guild,session.user,name,path)}})
    private val scheduleDispatcher=ProjectAgentScheduleDispatcher(schedules,{current()!=null && config().enabled},jobs::isBusy,{task->worker.submit {task()}},{claimed->
        val jda=current() ?: error("Discord 연결이 없습니다.")
        val session=store.channel(claimed.channel) ?: error("예약 채널이 해제됐습니다.")
        check(session.guild==claimed.guild && session.user==claimed.user && session.contextEpoch==claimed.epoch && store.preferences(session.guild,session.user).revision==claimed.revision) {"예약 후 소유자·문맥·모델·승인 설정이 변경되어 실행하지 않습니다."}
        check(privateAccess(jda,session).owner) {"소유자 권한이 철회되었습니다."}
        val name=jda.getGuildById(session.guild)?.getMemberById(session.user)?.effectiveName ?: session.user
        jda.getTextChannelById(session.channel)?.sendMessageEmbeds(EmbedBuilder().setTitle("예약 작업 시작").setDescription("${claimed.title} · ${claimed.at}").build())?.setAllowedMentions(emptyList())?.complete()
        start(jda,session,name,claimed.prompt,null,null,null)
    },{claimed,e->current()?.getTextChannelById(claimed.channel)?.sendMessage("예약 ‘${claimed.title}’을 실행하지 못했습니다: ${safe(e)}")?.setAllowedMentions(emptyList())?.queue({}, {})})
    private val schedulePolling=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"project-agent-schedules").apply {isDaemon=true}}
    init {schedulePolling.scheduleWithFixedDelay({runCatching {scheduleDispatcher.tick()}},2,2,java.util.concurrent.TimeUnit.SECONDS)}
    companion object {
        private fun models(name:String,description:String)=OptionData(OptionType.STRING,name,description,false).also {option->LocalModels.all.forEach {option.addChoice(it.label,it.id)}}
        fun command()=Commands.slash("agent","로컬 프로젝트 에이전트 · 개인 채널·도구·승인")
            .addSubcommands(
                SubcommandData("setting","내 기본 모델·Thinking·승인 모드 설정")
                    .addOptions(models("default","내 기본 로컬 모델"),
                        OptionData(OptionType.BOOLEAN,"explicit_permission","true: 변경·명령마다 승인 / false: 독립 위험 자동 판단",false),OptionData(OptionType.BOOLEAN,"think","Thinking 사용 (지원 모델만)",false),
                        OptionData(OptionType.INTEGER,"tool_output_limit","도구 결과 발췌 최대 글자 수 · 원문 읽기/편집 제외 (기본2400)",false).setRequiredRange(PiToolOutput.MIN.toLong(),PiToolOutput.MAX.toLong())),
                SubcommandData("channel","같은 카테고리에 내 비공개 프로젝트 채널 생성").addOption(OptionType.STRING,"project","등록한 프로젝트 이름 (생략하면 기본 홈)",false),
                SubcommandData("project","기존 폴더를 내 프로젝트의 기본 작업 위치로 등록").addOption(OptionType.STRING,"name","프로젝트 이름",true).addOption(OptionType.STRING,"path","관리 앱 컴퓨터의 폴더 전체 경로",true),
                SubcommandData("run","이 프로젝트 채널에서 작업 요청").addOptions(OptionData(OptionType.STRING,"prompt","목표와 완료 조건",true).setMaxLength(6000),
                    models("model","이번 요청의 모델 (생략하면 기본 모델)"),OptionData(OptionType.BOOLEAN,"think","이번 요청의 Thinking (지원 모델만)",false),OptionData(OptionType.INTEGER,"turns","이번 요청의 최대 턴 수 (역할 상한 이내)",false).setRequiredRange(1,100),OptionData(OptionType.INTEGER,"window","이번 요청의 Context window (채널 기본값은 유지)",false).addChoice("8K",8192L).addChoice("16K · 메모리 주의",16384L)),
                SubcommandData("context","채널 문맥 조회·초기화·자동 압축 설정").addOptions(
                    OptionData(OptionType.STRING,"action","문맥 작업",true).addChoice("상태","status").addChoice("초기화","reset").addChoice("압축 기준","configure").addChoice("지금 압축","compact"),
                    OptionData(OptionType.INTEGER,"limit","자동 압축 기준 (실행 창의 입력 예산 이내)",false).setRequiredRange(1024,15600),
                    OptionData(OptionType.INTEGER,"window","실행 창 (16K는 메모리 압력 증가)",false).addChoice("8K · 기본",8192L).addChoice("16K · 메모리 주의",16384L),
                    OptionData(OptionType.INTEGER,"output","출력 예약 토큰",false).setRequiredRange(512,4096)),
                SubcommandData("history","모델이 조회할 채널·포럼을 직접 허용·철회").addOptions(
                    OptionData(OptionType.STRING,"action","기록 권한",true).addChoice("허용","allow").addChoice("철회","remove").addChoice("목록","list").addChoice("정보 사용 허용","grant").addChoice("전달 채널 철회","revoke"),
                    OptionData(OptionType.CHANNEL,"source","허용할 채널 또는 포럼 (포럼 허용은 게시글 포함)",false).setChannelTypes(net.dv8tion.jda.api.entities.channel.ChannelType.TEXT,net.dv8tion.jda.api.entities.channel.ChannelType.NEWS,net.dv8tion.jda.api.entities.channel.ChannelType.FORUM,net.dv8tion.jda.api.entities.channel.ChannelType.GUILD_PUBLIC_THREAD,net.dv8tion.jda.api.entities.channel.ChannelType.GUILD_PRIVATE_THREAD,net.dv8tion.jda.api.entities.channel.ChannelType.GUILD_NEWS_THREAD),
                    OptionData(OptionType.STRING,"target","정보를 사용할 에이전트 채널 ID 또는 링크 (다른 서버 가능)",false),
                    OptionData(OptionType.STRING,"users","사용할 Discord 사용자 ID/멘션, 쉼표 구분 (grant 시 필수)",false)),
                SubcommandData("schedules","내 예약 목록·취소").addOptions(OptionData(OptionType.STRING,"cancel","취소할 예약 ID (생략하면 목록)",false)),
                SubcommandData("link","관리 웹 소유자 계정 연결").addOption(OptionType.STRING,"code","웹 AI 설정에서 발급한 일회용 코드",true),
                SubcommandData("stop","이 채널의 작업·명령·대기 승인을 중단"),
                SubcommandData("turns","내 에이전트 최대 턴 수 조회·설정").addOptions(OptionData(OptionType.INTEGER,"count","한 요청에서 쓸 최대 턴 수 (역할 상한 이내)",false).setRequiredRange(1,100)))
            .addSubcommandGroups(SubcommandGroupData("access","친구 역할·모델·턴 제한 관리 (최고 관리자)").addSubcommands(
                SubcommandData("role-set","역할이 Qwen 에이전트를 쓰도록 허용·수정").addOptions(
                    OptionData(OptionType.ROLE,"role","허용할 Discord 역할",true),
                    OptionData(OptionType.STRING,"models","쓸 수 있는 모델",true).addChoice("14B만","14b").addChoice("30B만","30b").addChoice("14B와 30B","both"),
                    OptionData(OptionType.STRING,"tier","권한: read 읽기 전용 / edit 승인 후 부분 수정",true).addChoice("read","READ").addChoice("edit","EDIT"),
                    OptionData(OptionType.INTEGER,"max_turns","이 역할의 요청당 최대 턴 수",false).setRequiredRange(1,100),
                    OptionData(OptionType.STRING,"projects","허용할 공유 프로젝트 이름, 쉼표 구분 (생략하면 개인 작업 폴더만)",false)),
                SubcommandData("role-remove","역할의 에이전트 사용 허용 해제").addOption(OptionType.ROLE,"role","해제할 역할",true),
                SubcommandData("list","허용 역할·공유 프로젝트·턴 제한 보기"),
                SubcommandData("limits","서버 전체 턴 상한·기본값 설정").addOptions(
                    OptionData(OptionType.INTEGER,"cap","어떤 역할도 넘을 수 없는 턴 상한",false).setRequiredRange(1,100),
                    OptionData(OptionType.INTEGER,"default","턴 수를 지정하지 않았을 때 기본값",false).setRequiredRange(1,100)),
                SubcommandData("project-add","친구가 쓸 공유 프로젝트 폴더 등록").addOption(OptionType.STRING,"name","프로젝트 이름",true).addOption(OptionType.STRING,"path","관리 앱 컴퓨터의 폴더 전체 경로",true),
                SubcommandData("project-remove","공유 프로젝트 등록 해제").addOption(OptionType.STRING,"name","프로젝트 이름",true)))
    }
    fun register(jda:JDA,guild:String) {
        val g=jda.getGuildById(guild) ?: return
        g.upsertCommand(command()).queue({}, {})
        // Remove replaced commands, without disturbing Minecraft/music/cloud commands.
        g.retrieveCommands().queue({commands->commands.filter {it.name in setOf("local-llm","assistant")}.forEach {it.delete().queue({}, {})}},{})
    }
    /** Decided from the member's live roles on every call: a revoked role loses access at the next check. */
    private fun authorize(jda:JDA,guild:String,user:String):AgentGrant {
        check(jda===current() && config().enabled) {"Discord 연결이 변경되었거나 꺼졌습니다."}
        val g=jda.getGuildById(guild) ?: error("Discord 서버에 연결되지 않았습니다.")
        val member=g.retrieveMemberById(user).useCache(false).complete()
        val roles=member.roles.map {it.id}.toSet()
        val owner=config().superAdmins.any {it.guildId==guild && it.roleId in roles}
        // The super-admin role itself is the owner here. A linked web account is optional and only adds the web-registered project folders.
        val actor=if(owner)identity.resolveDiscord(user,guild) else null
        return AgentAccess.resolve(access.view(),guild,roles,owner,actor) ?: error("Qwen 에이전트를 쓸 수 있는 역할이 아닙니다. 최고 관리자에게 /agent access role-set 으로 역할 허용을 요청하세요.")
    }
    private fun isSuperAdmin(guild:net.dv8tion.jda.api.entities.Guild,user:String):Boolean {
        val member=guild.retrieveMemberById(user).useCache(false).complete()
        return config().enabled && config().superAdmins.any {it.guildId==guild.id && member.roles.any {role->role.id==it.roleId}}
    }
    private fun privateAccess(jda:JDA,session:ProjectAgentChannel):AgentGrant {
        val grant=authorize(jda,session.guild,session.user)
        val channel=jda.getTextChannelById(session.channel) ?: error("프로젝트 채널이 없습니다.")
        check(channel.guild.id==session.guild && !channel.guild.publicRole.hasPermission(channel,Permission.VIEW_CHANNEL)) {"프로젝트 채널이 공개되었습니다. 권한을 다시 설정하세요."}
        return grant
    }
    private fun access(event:SlashCommandInteractionEvent,guild:net.dv8tion.jda.api.entities.Guild):String {
        check(isSuperAdmin(guild,event.user.id)) {"현재 Discord 최고 관리자 역할이 필요합니다."}
        val data=access.view()
        return when(event.subcommandName) {
            "role-set" -> {
                val models=when(event.getOption("models")!!.asString) {"both"->setOf("14b","30b");else->setOf(event.getOption("models")!!.asString)}
                val role=event.getOption("role")!!.asRole
                val projects=event.getOption("projects")?.asString?.split(',')?.map {it.trim()}?.filter {it.isNotEmpty()}?.toSet().orEmpty()
                val saved=access.setRole(AgentRolePolicy(guild.id,role.id,models,event.getOption("tier")!!.asString,event.getOption("max_turns")?.asInt ?: minOf(data.defaultTurns,data.turnCap),projects))
                "`${role.name}` 허용: 모델 ${saved.models.sorted().joinToString("·")} · ${saved.tier} · 최대 ${saved.maxTurns}턴 · 프로젝트 ${saved.projects.sorted().joinToString().ifBlank {"개인 폴더만"}}"
            }
            "role-remove" -> {val role=event.getOption("role")!!.asRole;if(access.removeRole(guild.id,role.id))"`${role.name}` 허용을 해제했습니다. 진행 중인 작업은 다음 권한 확인에서 중단됩니다." else "허용되어 있지 않은 역할입니다."}
            "list" -> buildString {
                append("턴 상한 ${data.turnCap} · 기본 ${data.defaultTurns}\n")
                data.roles.filter {it.guildId==guild.id}.forEach {append("<@&${it.roleId}> · 모델 ${it.models.sorted().joinToString("·")} · ${it.tier} · ${it.maxTurns}턴 · 프로젝트 ${it.projects.sorted().joinToString().ifBlank {"개인 폴더"}}\n")}
                if(data.roles.none {it.guildId==guild.id})append("허용된 친구 역할이 없습니다. 최고 관리자만 사용할 수 있습니다.\n")
                data.sharedProjects.filter {it.guildId==guild.id}.forEach {append("공유 프로젝트 `${it.name}` → `${it.path}`\n")}
            }
            "limits" -> {val next=access.setLimits(event.getOption("cap")?.asInt,event.getOption("default")?.asInt);"턴 상한 ${next.turnCap} · 기본 ${next.defaultTurns} (이를 넘던 역할·개인 설정은 상한으로 낮췄습니다)"}
            "project-add" -> {val project=access.addProject(guild.id,event.getOption("name")!!.asString,Path.of(event.getOption("path")!!.asString));"공유 프로젝트 `${project.name}` → `${project.path}`. 역할에 연결하려면 role-set 의 projects 에 이름을 넣으세요."}
            "project-remove" -> if(access.removeProject(guild.id,event.getOption("name")!!.asString))"공유 프로젝트를 해제하고 모든 역할의 허용에서도 제거했습니다." else "없는 프로젝트입니다."
            else -> error("알 수 없는 access 명령입니다.")
        }
    }
    fun handle(event:SlashCommandInteractionEvent) {
        if(event.jda!==current() || event.name!="agent")return
        val guild=event.guild ?: return
        event.deferReply(true).queue({hook->worker.submit {
            try {
                if(event.subcommandName=="link") {
                    check(config().enabled)
                    val member=guild.retrieveMemberById(event.user.id).useCache(false).complete()
                    check(config().superAdmins.any {it.guildId==guild.id && member.roles.any {r->r.id==it.roleId}})
                    identity.link(event.getOption("code")!!.asString,event.user.id,guild.id)
                    hook.editOriginal("소유자 계정을 연결했습니다. /agent channel로 비공개 채널을 만드세요.").queue({}, {});return@submit
                }
                if(event.subcommandGroup=="access") {
                    hook.editOriginal(access(event,guild).take(1900)).setAllowedMentions(emptyList()).queue({}, {});return@submit
                }
                val grant=authorize(event.jda,guild.id,event.user.id);val actor=grant.actor
                val embed=EmbedBuilder().setAuthor(event.user.effectiveName,null,event.user.effectiveAvatarUrl)
                when(event.subcommandName) {
                    "setting"->{
                        event.getOption("default")?.asString?.let {check(grant.allows(it)) {"이 역할은 ${grant.models.sorted().joinToString("·")} 모델만 쓸 수 있습니다."}}
                        check(event.getOption("explicit_permission")?.asBoolean!=false || grant.owner) {"친구 권한에서는 변경·명령마다 승인하는 모드를 끌 수 없습니다."}
                        val value=if(event.options.isEmpty())store.preferences(guild.id,event.user.id) else store.settings(guild.id,event.user.id,event.getOption("default")?.asString,event.getOption("think")?.asBoolean,event.getOption("explicit_permission")?.asBoolean,event.getOption("tool_output_limit")?.asInt)
                        if(event.options.isNotEmpty()){engine.settingsChanged(guild.id,event.user.id);jobs.stopUser(event.user.id)}
                        embed.setDescription("${event.user.asMention} 님의 기본 에이전트: ${LocalModels.label(value.model ?: qwen.config().model)} · Thinking ${if(LocalModels.get(value.model ?: qwen.config().model).supportsThinking)value.think.toString() else "미지원 (자동 꺼짐)"}\n"+
                            if(value.explicitPermission)"명시적 승인: 파일 변경·명령 실행 전 동의가 필요합니다." else "자동 명령 위험판단을 사용합니다. 낮은 위험 또는 검증된 범위의 사전 명시 동의가 확인된 보통 위험 요청을 자동 실행하며, 위험·불확실한 명령은 동의를 요청하고 거부 판정은 차단합니다. 모델의 위험 판단은 안전 보증이 아닙니다. 명령은 관리 앱 사용자 권한으로 전체 파일·네트워크에 접근할 수 있습니다.")
                        embed.addField("도구 출력 제한","${value.toolOutputLimit}자 · 초과 원문은 파일 보관/첨부, 모델에는 일부 발췌와 read_result ID 전달. 원문 읽기·편집·정보 질문은 제외합니다.",false)
                        hook.editOriginalEmbeds(embed.build()).setAllowedMentions(emptyList()).queue({}, {})
                    }
                    "history"->{
                        check(grant.owner) {"정보 제공 서버의 인증된 최고 관리자만 출처·전달 권한을 설정할 수 있습니다."}
                        val action=event.getOption("action")!!.asString
                        if(action!="list") {
                            val source=event.getOption("source")?.asChannel ?: error("현재 정보 제공 서버의 출처 채널·포럼을 선택하세요.")
                            check(source.guild.id==guild.id)
                            when(action) {
                                "allow"->{personalHistory.checkMember(source,event.user.id);historyGrants.register(guild.id,event.user.id,source.id,source.type.name)}
                                "remove"->historyGrants.remove(guild.id,event.user.id,source.id)
                                "grant","revoke"->{
                                    val raw=event.getOption("target")?.asString ?: error("정보 사용 에이전트 채널 ID/링크가 필요합니다.")
                                    val targetId=Regex("[0-9]{1,22}").findAll(raw).lastOrNull()?.value ?: error("올바른 대상 채널 ID/링크를 지정하세요.")
                                    if(action=="revoke"){historyGrants.revokeTarget(guild.id,event.user.id,source.id,targetId)}else {
                                    val target=store.channel(targetId) ?: error("/agent channel 로 등록한 정보 사용 채널만 지정할 수 있습니다.")
                                    val targetChannel=event.jda.getTextChannelById(target.channel) ?: error("봇이 정보 사용 서버·채널에 연결되어 있지 않습니다.")
                                    check(targetChannel.guild.id==target.guild && !targetChannel.guild.publicRole.hasPermission(targetChannel,Permission.VIEW_CHANNEL)) {"정보 사용 채널이 공개되어 있습니다."}
                                    val users=event.getOption("users")?.asString?.let {Regex("[0-9]{1,22}").findAll(it).map {m->m.value}.toSet()} ?: if(action=="revoke")setOf(target.user)else error("이 출처를 사용할 사용자 ID/멘션을 명시하세요.")
                                    if(action=="grant")check(target.user in users) {"대상 에이전트 채널 소유자 ${target.user}를 사용 허용 목록에 포함해야 합니다."}
                                    historyGrants.bind(guild.id,event.user.id,source.id,target.guild,target.channel,users,true)
                                    }
                                }
                                else->error("유효하지 않은 권한 작업입니다.")
                            }
                        }
                        val entries=historyGrants.provider(guild.id,event.user.id)
                        hook.editOriginal((entries.joinToString("\n") {source->"출처 <#${source.channel}> → "+source.targets.joinToString {t->"서버 ${t.guild} / 채널 ${t.channel} / 사용자 ${t.users.joinToString()}"}.ifBlank {"정보 사용 대상 없음"}}.ifBlank {"등록한 출처 없음"}+"\nallow로 출처 등록 후 grant로 사용 채널·사용자를 허용하세요. 대상 채널의 열람자도 전달된 답변을 볼 수 있습니다. 철회는 다음 조회부터 적용되며 기존 문맥은 사용 채널에서 초기화해야 합니다.").take(1900)).setAllowedMentions(emptyList()).queue({}, {})
                    }
                    "schedules"->{
                        check(grant.owner)
                        event.getOption("cancel")?.asString?.let {schedules.cancel(guild.id,event.user.id,it)}
                        hook.editOriginal(schedules.list(guild.id,event.user.id).joinToString("\n") {"${it.id} · ${it.state} · ${it.at.atZone(java.time.ZoneId.systemDefault())} · ${it.title}"}.ifBlank {"예약이 없습니다."}.take(1900)).setAllowedMentions(emptyList()).queue({}, {})
                    }
                    "turns"->{
                        val data=access.view();val limit=minOf(grant.maxTurns,data.turnCap)
                        event.getOption("count")?.asInt?.let {check(it<=limit) {"이 역할의 턴 상한은 ${limit}입니다."};access.setUserTurns(guild.id,event.user.id,it)}
                        hook.editOriginal("내 요청당 최대 턴 수: ${AgentAccess.turns(access.view(),grant,guild.id,event.user.id,null)} (역할 상한 $limit · 서버 기본 ${data.defaultTurns})").queue({}, {})
                    }
                    "project"->{check(grant.owner) {"임의 폴더 등록은 최고 관리자만 할 수 있습니다. 공유 프로젝트는 /agent access project-add 로 등록합니다."};val project=store.register(guild.id,event.user.id,event.getOption("name")!!.asString,Path.of(event.getOption("path")!!.asString));hook.editOriginalEmbeds(embed.setDescription("프로젝트 `${project.name}` 등록: `${project.path}`\n이 경로는 기본 작업 위치이며 접근 범위를 제한하지 않습니다.").build()).queue({}, {})}
                    "channel"->{
                        val source=event.channel as? TextChannel ?: error("일반 텍스트 채널에서 실행하세요.")
                        val projectName=event.getOption("project")?.asString
                        check(grant.owner || store.channelsOf(guild.id,event.user.id)<3) {"친구 권한은 에이전트 채널을 3개까지 만들 수 있습니다. 쓰지 않는 채널을 정리한 뒤 다시 시도하세요."}
                        val shared=projectName?.let {name->access.project(guild.id,name)?.takeIf {name in grant.projects}}
                        val project=projectName?.let {name->shared?.let {ProjectAgentProject(it.name,it.path)} ?: (if(grant.owner)store.projects(guild.id,event.user.id).find {it.name==name} ?: actor?.let {a->roots(a).find {it.id==name || it.name==name}?.let {ProjectAgentProject(it.id,it.path)}} else null) ?: error("사용할 수 있는 프로젝트가 아닙니다. 최고 관리자가 역할에 공유 프로젝트를 연결해야 합니다.")}
                        val personal=if(grant.owner)Path.of(System.getProperty("user.home"),"ServerManagerProjects") else Path.of(System.getProperty("user.home"),"ServerManagerProjects","friends","${guild.id}-${event.user.id}")
                        val home=project?.let {Path.of(it.path).toRealPath()} ?: personal.createDirectories().toRealPath()
                        val name=("qwen-"+(project?.name ?: event.user.name)).lowercase().replace(Regex("[^\\p{L}\\p{N}_-]"),"-").take(90)
                        val action=ProjectAgentChannels.create(guild,source.parentCategory,event.user.idLong,name)
                        val created=action.complete()
                        try {store.addChannel(ProjectAgentChannel(guild.id,created.id,event.user.id,project?.name,home.toString()))}catch(e:Exception){created.delete().queue({}, {});throw e}
                        val natural=if(net.dv8tion.jda.api.requests.GatewayIntent.MESSAGE_CONTENT in event.jda.gatewayIntents)"자연어로 요청하거나 `/agent run`을 사용하세요." else "현재 봇의 메시지 콘텐츠 권한이 없어 `/agent run`을 사용해야 합니다. 자연어 대화는 Discord Developer Portal의 Message Content Intent를 허용한 뒤 봇을 다시 연결하세요."
                        created.sendMessage("로컬 에이전트 프로젝트 채널입니다. 홈: `$home` · 도구 실행: 관리 앱 컴퓨터\nThinking 지원 모델은 기본 켜짐 · 명시적 승인 기본 켜짐. 대화·결과는 이 채널을 볼 수 있는 사람에게 보입니다. Discord 서버 관리자는 비공개 채널에도 접근할 수 있습니다. $natural").setAllowedMentions(emptyList()).queue({}, {})
                        hook.editOriginalEmbeds(embed.setDescription("${event.user.asMention} 님의 ${project?.name ?: "기본"} 채널이 ${created.asMention}에 할당되었습니다.").build()).setAllowedMentions(emptyList()).queue({}, {})
                    }
                    "context"->{
                        var session=store.channel(event.channel.id) ?: error("에이전트 채널에서 사용하세요.")
                        check(session.user==event.user.id);privateAccess(event.jda,session)
                        val action=event.getOption("action")!!.asString
                        if(action!="status"){
                            runCatching {jobs.stop(session.channel,session.user)};engine.stop(session.channel,session.user)
                            if(action=="reset")audience.clear(session.channel)
                            session=store.context(session.channel,session.user,action=="reset",if(action=="configure")event.getOption("limit")?.asLong?.toInt()else null,if(action=="configure")event.getOption("window")?.asLong?.toInt()else null,if(action=="configure")event.getOption("output")?.asLong?.toInt()else null)
                            if(action=="compact"){
                                val job=jobs.begin(session.channel,session.user)
                                try {engine.compactWithTimeout(session.channel,session.user,inference(session),qwen.config().requestTimeoutMinutes*60){privateAccess(event.jda,session)}}finally{jobs.finish(session.channel,job)}
                                session=store.channel(session.channel)!!
                            }
                        }
                        val llm=inference(session);val maximum=llm.modelLimit()
                        hook.editOriginal("${if(action=="reset")"문맥을 초기화했습니다. 프로젝트 정보는 유지됩니다.\n" else ""}최근 15분 대화 참여자 ${audience.audience(session.channel).participantIds.size}명 (관측된 발언 기준)\nPi 최근 문맥 ${engine.contextTokens(session)?.toString() ?: "미수신"} tokens · 설정 압축 기준 ${session.contextLimit} · Pi 적용 기준 ${maxOf(session.contextLimit,minOf(4096,session.executionWindow-session.outputTokens-256))}\n실행 창 ${session.executionWindow} · 입력 예산 ${session.executionWindow-session.outputTokens-256} · 출력 예약 ${session.outputTokens} · 모델 최대 ${maximum?.toString() ?: "확인 불가"}\n${LocalContextBudget(session.executionWindow,session.outputTokens).warning}").queue({}, {})
                    }
                    "stop"->{jobs.stop(event.channel.id,event.user.id);engine.stop(event.channel.id,event.user.id);hook.editOriginal("현재 작업과 대기 승인을 중단했습니다.").queue({}, {})}
                    "run"->{
                        val session=store.channel(event.channel.id) ?: error("/agent channel로 만든 채널에서 실행하세요.")
                        check(session.user==event.user.id);privateAccess(event.jda,session)
                        hook.editOriginal("${event.channel.asMention}에서 시작합니다. 결과는 채널 메시지에 표시됩니다.").queue({}, {})
                        start(event.jda,session,event.member?.effectiveName ?: event.user.effectiveName,event.getOption("prompt")!!.asString,event.getOption("model")?.asString,event.getOption("think")?.asBoolean,event.getOption("turns")?.asInt,event.getOption("window")?.asInt)
                    }
                    else->error("/agent setting, channel, project, run, turns, link, stop, access를 사용하세요.")
                }
            }catch(e:Exception){hook.editOriginal("요청 실패: ${safe(e)}").setAllowedMentions(emptyList()).queue({}, {})}
        }},{})
    }
    fun message(event:MessageReceivedEvent) {
        if(event.jda!==current() || event.isWebhookMessage || !event.isFromGuild)return
        val session=store.channel(event.channel.id) ?: return
        val channel=event.channel as? TextChannel ?: return
        if(channel.guild.id!=session.guild || channel.guild.publicRole.hasPermission(channel,Permission.VIEW_CHANNEL))return
        if(!event.author.isBot && session.user==event.author.id && seededChannels.add(session.channel)){
            channel.sendTyping().queue({}, {})
            worker.submit {try {
                privateAccess(event.jda,session)
                channel.history.retrievePast(25).timeout(5,java.util.concurrent.TimeUnit.SECONDS).complete().asReversed().forEach {m->check(store.channel(session.channel)?.contextEpoch==session.contextEpoch) {"문맥 설정이 변경되어 이전 요청을 취소했습니다."};audience.record(session.channel,AgentConversationMessage(m.id,m.author.id,m.member?.effectiveName ?: m.author.effectiveName,m.author.isBot,m.contentRaw,m.timeCreated.toInstant().toEpochMilli(),m.messageReference?.messageId,replyAuthor=m.referencedMessage?.author?.id))}
                if(store.channel(session.channel)?.contextEpoch==session.contextEpoch)message(event)
            }catch(e:Exception){seededChannels.remove(session.channel);channel.sendMessage("대화 대상 확인 실패: ${safe(e)}. /agent run으로 직접 요청할 수 있습니다.").setAllowedMentions(emptyList()).queue({}, {})}}
            return
        }
        val record=AgentConversationMessage(event.messageId,event.author.id,event.member?.effectiveName ?: event.author.effectiveName,event.author.isBot,event.message.contentRaw,event.message.timeCreated.toInstant().toEpochMilli(),event.message.messageReference?.messageId,replyAuthor=event.message.referencedMessage?.author?.id)
        val destination=audience.decide(session.channel,record,event.jda.selfUser.id)
        audience.record(session.channel,record.copy(agentDirected=destination==AgentAddressee.AGENT && !event.author.isBot))
        // Non-owner participation helps identify the audience; it NEVER grants tool/approval authority.
        if(event.author.isBot || session.user!=event.author.id || (event.message.contentRaw.isBlank() && event.message.attachments.isEmpty()))return
        if(engine.hasApproval(session.channel,session.user)) {
            worker.submit {try {val accepted=engine.approveText(session.channel,session.user,event.message.contentRaw){privateAccess(event.jda,session)}
                if(accepted)event.message.addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("✅")).queue({}, {})
                else channel.sendMessage("표시된 도구 요청을 승인하려면 ‘실행해’ 또는 ‘허용’, 거절하려면 ‘거절’이라고 답하거나 버튼을 눌러주세요.").setAllowedMentions(emptyList()).queue({}, {})
            }catch(e:Exception){channel.sendMessage("승인 답변 실패: ${safe(e)}").setAllowedMentions(emptyList()).queue({}, {})}}
            return
        }
        if(engine.hasQuestion(session.channel,session.user)) {
            worker.submit {try {if(engine.answerQuestion(session.channel,session.user,null,event.message.contentRaw){privateAccess(event.jda,session)})event.message.addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("✅")).queue({}, {})}catch(e:Exception){channel.sendMessage("질문 답변 실패: ${safe(e)}").setAllowedMentions(emptyList()).queue({}, {})}}
            return
        }
        if(destination==AgentAddressee.HUMAN)return
        if(destination==AgentAddressee.UNCERTAIN){channel.sendMessage("최근 대화에 여러 사람이 참여해 누구에게 하신 말씀인지 확실하지 않아요. 루오에게 요청하신 거라면 `루오, …` 또는 `/agent run`으로 불러주세요.").setAllowedMentions(emptyList()).queue({}, {});return}
        // Real-time intervention: while a run is in progress the owner's message steers it instead of being rejected.
        val steering=try {engine.steer(session.channel,session.user,event.message.contentRaw+(if(event.message.attachments.isEmpty())"" else "\n(첨부 파일 ${event.message.attachments.size}개: list_attachments로 확인)"))}
            catch(e:Exception){channel.sendMessage("개입을 반영하지 못했습니다: ${safe(e)}").setAllowedMentions(emptyList()).queue({}, {});return}
        if(steering){event.message.addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("🧭")).queue({}, {});return}
        if(!routing.add(session.channel)){channel.sendMessage("이 채널에서 작업 중입니다. 승인 요청을 처리하거나 /agent stop으로 중단한 뒤 다시 요청하세요.").setAllowedMentions(emptyList()).queue({}, {});return}
        channel.sendTyping().queue({}, {})
        worker.submit {try {privateAccess(event.jda,session);start(event.jda,session,event.member?.effectiveName ?: event.author.effectiveName,(event.message.contentRaw+if(event.message.attachments.isEmpty())"" else "\n첨부 파일: "+event.message.attachments.joinToString {"${it.fileName} (message=${event.messageId}, attachment=${it.id})"}).take(6000),null,null,null)}catch(e:Exception){channel.sendMessage("에이전트 요청 실패: ${safe(e)}").setAllowedMentions(emptyList()).queue({}, {})}finally {routing.remove(session.channel)}}
    }
    fun button(event:ButtonInteractionEvent) {
        if(event.jda!==current() || !event.componentId.startsWith("qa:"))return
        event.deferEdit().queue({hook->worker.submit {try {
            val parts=event.componentId.split(':');check(parts.size in 3..4)
            val session=store.channel(event.channel.id) ?: error("채널이 해제되었습니다.")
            check(session.user==event.user.id) {"요청한 채널 소유자만 처리할 수 있습니다."}
            if(parts[1]=="file") {
                check(parts.size==3);privateAccess(event.jda,session)
                val change=filePreviews.get(parts[2],session.channel,event.user.id,session.contextEpoch).change
                val base=Path.of(change.path).fileName.toString().replace(Regex("[^\\p{L}\\p{N}._-]"),"_").take(90)
                val files=listOfNotNull(change.before?.let {FileUpload.fromData(it.toByteArray(Charsets.UTF_8),"before-$base")},change.after?.let {FileUpload.fromData(it.toByteArray(Charsets.UTF_8),"after-$base")})
                hook.sendMessage("`${change.path.take(500)}` 변경 전·후 UTF-8 원문입니다. 당시 스냅샷이며 현재 파일을 다시 읽지 않습니다.").setEphemeral(true).setAllowedMentions(emptyList()).addFiles(files).queue({}, {})
                return@submit
            }
            if(parts[1] in setOf("memory","memory-cancel")) {
                check(parts.size==3);privateAccess(event.jda,session)
                val approval=memoryApprovals[parts[2]] ?: error("만료되거나 이미 처리한 메모리 경고입니다.")
                check(approval.channel==session.channel && approval.user==event.user.id && approval.epoch==session.contextEpoch && approval.revision==store.preferences(session.guild,session.user).revision && java.time.Instant.now().isBefore(approval.until)) {"요청·설정이 바뀌었거나 승인이 만료됐습니다."}
                check(approval.result.complete(parts[1]=="memory")) {"이미 처리했습니다."};hook.editOriginalComponents(emptyList()).queue({}, {});return@submit
            }
            if(parts[1]=="answer"){
                check(parts.size==4)
                val question=event.message.embeds.firstOrNull() ?: error("질문 메시지를 찾을 수 없습니다.")
                val selected=event.button.label
                engine.answerQuestion(event.channel.id,event.user.id,parts[2],parts[3]){privateAccess(event.jda,session)}
                hook.editOriginalEmbeds(DiscordQuestionAnswer.embed(question.description.orEmpty(),selected,event.user.id)).setComponents(emptyList()).setAllowedMentions(emptyList()).queue({}, {})
                return@submit
            }
            check(parts.size==3)
            if(parts[1]=="stop"){jobs.stop(event.channel.id,event.user.id,parts[2]);engine.stop(event.channel.id,event.user.id);hook.sendMessage("생성 중지 요청을 보냈습니다.").setEphemeral(true).queue({}, {});return@submit}
            engine.approve(event.channel.id,event.user.id,parts[2],parts[1]=="allow") {privateAccess(event.jda,session)}
            hook.editOriginalComponents(emptyList()).queue({}, {})
        }catch(e:Exception){hook.sendMessage("승인 실패: ${safe(e)}").setEphemeral(true).queue({}, {})}}},{})
    }
    private fun start(jda:JDA,session:ProjectAgentChannel,userName:String,prompt:String,modelOverride:String?,thinkOverride:Boolean?,turnsOverride:Int?,windowOverride:Int?=null):Boolean {
        val job=jobs.begin(session.channel,session.user)
        try {
        val channel=jda.getTextChannelById(session.channel) ?: error("채널이 없습니다.")
        check(store.channel(session.channel)?.contextEpoch==session.contextEpoch) {"문맥 설정이 바뀌었습니다. 다시 요청하세요."}
        val grant=authorize(jda,session.guild,session.user);val actor=grant.actor
        val saved=store.preferences(session.guild,session.user)
        val chosen=qwen.config();val wanted=modelOverride ?: saved.model ?: chosen.model
        // An explicit request for a model the role may not use is an error; a stale saved default quietly falls back.
        val model=if(grant.allows(wanted))wanted else if(modelOverride!=null)error("이 역할은 ${grant.models.sorted().joinToString("·")} 모델만 쓸 수 있습니다.") else grant.models.sorted().first()
        val preferences=saved.copy(think=LocalModels.thinking(model,thinkOverride ?: saved.think),explicitPermission=if(grant.owner)saved.explicitPermission else true)
        val maxTurns=AgentAccess.turns(access.view(),grant,session.guild,session.user,turnsOverride)
        val runSession=if(windowOverride==null)session else session.copy(executionWindow=LocalContextBudget(windowOverride,session.outputTokens).window)
        // Audience routing is deterministic and immediate; no coding-model inference before acknowledgement.
        check(!job.cancelled.get() && !Thread.currentThread().isInterrupted) {"작업을 중단했습니다."}
        val main=channel.sendMessageEmbeds(EmbedBuilder().setTitle("Pi · ${LocalModels.label(model)} · 요청 인식 완료").setDescription(prompt.take(1800)).setFooter("최근 대화 참여자 ${(audience.audience(session.channel).participantIds+session.user).size}명 · 모델 응답 대기").build()).setComponents(ActionRow.of(Button.danger("qa:stop:${job.id}","에이전트 중지"))).setAllowedMentions(emptyList()).complete()
        val events=DiscordProjectAgentDisplay(channel,main,prompt,model,job.id,maxTurns=maxTurns,filePreview={change->filePreviews.put(session.channel,session.user,session.contextEpoch,change)},toolOutputLimit=preferences.toolOutputLimit)
        var lastCheck=0L
        fun active() {
            check(!job.cancelled.get() && store.channel(session.channel)?.contextEpoch==session.contextEpoch) {"사용자가 작업을 중단했습니다."}
            check(jda===current() && config().enabled && (actor==null || identity.resolveDiscord(session.user,session.guild)==actor)) {"소유자 연결 또는 Discord 연결이 철회되었습니다."}
            if(System.nanoTime()-lastCheck>java.util.concurrent.TimeUnit.SECONDS.toNanos(3)){val fresh=privateAccess(jda,session);lastCheck=System.nanoTime();check(fresh.tier==grant.tier && fresh.allows(model)) {"역할 권한이 바뀌어 작업을 중단했습니다."}}
        }
        val inference=inference(runSession,model) {reason->
            active()
            val token=java.util.UUID.randomUUID().toString()
            val approval=MemoryApproval(session.channel,session.user,session.contextEpoch,saved.revision)
            memoryApprovals[token]=approval
            val warning=channel.sendMessageEmbeds(EmbedBuilder().setColor(0xe67e22).setTitle("메모리 위험 · 모델 호출 중단")
                .setDescription((reason.substringAfter(LlmMemoryGuard.MARKER)+"\n메모리 부족으로 앱 응답 지연·종료가 발생할 수 있습니다. 이번 모델 호출만 위험 감지를 무시하고 재시도할 수 있습니다. 파일·명령 승인은 별도이며, 앞서 실행한 도구를 다시 실행하지 않습니다. 10분 후 만료됩니다.").take(3500)).build())
                .setComponents(ActionRow.of(Button.danger("qa:memory:${token}","위험을 감수하고 재시도"),Button.secondary("qa:memory-cancel:${token}","중단"))).setAllowedMentions(emptyList()).complete()
            try {
                val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(10)
                while(!approval.result.isDone){active();check(store.preferences(session.guild,session.user).revision==saved.revision) {"모델·승인 설정이 변경됐습니다."};check(System.nanoTime()<deadline) {"메모리 위험 승인 대기가 만료됐습니다."};try{approval.result.get(250,java.util.concurrent.TimeUnit.MILLISECONDS)}catch(_:java.util.concurrent.TimeoutException){}}
                check(approval.result.get()) {"메모리 위험으로 중단했습니다. 사용자가 재시도를 허용하지 않았습니다."}
            }catch(e:Exception){job.cancelled.set(true);engine.stop(session.channel,session.user);throw e}
            finally{memoryApprovals.remove(token,approval);warning.editMessageComponents(emptyList()).queue({}, {})}
        }
        val runStarted=System.nanoTime()
        try {engine.runWindow(session.channel,session.user,userName,prompt,preferences,inference,ProjectAgentLimits(maxTurns,grant.tier),windowOverride,chosen.requestTimeoutMinutes*60,::active,events::emit);return true}catch(e:Exception){events.emit(ProjectAgentEvent("done",text=if(job.cancelled.get())"작업 중단: 사용자가 중지했습니다." else "작업 중단: ${safe(e)}",seconds=(System.nanoTime()-runStarted)/1e9));return false}
        }finally{jobs.finish(session.channel,job)}
    }
    private fun inference(session:ProjectAgentChannel,override:String?=null,memoryPermission:((String)->Unit)?=null):ProjectAgentInference {
        val chosen=qwen.config();val model=override ?: store.preferences(session.guild,session.user).model ?: chosen.model
        return object:ProjectAgentInference {
            private var last:QwenUsage?=null;private var helperInput=0L;private var helperOutput=0L;private var helperCalls=0;private var nativeReply:String?=null;private var wasLimited=false;private var currentUsage:QwenUsage?=null
            override fun nativeTools()=true
            override fun nativeMessage()=nativeReply
            override fun limited()=wasLimited
            override fun providerUsage()=currentUsage
            override fun reviewerName()="${LocalModels.label(model)} · 독립 새 세션 · Thinking off"
            override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
                val pi=PiWire.decode(messages)
                val main=pi?.path("tools")?.let {it.isArray && it.size()>0} ?: (messages.firstOrNull()?.content?.startsWith("You are 루오노타르")==true)
                val budget=LocalContextBudget(session.executionWindow,session.outputTokens,requestTimeoutSeconds=chosen.requestTimeoutMinutes*60).let {if(main || pi!=null)it else it.helper()}
                val response=try {QwenRequests.conversationWithBudget(model,messages,think,chosen.runnerId,budget,progress).answer}
                    catch(e:Exception){val warning=LlmMemoryGuard.reason(e);if(warning==null || memoryPermission==null)throw e
                        memoryPermission(warning)
                        QwenRequests.conversationWithBudget(model,messages,think,chosen.runnerId,budget.copy(allowCriticalMemory=true),progress).answer}
                nativeReply=response.nativeMessage;wasLimited=response.limited;currentUsage=QwenUsage(response.inputTokens,response.tokens,response.inputTokens?.toInt() ?: 0,response.modelContext,executionWindow=budget.window,inputBudget=budget.window-budget.output-256,outputReserved=budget.output,tokensPerSecond=response.tokensPerSecond,memory=response.memory)
                if(!main){helperInput+=response.inputTokens ?: 0;helperOutput+=response.tokens;helperCalls++}
                last=if(main)QwenUsage(response.inputTokens,response.tokens,response.inputTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: messages.sumOf {ProjectAgentContext.messageTokens(it)},response.modelContext,helperInput=helperInput,helperOutput=helperOutput,helperCalls=helperCalls,executionWindow=budget.window,inputBudget=if(pi!=null)budget.window-budget.output-256 else budget.input,outputReserved=budget.output,tokensPerSecond=response.tokensPerSecond,memory=response.memory)
                    else (last ?: QwenUsage(executionWindow=budget.window,inputBudget=budget.input,outputReserved=session.outputTokens)).copy(helperInput=helperInput,helperOutput=helperOutput,helperCalls=helperCalls)
                check(!response.limited || pi!=null) {"로컬 모델 출력 한도에 도달했습니다. 문맥을 압축하거나 요청을 나눠주세요."};return response.text
            }
            override fun usage()=last
            override fun modelLimit():Int?=runCatching {RemoteLlm.modelLimitProvider?.invoke(model,chosen.runnerId) ?: if(chosen.runnerId==null)LocalLlm().modelContext(model) else null}.getOrNull()
        }
    }
    private fun safe(e:Exception)=if(e is IllegalArgumentException || e is IllegalStateException)e.message.orEmpty().take(1200) else "연결·실행 오류입니다. 봇 권한과 실행 로그를 확인하세요."
    fun disconnect(){filePreviews.clear();memoryApprovals.values.forEach {it.result.complete(false)};memoryApprovals.clear();jobs.close()}
    override fun close(){schedulePolling.shutdownNow();disconnect();engine.close();worker.shutdownNow()}
}

internal object ProjectAgentChannels {
    fun create(guild:Guild,category:Category?,owner:Long,name:String):ChannelAction<TextChannel> {
        val allowed=Permission.getRaw(Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_HISTORY,Permission.MESSAGE_SEND_IN_THREADS,Permission.CREATE_PUBLIC_THREADS,Permission.MESSAGE_ATTACH_FILES,Permission.MESSAGE_EMBED_LINKS)
        // Do not copy category member/role allows into a newly private channel.
        return guild.createTextChannel(name,category).clearPermissionOverrides()
            .addRolePermissionOverride(guild.publicRole.idLong,0,Permission.VIEW_CHANNEL.rawValue)
            .addMemberPermissionOverride(owner,allowed,0)
            .addMemberPermissionOverride(guild.selfMember.idLong,allowed,0)
    }
}

/** Plain model messages; embeds contain turn status, permission requests and condensed results only. */
internal class DiscordProjectAgentDisplay(private val channel:TextChannel,private val main:Message,private val request:String,private val model:String,private val jobId:String,private val editIntervalMillis:Long=750,private val maxTurns:Int=0,private val filePreview:((ProjectAgentFileChange)->String)?=null,private val toolOutputLimit:Int=PiToolOutput.DEFAULT) {
    private fun output(text:String,plan:ProjectAgentPlan?)=if(PiToolOutput.limited(plan?.tool?.name ?: "bash"))PiToolOutput.live(text,toolOutputLimit)else text
    private fun tn(turn:Int)=if(maxTurns>0)"$turn/$maxTurns" else "$turn"
    private val readResults=mutableSetOf<String>();private val cardBases=mutableMapOf<String,net.dv8tion.jda.api.entities.MessageEmbed>();private val turns=mutableMapOf<String,Message>();private val threads=mutableMapOf<String,ThreadChannel>()
    private val thoughts=mutableMapOf<Int,DiscordAgentText>();private val replies=mutableMapOf<Int,DiscordAgentText>();private val logs=mutableMapOf<String,Pair<StringBuilder,DiscordAgentText>>()
    private var usage:QwenUsage?=null;private var finished=false
    private class StatusSlot(val message:Message) {var active=true;lateinit var edits:CoalescedMessageEdits<net.dv8tion.jda.api.entities.MessageEmbed>}
    private var live:StatusSlot?=null
    private var actorTurn=0
    private var latestStatus="요청 인식 완료"
    private fun controls()=listOf(ActionRow.of(Button.danger("qa:stop:$jobId","에이전트 중지")))
    private val summaryEdits=CoalescedMessageEdits<net.dv8tion.jda.api.entities.MessageEmbed>({card,complete->
        main.editMessageEmbeds(card).setComponents(emptyList()).setAllowedMentions(emptyList()).queue({complete()},{complete()})
    },intervalMillis=editIntervalMillis)
    private fun footer()=usage?.let(QwenMetricsText::footer) ?: "입력·출력 토큰 대기"
    private fun summary(status:String,done:Boolean=false){summaryEdits.offer(EmbedBuilder().setTitle("Pi · ${LocalModels.label(model)} · ${if(done)status else "작업 요약"}").setDescription(request.take(1800)).addField("진행",status.take(1024),false).setFooter(footer()).build(),done)}
    private fun statusCard(status:String)=EmbedBuilder().setTitle("상태창 · 턴 ${tn(actorTurn)} · $status".take(250)).setFooter(footer()).build()
    private fun statusSlot():StatusSlot {
        live?.let {return it}
        val slot=StatusSlot(channel.sendMessageEmbeds(statusCard(latestStatus)).setComponents(controls()).setAllowedMentions(emptyList()).complete())
        slot.edits=CoalescedMessageEdits({card,complete->synchronized(this){
            if(!slot.active){complete()}else slot.message.editMessageEmbeds(card).setAllowedMentions(emptyList()).queue({complete()},{complete()})
        }},intervalMillis=editIntervalMillis)
        live=slot;return slot
    }
    private fun retireStatus():Message? {
        val slot=live ?: return null
        slot.active=false;slot.edits.cancel();live=null
        return slot.message
    }
    private fun render(status:String,done:Boolean=false){
        latestStatus=status;summary(status,done)
        if(done){retireStatus()?.editMessageEmbeds(statusCard(status))?.setComponents(emptyList())?.setAllowedMentions(emptyList())?.queue({}, {});return}
        // A status converted to a tool card stays a tool card until the next actor turn.
        if(live!=null || turns.isEmpty())statusSlot().edits.offer(statusCard(status))
    }
    @Synchronized private fun textPosted(){
        if(finished)return
        // Discord cannot reorder messages. Replace only the live placeholder after a
        // new answer chunk, preserving every completed tool card and the top summary.
        if(live!=null){retireStatus()?.delete()?.queue({}, {});statusSlot()}
    }
    private fun clearThinking(){thoughts.values.forEach {it.discard()};thoughts.clear()}
    private fun reply(turn:Int)=replies.getOrPut(turn){DiscordAgentText(channel,editIntervalMillis,::textPosted)}
    @Synchronized fun emit(event:ProjectAgentEvent) {
        if(finished)return
        val key=event.callId ?: "turn:${event.turn}"
        if(event.plan?.tool?.name=="read_result" && event.kind in setOf("tool_start","result_read","tool_end","denied")) {
            if(event.kind=="denied" || event.kind=="tool_end" && event.text.startsWith("도구 오류"))render("결과 읽기 실패 · ${event.text.take(120)}")
            event.readProgress?.let {p->readResults.add(p.id);DiscordResultCards.reading(channel.id,p);summary("모델이 결과를 읽는 중… ${p.id} · ${p.current}/${p.total}줄");live?.edits?.offer(statusCard("결과 조회 · ${p.current}/${p.total}줄"))}
            return
        }
        when(event.kind){
            "usage"->{usage=event.usage;render("턴 ${tn(event.turn)} · 결과 확인")}
            "status"->render("턴 ${tn(event.turn)} · ${event.text}")
            "ledger"->render("턴 ${tn(event.turn)} · 📋 ${event.text.take(200)}")
            "steer"->render("턴 ${tn(event.turn)} · 🧭 개입 반영: ${event.text.take(120).replace('\n',' ')}")
            "thinking"->{
                clearThinking()
                event.usage?.let {usage=it}
                if(actorTurn!=event.turn){retireStatus()?.delete()?.queue({}, {});actorTurn=event.turn;latestStatus="모델 응답 대기";statusSlot()}
                render("모델 응답 대기")
            }
            "stream"->{if(event.text.isNotBlank()){clearThinking();reply(event.turn).offer(event.text)}else if(!event.output.isNullOrBlank())thoughts.getOrPut(event.turn){DiscordAgentText(channel,editIntervalMillis,::textPosted)}.offer("‘${event.output.takeLast(1500)}’")}
            "message"->{clearThinking();reply(event.turn).offer(event.text);render("턴 ${tn(event.turn)} · 모델 응답")}
            "reviewing"->{clearThinking();render("턴 ${tn(event.turn)} · 독립 위험 검토")}
            "question"->{
                val choices=event.plan!!.tool.arguments.path("choices").mapIndexed {i,v->Button.secondary("qa:answer:${event.token}:$i",v.asText())}
                val message=channel.sendMessageEmbeds(EmbedBuilder().setTitle("루오의 질문").setDescription(event.text).setFooter("선택지를 누르거나 이 채널에 직접 답해주세요. 실행 승인은 아닙니다.").build()).setComponents(ActionRow.of(choices)).setAllowedMentions(emptyList()).complete()
                turns["question:${event.token}"]=message;render("질문 답변 대기")
            }
            "question_answered"->{turns["question:${event.token}"]?.editMessageEmbeds(DiscordQuestionAnswer.embed(event.plan!!.tool.arguments.path("question").asText(),event.text,event.requesterId.orEmpty()))?.setComponents(emptyList())?.setAllowedMentions(emptyList())?.queue({}, {})}
            "approval","tool_start","denied"->{
                clearThinking()
                val plan=event.plan!!
                val card=EmbedBuilder().setTitle("턴 ${event.turn} : ${plan.tool.name} · ${when(event.kind){"approval"->"승인 대기";"denied"->"실행 거부";else->"도구 실행 중"}}").setDescription(plan.description.take(2800))
                    .addField("실행 폴더",plan.cwd.take(1000),false)
                    .addField("영향 경로",plan.resolvedTargets.joinToString("\n").take(1000),false)
                    .addField("독립 검토",event.risk?.let {"${it.action} · ${it.risk} · ${it.projectScope}\n${it.reason}"}.orEmpty().take(1000),false).setFooter(footer())

                val actions=if(event.kind=="approval")listOf(ActionRow.of(Button.success("qa:allow:${event.token}","이 요청 허용"),Button.danger("qa:deny:${event.token}","거절"),Button.danger("qa:stop:$jobId","에이전트 중지")))else if(event.kind=="tool_start")controls()else emptyList()
                cardBases[key]=card.build()
                val message=turns[key]?.also {it.editMessageEmbeds(card.build()).setComponents(actions).complete()} ?: (retireStatus() ?: channel.sendMessageEmbeds(card.build()).setAllowedMentions(emptyList()).complete()).also {it.editMessageEmbeds(card.build()).setComponents(actions).setAllowedMentions(emptyList()).complete();turns[key]=it}
                if(event.kind=="tool_start"){
                    if(!plan.readOnly || plan.tool.name=="bash"){val thread=message.createThreadChannel("턴 ${event.turn} : ${plan.tool.name}".take(95)).complete();threads[key]=thread;DiscordAgentText(thread).offer("도구 호출 ${event.callId ?: event.turn}\n실행 폴더: ${plan.cwd}\n${plan.description}\n독립 검토: ${event.risk?.reason.orEmpty()}",true);logs[key]=StringBuilder() to DiscordAgentText(thread)}
                    render("턴 ${tn(event.turn)} · 도구 실행")
                }
            }
            "tool_output_snapshot"->{logs[key]?.let {(text,writer)->text.setLength(0);text.append(event.text);writer.offer(DiscordAgentCode.wrap(output(text.toString(),event.plan)))};render("턴 ${tn(event.turn)} · 실시간 출력")}
            "tool_output"->{if(event.text.isNotEmpty())logs[key]?.let {(text,writer)->text.append(event.text);writer.offer(DiscordAgentCode.wrap(output(text.toString(),event.plan)))};render("턴 ${tn(event.turn)} · 도구 실행 ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초")}
            "stored_output"->{
                turns[key]?.let {m->cardBases[key]?.let {base->DiscordResultCards.register(channel.id,event.token!!,key,m,base)}}
                val target=threads[key]
                val attachment=FileUpload.fromData(event.output.orEmpty().toByteArray(Charsets.UTF_8),"${event.token}.txt")
                if(target!=null)target.sendMessage("도구 출력 원문 · ${event.token} · 모델에는 ${event.text.length}자 이내 발췌 전달").addFiles(attachment).setAllowedMentions(emptyList()).queue({}, {render("출력 원문 첨부 실패 · 보관 ID ${event.token}")})
                else channel.sendMessage("도구 출력 원문 · ${event.token}").addFiles(attachment).setAllowedMentions(emptyList()).queue({}, {render("출력 원문 첨부 실패 · 보관 ID ${event.token}")})
            }
            "tool_end"->{
                logs[key]?.let {(text,writer)->writer.offer(DiscordAgentCode.wrap(output(text.toString().ifBlank {"출력 없음"},event.plan)),true)}
                val completedCard=EmbedBuilder().setTitle("턴 ${event.turn} : ${event.plan!!.tool.name} · ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초").setDescription("${event.plan!!.description.take(1600)}\n\n${event.text.take(1100)}").addField("독립 검토",event.risk?.let {"${it.action} · ${it.risk} · ${it.reason}"}?.take(1000) ?: "검토 기록은 실행 전 카드에 표시됩니다.",false).setFooter(footer()).build()
                cardBases[key]=completedCard;DiscordResultCards.completed(channel.id,key,completedCard)
                turns[key]?.editMessageEmbeds(completedCard)?.setComponents(emptyList())?.queue({}, {})
                event.changes.forEach {change->
                    val token=filePreview?.invoke(change)
                    val actions=if(token==null)emptyList()else listOf(ActionRow.of(Button.secondary("qa:file:$token","자세히 · 변경 전/후 원문")))
                    channel.sendMessageEmbeds(EmbedBuilder().setTitle("파일 변경 · "+Path.of(change.path).fileName.toString().take(180)).setDescription(change.path.take(1000)).setFooter("실행 전후 관측된 변경 · diff는 일부일 수 있습니다 · 원문 10분 보관").build()).setComponents(actions).setAllowedMentions(emptyList()).complete()
                    DiscordAgentText(channel).offer(DiscordAgentCode.wrap(change.diff,"diff"),true)
                }
                render("턴 ${tn(event.turn)} · 결과 검토 후 다음 단계")
            }
            "done"->{clearThinking();if(readResults.isNotEmpty())DiscordResultCards.finishReading(channel.id,readResults);reply(event.turn).offer(event.text,true);finished=true;render("${if(event.text.startsWith("작업 중단"))"중단" else "종료"} · ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초",true);turns.values.forEach {it.editMessageComponents(emptyList()).queue({}, {})}}
        }
    }
}

/** Plain answer field, not executable permission. Disable mentions at each send/edit site. */
internal object DiscordQuestionAnswer {
    fun embed(question:String,answer:String,user:String)=EmbedBuilder()
        .setTitle("루오의 질문 · 답변 완료")
        .setDescription(question.take(1000))
        .addField("선택한 답변",answer.take(1024),false)
        .apply {if(answer.length>1024)addField("답변 계속",answer.drop(1024).take(1024),false)}
        .setFooter(if(user.isBlank())"답변을 기록했습니다. 실행 승인은 아닙니다." else "응답자 ID: $user · 실행 승인은 아닙니다.")
        .build()
}
