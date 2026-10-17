package local.servermanager

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent

/**
 * Thin adapters that put the existing feature classes behind [DiscordModule] without changing their behaviour. Each keeps
 * its own legacy permission checks; the permission service is an additional gate (deny / ceiling / default-deny).
 */

class DiscordMinecraftModule(private val bot:DiscordBot) : DiscordModule {
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
    override fun register(jda:JDA,guildId:String) {}
    override fun onCommand(event:SlashCommandInteractionEvent) {
        val action=event.subcommandName
        if(action in setOf("start","stop","restart")) {
            val roles=event.member?.roles?.map {it.id}?.toSet().orEmpty()
            bot.modulesDenial(event.guild?.id,roles,"minecraft.control")?.let {event.reply(it).setEphemeral(true).queue({}, {});return}
        }
        bot.mcCommand(event)
    }
    override fun onButton(event:ButtonInteractionEvent) {
        val roles=event.member?.roles?.map {it.id}?.toSet().orEmpty()
        bot.modulesDenial(event.guild?.id,roles,"minecraft.control")?.let {event.reply(it).setEphemeral(true).queue({}, {});return}
        bot.mcButton(event)
    }
    override fun onAutoComplete(event:net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent) {bot.mcAutoComplete(event)}
}

class DiscordMusicModule(private val music:()->DiscordMusic?,private val audioReady:()->Boolean,private val currentClient:()->JDA?) : DiscordModule {
    override val id="music"
    override val label="음악"
    override val commands=setOf("music")
    override val componentPrefixes=setOf("music:")
    override val permissions=listOf(PermissionDecl("music.use","음악 명령과 패널 사용",PermissionRisk.LOW,"music"))
    override fun commandPermission(command:String)="music.use"
    private val unavailable="음악 오디오 기능을 시작하지 못했습니다. 서버 관리자에게 오디오 설정을 확인해 달라고 요청하세요."
    override fun register(jda:JDA,guildId:String) {music()?.register(jda,guildId)}
    override fun onCommand(event:SlashCommandInteractionEvent) {
        val feature=music()
        if(!audioReady() || feature==null)event.reply(unavailable).setEphemeral(true).queue({}, {}) else feature.handle(event)
    }
    override fun onButton(event:ButtonInteractionEvent) {
        if(audioReady())music()?.handleButton(event) else event.reply("음악 오디오 기능을 시작하지 못했습니다. 봇 오디오 설정을 확인하세요.").setEphemeral(true).queue({}, {})
    }
    override fun onSelect(event:StringSelectInteractionEvent) {
        if(audioReady())music()?.handleSelect(event) else event.reply("음악 오디오 기능을 시작하지 못했습니다. 봇 오디오 설정을 확인하세요.").setEphemeral(true).queue({}, {})
    }
    override fun onMessage(event:MessageReceivedEvent) {if(event.jda===currentClient() && audioReady())music()?.handleMessage(event)}
    override fun disconnect() {music()?.disconnectAll()}
    override fun close() {music()?.close()}
}

class DiscordProjectAgentModule(private val agent:DiscordProjectAgent) : DiscordModule {
    override val id="agent"
    override val label="AI 에이전트"
    override val commands=setOf("agent")
    override val componentPrefixes=setOf("qa:")
    override val permissions=listOf(PermissionDecl("agent.use","프로젝트 에이전트 명령과 채널 대화",PermissionRisk.MEDIUM,"agent"))
    override fun commandPermission(command:String)="agent.use"
    override val messagePermission="agent.use"
    override fun register(jda:JDA,guildId:String) {agent.register(jda,guildId)}
    override fun onCommand(event:SlashCommandInteractionEvent) {agent.handle(event)}
    override fun onButton(event:ButtonInteractionEvent) {agent.button(event)}
    override fun onMessage(event:MessageReceivedEvent) {agent.message(event)}
    override fun close() {agent.close()}
}

class DiscordCodeAgentModule(private val agent:DiscordCodeAgent) : DiscordModule {
    override val id="ai"
    override val label="AI 모델"
    override val commands=setOf("cloud-agent","sub-agent","agent-keys","local-llm")
    override val permissions=listOf(
        PermissionDecl("ai.cloud","클라우드 에이전트와 보조 에이전트",PermissionRisk.MEDIUM,"ai"),
        PermissionDecl("ai.local","로컬 모델 대화",PermissionRisk.LOW,"ai"))
    override fun commandPermission(command:String)=if(command=="local-llm")"ai.local" else "ai.cloud"
    override fun register(jda:JDA,guildId:String) {agent.register(jda,guildId)}
    override fun onCommand(event:SlashCommandInteractionEvent) {agent.handle(event)}
    override fun close() {agent.close()}
}

class DiscordAssistantModule(private val assistant:DiscordAssistant) : DiscordModule {
    override val id="assistant"
    override val label="파일 비서"
    override val commands=setOf("assistant","assistant-link","assistant-google")
    override val componentPrefixes=setOf("as:")
    override val permissions=listOf(PermissionDecl("assistant.use","파일 비서 명령",PermissionRisk.MEDIUM,"assistant"))
    override fun commandPermission(command:String)="assistant.use"
    override fun register(jda:JDA,guildId:String) {assistant.register(jda,guildId)}
    override fun onCommand(event:SlashCommandInteractionEvent) {assistant.handle(event)}
    override fun onButton(event:ButtonInteractionEvent) {assistant.button(event)}
    override fun close() {assistant.close()}
}
