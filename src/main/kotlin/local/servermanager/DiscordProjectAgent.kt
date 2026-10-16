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
    private val jobs=ProjectAgentJobs()
    private val engine=ProjectAgentEngine(store,{session->ProjectAgentTools(directory.resolve("project-agent-logs"),DiscordProjectAgentFiles({current()?.getTextChannelById(session.channel) ?: error("채널이 없습니다.")},{privateAccess(current() ?: error("연결 없음"),session)})) {name,path->store.register(session.guild,session.user,name,path)}})
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
                SubcommandData("context","채널 문맥 조회·초기화·자동 압축 설정").addOptions(
                    OptionData(OptionType.STRING,"action","문맥 작업",true).addChoice("상태","status").addChoice("초기화","reset").addChoice("압축 기준","configure").addChoice("지금 압축","compact"),
                    OptionData(OptionType.INTEGER,"limit","자동 압축 입력 토큰 기준 (1024~3584)",false).setRequiredRange(1024,3584)),
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
                        if(event.options.isNotEmpty()){engine.settingsChanged(guild.id,event.user.id);jobs.stopUser(event.user.id)}
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
                    "context"->{
                        var session=store.channel(event.channel.id) ?: error("에이전트 채널에서 사용하세요.")
                        check(session.user==event.user.id);privateAccess(event.jda,session)
                        val action=event.getOption("action")!!.asString
                        if(action!="status"){
                            runCatching {jobs.stop(session.channel,session.user)};engine.stop(session.channel,session.user)
                            session=store.context(session.channel,session.user,action=="reset",if(action=="configure")event.getOption("limit")?.asLong?.toInt() ?: error("limit을 입력하세요.")else null)
                            if(action=="compact"){
                                val job=jobs.begin(session.channel,session.user)
                                try {val compact=ProjectAgentMemory.compress(session.history,inference(session));privateAccess(event.jda,session);store.history(session.channel,compact,session.contextEpoch)}finally{jobs.finish(session.channel,job)}
                                session=store.channel(session.channel)!!
                            }
                        }
                        val llm=inference(session);val maximum=llm.modelLimit()
                        hook.editOriginal("${if(action=="reset")"문맥을 초기화했습니다. 프로젝트 정보는 유지됩니다.\n" else ""}문맥 약 ${session.history.sumOf {ProjectAgentContext.estimatedTokens(it.content)}} tokens (대화 기록 추정) · 압축 기준 ${session.contextLimit}\n실행 창 8192 · 출력 예약 4096 · 모델 최대 ${maximum?.toString() ?: "확인 불가"}").queue({}, {})
                    }
                    "stop"->{jobs.stop(event.channel.id,event.user.id);engine.stop(event.channel.id,event.user.id);hook.editOriginal("현재 작업과 대기 승인을 중단했습니다.").queue({}, {})}
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
        if(session.user!=event.author.id || (event.message.contentRaw.isBlank() && event.message.attachments.isEmpty()))return
        if(!routing.add(session.channel)){event.channel.sendMessage("이 채널에서 작업 중입니다. 승인 요청을 처리하거나 /agent stop으로 중단한 뒤 다시 요청하세요.").setAllowedMentions(emptyList()).queue({}, {});return}
        worker.submit {try {privateAccess(event.jda,session);start(event.jda,session,event.member?.effectiveName ?: event.author.effectiveName,(event.message.contentRaw+if(event.message.attachments.isEmpty())"" else "\n첨부 파일: "+event.message.attachments.joinToString {"${it.fileName} (message=${event.messageId}, attachment=${it.id})"}).take(6000),null,null,true)}catch(e:Exception){event.channel.sendMessage("에이전트 요청 실패: ${safe(e)}").setAllowedMentions(emptyList()).queue({}, {})}finally {routing.remove(session.channel)}}
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
    private fun start(jda:JDA,session:ProjectAgentChannel,userName:String,prompt:String,modelOverride:String?,thinkOverride:Boolean?,route:Boolean) {
        val job=jobs.begin(session.channel,session.user)
        try {
        val channel=jda.getTextChannelById(session.channel) ?: error("채널이 없습니다.")
        check(store.channel(session.channel)?.contextEpoch==session.contextEpoch) {"문맥 설정이 바뀌었습니다. 다시 요청하세요."}
        val saved=store.preferences(session.guild,session.user);val preferences=saved.copy(think=thinkOverride ?: saved.think)
        val chosen=qwen.config();val model=modelOverride ?: preferences.model ?: chosen.model
        val inference=inference(session,model)
        if(route && !engine.route(prompt,inference,userName,session.user,session.history))return
        val actor=authorize(jda,session.guild,session.user)
        check(!job.cancelled.get() && !Thread.currentThread().isInterrupted) {"작업을 중단했습니다."}
        val main=channel.sendMessageEmbeds(EmbedBuilder().setTitle("Qwen $model · 시작").setDescription(prompt.take(1800)).build()).setComponents(ActionRow.of(Button.danger("qa:stop:${job.id}","에이전트 중지"))).setAllowedMentions(emptyList()).complete()
        val events=DiscordProjectAgentDisplay(channel,main,prompt,model,job.id)
        var lastCheck=0L
        fun active() {
            check(!job.cancelled.get() && store.channel(session.channel)?.contextEpoch==session.contextEpoch) {"사용자가 작업을 중단했습니다."}
            check(jda===current() && config().enabled && identity.resolveDiscord(session.user,session.guild)==actor) {"소유자 연결 또는 Discord 연결이 철회되었습니다."}
            if(System.nanoTime()-lastCheck>java.util.concurrent.TimeUnit.SECONDS.toNanos(3)){privateAccess(jda,session);lastCheck=System.nanoTime()}
        }
        try {engine.run(session.channel,session.user,userName,prompt,preferences,inference,::active,events::emit)}catch(e:Exception){events.emit(ProjectAgentEvent("done",text=if(job.cancelled.get())"작업 중단: 사용자가 중지했습니다." else "작업 중단: ${safe(e)}"))}
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
                check(!response.limited) {"Qwen 출력 한도에 도달했습니다. 문맥을 압축하거나 요청을 나눠주세요."};return response.text
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
internal class DiscordProjectAgentDisplay(private val channel:TextChannel,private val main:Message,private val request:String,private val model:String,private val jobId:String,private val editIntervalMillis:Long=750) {
    private val turns=mutableMapOf<Int,Message>();private val threads=mutableMapOf<Int,ThreadChannel>()
    private val replies=mutableMapOf<Int,DiscordAgentText>();private val logs=mutableMapOf<Int,Pair<StringBuilder,DiscordAgentText>>()
    private var usage:QwenUsage?=null;private var finished=false
    private val edits=CoalescedMessageEdits<Pair<net.dv8tion.jda.api.entities.MessageEmbed,Boolean>>({snapshot,complete->
        val buttons=if(snapshot.second)emptyList() else listOf(ActionRow.of(Button.danger("qa:stop:$jobId","에이전트 중지")))
        main.editMessageEmbeds(snapshot.first).setComponents(buttons).setAllowedMentions(emptyList()).queue({complete()},{complete()})
    },intervalMillis=editIntervalMillis)
    private fun footer()=usage?.let {"문맥 약 ${it.estimatedContext}/8192 · 입력 ${it.input ?: "미수신"} · 출력 ${it.output ?: "미수신"} · 모델 최대 ${it.modelContext ?: "확인 불가"} · 보조 수신 ${it.helperCalls}회 입력 ${it.helperInput}/출력 ${it.helperOutput}"} ?: "입력·출력 토큰 대기"
    private fun render(status:String,done:Boolean=false){edits.offer(EmbedBuilder().setTitle("Qwen $model · $status").setFooter(footer()).build() to done,done)}
    private fun reply(turn:Int)=replies.getOrPut(turn){DiscordAgentText(channel,editIntervalMillis)}
    @Synchronized fun emit(event:ProjectAgentEvent) {
        if(finished)return
        when(event.kind){
            "usage"->{usage=event.usage;render("턴 ${event.turn} · 결과 확인")}
            "status"->render("턴 ${event.turn} · ${event.text}")
            "thinking"->{usage=event.usage;render("턴 ${event.turn} · 모델 응답 대기")}
            "stream"->{if(event.text.isNotBlank())reply(event.turn).offer(event.text) else if(!event.output.isNullOrBlank())reply(event.turn).offer("‘${event.output.takeLast(1500)}’")}
            "message"->{reply(event.turn).offer(event.text);render("턴 ${event.turn} · 모델 응답")}
            "reviewing"->render("턴 ${event.turn} · 독립 위험 검토")
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
                    render("턴 ${event.turn} · 도구 실행")
                }
            }
            "tool_output"->logs[event.turn]?.let {(text,writer)->text.append(event.text);writer.offer(text.toString())}
            "tool_end"->{
                logs[event.turn]?.let {(text,writer)->writer.offer(text.toString().ifBlank {"출력 없음"},true)}
                turns[event.turn]?.editMessageEmbeds(EmbedBuilder().setTitle("턴 ${event.turn} : ${event.plan!!.tool.name} · ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초").setDescription(event.text.take(3500)).setFooter(footer()).build())?.setComponents(emptyList())?.queue({}, {})
                render("턴 ${event.turn} · 결과 검토 후 다음 단계")
            }
            "done"->{reply(event.turn).offer(event.text,true);finished=true;render("${if(event.text.startsWith("작업 중단"))"중단" else "종료"} · ${"%.1f".format(java.util.Locale.ROOT,event.seconds)}초",true);turns.values.forEach {it.editMessageComponents(emptyList()).queue({}, {})}}
        }
    }
}
