package local.servermanager

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.actionrow.ActionRow
import java.util.concurrent.ConcurrentHashMap
import java.time.Instant
import java.awt.Color
import kotlin.concurrent.read

/** Only lifecycle notices enter this bounded queue; ordinary errors update the panel counter only. */
class DiscordAlerts {
    private data class Pending(var count:Int,var signal:ServerSignal)
    private val pending=linkedMapOf<Pair<String,String>,Pending>()
    @Synchronized fun offer(event:ServerSignal) {
        if(event.kind !in setOf("start","stop"))return
        val key=event.instanceId to event.kind
        if(key !in pending && pending.size>=1000)return
        pending.getOrPut(key){Pending(0,event)}.apply {count=(count+1).coerceAtMost(1000000);signal=event}
    }
    @Synchronized fun drain():List<Pair<ServerSignal,Int>> {
        val result=pending.values.map {it.signal to it.count};pending.clear();return result
    }
    @Synchronized fun clear(){pending.clear()}
}

/** Minecraft commands, panels, alerts and request deduplication; no dependency on DiscordBot. */
class DiscordMinecraftModule(
    private val control:ServerControlPort,private val audit:AuditLog,private val desktop:DesktopLifecycle,
    private val remoteFiles:RemoteFiles,private val settings:()->DiscordSettingsView,
    private val currentClient:()->JDA?,private val report:(String)->Unit,
    private val permissionDenial:(String?,Set<String>,String)->String?
):DiscordModule {
    private val client:JDA? get()=currentClient()
    private fun view()=settings()
    private var message=""
        set(value){field=value;report(value)}
    private val panels=DiscordPanelStore(control.dataRoot.resolve("security/discord"))
    private val alerts=DiscordAlerts()
    private val errorTimes=ConcurrentHashMap<String,ArrayDeque<Long>>()
    private val panelInflight=ConcurrentHashMap.newKeySet<String>()
    private val panelFingerprint=ConcurrentHashMap<String,String>()
    private val observedStates=ConcurrentHashMap<String,String>()
    private val signalSubscription=control.onSignal { signal ->
        if(view().config.enabled) {
            alerts.offer(signal)
            if(signal.kind=="error") {
                val queue=errorTimes.computeIfAbsent(signal.instanceId) { ArrayDeque() }
                synchronized(queue) { queue.addLast(System.currentTimeMillis());while(queue.size>10000)queue.removeFirst() }
            }
        }
    }
    private val requests=mutableMapOf<String,Long>()
    private val lastStart=mutableMapOf<String,Long>()
    override val id="minecraft"
    override val label="Minecraft 서버 제어"
    override val commands=setOf("mc")
    override val componentPrefixes=setOf("sm:")
    /** Who may start/stop which server stays with DiscordAccess (per-server role rules); the service only adds deny/ceiling. */
    override val permissions=listOf(
        PermissionDecl("minecraft.status","서버 상태 조회",PermissionRisk.LOW,"minecraft"),
        PermissionDecl("minecraft.control","서버 시작·중지·재시작",PermissionRisk.MEDIUM,"minecraft"))
    override fun commandPermission(command:String)="minecraft.status"
    /** Registered by DiscordBot.registerCommands together with its guild rules. */
    override fun register(jda:JDA,guildId:String) {
        val guild=jda.getGuildById(guildId) ?: return
            val command=Commands.slash("mc","Minecraft 서버 관리")
                .addSubcommands(
                    SubcommandData("status","내 역할에 허용된 서버 상태 보기").addOptions(OptionData(OptionType.STRING,"server","Minecraft 서버",true).setAutoComplete(true)),
                    SubcommandData("start","내 역할에 허용된 서버 시작").addOptions(OptionData(OptionType.STRING,"server","Minecraft 서버",true).setAutoComplete(true)),
                    SubcommandData("stop","내 역할에 허용된 서버 정상 종료").addOptions(OptionData(OptionType.STRING,"server","Minecraft 서버",true).setAutoComplete(true)),
                    SubcommandData("restart","내 역할에 허용된 서버 재시작").addOptions(OptionData(OptionType.STRING,"server","Minecraft 서버",true).setAutoComplete(true)))
            guild.upsertCommand(command).queue({}, { if(jda===client) message="명령 등록 실패: 봇 초대 권한과 서버 ID를 확인하세요." })
    }
    override fun onCommand(event:SlashCommandInteractionEvent) {
        val action=event.subcommandName
        if(action in setOf("start","stop","restart")) {
            val roles=event.member?.roles?.map {it.id}?.toSet().orEmpty()
            permissionDenial(event.guild?.id,roles,"minecraft.control")?.let {event.reply(it).setEphemeral(true).queue({}, {});return}
        }
        mcCommand(event)
    }
    override fun onButton(event:ButtonInteractionEvent) {
        val roles=event.member?.roles?.map {it.id}?.toSet().orEmpty()
        permissionDenial(event.guild?.id,roles,"minecraft.control")?.let {event.reply(it).setEphemeral(true).queue({}, {});return}
        mcButton(event)
    }
    override fun onAutoComplete(event:net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent) {mcAutoComplete(event)}
    fun choices(guild:String?,roles:Set<String>,start:Boolean,query:String):List<Pair<String,String>> = choices(guild,roles,if(start) "start" else "status",query)
    fun choices(guild:String?,roles:Set<String>,action:String,query:String):List<Pair<String,String>> {
        val config=view().config
        return control.list().map { it["config"] as Instance }
            .filter { DiscordAccess.allowed(config,guild,roles,it.id,action) && (it.name.contains(query,true) || it.id.startsWith(query)) }
            .take(25).map { "${it.name} · ${it.id.take(8)}".take(100) to it.id }
    }
    fun mcAutoComplete(event:CommandAutoCompleteInteractionEvent) {
        if(event.jda!==client || event.name!="mc") return
        val choices=choices(event.guild?.id,event.member?.roles?.map { it.id }?.toSet() ?: emptySet(),event.subcommandName ?: "status",event.focusedOption.value)
        event.replyChoices(choices.map { Command.Choice(it.first,it.second) }).queue({}, {})
    }
    fun mcCommand(event:SlashCommandInteractionEvent) {
        if(event.jda!==client || event.name!="mc") return
        val guild=event.guild
        val id=event.getOption("server")?.asString
        val action=event.subcommandName
        if(guild==null || id==null || action !in setOf("start","status","stop","restart")) {
            event.reply("설정된 Discord 서버에서 /mc 명령을 사용하세요.").setEphemeral(true).queue({}, {});return
        }
        // Acknowledge before REST membership verification. Roles are fetched fresh for each action.
        event.deferReply(true).queue({ hook ->
            guild.retrieveMemberById(event.user.id).useCache(false).queue({ member ->
                if(event.jda!==client) { hook.editOriginal("설정이 변경되었습니다. 다시 요청하세요.").queue({}, {});return@queue }
                val result=execute(guild.id,member.roles.map { it.id }.toSet(),event.user.id,event.id,id,action!!,StartActor("DISCORD",member.effectiveName,event.user.id,member.user.effectiveAvatarUrl))
                hook.editOriginal(result).setAllowedMentions(emptyList()).queue({}, {})
            }, { hook.editOriginal("현재 서버 역할을 확인하지 못했습니다. 잠시 후 다시 시도하세요.").queue({}, {}) })
        }, {})
    }
    fun mcButton(event:ButtonInteractionEvent) {
        if(event.jda!==client || !event.componentId.startsWith("sm:")) return
        val parts=event.componentId.split(':')
        val guild=event.guild
        val action=parts.getOrNull(2)
        val id=parts.getOrNull(1)
        val route=view().config.alerts.find { it.panel && it.guildId==guild?.id && it.channelId==event.channel.id && it.instanceId==id }
        if(parts.size!=3 || id==null || guild==null || action==null || action !in setOf("start","stop","restart") || route==null || panels.get(DiscordPanelStore.key(route)).panelId!=event.messageId) {
            event.reply("이 제어 패널은 현재 설정과 일치하지 않습니다. 새 패널을 사용하세요.").setEphemeral(true).queue({}, {});return
        }
        event.deferReply(true).queue({ hook ->
            guild.retrieveMemberById(event.user.id).useCache(false).queue({ member ->
                if(event.jda!==client) { hook.editOriginal("Discord 연결이 변경되었습니다. 다시 시도하세요.").queue({}, {});return@queue }
                val result=execute(guild.id,member.roles.map { it.id }.toSet(),event.user.id,event.id,id,action,StartActor("DISCORD",member.effectiveName,event.user.id,member.user.effectiveAvatarUrl))
                hook.editOriginal(result).setAllowedMentions(emptyList()).queue({}, {})
            }, { hook.editOriginal("현재 서버 역할을 확인하지 못했습니다. 잠시 후 다시 시도하세요.").queue({}, {}) })
        }, {})
    }
    fun execute(guild:String,roles:Set<String>,user:String,requestId:String,id:String,action:String,actor:StartActor?=null):String = desktop.gate.read {
        if(desktop.stopping) "관리 앱 업데이트 중입니다. 잠시 후 다시 시도하세요."
        else executeAllowed(guild,roles,user,requestId,id,action,actor)
    }
    @Synchronized private fun executeAllowed(guild:String,roles:Set<String>,user:String,requestId:String,id:String,action:String,actor:StartActor?):String {
        val starter=actor?.takeIf {it.source=="DISCORD" && it.discordId==user} ?: StartActor("DISCORD","Discord 사용자",user)
        val config=view().config
        permissionDenial(guild,roles,if(action=="status")"minecraft.status"else "minecraft.control")?.let {return it}
        if(!DiscordAccess.allowed(config,guild,roles,id,action)) {
            audit.append("discord:$guild:$user","discord $action",403,"discord");return "이 서버에 대한 ${mapOf("start" to "시작","stop" to "중지","restart" to "재시작")[action] ?: "조회"} 권한이 없습니다. 관리자에게 역할 설정을 요청하세요."
        }
        val now=System.currentTimeMillis()
        requests.entries.removeIf { now-it.value>300000 }; lastStart.entries.removeIf { now-it.value>10000 }
        if(requests.putIfAbsent(requestId,now)!=null) return "이미 처리한 명령입니다. /mc status로 상태를 확인하세요."
        if(requests.size>10000) { requests.remove(requestId);return "요청이 많습니다. 잠시 후 다시 시도하세요." }
        return try {
            val i=control.instance(id);val state=control.view(id)["state"]
            if(action=="status") {
                val players=control.view(id)["players"] as? PlayerSample
                val power=control.view(id)["powerSaving"] as? PowerStatus
                "${i.name}: ${stateLabel(state.toString())}${players?.let { " · 접속 ${it.count}명" } ?: " · 접속 인원 알 수 없음"}${power?.let { " · "+powerLabel(it) } ?: ""}"
            } else if(action=="start") {
                if(state in setOf("RUNNING","STARTING")) return "${i.name}: 이미 실행 중이거나 시작 중입니다."
                val key="$guild:$user"
                if(now-(lastStart[key] ?: 0)<10000) return "시작 요청은 10초 후 다시 보낼 수 있습니다."
                lastStart[key]=now
                control.startAs(id,starter)
                audit.append("discord:$guild:$user","discord start $id",200,"discord")
                "${i.name}: 시작을 요청했습니다. /mc status로 준비 상태를 확인하세요."
            } else if(action=="stop") {
                if(state !in setOf("RUNNING","STARTING")) return "${i.name}: 실행 중인 서버가 없습니다."
                control.stop(id,false)
                audit.append("discord:$guild:$user","discord stop $id",200,"discord")
                "${i.name}: 저장 후 정상 종료를 요청했습니다."
            } else {
                if(state!="RUNNING") return "${i.name}: 실행 중인 서버만 재시작할 수 있습니다."
                control.restartAs(id,starter)
                audit.append("discord:$guild:$user","discord restart $id",200,"discord")
                "${i.name}: 저장 후 재시작을 요청했습니다."
            }
        } catch (_:Exception) {
            audit.append("discord:$guild:$user","discord $action $id",409,"discord")
            "요청을 완료하지 못했습니다. 관리 웹에서 서버 설치·메모리·실행 상태를 확인하세요."
        }
    }
    fun sendAlerts() {
        val jda=client
        if(jda==null || jda.status!=JDA.Status.CONNECTED || !view().config.enabled) { alerts.clear();return }
        alerts.drain().forEach { (signal,count) ->
            val name=runCatching { control.instance(signal.instanceId).name }.getOrNull() ?: return@forEach
            val routes=view().config.alerts.filter { it.instanceId==signal.instanceId && it.accepts(signal) }
            if(routes.isEmpty())return@forEach
            val report=signal.exit?.crashReport
            val reportBytes=report?.let {name->if(!remoteFiles.filesRemote(signal.instanceId))CrashReports.attachment(control.serverDir(signal.instanceId),name) else runCatching {
                require(CrashReports.validName(name));val out=java.io.ByteArrayOutputStream();remoteFiles.download(signal.instanceId,"crash-reports/$name",out,CrashReports.MAX_BYTES.toLong());out.toByteArray().takeIf {it.isNotEmpty()}
            }.getOrNull()}
            val reportSummary=reportBytes?.let {DiscordErrorBuffer.fromReport(it)}
            val summary=reportSummary ?: signal.error
            routes.forEach { route ->
                    // Channel lookup is scoped to the configured guild, never a globally supplied channel ID.
                    val channel=jda.getGuildById(route.guildId)?.getTextChannelById(route.channelId)
                    if(channel==null) { message="알림 채널을 찾을 수 없습니다. 서버·텍스트 채널 ID와 봇 권한을 확인하세요.";return@forEach }
                    val key=DiscordPanelStore.key(route)
                    if(signal.kind=="start") {
                        listOfNotNull(panels.get(key).stopId,panels.get(key).startId).forEach {channel.deleteMessageById(it).queue({}, {})}
                        panels.change(key) {it.copy(stopId=null,startId=null)}
                    }
                    if(signal.kind=="stop" && route.panel) {
                        val old=panels.get(key).errorIds+listOfNotNull(panels.get(key).startId)
                        old.forEach { channel.deleteMessageById(it).queue({}, {}) }
                        panels.change(key) { it.copy(errorIds=emptyList(),startId=null) }
                        errorTimes.remove(signal.instanceId)
                    }
                    val title=if(signal.kind=="start") "🟢 서버 시작 완료" else if(signal.kind=="error") "⚠️ 오류 감지" else if(signal.exit?.reason=="IDLE") "🌙 무인 절전 종료" else if(signal.exit?.requested==false||signal.detail.contains("예기치")) "🛑 비정상 종료" else "⏹ 서버 종료"
                    val canAttach=channel.guild.selfMember.hasPermission(channel,net.dv8tion.jda.api.Permission.MESSAGE_ATTACH_FILES)
                    val attachment=reportBytes?.takeIf {canAttach}
                    val restart=(control.view(signal.instanceId)["autoRestart"] as? AutoRestartStatus)?.takeIf {signal.kind=="stop"&&it.config.enabled}
                    val restartText=restart?.let {if(it.remainingSeconds!=null)"\n자동 재시작 대기: 파일·클라우드 인계 완료 후 ${it.remainingSeconds}초 이내 (최대 3회/10분)." else if(it.message!=null)"\n${it.message}" else ""}.orEmpty()
                    val reportText=if(report!=null&&!canAttach)"\n크래시 리포트를 보내려면 봇에 파일 첨부 권한을 허용하세요. 관리 웹의 로그 파일에서도 확인할 수 있습니다." else if(report!=null&&attachment==null)"\n크래시 리포트가 8 MiB를 넘거나 읽을 수 없어 첨부하지 못했습니다. 관리 웹의 로그 파일에서 확인하세요." else ""
                    val embedBuilder=EmbedBuilder().setTitle("$title · ${name.take(80)}")
                        .setDescription((signal.detail+restartText+reportText+(if(count>1) "\n같은 종류의 알림 ${count}건을 묶었습니다." else "")).take(1500))
                        .setColor(if(signal.kind=="start") Color(0x4caf83) else if(signal.kind=="error" || signal.exit?.requested==false || signal.detail.contains("예기치")) Color(0xd85c55) else Color(0x5f8bb5))
                        .setTimestamp(Instant.now())
                    if(signal.kind=="start")DiscordStartProfile.apply(embedBuilder,signal.startActor)
                    summary?.fields()?.forEach {(label,value)->embedBuilder.addField(label,value,false)}
                    if(summary!=null)embedBuilder.setFooter(if(reportSummary!=null)"크래시 리포트 요약" else "종료 직전 로그 요약")
                    val embed=embedBuilder.build()
                    val action=channel.sendMessageEmbeds(embed).setAllowedMentions(emptyList())
                    if(attachment!=null)action.addFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(attachment,report!!))
                    action.queue({ sent ->
                        if(route.panel) {
                            if(signal.kind=="error") {
                                val next=panels.change(key) { it.copy(errorIds=(it.errorIds+sent.id).takeLast(30)) }
                                if(next.errorIds.size>=30) cleanChannel(channel,key)
                            } else if(signal.kind=="start") panels.change(key) {it.copy(startId=sent.id)} else panels.change(key) { it.copy(stopId=sent.id) }
                        }
                    }, { if(jda===client) message="알림 전송 실패: 채널 보기·메시지 보내기·링크 임베드 권한을 확인하세요." })
                }
        }
    }
    fun refreshPanels() {
        val jda=client ?: return
        if(jda.status!=JDA.Status.CONNECTED || !view().config.enabled) return
        view().config.alerts.filter { it.panel }.forEach { route ->
            val key=DiscordPanelStore.key(route)
            if(!panelInflight.add(key)) return@forEach
            val channel=jda.getGuildById(route.guildId)?.getTextChannelById(route.channelId)
            val state=runCatching { control.view(route.instanceId) }.getOrNull()
            if(channel==null || state==null) { panelInflight.remove(key);return@forEach }
            val status=state["state"].toString()
            val previous=observedStates.put(key,status)
            if(status in setOf("STOPPED","FAILED") && previous in setOf("STARTING","RUNNING","STOPPING")) {
                panels.get(key).errorIds.forEach { channel.deleteMessageById(it).queue({}, {}) }
                panels.change(key) { it.copy(errorIds=emptyList()) }
                errorTimes.remove(route.instanceId)
            }
            if(status in setOf("STARTING","RUNNING") && previous !in setOf("STARTING","RUNNING")) {
                errorTimes.remove(route.instanceId)
                panels.get(key).stopId?.let { channel.deleteMessageById(it).queue({}, {}) }
                panels.change(key) { it.copy(stopId=null) }
            }
            val players=state["players"] as? PlayerSample
            val uptime=(state["resources"] as? ResourceSample)?.uptimeSeconds
            val errors=errorFrequency(route.instanceId)
            val name=(state["config"] as Instance).name
            val power=state["powerSaving"] as? PowerStatus
            val transfer=(state["fileTransfer"] ?: state["folderImport"]) as? FolderImportProgress
            val transferText=transfer?.let(TransferDisplay::text)
            val finger="$transferText:$status:${uptime?.div(60)}:${players?.count}:${players?.names}:${errors}:${power?.config}:${power?.status}:${power?.remainingSeconds?.div(60)}:${power?.lastStopReason}"
            val embed=EmbedBuilder().setTitle("🎮 ${name.take(80)} · 서버 제어")
                .setColor(if(status=="RUNNING") Color(0x4caf83) else if(status=="FAILED") Color(0xd85c55) else Color(0x697c93))
                .addField("상태",stateLabel(status),true)
                .addField("업타임",uptime?.let { "${it/3600}시간 ${(it%3600)/60}분" } ?: "—",true)
                .addField("최근 1시간 오류",errors.toString(),true)
                .addField("접속 인원",players?.let { "${it.count}명" } ?: "알 수 없음",true)
                .addField("플레이어",players?.names?.take(30)?.joinToString(", ")?.take(1000)?.ifBlank { "없음" } ?: "알 수 없음",false)
                .addField("무인 절전",power?.let(::powerLabel) ?: "꺼짐",false)
                .apply {if(transferText!=null)addField("서버 파일 전송",transferText,false)}
                .setFooter("Server Manager · ${route.instanceId.take(8)}")
                .setTimestamp(Instant.now()).build()
            val buttons=listOf(
                Button.success("sm:${route.instanceId}:start","시작").withDisabled(status !in setOf("STOPPED","FAILED")),
                Button.secondary("sm:${route.instanceId}:restart","재시작").withDisabled(status!="RUNNING"),
                Button.danger("sm:${route.instanceId}:stop","중지").withDisabled(status !in setOf("RUNNING","STARTING")))
            val oldId=panels.get(key).panelId
            if(oldId!=null && panelFingerprint[key]==finger) { panelInflight.remove(key);return@forEach }
            fun create() { channel.sendMessageEmbeds(embed).setComponents(ActionRow.of(buttons)).queue({ sent ->
                panels.change(key) { it.copy(panelId=sent.id) };panelFingerprint[key]=finger;panelInflight.remove(key)
            }, { panelInflight.remove(key);message="제어 패널을 만들지 못했습니다. 채널 권한을 확인하세요." }) }
            if(oldId==null) create() else channel.retrieveMessageById(oldId).queue({ existing ->
                existing.editMessageEmbeds(embed).setComponents(ActionRow.of(buttons)).queue({ panelFingerprint[key]=finger;panelInflight.remove(key) }, { panelInflight.remove(key) })
            }, { create() })
        }
    }
    private fun errorFrequency(id:String):Int {
        val queue=errorTimes[id] ?: return 0
        val cutoff=System.currentTimeMillis()-3600000
        return synchronized(queue) { while(queue.isNotEmpty() && queue.first()<cutoff)queue.removeFirst();queue.size }
    }
    private fun powerLabel(power:PowerStatus):String {
        val reason=if(power.lastStopReason=="IDLE")" · 최근 종료: 무인 절전" else ""
        return (if(!power.config.enabled)"꺼짐" else if(power.remainingSeconds!=null)"${power.config.minutes}분 연속 무인 시 종료 · 남은 ${((power.remainingSeconds+59)/60)}분" else "${power.config.minutes}분 연속 무인 시 종료 · "+when(power.status){"UNKNOWN"->"인원 확인 불가";"DIAGNOSTIC"->"진단 중 일시 정지";"OCCUPIED"->"플레이어 접속 중";"STOP_REQUESTED"->"최종 확인·저장 종료 중";"VERIFY_FAILED"->"최종 확인 취소 (실행기 연결·버전 확인)";else->"대기 중"})+reason
    }
    fun cleanPanelChannels() {
        val jda=client ?: return
        if(jda.status!=JDA.Status.CONNECTED) return
        view().config.alerts.filter { it.panel }.forEach { route ->
            val channel=jda.getGuildById(route.guildId)?.getTextChannelById(route.channelId) ?: return@forEach
            cleanChannel(channel,DiscordPanelStore.key(route))
        }
    }
    private fun cleanChannel(channel:TextChannel,key:String) {
        val record=panels.get(key)
        if(record.panelId==null) return
        val keep=(record.errorIds+listOfNotNull(record.panelId,record.stopId,record.startId)).toSet()
        channel.history.retrievePast(100).queue({ rows ->
            rows.filter { it.id !in keep }.forEach { it.delete().queue({}, { if(client!=null) message="전용 채널 정리 실패: 메시지 관리 권한을 확인하세요." }) }
        }, { if(client!=null) message="전용 채널을 읽지 못했습니다. 메시지 기록 보기 권한을 확인하세요." })
    }
    private fun stateLabel(value:String)=mapOf("NEW" to "설치 필요","STOPPED" to "정지됨","STARTING" to "시작 중","RUNNING" to "실행 중","STOPPING" to "종료 중","FAILED" to "오류","INSTALLING" to "설치 중","IMPORTING" to "가져오는 중","IMPORT_FAILED" to "가져오기 실패","TRANSFERRING" to "파일 전송 중","SYNCING" to "파일 인계 중","SYNC_FAILED" to "인계 재시도 대기","UNKNOWN" to "연결 확인 중")[value] ?: value

    fun configurationChanged(){alerts.clear();panelFingerprint.clear()}
    override fun disconnect(){configurationChanged()}
    override fun close(){signalSubscription.close();alerts.clear()}
}
