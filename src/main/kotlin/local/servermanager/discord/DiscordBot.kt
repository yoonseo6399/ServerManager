package local.servermanager

import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.StatusChangeEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import moe.kyokobot.libdave.NativeDaveFactory
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.read

data class DiscordBotStatus(val state:String, val message:String, val inviteUrl:String?, val guilds:List<Map<String,String>>)

@Service
class DiscordBot(private val manager:ServerManager, private val audit:AuditLog, private val desktop:DesktopLifecycle,private val remoteFiles:RemoteFiles=RemoteFiles(manager),private val assistant:AssistantHarness?=null,private val assistantIdentity:AssistantIdentity?=null,private val assistantGoogle:AssistantGoogle?=null):ListenerAdapter() {
    private val control:ServerControlPort=ServerManagerControl(manager)
    private val settings=DiscordSettingsStore(control.dataRoot.resolve("security/discord"))
    private val moduleSettings=DiscordModuleSettings(control.dataRoot.resolve("security/discord"))
    private fun featureConfig(id:String)=view().config.let {it.copy(enabled=it.enabled && moduleSettings.enabled(id))}
    private val lifecycle=Executors.newSingleThreadExecutor()
    private val generation=AtomicLong()
    @Volatile private var client:JDA?=null
    private val codeAgent=DiscordCodeAgent({featureConfig("ai")},{client},{AgentApiKeyStore(control.dataRoot.resolve("security/agent")).read()},control.dataRoot.resolve("security/agent"))
    private val assistantCommands=if(assistant!=null && assistantIdentity!=null)DiscordAssistant(assistant,assistantIdentity,{featureConfig("assistant")},{client},assistantGoogle) else null
    private val projectAgent=assistantIdentity?.let {identity->DiscordProjectAgent(control.dataRoot.resolve("security/agent"),identity,{featureConfig("agent")},{client},{actor->assistant?.engine?.policy(actor)?.roots.orEmpty()})}
    private val music by lazy { runCatching { run {
        val directory=control.dataRoot.resolve("security/discord")
        val library=DiscordMusicLibraryStore(directory)
        DiscordMusic({featureConfig("music")},{client},DiscordMusicPlayback(library),directory,library)
    } }.getOrNull() }
    @Volatile private var musicAudioReady=false
    /** Module permissions: policy and audit live under security/discord; the ceiling is only editable locally. */
    val permissions=PermissionService(control.dataRoot.resolve("security/discord"))
    fun modulesDenial(guildId:String?,roles:Set<String>,permission:String):String? = modules.denial(guildId,roles,permission)
    private fun isSuperAdmin(guild:String,roles:Set<String>)=view().config.superAdmins.any {it.guildId==guild && it.roleId in roles}
    /** Feature modules (music, agents, permission management). New features plug in here; see docs/architecture/discord-modules.md. */
    private val minecraft=DiscordMinecraftModule(control,audit,desktop,remoteFiles,{view().let {it.copy(config=featureConfig("minecraft"))}},{client},{message=it},::modulesDenial)
    private val modules=DiscordModuleRegistry(permissions,::isSuperAdmin).also {registry->
        registry.enabled=moduleSettings::enabled
        registry.add(minecraft)
        registry.add(DiscordCodeAgentModule(codeAgent))
        assistantCommands?.let {registry.add(DiscordAssistantModule(it))}
        projectAgent?.let {registry.add(DiscordProjectAgentModule(it))}
        registry.add(DiscordMusicModule({music},{musicAudioReady},{client}))
        registry.add(DiscordPermissionModule(permissions,::isSuperAdmin))
    }
    @Volatile private var message="봇 토큰을 입력하고 연동을 켜세요."
    @Volatile private var connecting=false
    fun view()=settings.view()
    fun moduleView():Map<String,Any> = mapOf("revision" to moduleSettings.view().revision,"warning" to moduleSettings.warning,"modules" to modules.all.map {mapOf("id" to it.id,"label" to it.label,"enabled" to moduleSettings.enabled(it.id))})
    @Synchronized fun saveModules(request:DiscordModuleSave):Map<String,Any> {
        val before=moduleSettings.view()
        val unavailable=before.disabled-modules.all.map {it.id}.toSet()
        val next=moduleSettings.save(request.copy(disabled=request.disabled+unavailable))
        modules.all.filter {it.id in next.disabled && it.id !in before.disabled}.forEach {runCatching {it.disconnect()}}
        client?.let {registerCommands(it)}
        return moduleView()
    }
    fun status():DiscordBotStatus {
        val jda=client
        val state=when { !view().config.enabled->"OFF"; jda!=null->jda.status.name; connecting->"CONNECTING"; else->"ERROR" }
        val ready=jda?.status==JDA.Status.CONNECTED
        val permissions=125952L or (1L shl 35) or (1L shl 38) or Permission.MANAGE_CHANNEL.rawValue or Permission.VOICE_CONNECT.rawValue or Permission.VOICE_SPEAK.rawValue or Permission.MESSAGE_MANAGE.rawValue or Permission.PIN_MESSAGES.rawValue
        val invite=if(ready) "https://discord.com/oauth2/authorize?client_id=${jda!!.selfUser.id}&scope=bot%20applications.commands&permissions=$permissions" else null
        return DiscordBotStatus(state,message,invite,if(ready) jda!!.guilds.map { mapOf("id" to it.id,"name" to it.name) } else emptyList())
    }
    @Synchronized fun save(request:DiscordSave):DiscordSettingsView {
        val before=view()
        val next=settings.save(request) { runCatching { control.instance(it) }.isSuccess }
        val reconnect=before.config.enabled!=next.config.enabled || !request.token.isNullOrBlank() || request.clearToken || client==null
        minecraft.configurationChanged()
        if(reconnect) connect() else client?.let { registerCommands(it) }
        return next
    }
    @EventListener(ApplicationReadyEvent::class) fun ready() { connect() }
    @Synchronized fun connect() {
        val epoch=generation.incrementAndGet()
        // Invalidate old callbacks immediately. Network shutdown/login stays off the request thread.
        val old=client;client=null
        connecting=view().config.enabled
        message=if(connecting) "Discord에 연결 중입니다." else "Discord 연동이 꺼져 있습니다."
        lifecycle.submit {
            runCatching { modules.disconnect() }
            old?.shutdownNow()
            if(epoch!=generation.get() || !view().config.enabled) return@submit
            try {
                val token=settings.token() ?: error("missing token")
                val intents=mutableListOf(GatewayIntent.GUILD_VOICE_STATES,GatewayIntent.GUILD_MESSAGES)
                if(DiscordMusicGateway.messageContentAllowed(token))intents.add(GatewayIntent.MESSAGE_CONTENT)
                val builder=JDABuilder.createLight(token,intents).enableCache(CacheFlag.VOICE_STATE)
                    .setMemberCachePolicy(net.dv8tion.jda.api.utils.MemberCachePolicy.VOICE)
                    .setEnableShutdownHook(false).addEventListeners(this)
                musicAudioReady=runCatching {
                    NativeDaveFactory.ensureAvailable()
                    builder.setAudioModuleConfig(AudioModuleConfig().withDaveSessionFactory(LDJDADaveSessionFactory(NativeDaveFactory())))
                    true
                }.getOrDefault(false)
                val next=builder.build()
                synchronized(this) { if(epoch==generation.get()) client=next else next.shutdownNow() }
            } catch (_:Exception) {
                if(epoch==generation.get()) { connecting=false;message="연결하지 못했습니다. 봇 토큰과 인터넷 연결을 확인한 뒤 다시 연결하세요." }
            }
        }
    }
    override fun onReady(event:ReadyEvent) {
        if(event.jda!==client) return
        connecting=false;message="연결되었습니다. 역할과 알림 채널을 설정하세요. "+if(musicAudioReady)"음악 오디오 준비 완료." else "음악 오디오를 초기화하지 못했습니다."
        registerCommands(event.jda)
    }
    override fun onStatusChange(event:StatusChangeEvent) {
        if(event.jda!==client) return
        if(event.newStatus==JDA.Status.SHUTDOWN) { runCatching { modules.disconnect() };connecting=false;message="Discord 연결이 종료되었습니다. 토큰과 네트워크를 확인한 뒤 다시 연결하세요." }
        else if(event.newStatus==JDA.Status.CONNECTED) { connecting=false;message="Discord에 연결되었습니다." }
    }
    override fun onGuildJoin(event:GuildJoinEvent) { if(event.jda===client) registerCommands(event.jda) }
    override fun onGuildVoiceUpdate(event:net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent) {if(event.jda===client)modules.voiceState(event)}
    private fun registerCommands(jda:JDA) {
        val config=view().config
        if(!config.enabled) return
        val guildIds=(config.grants.map { it.guildId }+config.alerts.map { it.guildId }+config.superAdmins.map { it.guildId }).toSet()
        guildIds.forEach { id ->
            val guild=jda.getGuildById(id)
            if(guild==null) { message="설정한 Discord 서버에 봇을 초대하세요. 서버 ID도 확인하세요."; return@forEach }
            modules.register(jda,id)
        }
    }
    override fun onCommandAutoCompleteInteraction(event:CommandAutoCompleteInteractionEvent) {
        modules.autoComplete(event)
    }
    override fun onSlashCommandInteraction(event:SlashCommandInteractionEvent) {
        modules.command(event)
    }
    override fun onButtonInteraction(event:ButtonInteractionEvent) {
        modules.button(event)
    }
    override fun onStringSelectInteraction(event:net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent) {
        modules.select(event)
    }
    override fun onMessageReceived(event:net.dv8tion.jda.api.events.message.MessageReceivedEvent) {
        modules.message(event)
    }
    fun choices(guild:String?,roles:Set<String>,start:Boolean,query:String)=minecraft.choices(guild,roles,start,query)
    fun choices(guild:String?,roles:Set<String>,action:String,query:String)=minecraft.choices(guild,roles,action,query)
    fun execute(guild:String,roles:Set<String>,user:String,requestId:String,id:String,action:String,actor:StartActor?=null)=minecraft.execute(guild,roles,user,requestId,id,action,actor)
    @Scheduled(fixedDelay=2000) fun sendAlerts()=minecraft.sendAlerts()
    @Scheduled(fixedDelay=15000) fun refreshPanels()=minecraft.refreshPanels()
    @Scheduled(fixedDelay=60000) fun cleanPanelChannels()=minecraft.cleanPanelChannels()
    @PreDestroy fun close() { generation.incrementAndGet();modules.close();client?.shutdownNow();client=null;lifecycle.shutdownNow() }
}

@RestController
@RequestMapping("/api/admin/discord")
class DiscordApi(private val bot:DiscordBot) {
    @GetMapping fun settings()=ResponseEntity.ok().header("Cache-Control","no-store").body(bot.view())
    @GetMapping("/status") fun status()=ResponseEntity.ok().header("Cache-Control","no-store").body(bot.status())
    @GetMapping("/modules") fun modules()=ResponseEntity.ok().header("Cache-Control","no-store").body(bot.moduleView())
    private fun fresh(req:HttpServletRequest) {
        val verified=req.getSession(false)?.getAttribute("sm.verified") as? Long ?: 0
        if(System.currentTimeMillis()-verified>300000) throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"패스키로 다시 인증한 뒤 진행하세요")
    }
    @PutMapping fun save(@RequestBody request:DiscordSave,req:HttpServletRequest):DiscordSettingsView { fresh(req);return bot.save(request) }
    @PutMapping("/modules") fun saveModules(@RequestBody request:DiscordModuleSave,req:HttpServletRequest):Map<String,Any> {fresh(req);return bot.saveModules(request)}
    @PostMapping("/reconnect") fun reconnect(req:HttpServletRequest):Map<String,Boolean> { fresh(req);bot.connect();return mapOf("requested" to true) }
}
