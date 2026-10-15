package local.servermanager

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.components.actionrow.ActionRow
import java.awt.Color
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DiscordMusicPanel(
    private val store:DiscordMusicChannelStore,
    private val playback:DiscordMusicPlayback,
    private val currentClient:()->JDA?
):AutoCloseable {
    private val scheduler=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"discord-music-panel").apply { isDaemon=true } }
    private val pending=ConcurrentHashMap<String,ScheduledFuture<*>>()
    private val inflight=ConcurrentHashMap.newKeySet<String>()

    init { playback.setChangeListener { guildId -> refresh(guildId) } }

    fun get(guildId:String):MusicChannelRecord?=store.get(guildId)

    fun register(guild:Guild,channel:TextChannel):String {
        require(channel.guild.id==guild.id) { "같은 Discord 서버의 텍스트 채널을 선택하세요." }
        val previous=store.get(guild.id)
        if(previous?.channelId==channel.id) {
            refresh(guild.id)
            return "이미 등록된 음악 채널입니다. 고정 패널을 확인하거나 복구합니다."
        }
        store.put(MusicChannelRecord(guild.id,channel.id))
        previous?.panelId?.let { oldId -> guild.getTextChannelById(previous.channelId)?.unpinMessageById(oldId)?.queue({}, {}) }
        refresh(guild.id)
        return "${channel.name} 채널을 음악 채널로 등록했습니다. 고정 패널을 준비합니다."
    }

    fun unregister(guildId:String):String {
        val old=store.remove(guildId) ?: return "등록된 음악 채널이 없습니다."
        pending.remove(guildId)?.cancel(false)
        val channel=currentClient()?.getGuildById(guildId)?.getTextChannelById(old.channelId)
        old.panelId?.let { id -> channel?.unpinMessageById(id)?.queue({}, {}) }
        return "음악 채널 등록을 해제했습니다."
    }

    fun refresh(guildId:String) {
        if(store.get(guildId)==null || scheduler.isShutdown) return
        pending.compute(guildId) { _,old ->
            old?.cancel(false)
            scheduler.schedule({ pending.remove(guildId); update(guildId) },1,TimeUnit.SECONDS)
        }
    }

    fun restore(jda:JDA,guildIds:Set<String>) {
        if(jda!==currentClient()) return
        store.all().filter { it.guildId in guildIds }.forEach { refresh(it.guildId) }
    }

    private fun update(guildId:String) {
        if(!inflight.add(guildId)) { refresh(guildId);return }
        val jda=currentClient()
        val record=store.get(guildId)
        val channel=record?.let { jda?.getGuildById(guildId)?.getTextChannelById(it.channelId) }
        if(jda==null || record==null || channel==null) { inflight.remove(guildId);return }
        val embed=runCatching { render(playback.snapshot(guildId)) }.getOrElse { inflight.remove(guildId);return }
        if(record.panelId==null) { create(channel,record,embed,jda);return }
        channel.editMessageEmbedsById(record.panelId,embed).setAllowedMentions(emptyList()).queue({
            inflight.remove(guildId)
        },{ error ->
            if(error is ErrorResponseException && error.errorResponse==ErrorResponse.UNKNOWN_MESSAGE && store.get(guildId)==record && jda===currentClient()) {
                store.put(record.copy(panelId=null));create(channel,record.copy(panelId=null),embed,jda)
            } else inflight.remove(guildId)
        })
    }

    private fun create(channel:TextChannel,record:MusicChannelRecord,embed:MessageEmbed,jda:JDA) {
        if(store.get(record.guildId)!=record || jda!==currentClient()) { inflight.remove(record.guildId);return }
        channel.sendMessageEmbeds(embed)
            .setComponents(ActionRow.of(Button.success("music:resume","▶ 재생"),Button.secondary("music:pause","⏸ 일시정지"),
                Button.danger("music:stop","⏹ 정지"),Button.secondary("music:previous","⏮ 이전"),Button.secondary("music:skip","⏭ 다음")))
            .setAllowedMentions(emptyList())
            .queue({ message ->
                if(store.get(record.guildId)==record && jda===currentClient()) {
                    if(runCatching { store.put(record.copy(panelId=message.id)) }.isSuccess) message.pin().queue({}, {})
                } else message.delete().queue({}, {})
                inflight.remove(record.guildId)
            }, { inflight.remove(record.guildId) })
    }

    override fun close() {
        playback.setChangeListener { }
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        scheduler.shutdownNow()
    }

    companion object {
        fun render(snapshot:MusicSnapshot):MessageEmbed {
            val current=snapshot.current
            val title=current?.title?.replace('\n',' ')?.replace('\r',' ')?.take(200) ?: "재생 중인 곡 없음"
            val safeUrl=current?.url?.let(::safeYoutubeUrl)
            val status=when { current==null -> "대기 중"; snapshot.paused -> "일시정지"; else -> "재생 중" }
            val embed=EmbedBuilder().setTitle("🎵 음악 플레이어").setColor(Color(0x5865F2))
                .addField("현재 곡",title,false)
                .addField("상태",status+(snapshot.channelId?.let { " · <#$it>" } ?: ""),false)
            if(safeUrl!=null) embed.addField("YouTube",safeUrl,false)
            current?.requester?.let {requester ->
                embed.setAuthor("등록: "+requester.name.replace('\n',' ').replace('\r',' ').take(80),null,requester.avatarUrl?.let(::safeAvatarUrl))
            }
            val videoId=current?.identifier?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            if(videoId!=null && safeUrl!=null) embed.setThumbnail("https://img.youtube.com/vi/$videoId/hqdefault.jpg")
            val queue=snapshot.queue.take(10).mapIndexed { index,track ->
                "${index+1}. ${track.title.replace('\n',' ').replace('\r',' ').take(65)}"+
                    (track.requester?.let {" · "+it.name.replace('\n',' ').replace('\r',' ').take(18)} ?: "")
            }
            embed.addField("대기열 · ${snapshot.queue.size}곡",queue.joinToString("\n").ifEmpty { "비어 있습니다." },false)
            snapshot.error?.take(200)?.let { embed.setFooter(it) }
            return embed.build()
        }

        fun safeYoutubeUrl(raw:String):String? {
            val uri=runCatching { URI(raw) }.getOrNull() ?: return null
            val host=uri.host?.lowercase()?.removePrefix("www.") ?: return null
            return raw.takeIf { uri.scheme=="https" && uri.userInfo==null && uri.port==-1 && host in setOf("youtube.com","m.youtube.com","music.youtube.com","youtu.be") }
        }
        fun safeAvatarUrl(raw:String):String? {
            val uri=runCatching {URI(raw)}.getOrNull() ?: return null
            return raw.takeIf {it.length<=1024 && uri.scheme=="https" && uri.userInfo==null && uri.port==-1 && uri.host in setOf("cdn.discordapp.com","media.discordapp.net")}
        }
    }
}
