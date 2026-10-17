package local.servermanager

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.slf4j.LoggerFactory

/**
 * One feature of the Discord bot (music, AI agent, permission management...). The bot owns the JDA connection and routes
 * events to modules by slash-command name and component-id prefix; a module never sees events that are not its own.
 * See docs/architecture/discord-modules.md for the contract and a worked example.
 */
interface DiscordModule : AutoCloseable {
    /** Stable lowercase id; also the prefix of every permission this module declares ("music" -> "music.use"). */
    val id:String
    val label:String
    /** Top-level slash command names this module owns. No two modules may claim the same name. */
    val commands:Set<String>
    /** Prefixes of button/select component ids this module owns, e.g. "music:". */
    val componentPrefixes:Set<String> get()=emptySet()
    /** Capabilities this module asks the permission service to gate. Declaring grants nothing. */
    val permissions:List<PermissionDecl> get()=emptyList()

    /** Registers slash commands for one guild. Called on every (re)connect. */
    fun register(jda:JDA,guildId:String)
    fun onCommand(event:SlashCommandInteractionEvent)
    fun onButton(event:ButtonInteractionEvent) {}
    fun onSelect(event:StringSelectInteractionEvent) {}
    fun onAutoComplete(event:CommandAutoCompleteInteractionEvent) {}
    /** Every guild message; return quickly, a module must ignore messages it does not care about. */
    fun onMessage(event:MessageReceivedEvent) {}

    /** Permission id required to run [command]; null means the module does its own checks only. */
    fun commandPermission(command:String):String? = null
    /** Permission id required for [onMessage] processing; a denied message is ignored silently. */
    val messagePermission:String? get()=null

    /** The Discord connection dropped or was replaced: stop anything bound to it (voice connections, running jobs). */
    fun disconnect() {}
    override fun close() {}
}

/**
 * Routes events to modules and isolates their failures: an exception in one module is logged and reported to the user,
 * never propagated to other modules or the JDA event thread. Permission gating happens here before the module runs.
 */
class DiscordModuleRegistry(private val permissions:PermissionService?,private val superAdmin:(String,Set<String>)->Boolean) : AutoCloseable {
    private val log=LoggerFactory.getLogger(DiscordModuleRegistry::class.java)
    private val modules=LinkedHashMap<String,DiscordModule>()
    private val byCommand=HashMap<String,DiscordModule>()
    val all:List<DiscordModule> get()=modules.values.toList()
    companion object {
        /** Handled by the bot itself (Minecraft control panel); a module may not shadow them. */
        /** Only the built-in Minecraft control module may use these. */
        const val OWNER_OF_RESERVED="minecraft"
        val RESERVED_COMMANDS=setOf("mc")
        val RESERVED_PREFIXES=setOf("sm:")
    }

    @Synchronized fun add(module:DiscordModule):DiscordModuleRegistry {
        require(Regex("[a-z][a-z0-9_]{0,30}").matches(module.id)) {"모듈 ID가 올바르지 않습니다: ${module.id}"}
        require(module.id !in modules) {"이미 등록된 모듈입니다: ${module.id}"}
        require(module.commands.isNotEmpty() || module.componentPrefixes.isNotEmpty() || module.messagePermission!=null || module.permissions.isNotEmpty()) {"${module.id}: 처리할 명령이나 컴포넌트가 없습니다."}
        if(module.id!=OWNER_OF_RESERVED)module.commands.forEach {require(it !in RESERVED_COMMANDS) {"명령 /$it 은(는) minecraft 모듈이 예약했습니다."}}
        if(module.id!=OWNER_OF_RESERVED)module.componentPrefixes.forEach {prefix->require(RESERVED_PREFIXES.none {r->r.startsWith(prefix) || prefix.startsWith(r)}) {"컴포넌트 접두사 $prefix 은(는) 봇 본체가 예약했습니다."}}
        module.commands.forEach {name->byCommand[name]?.let {require(false) {"명령 /$name 은(는) 이미 ${it.id} 모듈이 사용합니다."}}}
        module.componentPrefixes.forEach {prefix->modules.values.firstOrNull {m->m.componentPrefixes.any {p->p.startsWith(prefix) || prefix.startsWith(p)}}?.let {
            require(false) {"컴포넌트 접두사 $prefix 은(는) ${it.id} 모듈과 겹칩니다."}}}
        module.permissions.forEach {require(it.module==module.id) {"${module.id}: 다른 모듈의 권한을 선언할 수 없습니다 (${it.id})."}}
        permissions?.declare(module.permissions)
        modules[module.id]=module;module.commands.forEach {byCommand[it]=module}
        return this
    }
    @Synchronized fun module(id:String):DiscordModule? = modules[id]
    fun forCommand(name:String):DiscordModule? = byCommand[name]
    fun forComponent(id:String):DiscordModule? = modules.values.firstOrNull {m->m.componentPrefixes.any {id.startsWith(it)}}
    fun declarations():List<PermissionDecl> = modules.values.flatMap {it.permissions}

    /** null = proceed; otherwise the denial text to show the user. [roles] are the caller's current role ids. */
    fun denial(guildId:String?,roles:Set<String>,permission:String?):String? {
        if(permission==null || permissions==null)return null
        if(guildId==null)return "서버(길드) 안에서만 사용할 수 있습니다."
        val decision=permissions.check(guildId,roles,permission,superAdmin(guildId,roles))
        return if(decision.allowed==false)"이 기능을 사용할 권한이 없습니다 (`$permission`): ${decision.reason}" else null
    }

    fun register(jda:JDA,guildId:String) = modules.values.forEach {runCatching {it.register(jda,guildId)}.onFailure {e->log.warn("module {} register failed: {}",it.id,e.message)}}

    fun command(event:SlashCommandInteractionEvent):Boolean {
        val module=forCommand(event.name) ?: return false
        val roles=event.member?.roles?.map {it.id}?.toSet().orEmpty()
        denial(event.guild?.id,roles,module.commandPermission(event.name))?.let {text->event.reply(text).setEphemeral(true).queue({}, {});return true}
        guarded(module,"command ${event.name}",{text->if(!event.isAcknowledged)event.reply(text).setEphemeral(true).queue({}, {})}) {module.onCommand(event)}
        return true
    }
    fun button(event:ButtonInteractionEvent):Boolean {
        val module=forComponent(event.componentId) ?: return false
        guarded(module,"button",{text->if(!event.isAcknowledged)event.reply(text).setEphemeral(true).queue({}, {})}) {module.onButton(event)};return true
    }
    fun select(event:StringSelectInteractionEvent):Boolean {
        val module=forComponent(event.componentId) ?: return false
        guarded(module,"select",{text->if(!event.isAcknowledged)event.reply(text).setEphemeral(true).queue({}, {})}) {module.onSelect(event)};return true
    }
    fun autoComplete(event:CommandAutoCompleteInteractionEvent):Boolean {
        val module=forCommand(event.name) ?: return false
        guarded(module,"autocomplete",{}) {module.onAutoComplete(event)};return true
    }
    fun message(event:MessageReceivedEvent) {
        val roles=event.member?.roles?.map {it.id}?.toSet().orEmpty()
        val guildId=if(event.isFromGuild)event.guild.id else null
        modules.values.forEach {module->
            if(denial(guildId,roles,module.messagePermission)!=null)return@forEach
            guarded(module,"message",{}) {module.onMessage(event)}
        }
    }
    fun disconnect() = modules.values.forEach {runCatching {it.disconnect()}.onFailure {e->log.warn("module {} disconnect failed: {}",it.id,e.message)}}

    private fun guarded(module:DiscordModule,what:String,report:(String)->Unit,body:()->Unit) {
        try {body()} catch(e:Exception) {
            log.warn("module {} failed in {}: {}",module.id,what,e.toString())
            runCatching {report("${module.label} 기능에서 오류가 발생했습니다. 잠시 후 다시 시도하세요.")}
        }
    }
    override fun close() {modules.values.reversed().forEach {runCatching {it.close()}.onFailure {e->log.warn("module {} close failed: {}",it.id,e.message)}}}
}
