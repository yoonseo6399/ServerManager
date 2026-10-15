package local.servermanager

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import dev.lavalink.youtube.YoutubeAudioSourceManager
import dev.lavalink.youtube.clients.*
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

data class MusicRequester(val name:String,val avatarUrl:String?,val userId:String)
data class MusicTrackView(val title:String,val url:String?,val identifier:String?,val duration:Long,val position:Long=0,val requester:MusicRequester?=null)
data class MusicSnapshot(val current:MusicTrackView?,val queue:List<MusicTrackView>,val paused:Boolean,val channelId:String?,val error:String?)

/** Local Lavaplayer engine. All queue mutations for a guild are serialized on its Session lock. */
class DiscordMusicPlayback internal constructor(private val manager: AudioPlayerManager) : AutoCloseable {
    constructor() : this(DefaultAudioPlayerManager().apply {
        registerSourceManager(YoutubeAudioSourceManager(Web(ClientOptions().apply {playback=false}),CurrentYoutubeIos(),TvHtml5Simply(),AndroidVr(),WebEmbedded()))
    })

    private inner class Session(val guild: Guild) : AudioEventAdapter() {
        val player: AudioPlayer = manager.createPlayer()
        val pending = ArrayDeque<AudioTrack>()
        val history = ArrayDeque<AudioTrack>()
        var generation = 0L
        var lastError: String? = null
        var channelId: String? = null
        init { player.addListener(this) }

        override fun onTrackStart(player:AudioPlayer, track:AudioTrack) { changed(guild.id) }

        fun remember(track:AudioTrack) {
            if (history.size >= 20) history.removeFirst()
            history.addLast(cloneWithRequester(track))
        }

        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            if (endReason.mayStartNext) synchronized(this) {
                if (player.playingTrack == null || player.playingTrack === track) {
                    remember(track)
                    advance(false)
                }
            }
            changed(guild.id)
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
            synchronized(this) {
                lastError = "재생 중 오류가 발생해 다음 곡으로 이동합니다."
                if (player.playingTrack === track) { remember(track); advance(false) }
            }
            changed(guild.id)
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            synchronized(this) {
                lastError = "재생이 멈춰 다음 곡으로 이동합니다."
                if (player.playingTrack === track) { remember(track); advance(false) }
            }
            changed(guild.id)
        }

        fun advance(rememberCurrent:Boolean=true) {
            if (rememberCurrent) player.playingTrack?.let(::remember)
            if (pending.isEmpty()) player.stopTrack() else player.startTrack(pending.removeFirst(), false)
            changed(guild.id)
        }

        fun clear() {
            generation++
            pending.clear()
            history.clear()
            player.stopTrack()
            player.isPaused = false
            lastError = null
            changed(guild.id)
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val requesters=java.util.Collections.synchronizedMap(java.util.WeakHashMap<AudioTrack,MusicRequester>())
    private fun cloneWithRequester(track:AudioTrack):AudioTrack=track.makeClone().also {copy -> requesters[track]?.let {requesters[copy]=it} }
    @Volatile private var changeListener:(String)->Unit = {}

    fun setChangeListener(listener:(String)->Unit) { changeListener=listener }
    private fun changed(guildId:String) { runCatching { changeListener(guildId) } }

    private fun AudioTrack.view():MusicTrackView = MusicTrackView(info.title,info.uri,info.identifier,duration,position,requesters[this])
    fun snapshot(guildId:String):MusicSnapshot = sessions[guildId]?.let { session -> synchronized(session) {
        MusicSnapshot(session.player.playingTrack?.view(),session.pending.map { it.view() },session.player.isPaused,session.channelId,session.lastError)
    } } ?: MusicSnapshot(null,emptyList(),false,null,null)

    private fun session(guild: Guild): Session = sessions.computeIfAbsent(guild.id) { Session(guild) }

    fun channelId(guildId:String):String?=sessions[guildId]?.let { synchronized(it) {it.channelId} }

    fun join(guild: Guild, channel: AudioChannel): String {
        val audio = guild.audioManager
        val session=session(guild)
        synchronized(session) {
        val active=audio.connectedChannel?.id ?: session.channelId
        check(active==null || active==channel.id) { "봇이 다른 음성 채널에서 사용 중입니다." }
        if (active == channel.id) return "이미 ${channel.name} 음성 채널에 연결했거나 연결 중입니다."
        session.channelId=channel.id
        changed(guild.id)
        audio.connectionListener=object:net.dv8tion.jda.api.audio.hooks.ConnectionListener {
            override fun onStatusChange(status:net.dv8tion.jda.api.audio.hooks.ConnectionStatus) {
                if(status.name.startsWith("DISCONNECTED_") || status==net.dv8tion.jda.api.audio.hooks.ConnectionStatus.ERROR_CONNECTION_TIMEOUT) {
                    if(sessions.remove(guild.id,session)) synchronized(session) {session.clear();session.player.destroy();changed(guild.id)}
                }
            }
        }
        audio.sendingHandler = object : AudioSendHandler {
            private var frame: AudioFrame? = null
            override fun canProvide(): Boolean {
                if (frame == null) frame = session.player.provide()
                return frame != null
            }
            override fun provide20MsAudio(): ByteBuffer? {
                val next = frame ?: session.player.provide()
                frame = null
                return next?.data?.let(ByteBuffer::wrap)
            }
            override fun isOpus() = true
        }
        try {audio.openAudioConnection(channel)} catch(e:Exception) {session.channelId=null;throw e}
        return "${channel.name} 음성 채널에 연결합니다."
        }
    }

    fun play(guild: Guild, channel: AudioChannel, query: String, requester:MusicRequester?=null): String {
        val input = query.trim()
        if (input.isEmpty()) return "검색어 또는 YouTube URL을 입력하세요."
        val source = if (input.contains(":") || input.startsWith("//")) {
            val uri = runCatching { URI(input) }.getOrNull()
            if (uri?.scheme != "https" || uri.host?.lowercase() !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be") ||
                uri.userInfo != null || uri.port != -1) return "HTTPS YouTube URL만 재생할 수 있습니다."
            input
        } else "ytsearch:$input"
        val joined = join(guild, channel)
        val session = session(guild)
        val generation = synchronized(session) { session.generation }
        val result = CompletableFuture<String>()
        manager.loadItemOrdered(guild.id, source, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { enqueue(listOf(track)) }
            override fun playlistLoaded(playlist: AudioPlaylist) {
                val tracks = if (playlist.isSearchResult) listOfNotNull(playlist.tracks.firstOrNull()) else playlist.tracks.take(100)
                enqueue(tracks)
            }
            private fun enqueue(tracks: List<AudioTrack>) {
                synchronized(session) {
                    if (session.generation != generation || sessions[guild.id] !== session) { result.complete("요청이 취소되었습니다."); return }
                    val available = (100 - session.pending.size - if (session.player.playingTrack == null) 0 else 1).coerceAtLeast(0)
                    if (available == 0) { result.complete("대기열이 가득 찼습니다(최대 100곡)."); return }
                    tracks.take(available).forEach { track ->
                        if(requester!=null)requesters[track]=requester
                        if (session.player.playingTrack == null && session.pending.isEmpty()) session.player.startTrack(track, false)
                        else session.pending.addLast(track)
                    }
                    session.lastError = null
                    changed(guild.id)
                    result.complete(if (tracks.isEmpty()) "YouTube에서 일치하는 곡을 찾지 못했습니다." else "${tracks.take(available).size}곡을 재생 또는 대기열에 추가했습니다. $joined")
                }
            }
            override fun noMatches() { synchronized(session) { session.lastError="YouTube에서 일치하는 곡을 찾지 못했습니다.";changed(guild.id) };result.complete("YouTube에서 일치하는 곡을 찾지 못했습니다.") }
            override fun loadFailed(exception: FriendlyException) { synchronized(session) { session.lastError="YouTube 곡을 불러오지 못했습니다. URL 또는 검색어를 확인하세요.";changed(guild.id) };result.complete("YouTube 곡을 불러오지 못했습니다. URL 또는 검색어를 확인하세요.") }
        })
        return runCatching { result.get(15, TimeUnit.SECONDS) }.getOrElse { "YouTube 응답이 지연되고 있습니다. 잠시 후 대기열을 확인하세요." }
    }

    fun pause(guildId: String): String = sessions[guildId]?.let { session -> synchronized(session) {
        if (session.player.playingTrack == null) "재생 중인 곡이 없습니다." else { session.player.isPaused = true;changed(guildId); "일시정지했습니다." }
    } } ?: "재생 중인 곡이 없습니다."

    fun resume(guildId: String): String = sessions[guildId]?.let { session -> synchronized(session) {
        if (session.player.playingTrack == null && session.pending.isNotEmpty()) session.advance(false)
        if (session.player.playingTrack == null) "재생 중인 곡이 없습니다." else { session.player.isPaused = false;changed(guildId); "재생을 다시 시작했습니다." }
    } } ?: "재생 중인 곡이 없습니다."

    fun skip(guildId: String): String = sessions[guildId]?.let { session -> synchronized(session) {
        if (session.player.playingTrack == null) "재생 중인 곡이 없습니다." else {
            session.advance()
            "다음 곡으로 넘겼습니다."
        }
    } } ?: "재생 중인 곡이 없습니다."

    fun stop(guildId: String): String = sessions[guildId]?.let { session -> synchronized(session) {
        session.clear()
        "재생과 대기열을 비웠습니다."
    } } ?: "재생 중인 곡이 없습니다."

    fun previous(guildId:String):String = sessions[guildId]?.let { session -> synchronized(session) {
        val current=session.player.playingTrack
        val previous=session.history.pollLast()
        if(previous==null && current==null) return@synchronized "이전 곡이 없습니다."
        if(previous==null) {
            session.player.startTrack(cloneWithRequester(current!!),false)
            changed(guildId)
            "현재 곡을 처음부터 재생합니다."
        } else {
            if(current!=null) session.pending.addFirst(cloneWithRequester(current))
            session.player.startTrack(cloneWithRequester(previous),false)
            changed(guildId)
            "이전 곡을 재생합니다."
        }
    } } ?: "이전 곡이 없습니다."

    fun queue(guildId: String): String = sessions[guildId]?.let { session -> synchronized(session) {
        val current = session.player.playingTrack?.info?.title ?: "없음"
        val upcoming = session.pending.take(10).mapIndexed { index, track -> "${index + 1}. ${track.info.title}" }
        "현재: $current" + (if (upcoming.isEmpty()) "\n대기열이 비어 있습니다." else "\n" + upcoming.joinToString("\n")) +
            (session.lastError?.let { "\n$it" } ?: "")
    } } ?: "재생 중인 곡이 없습니다."

    fun leave(guild: Guild): String {
        sessions.remove(guild.id)?.let { synchronized(it) { it.clear(); it.player.destroy() } }
        guild.audioManager.closeAudioConnection()
        guild.audioManager.sendingHandler = null
        changed(guild.id)
        return "음성 채널에서 나왔습니다."
    }

    fun disconnectAll() {
        sessions.values.toList().forEach { runCatching {leave(it.guild)} }
    }

    override fun close() {
        try {disconnectAll()} finally {manager.shutdown()}
    }
}

/** Backport youtube-source's post-1.18.2 iOS fix: upstream commit 6579cdf. */
private class CurrentYoutubeIos:Ios() {
    override fun getBaseClientConfig(httpInterface:HttpInterface):ClientConfig = super.getBaseClientConfig(httpInterface)
        .withClientField("clientVersion","21.32.4")
        .withUserAgent("com.google.ios.youtube/21.32.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)")
    override fun getPlayerParams():String=WEB_PLAYER_PARAMS
}
