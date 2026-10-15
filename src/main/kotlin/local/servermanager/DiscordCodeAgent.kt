package local.servermanager

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import java.nio.file.Path
import kotlin.io.path.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.Color
import java.time.Instant
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel

object AgentPresentation {
    fun fileNames(input:String)=input.split(',').map {it.trim().removePrefix("/")}.filter {it.isNotBlank()}
    fun startFailure(stage:String,error:Exception):String = if(error is IllegalArgumentException || error is IllegalStateException)
        "작업을 시작하지 못했습니다: ${error.message.orEmpty().take(1000)}"
        else "$stage 단계에서 실패했습니다. 봇의 채널 보기·메시지 전송·공개 스레드 생성·스레드 메시지 전송 권한과 연결 상태를 확인하세요."
    fun threadName(agent:String,goal:String):String = "$agent · $goal".replace(Regex("[\\p{Cntrl}\\s]+")," ").trim().take(95).trimEnd {Character.isHighSurrogate(it)}
    fun heading(event:AgentEvent):String {
        val label=when(event.kind) {"call_start"->"▶ 호출 시작";"call_end"->"■ 호출 종료";"task_start"->"▶ 작업 시작";"task_end"->"■ 작업 종료";"tool_start"->"▶ 도구 실행 시작";"tool_end"->"■ 도구 실행 종료";"quota"->"API 키·한도";"reasoning"->"추론 요약";"merge","task_merge"->"병합·원본 반영";"delegate"->"서브 작업 분배";"model"->"모델 출력";"tool"->"도구 요청";"output"->"실제 실행 결과";"summary"->"작업 결과";else->"진행"}
        return event.call?.let {"호출 #$it · $label"} ?: label
    }
}

private class DiscordAgentRun(val message:Message,val thread:ThreadChannel,val card:EmbedBuilder,val started:Instant)

/** Main and direct worker commands share presentation and project quota tracking. */
class DiscordCodeAgent(private val config:()->DiscordConfig,private val current:()->JDA?,private val storedKey:()->String?={null},private val settingsDirectory:Path?=null):AutoCloseable {
    private val worker=Executors.newFixedThreadPool(4)
    private val mainBusy=AtomicBoolean()
    private val localBusy=AtomicBoolean()
    private val subSlots=java.util.concurrent.Semaphore(3)
    private val key:String get()=System.getenv("GEMINI_API_KEY")?.takeIf {it.isNotBlank()} ?: storedKey().orEmpty()
    private val poolStore=settingsDirectory?.let {GeminiKeyPoolStore(it,{key.takeIf {value->value.isNotBlank()}})}
    private val mainSettings=settingsDirectory?.let {MainAgentSettings(it)}
    private val workspace=System.getenv("LUONNOTAR_AGENT_WORKSPACE")?.takeIf {it.isNotBlank()} ?: settingsDirectory?.resolve("workspace.txt")?.takeIf {it.isRegularFile()}?.readText()?.trim().orEmpty()
    private val testsEnabled=System.getenv("LUONNOTAR_AGENT_TESTS")=="true" || settingsDirectory?.resolve("tests-enabled")?.takeIf {it.isRegularFile()}?.readText()?.trim()=="true"
    private val model=System.getenv("LUONNOTAR_AGENT_MODEL")?.takeIf {it.isNotBlank()} ?: "gemini-3.8-flash"
    private val quota=poolStore?.let {GeminiQuotaTracker(it,model)}
    fun register(jda:JDA,guildId:String) {
        val guild=jda.getGuildById(guildId) ?: return
        fun command(name:String,main:Boolean):net.dv8tion.jda.api.interactions.commands.build.SlashCommandData {
            val command=Commands.slash(name,if(main)"GPT 메인 작업 · 서브에이전트 분배·병합" else "Gemini 서브에이전트 직접 실행")
                .addOptions(OptionData(OptionType.STRING,"task","작업 목표와 완료 조건",true).setMaxLength(3000),
                    OptionData(OptionType.STRING,"files",if(main)"선택: 파일 범위 제한 (생략하면 코드 전체)" else "파일 경로, 쉼표 구분 (새 파일도 가능)",!main).setMaxLength(1500),
                    OptionData(OptionType.BOOLEAN,"edit","코드 수정 및 검증 후 원본 반영 허용",false),
                    OptionData(OptionType.BOOLEAN,"build-files","Gradle·package.json 등 빌드 설정 수정 허용",false),
                    OptionData(OptionType.STRING,"agent","스레드에 표시할 에이전트 이름",false).setMaxLength(60))
            if(main)command.addOptions(OptionData(OptionType.STRING,"model","OpenAI 모델 ID (생략하면 웹 설정)",false).setMaxLength(80),
                OptionData(OptionType.STRING,"reasoning","추론 강도 (모델별 지원 범위가 다름)",false).apply {listOf("auto","none","low","medium","high","xhigh","max").forEach {addChoice(it,it)}},
                OptionData(OptionType.INTEGER,"parallel","동시 서브에이전트 수 (1~3)",false).setMinValue(1).setMaxValue(3))
            return command
        }
        guild.upsertCommand(Commands.slash("local-llm","로컬 Qwen 일반 대화 (코드·도구 접근 없음)")
            .addOptions(OptionData(OptionType.STRING,"model","Qwen 모델",true).addChoice("Qwen3 14B","14b").addChoice("Qwen3 30B","30b"),
                OptionData(OptionType.STRING,"prompt","질문 또는 요청 (매번 독립 대화)",true).setMaxLength(6000),
                OptionData(OptionType.BOOLEAN,"think","사고 출력 요청 (기본 꺼짐)",false))).queue({}, {})
        guild.upsertCommand(command("agent",true)).queue({}, {})
        guild.upsertCommand(command("sub-agent",false)).queue({}, {})
        guild.upsertCommand(Commands.slash("agent-keys","프로젝트별 Gemini API 사용량 확인 (최고 관리자)")).queue({}, {})
    }
    private fun redact(text:String):String=mainSettings?.redact(poolStore?.redact(text) ?: text) ?: (poolStore?.redact(text) ?: text)
    private fun gemini(notify:(String)->Unit):AgentModel=if(poolStore!=null && quota!=null)PooledGeminiModel(poolStore,model,quota,notify) else GeminiAgentModel(key,model)
    fun handle(event:SlashCommandInteractionEvent) {
        if(event.jda!==current() || event.name !in setOf("agent","sub-agent","agent-keys","local-llm"))return
        val guild=event.guild;val channel=event.channel as? TextChannel
        if(guild==null || channel==null) {event.reply("일반 텍스트 채널에서 실행하세요.").setEphemeral(true).queue({}, {});return}
        event.deferReply(true).queue({hook ->
            guild.retrieveMemberById(event.user.id).useCache(false).queue({member ->
                val settings=config();val roles=member.roles.map {it.id}.toSet()
                if(event.jda!==current() || !settings.enabled || settings.superAdmins.none {it.guildId==guild.id && it.roleId in roles}) {
                    hook.editOriginal("설정된 Discord 최고 관리자 역할만 실행할 수 있습니다.").queue({}, {});return@queue
                }
                if(event.name=="agent-keys") {
                    val status=runCatching {quota?.status(poolStore!!.configs()) ?: "API 키 풀 설정이 없습니다."}.getOrElse {"키 설정이나 사용량을 읽지 못했습니다."}
                    hook.editOriginal(status.take(1800)+"\n이 봇이 기록한 사용량입니다.").setAllowedMentions(emptyList()).queue({}, {});return@queue
                }
                if(event.name=="local-llm") {
                    if(!localBusy.compareAndSet(false,true)) {hook.editOriginal("다른 로컬 모델 요청이 진행 중입니다. 완료 후 요청하세요.").queue({}, {});return@queue}
                    try {worker.submit {
                        try {
                            val size=event.getOption("model")!!.asString
                            val prompt=event.getOption("prompt")!!.asString;val think=event.getOption("think")?.asBoolean==true
                            val remote=RemoteLlm.chat(size,prompt,think)
                            val answer=remote?.answer ?: LocalLlm().chat(size,prompt,think)
                            val where=remote?.let {" · 실행기 ${it.runnerName.replace(Regex("[\\p{Cntrl}]")," ").take(40)}"} ?: ""
                            if(event.jda===current()) {
                                val info="Qwen3 $size$where · ${"%.1f".format(java.util.Locale.ROOT,answer.seconds)}초 · 출력 ${answer.tokens}토큰"+(if(answer.limited)" · 출력 한도 도달: 답변이 미완성일 수 있습니다." else "")
                                if(answer.text.length<=1600)hook.editOriginal("$info\n\n${answer.text}").setAllowedMentions(emptyList()).complete()
                                else hook.editOriginal(info+"\n전체 답변은 첨부 파일에 있습니다.").setAllowedMentions(emptyList())
                                    .setFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(answer.text.toByteArray(Charsets.UTF_8),"qwen-answer.txt")).complete()
                            }
                        } catch(_:Exception) {hook.editOriginal("Qwen 요청을 완료하지 못했습니다. Qwen을 실행하는 컴퓨터(실행기 또는 관리 앱)의 Ollama 실행·모델 태그·메모리와 3분 제한을 확인하세요.").setAllowedMentions(emptyList()).queue({}, {})}
                        finally {localBusy.set(false)}
                    }} catch(_:Exception) {localBusy.set(false);hook.editOriginal("실행기가 종료되었습니다.").queue({}, {})}
                    return@queue
                }
                val main=event.name=="agent"
                if(workspace.isBlank()) {hook.editOriginal("에이전트 작업 폴더를 먼저 설정하세요.").queue({}, {});return@queue}
                if(main && mainSettings?.key().isNullOrBlank()) {hook.editOriginal("계정과 보안 → GPT 메인 에이전트에서 OpenAI API 키를 설정하세요. Gemini 키와 별도입니다.").queue({}, {});return@queue}
                if(!main && runCatching {poolStore?.credentials()?.isEmpty() ?: key.isBlank()}.getOrDefault(true)) {hook.editOriginal("Gemini API 키를 먼저 설정하세요.").queue({}, {});return@queue}
                val accepted=if(main)mainBusy.compareAndSet(false,true) else subSlots.tryAcquire()
                if(!accepted) {hook.editOriginal(if(main)"다른 GPT 메인 작업이 진행 중입니다." else "서브에이전트 3개가 실행 중입니다. 완료 후 요청하세요.").queue({}, {});return@queue}
                try {worker.submit {
                    var starter:Message?=null;var stage="파일 검증"
                    try {
                        val task=event.getOption("task")!!.asString
                        val files=event.getOption("files")?.asString?.let {AgentPresentation.fileNames(it)} ?: emptyList()
                        val editable=event.getOption("edit")?.asBoolean==true
                        val buildFiles=event.getOption("build-files")?.asBoolean==true
                        val agent=event.getOption("agent")?.asString?.trim()?.takeIf {it.isNotBlank()} ?: if(main)"GPT 메인" else "Gemini 서브에이전트"
                        val selected=mainSettings?.config()?.let {it.copy(model=event.getOption("model")?.asString ?: it.model,reasoning=event.getOption("reasoning")?.asString ?: it.reasoning,parallelism=event.getOption("parallel")?.asInt ?: it.parallelism)}
                        if(main)mainSettings!!.validate(selected!!)
                        if(!main)AgentWorkspace(Path.of(workspace),files,editable,testsEnabled,editable,buildFiles)
                        val chosenModel=if(main)selected!!.model else model
                        val card=EmbedBuilder().setTitle("$agent · 진행 중").setDescription(task.take(3000)).setColor(Color(0x5f8bb5))
                            .addField("모델",chosenModel,true).addField("모드",if(editable)"수정 허용" else "조사",true)
                            .addField("파일 범위",files.joinToString("\n").ifBlank {"코드 전체"}.take(1000),false).setFooter("작업 ${event.id}").setTimestamp(Instant.now())
                        stage="공개 작업 메시지 생성"
                        val parent=channel.sendMessageEmbeds(card.build()).setAllowedMentions(emptyList()).complete();starter=parent
                        stage="공개 스레드 생성"
                        val thread=parent.createThreadChannel(AgentPresentation.threadName(agent,task)).complete()
                        hook.editOriginal("작업 메시지: ${parent.jumpUrl}\n스레드: ${thread.asMention}").complete()
                        var integrationRun:DiscordAgentRun?=null
                        fun post(target:ThreadChannel,event:AgentEvent) {
                            val safe=redact(event.text).replace("```","''' ")
                            target.sendMessage("**${AgentPresentation.heading(event)}** · <t:${Instant.now().epochSecond}:T>\n${safe.take(1800)}").setAllowedMentions(emptyList()).complete()
                        }
                        fun fork(name:String,goal:String,scope:String):DiscordAgentRun {
                            val started=Instant.now()
                            val runCard=EmbedBuilder().setTitle("$name · 실행 중").setDescription(goal.take(2000)).setColor(Color(0x7f75bb))
                                .addField("메인 작업",parent.jumpUrl,false).addField("실행 구역",scope.take(1000),false)
                                .addField("시작", "<t:${started.epochSecond}:F>",false).setTimestamp(started)
                            val message=channel.sendMessageEmbeds(runCard.build()).setAllowedMentions(emptyList()).complete()
                            val created=message.createThreadChannel(AgentPresentation.threadName(name,goal)).complete()
                            post(thread,AgentEvent("delegate","$name → ${created.asMention}\n실행 구역: $scope"))
                            post(created,AgentEvent("status","메인 스레드: ${thread.asMention}\n실행 구역: $scope"))
                            return DiscordAgentRun(message,created,runCard,started)
                        }
                        fun finish(run:DiscordAgentRun,name:String,state:String,success:Boolean) {
                            val ended=Instant.now()
                            run.message.editMessageEmbeds(run.card.setTitle("$name · $state").setColor(Color(if(success)0x4caf83 else 0xd85c55))
                                .addField("종료", "<t:${ended.epochSecond}:F> · ${java.time.Duration.between(run.started,ended).seconds}초",false).build()).complete()
                        }
                        fun send(event:AgentEvent) {
                            if(event.zone=="integration") {
                                val run=integrationRun ?: fork("통합",task,"통합 체크아웃 · 병합·충돌 처리·최종 테스트·원본 반영").also {integrationRun=it}
                                post(run.thread,event)
                            } else post(thread,event)
                        }
                        var successful=false
                        try {
                            val runs=Path.of(workspace).resolve("build/agent-runs")
                            if(main) {
                                val childThreads=java.util.concurrent.ConcurrentHashMap<String,DiscordAgentRun>()
                                val result=MainAgentCoordinator(OpenAIResponsesModel(mainSettings!!.key()!!),{_,sink ->
                                    val provider=gemini {sink(AgentEvent("quota",it))}
                                    AgentModel {req ->subSlots.acquire();try {provider.generate(req)} finally {subSlots.release()}}
                                },selected!!,Path.of(workspace),runs,editable,testsEnabled,buildFiles,files,::send,{child,e ->
                                    val childRun=childThreads.computeIfAbsent(child.id) {
                                        fork("${child.name} (${child.id})",child.goal,child.files.joinToString("\n"))
                                    }
                                    post(childRun.thread,e)
                                    if(e.kind=="task_end") {
                                        val state=if(child.state=="COMPLETED")if(editable)"수정 완료 · 병합 대기" else "조사 완료" else child.state
                                        finish(childRun,child.name,state,child.state=="COMPLETED")
                                        post(thread,AgentEvent("status","${child.name} (${child.id}) · $state → ${childRun.thread.asMention}"))
                                    }
                                    if(e.kind=="task_merge") {
                                        val state=if(child.merged)"병합 완료" else if(child.state=="CONFLICT")"병합 충돌" else "제외됨"
                                        childRun.message.editMessageEmbeds(childRun.card.setTitle("${child.name} · $state")
                                            .setColor(Color(if(child.merged)0x4caf83 else 0xd85c55)).build()).complete()
                                    }
                                }).run(task)
                                successful=result.completed
                                integrationRun?.let {finish(it,"통합",if(successful)"완료" else "미완료",successful)}
                                send(AgentEvent("summary","${if(successful)"완료" else "미완료"} · GPT 호출 ${result.calls} · 토큰 ${result.tokens}\n원본 반영: ${result.published.joinToString().ifBlank {"없음"}}\n작업 보관: ${result.artifact}"))
                            } else {
                                val project=IsolatedAgentProject.create(Path.of(workspace),runs)
                                val scope=AgentWorkspace(project.integration,files,editable,testsEnabled,editable,buildFiles)
                                var call:Int?=null
                                val result=CodeSubagent(gemini {send(AgentEvent("quota",it,call))}).run(task,files,scope) {e->call=e.call;send(e)}
                                successful=result.completed
                                if(successful && editable && scope.changed.isNotEmpty()) {
                                    if(testsEnabled && scope.lastTestSucceeded!=true) {
                                        val output=scope.execute(com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree("{\"action\":\"run_tests\"}"));send(AgentEvent("output",output.takeLast(1400)))
                                        successful=scope.lastTestSucceeded==true
                                    }
                                    if(successful)send(AgentEvent("merge","원본 반영 완료: ${project.publish(files.toSet()).joinToString()}"))
                                }
                                send(AgentEvent("summary","${if(successful)"완료" else "원본 반영 안 됨"} · 작업 보관: ${project.home}"))
                            }
                            parent.editMessageEmbeds(card.setTitle("$agent · ${if(successful)"완료" else "미완료"}").setColor(Color(if(successful)0x4caf83 else 0xd85c55)).build()).complete()
                        } catch(e:Exception) {
                            integrationRun?.let {runCatching {finish(it,"통합","중단",false)}}
                            val reason=if(e is IllegalArgumentException || e is IllegalStateException)e.message.orEmpty() else "네트워크 또는 실행 오류입니다."
                            send(AgentEvent("summary","작업 중단: $reason\n분리된 작업 결과는 보관됩니다. 원본 반영 여부는 병합 로그를 확인하세요."))
                            parent.editMessageEmbeds(card.setTitle("$agent · 중단").setColor(Color(0xd85c55)).build()).complete()
                        }
                    } catch(e:Exception) {
                        val reason=AgentPresentation.startFailure(stage,e)
                        starter?.editMessage(reason)?.setEmbeds(emptyList())?.setAllowedMentions(emptyList())?.queue({}, {})
                        hook.editOriginal(reason).setAllowedMentions(emptyList()).queue({}, {})
                    } finally {if(main)mainBusy.set(false) else subSlots.release()}
                }} catch(_:Exception) {if(main)mainBusy.set(false) else subSlots.release();hook.editOriginal("실행기가 종료되었습니다.").queue({}, {})}
            },{hook.editOriginal("현재 역할을 확인하지 못했습니다.").queue({}, {})})
        },{})
    }
    override fun close() {worker.shutdownNow()}
}
