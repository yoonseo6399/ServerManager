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
    private val engine=ProjectAgentEngine(store,{session->ProjectAgentTools(directory.resolve("project-agent-logs"),DiscordProjectAgentFiles({current()?.getTextChannelById(session.channel) ?: error("채널이 없습니다.")},{privateAccess(current() ?: error("연결 없음"),session)}),session.channel) {name,path->store.register(session.guild,session.user,name,path)}})
    companion object {
        private fun models(name:String,description:String)=OptionData(OptionType.STRING,name,description,false).also {option->LocalModels.all.forEach {option.addChoice(it.label,it.id)}}
        fun command()=Commands.slash("agent","로컬 프로젝트 에이전트 · 개인 채널·도구·승인")
            .addSubcommands(
                SubcommandData("setting","내 기본 모델·Thinking·승인 모드 설정")
                    .addOptions(models("default","내 기본 로컬 모델"),
                        OptionData(OptionType.BOOLEAN,"explicit_permission","true: 변경·명령마다 승인 / false: 독립 위험 자동 판단",false),OptionData(OptionType.BOOLEAN,"think","Thinking 사용 (지원 모델만)",false)),
                SubcommandData("channel","같은 카테고리에 내 비공개 프로젝트 채널 생성").addOption(OptionType.STRING,"project","등록한 프로젝트 이름 (생략하면 기본 홈)",false),
                SubcommandData("project","기존 폴더를 내 프로젝트의 기본 작업 위치로 등록").addOption(OptionType.STRING,"name","프로젝트 이름",true).addOption(OptionType.STRING,"path","관리 앱 컴퓨터의 폴더 전체 경로",true),
                SubcommandData("run","이 프로젝트 채널에서 작업 요청").addOptions(OptionData(OptionType.STRING,"prompt","목표와 완료 조건",true).setMaxLength(6000),
                    models("model","이번 요청의 모델 (생략하면 기본 모델)"),OptionData(OptionType.BOOLEAN,"think","이번 요청의 Thinking (지원 모델만)",false),OptionData(OptionType.INTEGER,"turns","이번 요청의 최대 턴 수 (역할 상한 이내)",false).setRequiredRange(1,100)),
                SubcommandData("context","채널 문맥 조회·초기화·자동 압축 설정").addOptions(
                    OptionData(OptionType.STRING,"action","문맥 작업",true).addChoice("상태","status").addChoice("초기화","reset").addChoice("압축 기준","configure").addChoice("지금 압축","compact"),
                    OptionData(OptionType.INTEGER,"limit","자동 압축 입력 토큰 기준 (1024~3584)",false).setRequiredRange(1024,3584)),
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
                        val value=if(event.options.isEmpty())store.preferences(guild.id,event.user.id) else store.settings(guild.id,event.user.id,event.getOption("default")?.asString,event.getOption("think")?.asBoolean,event.getOption("explicit_permission")?.asBoolean)
                        if(event.options.isNotEmpty()){engine.settingsChanged(guild.id,event.user.id);jobs.stopUser(event.user.id)}
                        embed.setDescription("${event.user.asMention} 님의 기본 에이전트: ${LocalModels.label(value.model ?: qwen.config().model)} · Thinking ${if(LocalModels.get(value.model ?: qwen.config().model).supportsThinking)value.think.toString() else "미지원 (자동 꺼짐)"}\n"+
                            if(value.explicitPermission)"명시적 승인: 파일 변경·명령 실행 전 동의가 필요합니다." else "자동 명령 위험판단을 사용합니다. 낮은 위험으로 허용된 요청만 자동 실행하며, 위험·불확실한 명령은 동의를 요청하고 거부 판정은 차단합니다. 모델의 위험 판단은 안전 보증이 아닙니다. 명령은 관리 앱 사용자 권한으로 전체 파일·네트워크에 접근할 수 있습니다.")
                        hook.editOriginalEmbeds(embed.build()).setAllowedMentions(emptyList()).queue({}, {})
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
                            session=store.context(session.channel,session.user,action=="reset",if(action=="configure")event.getOption("limit")?.asLong?.toInt() ?: error("limit을 입력하세요.")else null)
                            if(action=="compact"){
                                val job=jobs.begin(session.channel,session.user)
                                try {val compact=ProjectAgentMemory.compress(session.history,inference(session));privateAccess(event.jda,session);store.history(session.channel,compact,session.contextEpoch)}finally{jobs.finish(session.channel,job)}
                                session=store.channel(session.channel)!!
                            }
                        }
                        val llm=inference(session);val maximum=llm.modelLimit()
                        hook.editOriginal("${if(action=="reset")"문맥을 초기화했습니다. 프로젝트 정보는 유지됩니다.\n" else ""}최근 15분 대화 참여자 ${audience.audience(session.channel).participantIds.size}명 (관측된 발언 기준)\n문맥 약 ${session.history.sumOf {ProjectAgentContext.estimatedTokens(it.content)}} tokens (대화 기록 추정) · 압축 기준 ${session.contextLimit}\n실행 창 8192 · 출력 예약 4096 · 모델 최대 ${maximum?.toString() ?: "확인 불가"}").queue({}, {})
                    }
                    "stop"->{jobs.stop(event.channel.id,event.user.id);engine.stop(event.channel.id,event.user.id);hook.editOriginal("현재 작업과 대기 승인을 중단했습니다.").queue({}, {})}
                    "run"->{
                        val session=store.channel(event.channel.id) ?: error("/agent channel로 만든 채널에서 실행하세요.")
                        check(session.user==event.user.id);privateAccess(event.jda,session)
                        hook.editOriginal("${event.channel.asMention}에서 시작합니다. 결과는 채널 메시지에 표시됩니다.").queue({}, {})
                        start(event.jda,session,event.member?.effectiveName ?: event.user.effectiveName,event.getOption("prompt")!!.asString,event.getOption("model")?.asString,event.getOption("think")?.asBoolean,event.getOption("turns")?.asInt)
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
            val parts=event.componentId.split(':');check(parts.size==3)
            val session=store.channel(event.channel.id) ?: error("채널이 해제되었습니다.")
            check(session.user==event.user.id) {"요청한 채널 소유자만 처리할 수 있습니다."}
            if(parts[1]=="stop"){jobs.stop(event.channel.id,event.user.id,parts[2]);engine.stop(event.channel.id,event.user.id);hook.sendMessage("생성 중지 요청을 보냈습니다.").setEphemeral(true).queue({}, {});return@submit}
            engine.approve(event.channel.id,event.user.id,parts[2],parts[1]=="allow") {privateAccess(event.jda,session)}
            hook.editOriginalComponents(emptyList()).queue({}, {})
        }catch(e:Exception){hook.sendMessage("승인 실패: ${safe(e)}").setEphemeral(true).queue({}, {})}}},{})
    }
    private fun start(jda:JDA,session:ProjectAgentChannel,userName:String,prompt:String,modelOverride:String?,thinkOverride:Boolean?,turnsOverride:Int?) {
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
        val inference=inference(session,model)
        // Audience routing is deterministic and immediate; no coding-model inference before acknowledgement.
        check(!job.cancelled.get() && !Thread.currentThread().isInterrupted) {"작업을 중단했습니다."}
        val main=channel.sendMessageEmbeds(EmbedBuilder().setTitle("${LocalModels.label(model)} · 요청 인식 완료").setDescription(prompt.take(1800)).setFooter("최근 대화 참여자 ${(audience.audience(session.channel).participantIds+session.user).size}명 · 모델 응답 대기").build()).setComponents(ActionRow.of(Button.danger("qa:stop:${job.id}","에이전트 중지"))).setAllowedMentions(emptyList()).complete()
        val events=DiscordProjectAgentDisplay(channel,main,prompt,model,job.id,maxTurns=maxTurns)
        var lastCheck=0L
        fun active() {
            check(!job.cancelled.get() && store.channel(session.channel)?.contextEpoch==session.contextEpoch) {"사용자가 작업을 중단했습니다."}
            check(jda===current() && config().enabled && (actor==null || identity.resolveDiscord(session.user,session.guild)==actor)) {"소유자 연결 또는 Discord 연결이 철회되었습니다."}
            if(System.nanoTime()-lastCheck>java.util.concurrent.TimeUnit.SECONDS.toNanos(3)){val fresh=privateAccess(jda,session);lastCheck=System.nanoTime();check(fresh.tier==grant.tier && fresh.allows(model)) {"역할 권한이 바뀌어 작업을 중단했습니다."}}
        }
        try {engine.run(session.channel,session.user,userName,prompt,preferences,inference,ProjectAgentLimits(maxTurns,grant.tier),::active,events::emit)}catch(e:Exception){events.emit(ProjectAgentEvent("done",text=if(job.cancelled.get())"작업 중단: 사용자가 중지했습니다." else "작업 중단: ${safe(e)}"))}
        }finally{jobs.finish(session.channel,job)}
    }
    private fun inference(session:ProjectAgentChannel,override:String?=null):ProjectAgentInference {
        val chosen=qwen.config();val model=override ?: store.preferences(session.guild,session.user).model ?: chosen.model
        return object:ProjectAgentInference {
            private var last:QwenUsage?=null;private var helperInput=0L;private var helperOutput=0L;private var helperCalls=0
            override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
                val response=QwenRequests.conversation(model,messages,think,chosen.runnerId,progress).answer
                val main=messages.firstOrNull()?.content?.startsWith("You are 루오노타르")==true
                if(!main){helperInput+=response.inputTokens ?: 0;helperOutput+=response.tokens;helperCalls++}
                last=if(main)QwenUsage(response.inputTokens,response.tokens,messages.sumOf {ProjectAgentContext.estimatedTokens(it.content)},response.modelContext,helperInput=helperInput,helperOutput=helperOutput,helperCalls=helperCalls)
                    else (last ?: QwenUsage()).copy(helperInput=helperInput,helperOutput=helperOutput,helperCalls=helperCalls)
                check(!response.limited) {"로컬 모델 출력 한도에 도달했습니다. 문맥을 압축하거나 요청을 나눠주세요."};return response.text
            }
            override fun usage()=last
            override fun modelLimit():Int?=runCatching {RemoteLlm.modelLimitProvider?.invoke(model,chosen.runnerId) ?: if(chosen.runnerId==null)LocalLlm().modelContext(model) else null}.getOrNull()
        }
    }
    private fun safe(e:Exception)=if(e is IllegalArgumentException || e is IllegalStateException)e.message.orEmpty().take(1200) else "연결·실행 오류입니다. 봇 권한과 실행 로그를 확인하세요."
    override fun close(){jobs.close();engine.close();worker.shutdownNow()}
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
internal class DiscordProjectAgentDisplay(private val channel:TextChannel,private val main:Message,private val request:String,private val model:String,private val jobId:String,private val editIntervalMillis:Long=750,private val maxTurns:Int=0) {
    private fun tn(turn:Int)=if(maxTurns>0)"$turn/$maxTurns" else "$turn"
    private val turns=mutableMapOf<Int,Message>();private val threads=mutableMapOf<Int,ThreadChannel>()
    private val replies=mutableMapOf<Int,DiscordAgentText>();private val logs=mutableMapOf<Int,Pair<StringBuilder,DiscordAgentText>>()
    private var usage:QwenUsage?=null;private var finished=false
    private val edits=CoalescedMessageEdits<Pair<net.dv8tion.jda.api.entities.MessageEmbed,Boolean>>({snapshot,complete->
        val buttons=if(snapshot.second)emptyList() else listOf(ActionRow.of(Button.danger("qa:stop:$jobId","에이전트 중지")))
        main.editMessageEmbeds(snapshot.first).setComponents(buttons).setAllowedMentions(emptyList()).queue({complete()},{complete()})
    },intervalMillis=editIntervalMillis)
    private fun footer()=usage?.let {"문맥 약 ${it.estimatedContext}/8192 · 입력 ${it.input ?: "미수신"} · 출력 ${it.output ?: "미수신"} · 모델 최대 ${it.modelContext ?: "확인 불가"} · 보조 수신 ${it.helperCalls}회 입력 ${it.helperInput}/출력 ${it.helperOutput}"} ?: "입력·출력 토큰 대기"
    private fun render(status:String,done:Boolean=false){edits.offer(EmbedBuilder().setTitle("${LocalModels.label(model)} · $status").setFooter(footer()).build() to done,done)}
    private fun reply(turn:Int)=replies.getOrPut(turn){DiscordAgentText(channel,editIntervalMillis)}
    @Synchronized fun emit(event:ProjectAgentEvent) {
        if(finished)return
        when(event.kind){
            "usage"->{usage=event.usage;render("턴 ${tn(event.turn)} · 결과 확인")}
            "status"->render("턴 ${tn(event.turn)} · ${event.text}")
            "ledger"->render("턴 ${tn(event.turn)} · 📋 ${event.text.take(200)}")
            "steer"->render("턴 ${tn(event.turn)} · 🧭 개입 반영: ${event.text.take(120).replace('\n',' ')}")
            "thinking"->{usage=event.usage;render("턴 ${tn(event.turn)} · 모델 응답 대기")}
            "stream"->{if(event.text.isNotBlank())reply(event.turn).offer(event.text) else if(!event.output.isNullOrBlank())reply(event.turn).offer("‘${event.output.takeLast(1500)}’")}
            "message"->{reply(event.turn).offer(event.text);render("턴 ${tn(event.turn)} · 모델 응답")}
            "reviewing"->render("턴 ${tn(event.turn)} · 독립 위험 검토")
            "approval","tool_start","denied"->{
                val plan=event.plan!!
                val card=EmbedBuilder().setTitle("턴 ${event.turn} : ${plan.tool.name}").setDescription(plan.description.take(2800))
                    .addField("실행 폴더",plan.cwd.take(1000),false)
                    .addField("영향 경로",plan.resolvedTargets.joinToString("\n").take(1000),false)
                    .addField("독립 검토",event.risk?.let {"${it.action} · ${it.risk} · ${it.projectScope}\n${it.reason}"}.orEmpty().take(1000),false).setFooter(footer())
                if(plan.gradleFingerprint!=null)card.addField("Gradle 프로젝트 신뢰","허용하면 현재 빌드 설정의 일반 build·test·check·assemble을 자동 승인 모드에서 재사용합니다. Gradle 캐시·의존성 다운로드를 포함하며 설정 변경 시 다시 확인합니다.",false)
                val actions=if(event.kind=="approval")listOf(ActionRow.of(Button.success("qa:allow:${event.token}","이 요청 허용"),Button.danger("qa:deny:${event.token}","거절")))else emptyList()
                val message=turns[event.turn]?.also {it.editMessageEmbeds(card.build()).setComponents(actions).complete()} ?: channel.sendMessageEmbeds(card.build()).setComponents(actions).setAllowedMentions(emptyList()).complete().also {turns[event.turn]=it}
                if(event.kind=="tool_start"){
                    if(!plan.readOnly){val thread=message.createThreadChannel("턴 ${event.turn} : ${plan.tool.name}".take(95)).complete();threads[event.turn]=thread;logs[event.turn]=StringBuilder() to DiscordAgentText(thread)}
                    render("턴 ${tn(event.turn)} · 도구 실행")
                }
            }
            "tool_output"->{if(event.text.isNotEmpty())logs[event.turn]?.let {(text,writer)->text.append(event.text);writer.offer(text.toString())};render("턴 ${tn(event.turn)} · 도구 실행 ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초")}
            "tool_end"->{
                logs[event.turn]?.let {(text,writer)->writer.offer(text.toString().ifBlank {"출력 없음"},true)}
                turns[event.turn]?.editMessageEmbeds(EmbedBuilder().setTitle("턴 ${event.turn} : ${event.plan!!.tool.name} · ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초").setDescription(event.text.take(3500)).setFooter(footer()).build())?.setComponents(emptyList())?.queue({}, {})
                render("턴 ${tn(event.turn)} · 결과 검토 후 다음 단계")
            }
            "done"->{reply(event.turn).offer(event.text,true);finished=true;render("${if(event.text.startsWith("작업 중단"))"중단" else "종료"} · ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초",true);turns.values.forEach {it.editMessageComponents(emptyList()).queue({}, {})}}
        }
    }
}
