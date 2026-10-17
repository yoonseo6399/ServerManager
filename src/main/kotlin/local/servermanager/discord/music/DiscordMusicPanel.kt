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
import net.dv8tion.jda.api.components.selections.StringSelectMenu
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
    private val pages=ConcurrentHashMap<String,Int>()

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
        pages.remove(guild.id)
        previous?.panelId?.let { oldId -> guild.getTextChannelById(previous.channelId)?.unpinMessageById(oldId)?.queue({}, {}) }
        refresh(guild.id)
        return "${channel.name} 채널을 음악 채널로 등록했습니다. 고정 패널을 준비합니다."
    }

    fun unregister(guildId:String):String {
        val old=store.remove(guildId) ?: return "등록된 음악 채널이 없습니다."
        pending.remove(guildId)?.cancel(false)
        pages.remove(guildId)
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

    fun page(guildId:String,page:Int) {
        if(store.get(guildId)==null) return
        val snapshot=playback.snapshot(guildId)
        pages[guildId]=boundedPage(snapshot,page)
        refresh(guildId)
    }

    fun components(guildId:String,snapshot:MusicSnapshot):List<ActionRow> {
        val page=boundedPage(snapshot,pages[guildId] ?: 0)
        pages[guildId]=page
        return componentRows(snapshot,page)
    }

    private fun lastPage(snapshot:MusicSnapshot)=((snapshot.queue.size.coerceAtMost(100)-1)/25).coerceAtLeast(0)
    private fun boundedPage(snapshot:MusicSnapshot,page:Int)=page.coerceIn(0,lastPage(snapshot))

    private fun update(guildId:String) {
        if(!inflight.add(guildId)) { refresh(guildId);return }
        val jda=currentClient()
        val record=store.get(guildId)
        val channel=record?.let { jda?.getGuildById(guildId)?.getTextChannelById(it.channelId) }
        if(jda==null || record==null || channel==null) { inflight.remove(guildId);return }
        val snapshot=runCatching { playback.snapshot(guildId) }.getOrElse { inflight.remove(guildId);return }
        val page=boundedPage(snapshot,pages[guildId] ?: 0)
        pages[guildId]=page
        val embed=render(snapshot,page)
        val rows=components(guildId,snapshot)
        if(record.panelId==null) { create(channel,record,embed,rows,jda);return }
        channel.editMessageEmbedsById(record.panelId,embed).setComponents(rows).setAllowedMentions(emptyList()).queue({
            inflight.remove(guildId)
        },{ error ->
            if(error is ErrorResponseException && error.errorResponse==ErrorResponse.UNKNOWN_MESSAGE && store.get(guildId)==record && jda===currentClient()) {
                store.put(record.copy(panelId=null));create(channel,record.copy(panelId=null),embed,rows,jda)
            } else inflight.remove(guildId)
        })
    }

    private fun create(channel:TextChannel,record:MusicChannelRecord,embed:MessageEmbed,rows:List<ActionRow>,jda:JDA) {
        if(store.get(record.guildId)!=record || jda!==currentClient()) { inflight.remove(record.guildId);return }
        channel.sendMessageEmbeds(embed)
            .setComponents(rows)
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
        pages.clear()
        scheduler.shutdownNow()
    }

    companion object {
        fun componentRows(snapshot:MusicSnapshot,requestedPage:Int=0):List<ActionRow> {
            val last=((snapshot.queue.size.coerceAtMost(100)-1)/25).coerceAtLeast(0)
            val page=requestedPage.coerceIn(0,last)
            val rows=mutableListOf(ActionRow.of(Button.success("music:resume","▶ 재생"),Button.secondary("music:pause","⏸ 일시정지"),
                Button.danger("music:stop","⏹ 정지"),Button.secondary("music:previous","⏮ 이전"),Button.secondary("music:skip","⏭ 다음")))
            val start=page*25
            val selectable=snapshot.queue.take(100).drop(start).take(25).mapIndexedNotNull { index,track ->
                track.id?.takeIf { it.isNotBlank() && it.length<=100 }?.let { id ->
                    id to "${start+index+1}. ${track.title.replace('\n',' ').replace('\r',' ').take(85)}"
                }
            }.distinctBy { it.first }
            if(selectable.isNotEmpty()) {
                val menu=StringSelectMenu.create("music:remove").setPlaceholder("삭제할 곡 선택")
                    .setMinValues(1).setMaxValues(selectable.size)
                selectable.forEach { (id,label) -> menu.addOption(label.take(100),id) }
                rows+=ActionRow.of(menu.build())
            }
            if(last>0) rows+=ActionRow.of(
                Button.secondary("music:page:${page-1}","◀ 이전 페이지").withDisabled(page==0),
                Button.secondary("music:page:${page+1}","다음 페이지 ▶").withDisabled(page>=last))
            rows+=ActionRow.of(
                if(snapshot.autoplay) Button.success("music:autoplay","📻 자동재생 ON") else Button.secondary("music:autoplay","📻 자동재생 OFF"),
                if(snapshot.repeat) Button.success("music:repeat","🔂 한 곡 반복 ON") else Button.secondary("music:repeat","🔂 한 곡 반복 OFF"))
            return rows
        }

        fun render(snapshot:MusicSnapshot,page:Int=0):MessageEmbed {
            val current=snapshot.current
            val title=current?.title?.replace('\n',' ')?.replace('\r',' ')?.take(200) ?: "재생 중인 곡 없음"
            val safeUrl=current?.url?.let(::safeYoutubeUrl)
            val status=when { current==null -> "대기 중"; snapshot.paused -> "일시정지"; else -> "재생 중" }
            val embed=EmbedBuilder().setTitle("🎵 음악 플레이어").setColor(Color(0x5865F2))
                .addField("현재 곡",title,false)
                .addField("상태",status+(snapshot.channelId?.let { " · <#$it>" } ?: "")+(if(snapshot.autoplay) " · 자동 재생 켜짐" else " · 자동 재생 꺼짐")+(if(snapshot.repeat) " · 한 곡 반복 켜짐" else ""),false)
            if(safeUrl!=null) embed.addField("YouTube",safeUrl,false)
            current?.requester?.let {requester ->
                embed.setAuthor("등록: "+requester.name.replace('\n',' ').replace('\r',' ').take(80),null,requester.avatarUrl?.let(::safeAvatarUrl))
            }
            val videoId=current?.identifier?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
            if(videoId!=null && safeUrl!=null) embed.setThumbnail("https://img.youtube.com/vi/$videoId/hqdefault.jpg")
            val last=((snapshot.queue.size.coerceAtMost(100)-1)/25).coerceAtLeast(0)
            val shown=page.coerceIn(0,last)
            val queue=snapshot.queue.take(100).drop(shown*25).take(25).mapIndexed { index,track ->
                "${shown*25+index+1}. ${track.title.replace('\n',' ').replace('\r',' ').take(22)}"+
                    (track.requester?.let {" · "+it.name.replace('\n',' ').replace('\r',' ').take(10)} ?: "")
            }
            embed.addField("대기열 · ${snapshot.queue.size}곡 · ${shown+1}/${last+1}페이지",queue.joinToString("\n").ifEmpty { "비어 있습니다." }.take(1024),false)
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
