package local.servermanager

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** The member is fetched through REST before this policy is evaluated; channel IDs come from voice-state events. */
object DiscordMusicAccess {
    private val actions=setOf("play","pause","resume","skip","stop","queue","join","leave","previous","remove","autoplay","save","load","playlists","delete","stats")
    fun allowed(config:DiscordConfig,guildId:String?,clientCurrent:Boolean,action:String,userChannelId:String?,botChannelId:String?):Boolean {
        if(!clientCurrent || !config.enabled || guildId==null || action !in actions) return false
        if((config.grants.none { it.guildId==guildId }) && (config.alerts.none { it.guildId==guildId }) && (config.superAdmins.none { it.guildId==guildId })) return false
        if(action in setOf("queue","playlists","stats")) return true
        return userChannelId!=null && (botChannelId==null || botChannelId==userChannelId)
    }

    fun validQuery(query:String):Boolean {
        val q=query.trim()
        if(q.isEmpty() || q.length>200 || q.any { Character.isISOControl(it) }) return false
        if(!q.contains("://")) return true
        val uri=runCatching { java.net.URI(q) }.getOrNull() ?: return false
        val host=uri.host?.lowercase()?.removePrefix("www.") ?: return false
        return uri.scheme=="https" && uri.userInfo==null && uri.port==-1 && host in setOf("youtube.com","m.youtube.com","music.youtube.com","youtu.be")
    }
}

class DiscordMusic(
    private val config:()->DiscordConfig,
    private val currentClient:()->JDA?,
    private val playback:DiscordMusicPlayback,
    directory:java.nio.file.Path,
    private val library:DiscordMusicLibraryStore
):AutoCloseable {
    private val panels=DiscordMusicPanel(DiscordMusicChannelStore(directory),playback,currentClient)
    private val workers=java.util.concurrent.ThreadPoolExecutor(3,3,0,java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(24),java.util.concurrent.ThreadFactory { task -> Thread(task,"discord-music-command").apply { isDaemon=true } })

    fun register(jda:JDA,guildId:String) {
        val guild=jda.getGuildById(guildId) ?: return
        guild.upsertCommand(Commands.slash("music","음성 채널에서 YouTube 음악 재생")
            .addSubcommands(
                SubcommandData("play","YouTube 검색어나 URL을 재생 대기열에 추가").addOption(OptionType.STRING,"query","YouTube 검색어나 URL",true),
                SubcommandData("pause","재생 일시정지"), SubcommandData("resume","재생 재개"),
                SubcommandData("previous","이전 곡 재생"), SubcommandData("skip","현재 곡 건너뛰기"),
                SubcommandData("stop","재생과 대기열 중지"), SubcommandData("queue","현재 곡과 대기열 보기"),
                SubcommandData("join","내 음성 채널에 참여"), SubcommandData("leave","음성 채널에서 나가기"),
                SubcommandData("channel","이 채널을 음악 전용 채널로 등록 (채널 관리자)").addOptions(
                    net.dv8tion.jda.api.interactions.commands.build.OptionData(OptionType.CHANNEL,"channel","생략하면 현재 채널",false)
                        .setChannelTypes(net.dv8tion.jda.api.entities.channel.ChannelType.TEXT)),
                SubcommandData("unchannel","음악 전용 채널 등록 해제 (채널 관리자)"),
                SubcommandData("autoplay","완주 기록 기반 자동재생 설정").addOption(OptionType.BOOLEAN,"enabled","자동재생 켜기",true),
                SubcommandData("stats","직접 등록한 곡의 완주 통계 보기"),
                SubcommandData("save","현재 곡·대기열 또는 YouTube 플레이리스트를 저장")
                    .addOption(OptionType.STRING,"name","저장할 이름 (40자 이내)",true)
                    .addOption(OptionType.STRING,"url","생략하면 현재 곡과 대기열 저장",false),
                SubcommandData("load","저장한 플레이리스트를 대기열에 추가").addOption(OptionType.STRING,"name","저장된 이름",true),
                SubcommandData("playlists","서버에 저장된 플레이리스트 목록"),
                SubcommandData("delete","저장한 플레이리스트 삭제 (작성자 또는 채널 관리자)").addOption(OptionType.STRING,"name","삭제할 이름",true)))
            .queue({}, {})
        panels.restore(jda,setOf(guildId))
    }

    private fun permitted(jda:JDA,guild:Guild,member:Member,action:String):Boolean =
        DiscordMusicAccess.allowed(config(),guild.id,jda===currentClient(),action,member.voiceState?.channel?.id,
            guild.audioManager.connectedChannel?.id ?: playback.channelId(guild.id))

    private fun managementAllowed(guild:Guild,member:Member,channel:net.dv8tion.jda.api.entities.channel.concrete.TextChannel):Boolean =
        DiscordMusicChannelAccess.manage(config(),guild.id,member.roles.map {it.id}.toSet(),member.hasPermission(channel,Permission.MANAGE_CHANNEL))

    private fun canManageLibrary(guild:Guild,member:Member):Boolean =
        member.hasPermission(Permission.MANAGE_CHANNEL) || config().superAdmins.any {it.guildId==guild.id && member.roles.any {role -> role.id==it.roleId}}

    private fun execute(guild:Guild,member:Member,action:String,query:String):String {
        val channel=member.voiceState?.channel
        if(action in setOf("play","join") && channel!=null &&
            !guild.selfMember.hasPermission(channel,Permission.VOICE_CONNECT,Permission.VOICE_SPEAK)) return "이 음성 채널에 연결하고 말할 권한이 필요합니다."
        return when(action) {
            "play" -> playback.play(guild,channel!!,query.trim(),MusicRequester(member.effectiveName,member.effectiveAvatarUrl,member.id))
            "join" -> playback.join(guild,channel!!)
            "pause" -> playback.pause(guild.id)
            "resume" -> playback.resume(guild.id)
            "previous" -> playback.previous(guild.id)
            "skip" -> playback.skip(guild.id)
            "stop" -> playback.stop(guild.id)
            "queue" -> playback.queue(guild.id)
            "leave" -> playback.leave(guild)
            else -> "알 수 없는 음악 명령입니다."
        }
    }

    private fun work(action:String,job:()->Unit,rejected:()->Unit) {
        if(action !in setOf("play","channel","unchannel","save","load","delete","autoplay")) {job();return}
        try {workers.execute(job)} catch(_:RejectedExecutionException) {rejected()}
    }

    private fun runAction(jda:JDA,guild:Guild,member:Member,action:String,query:String):String {
        if(!permitted(jda,guild,member,action)) return "같은 음성 채널에서 명령을 사용하세요. 연결이나 권한이 변경되었을 수도 있습니다."
        return runCatching {execute(guild,member,action,query)}.getOrElse {"음악 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요."}
    }

    fun handle(event:SlashCommandInteractionEvent) {
        if(event.name!="music") return
        val guild=event.guild
        val jda=event.jda
        val action=event.subcommandName.orEmpty()
        val query=event.getOption("query")?.asString.orEmpty()
        if(guild==null || !DiscordMusicAccess.allowed(config(),guild.id,jda===currentClient(),"queue",null,null)) {
            event.reply("설정된 Discord 서버에서만 음악 명령을 사용할 수 있습니다.").setEphemeral(true).queue({}, {});return
        }
        if(action=="play" && !DiscordMusicAccess.validQuery(query)) {
            event.reply("200자 이내의 YouTube 검색어나 HTTPS YouTube URL을 입력하세요.").setEphemeral(true).queue({}, {});return
        }
        event.deferReply(true).queue({hook ->
            fun reply(text:String) {
                hook.editOriginal(text.take(1900)).setAllowedMentions(emptyList()).queue({
                    hook.deleteOriginal().queueAfter(3,java.util.concurrent.TimeUnit.SECONDS,{}, {})
                },{})
            }
            guild.retrieveMemberById(event.user.id).useCache(false).queue({member ->
                if(jda!==currentClient()) {reply("Discord 연결이 변경되었습니다. 다시 시도하세요.");return@queue}
                work(action,{
                    val result=if(action=="channel" || action=="unchannel")runCatching {
                        check(jda===currentClient() && DiscordMusicAccess.allowed(config(),guild.id,true,"queue",null,null)) {"Discord 설정이 변경되었습니다."}
                        val channel=if(action=="channel")event.getOption("channel")?.asChannel?.asTextChannel() ?: event.channel.asTextChannel()
                            else panels.get(guild.id)?.let {guild.getTextChannelById(it.channelId)} ?: event.channel.asTextChannel()
                        check(managementAllowed(guild,member,channel)) {"채널 관리 권한 또는 등록된 최고 관리자 역할이 필요합니다."}
                        if(action=="unchannel")panels.unregister(guild.id) else {
                            check(guild.selfMember.hasPermission(channel,Permission.VIEW_CHANNEL,Permission.MESSAGE_SEND,Permission.MESSAGE_EMBED_LINKS,
                                Permission.MESSAGE_HISTORY,Permission.MESSAGE_MANAGE,Permission.PIN_MESSAGES)) {"봇에 채널 보기·메시지 전송·임베드·기록 보기·메시지 관리·메시지 고정 권한이 필요합니다."}
                            val result=panels.register(guild,channel)
                            result+if(net.dv8tion.jda.api.requests.GatewayIntent.MESSAGE_CONTENT in jda.gatewayIntents) "\n검색어나 YouTube URL을 채팅으로 보내세요. 검색 메시지와 안내는 3초 뒤 삭제됩니다."
                                else "\n일반 채팅을 받으려면 Developer Portal의 Message Content Intent를 켜고 봇을 재연결하세요."
                        }
                    }.getOrElse {if(it is IllegalStateException || it is IllegalArgumentException)it.message ?: "채널 등록 실패" else "채널 등록을 처리하지 못했습니다."}
                    else if(action in setOf("save","load","playlists","delete","stats","autoplay")) runCatching {
                        check(permitted(jda,guild,member,action)) {"같은 음성 채널에서 명령을 사용하세요."}
                        val name=event.getOption("name")?.asString?.trim().orEmpty()
                        when(action) {
                            "save" -> {
                                require(name.length in 1..40 && name.none(Char::isISOControl)) {"목록 이름은 1~40자로 입력하세요."}
                                val url=event.getOption("url")?.asString?.trim()
                                val tracks=if(url.isNullOrEmpty()) playback.savedQueue(guild.id) else {
                                    require(DiscordMusicAccess.validQuery(url) && url.startsWith("https://")) {"YouTube 플레이리스트 HTTPS URL을 입력하세요."}
                                    playback.inspectPlaylist(url)
                                }
                                check(tracks.isNotEmpty()) {"저장할 곡이 없습니다. 대기열 또는 공개 YouTube 플레이리스트 URL을 확인하세요."}
                                check(permitted(jda,guild,member,action)) {"Discord 연결 또는 음성 채널이 변경되었습니다."}
                                val saved=synchronized(library) {
                                    val existing=library.playlist(guild.id,name)
                                    check(existing==null || existing.ownerId==member.id || canManageLibrary(guild,member)) {"다른 사람이 저장한 목록은 작성자 또는 채널 관리자만 덮어쓸 수 있습니다."}
                                    library.save(guild.id,name,tracks,existing?.ownerId ?: member.id)
                                }
                                "플레이리스트 ‘${saved.name}’에 ${saved.tracks.size}곡을 저장했습니다. /music load name:${saved.name}"
                            }
                            "load" -> {
                                val saved=library.playlist(guild.id,name) ?: error("저장된 플레이리스트가 없습니다.")
                                val channel=member.voiceState?.channel ?: error("음성 채널에 먼저 참여하세요.")
                                check(guild.selfMember.hasPermission(channel,Permission.VOICE_CONNECT,Permission.VOICE_SPEAK)) {"음성 채널 연결·말하기 권한이 필요합니다."}
                                playback.playSaved(guild,channel,saved.tracks,MusicRequester(member.effectiveName,member.effectiveAvatarUrl,member.id))
                            }
                            "delete" -> {
                                synchronized(library) {
                                    val saved=library.playlist(guild.id,name) ?: error("저장된 플레이리스트가 없습니다.")
                                    check(saved.ownerId==member.id || canManageLibrary(guild,member)) {"작성자 또는 채널 관리자만 삭제할 수 있습니다."}
                                    if(library.delete(guild.id,name)) "플레이리스트를 삭제했습니다." else "이미 삭제된 목록입니다."
                                }
                            }
                            "playlists" -> library.playlists(guild.id).joinToString("\n") {"• ${it.name} (${it.tracks.size}곡)"}.ifEmpty {"저장된 플레이리스트가 없습니다."}
                            "stats" -> library.stats(guild.id).take(15).mapIndexed {i,stat -> "${i+1}. ${stat.track.title.take(70)} — ${stat.completions}회 완주"}.joinToString("\n").ifEmpty {"완주 기록이 없습니다. 직접 등록하고 끝까지 재생한 곡만 집계합니다."}
                            else -> playback.autoplay(guild.id,event.getOption("enabled")!!.asBoolean)
                        }
                    }.getOrElse {if(it is IllegalArgumentException || it is IllegalStateException) it.message ?: "음악 목록 처리 실패" else "음악 목록을 처리하지 못했습니다. 잠시 후 다시 시도하세요."}
                    else runAction(jda,guild,member,action,query)
                    if(jda===currentClient()) {
                        reply(result);panels.refresh(guild.id)
                        if(action=="play" && panels.get(guild.id)==null)event.channel.sendMessageEmbeds(DiscordMusicPanel.render(playback.snapshot(guild.id)))
                            .setAllowedMentions(emptyList()).queue({}, {})
                    }
                },{reply("음악 요청이 많거나 종료 중입니다. 잠시 후 다시 시도하세요.")})
            },{reply("현재 서버 멤버 정보를 확인하지 못했습니다.")})
        },{})
    }

    fun handleMessage(event:net.dv8tion.jda.api.events.message.MessageReceivedEvent) {
        if(!event.isFromGuild || event.jda!==currentClient()) return
        val record=panels.get(event.guild.id) ?: return
        if(!DiscordMusicChannelAccess.matches(record,event.guild.id,event.channel.id) ||
            !DiscordMusicAccess.allowed(config(),event.guild.id,true,"queue",null,null))return
        if(event.author.id==event.jda.selfUser.id && event.message.type==net.dv8tion.jda.api.entities.MessageType.CHANNEL_PINNED_ADD) {
            event.message.delete().queueAfter(3,java.util.concurrent.TimeUnit.SECONDS,{}, {});return
        }
        if(event.author.isBot || event.isWebhookMessage)return
        val channel=event.channel.asTextChannel()
        val query=event.message.contentRaw.trim()
        if(query.isBlank() && net.dv8tion.jda.api.requests.GatewayIntent.MESSAGE_CONTENT !in event.jda.gatewayIntents)return
        event.message.delete().queueAfter(3,java.util.concurrent.TimeUnit.SECONDS,{}, {})
        fun notice(text:String) {
            if(event.jda!==currentClient() || panels.get(event.guild.id)?.channelId!=record.channelId)return
            channel.sendMessage(text.take(1800)).setAllowedMentions(emptyList()).queue({msg ->
                msg.delete().queueAfter(3,java.util.concurrent.TimeUnit.SECONDS,{}, {})
            },{})
        }
        if(!DiscordMusicAccess.validQuery(query)) {notice("200자 이내의 YouTube 검색어나 URL을 보내세요.");return}
        event.guild.retrieveMemberById(event.author.id).useCache(false).queue({member ->
            work("play",{
                if(panels.get(event.guild.id)?.channelId!=record.channelId)return@work
                notice(runAction(event.jda,event.guild,member,"play",query))
                panels.refresh(event.guild.id)
            },{notice("요청이 많습니다. 잠시 후 다시 보내세요.")})
        },{notice("현재 서버 멤버 정보를 확인하지 못했습니다.")})
    }

    fun handleButton(event:net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent) {
        if(!event.componentId.startsWith("music:")) return
        val guild=event.guild
        val record=guild?.let {panels.get(it.id)}
        val action=event.componentId.removePrefix("music:")
        if(guild==null || event.jda!==currentClient() || record==null ||
            !DiscordMusicChannelAccess.matches(record,guild.id,event.channel.id,event.messageId) ||
            (action !in setOf("resume","pause","stop","previous","skip") && !action.startsWith("page:"))) {
            event.reply("현재 음악 패널에서 사용하세요.").setEphemeral(true).queue({}, {});return
        }
        event.deferReply(true).queue({hook ->
            guild.retrieveMemberById(event.user.id).useCache(false).queue({member ->
                val result=if(action.startsWith("page:")) {
                    if(permitted(event.jda,guild,member,"queue")) {
                        panels.page(guild.id,action.substringAfter(":").toIntOrNull() ?: 0);"대기열 페이지를 바꿨습니다."
                    } else "현재 서버에서 사용할 수 없습니다."
                } else runAction(event.jda,guild,member,action,"")
                hook.editOriginal(result).setAllowedMentions(emptyList()).queue({hook.deleteOriginal().queueAfter(3,java.util.concurrent.TimeUnit.SECONDS,{}, {})},{})
                panels.refresh(guild.id)
            },{hook.editOriginal("현재 서버 멤버 정보를 확인하지 못했습니다.").queue({}, {})})
        },{})
    }

    fun handleSelect(event:net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent) {
        if(event.componentId!="music:remove")return
        val guild=event.guild
        val record=guild?.let {panels.get(it.id)}
        if(guild==null || event.jda!==currentClient() || record==null ||
            !DiscordMusicChannelAccess.matches(record,guild.id,event.channel.id,event.messageId)) {
            event.reply("현재 음악 패널에서 사용하세요.").setEphemeral(true).queue({},{});return
        }
        event.deferReply(true).queue({hook ->
            guild.retrieveMemberById(event.user.id).useCache(false).queue({member ->
                val result=if(permitted(event.jda,guild,member,"remove")) playback.removeQueued(guild.id,event.values.take(25))
                    else "봇과 같은 음성 채널에서 곡을 삭제하세요."
                hook.editOriginal(result).setAllowedMentions(emptyList()).queue({hook.deleteOriginal().queueAfter(3,java.util.concurrent.TimeUnit.SECONDS,{}, {})},{})
                panels.refresh(guild.id)
            },{hook.editOriginal("멤버 정보를 확인하지 못했습니다.").queue({}, {})})
        },{})
    }

    fun disconnectAll()=playback.disconnectAll()
    override fun close() {panels.close();workers.shutdownNow();playback.close()}
}

object DiscordMusicChannelAccess {
    fun manage(config:DiscordConfig,guildId:String,roles:Set<String>,hasManageChannel:Boolean):Boolean =
        DiscordMusicAccess.allowed(config,guildId,true,"queue",null,null) && (hasManageChannel || config.superAdmins.any {it.guildId==guildId && it.roleId in roles})
    fun matches(record:MusicChannelRecord,guildId:String,channelId:String,panelId:String?=null)=
        record.guildId==guildId && record.channelId==channelId && (panelId==null || record.panelId==panelId)
}
