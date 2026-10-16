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
    private val worker=Executors.newVirtualThreadPerTaskExecutor()
    private val routing=ConcurrentHashMap.newKeySet<String>()
    private val engine=ProjectAgentEngine(store,{session->ProjectAgentTools(directory.resolve("project-agent-logs")) {name,path->store.register(session.guild,session.user,name,path)}})
    companion object {
        fun command()=Commands.slash("agent","Qwen 프로젝트 에이전트 · 개인 채널·도구·승인")
            .addSubcommands(
                SubcommandData("setting","내 기본 모델·Thinking·승인 모드 설정")
                    .addOptions(OptionData(OptionType.STRING,"default","내 기본 Qwen 모델",false).addChoice("Qwen 14B","14b").addChoice("Qwen 30B","30b"),
                        OptionData(OptionType.BOOLEAN,"explicit_permission","true: 변경·명령마다 승인 / false: 독립 위험 자동 판단",false),OptionData(OptionType.BOOLEAN,"think","Thinking 사용 (기본 true)",false)),
                SubcommandData("channel","같은 카테고리에 내 비공개 프로젝트 채널 생성").addOption(OptionType.STRING,"project","등록한 프로젝트 이름 (생략하면 기본 홈)",false),
                SubcommandData("project","기존 폴더를 내 프로젝트의 기본 작업 위치로 등록").addOption(OptionType.STRING,"name","프로젝트 이름",true).addOption(OptionType.STRING,"path","관리 앱 컴퓨터의 폴더 전체 경로",true),
                SubcommandData("run","이 프로젝트 채널에서 작업 요청").addOptions(OptionData(OptionType.STRING,"prompt","목표와 완료 조건",true).setMaxLength(6000),
                    OptionData(OptionType.STRING,"model","이번 요청의 모델 (기본 모델 생략 가능)",false).addChoice("Qwen 14B","14b").addChoice("Qwen 30B","30b"),OptionData(OptionType.BOOLEAN,"think","이번 요청의 Thinking (기본 true)",false)),
                SubcommandData("link","관리 웹 소유자 계정 연결").addOption(OptionType.STRING,"code","웹 AI 설정에서 발급한 일회용 코드",true),
                SubcommandData("stop","이 채널의 작업·명령·대기 승인을 중단"))
    }
    fun register(jda:JDA,guild:String) {
        val g=jda.getGuildById(guild) ?: return
        g.upsertCommand(command()).queue({}, {})
        // Remove replaced commands, without disturbing Minecraft/music/cloud commands.
        g.retrieveCommands().queue({commands->commands.filter {it.name in setOf("local-llm","assistant")}.forEach {it.delete().queue({}, {})}},{})
    }
    private fun authorize(jda:JDA,guild:String,user:String):String {
        check(jda===current() && config().enabled) {"Discord 연결이 변경되었거나 꺼졌습니다."}
        val g=jda.getGuildById(guild) ?: error("Discord 서버에 연결되지 않았습니다.")
        val member=g.retrieveMemberById(user).useCache(false).complete()
        check(config().superAdmins.any {it.guildId==guild && member.roles.any {role->role.id==it.roleId}}) {"현재 Discord 최고 관리자 역할이 필요합니다."}
        return identity.resolveDiscord(user,guild) ?: error("웹 AI 설정에서 연결 코드를 발급받아 /agent link code:코드 로 소유자 계정을 연결하세요.")
    }
    private fun privateAccess(jda:JDA,session:ProjectAgentChannel) {
        authorize(jda,session.guild,session.user)
        val channel=jda.getTextChannelById(session.channel) ?: error("프로젝트 채널이 없습니다.")
        check(channel.guild.id==session.guild && !channel.guild.publicRole.hasPermission(channel,Permission.VIEW_CHANNEL)) {"프로젝트 채널이 공개되었습니다. 권한을 다시 설정하세요."}
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
                val actor=authorize(event.jda,guild.id,event.user.id)
                val embed=EmbedBuilder().setAuthor(event.user.effectiveName,null,event.user.effectiveAvatarUrl)
                when(event.subcommandName) {
                    "setting"->{
                        val value=if(event.options.isEmpty())store.preferences(guild.id,event.user.id) else store.settings(guild.id,event.user.id,event.getOption("default")?.asString,event.getOption("think")?.asBoolean,event.getOption("explicit_permission")?.asBoolean)
                        if(event.options.isNotEmpty())engine.settingsChanged(guild.id,event.user.id)
                        embed.setDescription("${event.user.asMention} 님의 기본 에이전트: Qwen ${value.model ?: qwen.config().model} · Thinking ${value.think}\n"+
                            if(value.explicitPermission)"명시적 승인: 파일 변경·명령 실행 전 동의가 필요합니다." else "자동 명령 위험판단을 사용합니다. 낮은 위험으로 허용된 요청만 자동 실행하며, 위험·불확실한 명령은 동의를 요청하고 거부 판정은 차단합니다. 모델의 위험 판단은 안전 보증이 아닙니다. 명령은 관리 앱 사용자 권한으로 전체 파일·네트워크에 접근할 수 있습니다.")
                        hook.editOriginalEmbeds(embed.build()).setAllowedMentions(emptyList()).queue({}, {})
                    }
                    "project"->{val project=store.register(guild.id,event.user.id,event.getOption("name")!!.asString,Path.of(event.getOption("path")!!.asString));hook.editOriginalEmbeds(embed.setDescription("프로젝트 `${project.name}` 등록: `${project.path}`\n이 경로는 기본 작업 위치이며 접근 범위를 제한하지 않습니다.").build()).queue({}, {})}
                    "channel"->{
                        val source=event.channel as? TextChannel ?: error("일반 텍스트 채널에서 실행하세요.")
                        val projectName=event.getOption("project")?.asString
                        val project=projectName?.let {name->store.projects(guild.id,event.user.id).find {it.name==name} ?: roots(actor).find {it.id==name || it.name==name}?.let {ProjectAgentProject(it.id,it.path)} ?: error("등록한 프로젝트가 없습니다. /agent project로 등록하거나 프로젝트 생성 작업을 요청하세요.")}
                        val home=project?.let {Path.of(it.path).toRealPath()} ?: Path.of(System.getProperty("user.home"),"ServerManagerProjects").createDirectories().toRealPath()
                        val name=("qwen-"+(project?.name ?: event.user.name)).lowercase().replace(Regex("[^\\p{L}\\p{N}_-]"),"-").take(90)
                        val action=ProjectAgentChannels.create(guild,source.parentCategory,event.user.idLong,name)
                        val created=action.complete()
                        try {store.addChannel(ProjectAgentChannel(guild.id,created.id,event.user.id,project?.name,home.toString()))}catch(e:Exception){created.delete().queue({}, {});throw e}
                        val natural=if(net.dv8tion.jda.api.requests.GatewayIntent.MESSAGE_CONTENT in event.jda.gatewayIntents)"자연어로 요청하거나 `/agent run`을 사용하세요." else "현재 봇의 메시지 콘텐츠 권한이 없어 `/agent run`을 사용해야 합니다. 자연어 대화는 Discord Developer Portal의 Message Content Intent를 허용한 뒤 봇을 다시 연결하세요."
                        created.sendMessage("Qwen 프로젝트 채널입니다. 홈: `$home` · 도구 실행: 관리 앱 컴퓨터\nThinking 기본 켜짐 · 명시적 승인 기본 켜짐. 대화·결과는 이 채널을 볼 수 있는 사람에게 보입니다. Discord 서버 관리자는 비공개 채널에도 접근할 수 있습니다. $natural").setAllowedMentions(emptyList()).queue({}, {})
                        hook.editOriginalEmbeds(embed.setDescription("${event.user.asMention} 님의 ${project?.name ?: "기본"} 채널이 ${created.asMention}에 할당되었습니다.").build()).setAllowedMentions(emptyList()).queue({}, {})
                    }
                    "stop"->{engine.stop(event.channel.id,event.user.id);hook.editOriginal("현재 작업과 대기 승인을 중단했습니다.").queue({}, {})}
                    "run"->{
                        val session=store.channel(event.channel.id) ?: error("/agent channel로 만든 채널에서 실행하세요.")
                        check(session.user==event.user.id);privateAccess(event.jda,session)
                        hook.editOriginal("${event.channel.asMention}에서 시작합니다. 결과는 채널 메시지에 표시됩니다.").queue({}, {})
                        start(event.jda,session,event.member?.effectiveName ?: event.user.effectiveName,event.getOption("prompt")!!.asString,event.getOption("model")?.asString,event.getOption("think")?.asBoolean,false)
                    }
                    else->error("/agent setting, channel, project, run, link, stop을 사용하세요.")
                }
            }catch(e:Exception){hook.editOriginal("요청 실패: ${safe(e)}").setAllowedMentions(emptyList()).queue({}, {})}
        }},{})
    }
    fun message(event:MessageReceivedEvent) {
        if(event.jda!==current() || event.author.isBot || event.isWebhookMessage || !event.isFromGuild)return
        val session=store.channel(event.channel.id) ?: return
        if(session.user!=event.author.id || event.message.contentRaw.isBlank())return
        if(!routing.add(session.channel)){event.channel.sendMessage("이 채널에서 작업 중입니다. 승인 요청을 처리하거나 /agent stop으로 중단한 뒤 다시 요청하세요.").setAllowedMentions(emptyList()).queue({}, {});return}
        worker.submit {try {privateAccess(event.jda,session);start(event.jda,session,event.member?.effectiveName ?: event.author.effectiveName,event.message.contentRaw.take(6000),null,null,true)}catch(e:Exception){event.channel.sendMessage("에이전트 요청 실패: ${safe(e)}").setAllowedMentions(emptyList()).queue({}, {})}finally {routing.remove(session.channel)}}
    }
    fun button(event:ButtonInteractionEvent) {
        if(event.jda!==current() || !event.componentId.startsWith("qa:"))return
        event.deferEdit().queue({hook->worker.submit {try {
            val parts=event.componentId.split(':');check(parts.size==3)
            val session=store.channel(event.channel.id) ?: error("채널이 해제되었습니다.")
            check(session.user==event.user.id) {"요청한 채널 소유자만 승인할 수 있습니다."}
            engine.approve(event.channel.id,event.user.id,parts[2],parts[1]=="allow") {privateAccess(event.jda,session)}
            hook.editOriginalComponents(emptyList()).queue({}, {})
        }catch(e:Exception){hook.sendMessage("승인 실패: ${safe(e)}").setEphemeral(true).queue({}, {})}}},{})
    }
    private fun start(jda:JDA,session:ProjectAgentChannel,userName:String,prompt:String,modelOverride:String?,thinkOverride:Boolean?,route:Boolean) {
        val channel=jda.getTextChannelById(session.channel) ?: error("채널이 없습니다.")
        val saved=store.preferences(session.guild,session.user);val preferences=saved.copy(think=thinkOverride ?: saved.think)
        val chosen=qwen.config();val model=modelOverride ?: preferences.model ?: chosen.model
        val inference=ProjectAgentInference {messages,think,progress->val response=QwenRequests.conversation(model,messages,think,chosen.runnerId,progress).answer;check(!response.limited) {"Qwen 출력 한도에 도달했습니다. 요청을 작게 나눠주세요."};response.text}
        if(route && !engine.route(prompt,inference,userName,session.user))return
        val main=channel.sendMessageEmbeds(EmbedBuilder().setTitle("Qwen $model · 시작").setDescription(prompt.take(1800)).build()).setAllowedMentions(emptyList()).complete()
        val events=DiscordProjectAgentDisplay(channel,main,prompt,model)
        val actor=authorize(jda,session.guild,session.user);var lastCheck=0L
        fun active() {
            check(jda===current() && config().enabled && identity.resolveDiscord(session.user,session.guild)==actor) {"소유자 연결 또는 Discord 연결이 철회되었습니다."}
            if(System.nanoTime()-lastCheck>java.util.concurrent.TimeUnit.SECONDS.toNanos(3)){privateAccess(jda,session);lastCheck=System.nanoTime()}
        }
        try {engine.run(session.channel,session.user,userName,prompt,preferences,inference,::active,events::emit)}catch(e:Exception){events.emit(ProjectAgentEvent("done",text="작업 중단: ${safe(e)}"))}
    }
    private fun safe(e:Exception)=if(e is IllegalArgumentException || e is IllegalStateException)e.message.orEmpty().take(1200) else "연결·실행 오류입니다. 봇 권한과 실행 로그를 확인하세요."
    override fun close(){engine.close();worker.shutdownNow()}
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

/** Model output edits the channel's main reply; verbose tool data belongs in a turn thread, never the model context. */
private class DiscordProjectAgentDisplay(private val channel:TextChannel,private val main:Message,private val request:String,private val model:String) {
    private val mapper=jacksonObjectMapper();private val turns=mutableMapOf<Int,Message>();private val threads=mutableMapOf<Int,ThreadChannel>()
    private val activity=ArrayDeque<String>();private var response="";private var lastThinking=0L
    private fun render(status:String,seconds:Double=0.0) {
        val card=EmbedBuilder().setTitle("Qwen $model · $status").setDescription((response.ifBlank {request}).take(3500))
        if(activity.isNotEmpty())card.addField("이번 요청에서 읽은 파일·실행한 도구",activity.joinToString("\n").takeLast(1000),false)
        if(seconds>0)card.setFooter("요청 소요 ${"%.1f".format(java.util.Locale.ROOT,seconds)}초")
        main.editMessageEmbeds(card.build()).setAllowedMentions(emptyList()).complete()
    }
    fun emit(event:ProjectAgentEvent) {
        when(event.kind) {
            "thinking"->{if(System.nanoTime()-lastThinking>java.util.concurrent.TimeUnit.SECONDS.toNanos(2)){response=event.text;render("턴 ${event.turn} · Thinking");lastThinking=System.nanoTime()}}
            "message"->{response=event.text;render("턴 ${event.turn} · 모델 응답 · ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초")}
            "reviewing"->render("턴 ${event.turn} · 독립 위험 검토")
            "approval","tool_start","denied"->{
                val plan=event.plan!!
                val card=EmbedBuilder().setTitle("턴 ${event.turn} : ${plan.tool.name}").setDescription(plan.description.take(2800))
                    .addField("실행 컴퓨터·폴더","관리 앱 OS 사용자\n${plan.cwd}".take(1000),false)
                    .addField("프로젝트 경계", "코드 검사: ${plan.scope}\n실제 대상: ${plan.resolvedTargets.joinToString()}\n감시 Qwen: ${event.risk?.projectScope ?: "UNKNOWN"}".take(1000),false)
                    .addField("독립 검토",event.risk?.let {"${it.action} · ${it.risk}\n${it.reason}"}.orEmpty().take(1000),false)
                val actions=if(event.kind=="approval")listOf(ActionRow.of(Button.success("qa:allow:${event.token}","이 요청 허용"),Button.danger("qa:deny:${event.token}","거절"))) else emptyList()
                val message=turns[event.turn]?.also {it.editMessageEmbeds(card.build()).setComponents(actions).complete()} ?: channel.sendMessageEmbeds(card.build()).setComponents(actions).setAllowedMentions(emptyList()).complete().also {turns[event.turn]=it}
                if(event.kind=="approval")message.replyFiles(FileUpload.fromData(mapper.writeValueAsBytes(plan),"approval-exact-request.json")).setAllowedMentions(emptyList()).complete()
                if(event.kind=="tool_start") {
                    activity.addLast("${plan.tool.name}: "+plan.tool.arguments.path("path").asText(plan.cwd));while(activity.size>8)activity.removeFirst()
                    if(!plan.readOnly) {val thread=message.createThreadChannel("턴 ${event.turn} : ${plan.tool.name}".take(95)).complete();threads[event.turn]=thread;thread.sendFiles(FileUpload.fromData(mapper.writeValueAsBytes(plan),"tool-input.json")).complete()}
                    render("턴 ${event.turn} · 도구 실행")
                }
            }
            "tool_end"->{
                val detail=if(event.plan!!.readOnly)"조회: ${event.plan.resolvedTargets.joinToString()}" else event.text.take(3500)
                turns[event.turn]?.editMessageEmbeds(EmbedBuilder().setTitle("턴 ${event.turn} : ${event.plan.tool.name} · ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초").setDescription(detail).build())?.setComponents(emptyList())?.complete()
                threads[event.turn]?.sendFiles(FileUpload.fromData(event.output.orEmpty().toByteArray(Charsets.UTF_8),"tool-result.txt"))?.complete()
                render("턴 ${event.turn} · 도구 완료")
            }
            "done"->{response=event.text;render("종료",event.seconds);turns.values.forEach {it.editMessageComponents(emptyList()).queue({}, {})}}
        }
    }
}
