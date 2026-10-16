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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.ln
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class MusicRequester(val name:String,val avatarUrl:String?,val userId:String)
data class MusicTrackView(val title:String,val url:String?,val identifier:String?,val duration:Long,val position:Long=0,val requester:MusicRequester?=null,val id:String?=null)
data class MusicSnapshot(val current:MusicTrackView?,val queue:List<MusicTrackView>,val paused:Boolean,val channelId:String?,val error:String?,val autoplay:Boolean=true,val repeat:Boolean=false)

/** At most a six percent weight bonus for popular songs; every eligible song remains possible. */
internal fun chooseAutoplay(stats:List<MusicPlayStat>,lastUrl:String?,failed:Set<String>,random:Random=Random.Default):MusicSavedTrack? {
    val eligible=stats.filter {it.completions>0 && it.track.url !in failed}.let { all ->
        if(all.size>1) all.filter {it.track.url!=lastUrl} else all
    }
    if(eligible.isEmpty()) return null
    val maxCount=eligible.maxOf {it.completions}
    val denominator=ln(1.0+maxCount)
    val weights=eligible.map {1.0+0.06*ln(1.0+it.completions)/denominator}
    var draw=random.nextDouble()*weights.sum()
    eligible.forEachIndexed { index, stat ->
        draw-=weights[index]
        if(draw<0) return stat.track
    }
    return eligible.last().track
}

/** Local Lavaplayer engine. All queue mutations for a guild are serialized on its Session lock. */
class DiscordMusicPlayback internal constructor(private val manager: AudioPlayerManager,private val library:DiscordMusicLibraryStore=DiscordMusicLibraryStore()) : AutoCloseable {
    constructor(library:DiscordMusicLibraryStore=DiscordMusicLibraryStore()) : this(DefaultAudioPlayerManager().apply {
        registerSourceManager(YoutubeAudioSourceManager(Web(ClientOptions().apply {playback=false}),CurrentYoutubeIos(),TvHtml5Simply(),AndroidVr(),WebEmbedded()))
    },library)

    private inner class Session(val guild: Guild) : AudioEventAdapter() {
        val player: AudioPlayer = manager.createPlayer()
        val pending = ArrayDeque<AudioTrack>()
        val history = ArrayDeque<AudioTrack>()
        val finished=Collections.newSetFromMap(WeakHashMap<AudioTrack,Boolean>())
        val transitioned=Collections.newSetFromMap(WeakHashMap<AudioTrack,Boolean>())
        var generation = 0L
        var autoplayRevision=0L
        var lastError: String? = null
        var channelId: String? = null
        var autoplayLastUrl:String?=null
        var repeat=false
        val failedAutoUrls=mutableSetOf<String>()
        init { player.addListener(this) }

        override fun onTrackStart(player:AudioPlayer, track:AudioTrack) { changed(guild.id) }

        fun remember(track:AudioTrack) {
            if (history.size >= 20) history.removeFirst()
            history.addLast(cloneWithRequester(track))
        }

        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            synchronized(this) {
                // The player can clear its current track before delivering the end event.
                // Check the request generation even when clear() could not see that track.
                if(trackMeta[track]?.generation!=generation) return
                if (endReason==AudioTrackEndReason.FINISHED && track !in transitioned && finished.add(track) && trackMeta[track]?.manual==true) {
                    safeSaved(track)?.let { runCatching { library.recordCompletion(guild.id,it) } }
                }
                if(endReason.mayStartNext && transitioned.add(track) && (player.playingTrack == null || player.playingTrack === track)) {
                    if(endReason==AudioTrackEndReason.FINISHED && repeat) {
                        player.startTrack(cloneWithRequester(track),false)
                        changed(guild.id)
                        return
                    }
                    if(endReason==AudioTrackEndReason.FINISHED) autoplayLastUrl=safeSaved(track)?.url
                    remember(track)
                    advance(false)
                    if(endReason==AudioTrackEndReason.FINISHED && player.playingTrack==null) requestAutoplay(this)
                }
            }
            changed(guild.id)
        }

        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
            synchronized(this) {
                lastError = "재생 중 오류가 발생해 다음 곡으로 이동합니다."
                if (player.playingTrack === track && transitioned.add(track)) { remember(track); advance(false) }
            }
            changed(guild.id)
        }

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
            synchronized(this) {
                lastError = "재생이 멈춰 다음 곡으로 이동합니다."
                if (player.playingTrack === track && transitioned.add(track)) { remember(track); advance(false) }
            }
            changed(guild.id)
        }

        fun advance(rememberCurrent:Boolean=true) {
            if (rememberCurrent) player.playingTrack?.let { transitioned.add(it);remember(it) }
            if (pending.isEmpty()) {
                player.stopTrack()
            } else player.startTrack(pending.removeFirst(), false)
            changed(guild.id)
        }

        fun clear() {
            generation++
            autoplayRevision++
            player.playingTrack?.let { transitioned.add(it) }
            pending.clear()
            history.clear()
            player.stopTrack()
            player.isPaused = false
            lastError = null
            changed(guild.id)
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private data class TrackMeta(val id:String,val manual:Boolean,val requester:MusicRequester?,val generation:Long)
    private val trackMeta=Collections.synchronizedMap(WeakHashMap<AudioTrack,TrackMeta>())
    private fun mark(track:AudioTrack,manual:Boolean,generation:Long,requester:MusicRequester?=null) { trackMeta[track]=TrackMeta(UUID.randomUUID().toString(),manual,requester,generation) }
    private fun cloneWithRequester(track:AudioTrack):AudioTrack=track.makeClone().also {copy -> trackMeta[track]?.let {trackMeta[copy]=it.copy(id=UUID.randomUUID().toString())} }
    @Volatile private var changeListener:(String)->Unit = {}

    fun setChangeListener(listener:(String)->Unit) { changeListener=listener }
    private fun changed(guildId:String) { runCatching { changeListener(guildId) } }

    private fun AudioTrack.view():MusicTrackView = MusicTrackView(info.title,info.uri,info.identifier,duration,position,trackMeta[this]?.requester,trackMeta[this]?.id)
    private fun safeSaved(track:AudioTrack):MusicSavedTrack? = track.info.uri?.takeIf(::isYoutubeUrl)?.let { url ->
        val canonical=track.info.identifier?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{11}"))}?.let {"https://www.youtube.com/watch?v=$it"} ?: url
        MusicSavedTrack(track.info.title.filterNot { it.isISOControl() }.take(200).ifBlank { "제목 없음" },canonical)
    }
    private fun isYoutubeUrl(value:String):Boolean = runCatching { URI(value) }.getOrNull()?.let {
        it.scheme.equals("https",true) && it.host?.lowercase() in setOf("youtube.com","www.youtube.com","m.youtube.com","music.youtube.com","youtu.be") && it.userInfo==null && it.port==-1
    } ?: false
    private fun requestAutoplay(session:Session) {
        val guildId=session.guild.id
        if(!library.autoplayEnabled(guildId) || sessions[guildId]!==session) return
        val candidate=chooseAutoplay(runCatching { library.stats(guildId) }.getOrDefault(emptyList())
            .filter { isYoutubeUrl(it.track.url) },session.autoplayLastUrl,session.failedAutoUrls) ?: return
        session.autoplayLastUrl=candidate.url
        val generation=session.generation
        val revision=session.autoplayRevision
        manager.loadItemOrdered("autoplay:$guildId",candidate.url,object:AudioLoadResultHandler {
            override fun trackLoaded(track:AudioTrack) { synchronized(session) {
                if(sessions[guildId]===session && generation==session.generation && revision==session.autoplayRevision && session.player.playingTrack==null && session.pending.isEmpty() && library.autoplayEnabled(guildId)) {
                    mark(track,false,generation)
                    session.player.startTrack(track,false)
                    changed(guildId)
                }
            } }
            override fun playlistLoaded(playlist:AudioPlaylist) { playlist.tracks.firstOrNull()?.let(::trackLoaded) }
            override fun noMatches() { synchronized(session) {if(sessions[guildId]===session && generation==session.generation && revision==session.autoplayRevision){session.failedAutoUrls.add(candidate.url);session.lastError="자동재생 곡을 찾지 못했습니다.";changed(guildId)}} }
            override fun loadFailed(exception:FriendlyException) { synchronized(session) {if(sessions[guildId]===session && generation==session.generation && revision==session.autoplayRevision){session.failedAutoUrls.add(candidate.url);session.lastError="자동재생 곡을 불러오지 못했습니다.";changed(guildId)}} }
        })
    }
    fun snapshot(guildId:String):MusicSnapshot = sessions[guildId]?.let { session -> synchronized(session) {
        MusicSnapshot(session.player.playingTrack?.view(),session.pending.map { it.view() },session.player.isPaused,session.channelId,session.lastError,library.autoplayEnabled(guildId),session.repeat)
    } } ?: MusicSnapshot(null,emptyList(),false,null,null,library.autoplayEnabled(guildId))

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

    fun play(guild: Guild, channel: AudioChannel, query: String, requester:MusicRequester?=null): String = playInternal(guild,channel,query,requester,null,null,15_000)

    private fun playInternal(guild: Guild, channel: AudioChannel, query: String, requester:MusicRequester?,expectedSession:Session?,expectedGeneration:Long?,timeoutMs:Long): String {
        val input = query.trim()
        if (input.isEmpty()) return "검색어 또는 YouTube URL을 입력하세요."
        if(expectedSession!=null && sessions[guild.id]!==expectedSession) return "요청이 취소되었습니다."
        if(expectedGeneration!=null && sessions[guild.id]?.let { synchronized(it) {it.generation} }!=expectedGeneration) return "요청이 취소되었습니다."
        val source = if (input.contains(":") || input.startsWith("//")) {
            val uri = runCatching { URI(input) }.getOrNull()
            if (uri?.scheme != "https" || uri.host?.lowercase() !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be") ||
                uri.userInfo != null || uri.port != -1) return "HTTPS YouTube URL만 재생할 수 있습니다."
            canonicalPlaylistUrl(input) ?: input
        } else "ytsearch:$input"
        if(expectedSession!=null && sessions[guild.id]!==expectedSession) return "요청이 취소되었습니다."
        val joined = if(expectedSession==null) join(guild, channel) else "음성 채널에 연결되어 있습니다."
        val session = expectedSession ?: session(guild)
        if(expectedSession!=null && session!==expectedSession) return "요청이 취소되었습니다."
        val generation = synchronized(session) { session.autoplayRevision++;session.failedAutoUrls.clear();session.generation }
        if(expectedGeneration!=null && generation!=expectedGeneration) return "요청이 취소되었습니다."
        val result = CompletableFuture<String>()
        val expired=AtomicBoolean(false)
        manager.loadItemOrdered(guild.id, source, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) { enqueue(listOf(track)) }
            override fun playlistLoaded(playlist: AudioPlaylist) {
                val tracks = if (playlist.isSearchResult) listOfNotNull(playlist.tracks.firstOrNull()) else playlist.tracks.take(100)
                enqueue(tracks)
            }
            private fun enqueue(tracks: List<AudioTrack>) {
                synchronized(session) {
                    if (expired.get() || session.generation != generation || sessions[guild.id] !== session) { result.complete("요청이 취소되었습니다."); return }
                    val available = (100 - session.pending.size - if (session.player.playingTrack == null) 0 else 1).coerceAtLeast(0)
                    if (available == 0) { result.complete("대기열이 가득 찼습니다(최대 100곡)."); return }
                    tracks.take(available).forEach { track ->
                        mark(track,true,generation,requester)
                        if (session.player.playingTrack == null && session.pending.isEmpty()) session.player.startTrack(track, false)
                        else session.pending.addLast(track)
                    }
                    session.lastError = null
                    changed(guild.id)
                    result.complete(if (tracks.isEmpty()) "YouTube에서 일치하는 곡을 찾지 못했습니다." else "${tracks.take(available).size}곡을 재생 또는 대기열에 추가했습니다. $joined")
                }
            }
            private fun fail(message:String) = synchronized(session) {
                if(expired.get() || session.generation!=generation || sessions[guild.id]!==session) result.complete("요청이 취소되었습니다.")
                else {session.lastError=message;changed(guild.id);result.complete(message)}
            }
            override fun noMatches() {fail("YouTube에서 일치하는 곡을 찾지 못했습니다.")}
            override fun loadFailed(exception: FriendlyException) {fail("YouTube 곡을 불러오지 못했습니다. URL 또는 검색어를 확인하세요.")}
        })
        return runCatching { result.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrElse { expired.set(true);"YouTube 응답이 지연되어 요청을 취소했습니다." }
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
        if(current!=null && session.history.isNotEmpty() && session.pending.size>=99) return@synchronized "대기열이 가득 찼습니다. 한 곡을 삭제한 뒤 이전 곡으로 이동하세요."
        val previous=session.history.pollLast()
        if(previous==null && current==null) return@synchronized "이전 곡이 없습니다."
        if(previous==null) {
            session.transitioned.add(current!!)
            session.player.startTrack(cloneWithRequester(current!!),false)
            changed(guildId)
            "현재 곡을 처음부터 재생합니다."
        } else {
            if(current!=null) session.transitioned.add(current)
            if(current!=null) session.pending.addFirst(cloneWithRequester(current))
            session.player.startTrack(cloneWithRequester(previous),false)
            changed(guildId)
            "이전 곡을 재생합니다."
        }
    } } ?: "이전 곡이 없습니다."

    fun removeQueued(guildId:String,ids:List<String>):String = sessions[guildId]?.let { session -> synchronized(session) {
        if(ids.isEmpty()) return@synchronized "제거할 곡을 선택하세요."
        val selected=ids.toSet()
        val before=session.pending.size
        session.pending.removeIf { trackMeta[it]?.id in selected }
        val removed=before-session.pending.size
        if(removed>0) changed(guildId)
        if(removed==0) "선택한 대기열 항목이 없습니다. 목록을 새로 확인하세요." else "대기열에서 ${removed}곡을 제거했습니다."
    } } ?: "대기열이 비어 있습니다."

    fun autoplay(guildId:String,enabled:Boolean):String {
        library.setAutoplay(guildId,enabled)
        sessions[guildId]?.let { session -> synchronized(session) { session.autoplayRevision++;changed(guildId) } }
        return if(enabled) "자동재생을 켰습니다." else "자동재생을 껐습니다."
    }

    /** [enabled] null toggles. Replays a track that finished; skipping still moves to the next track. */
    fun repeat(guildId:String,enabled:Boolean?):String = sessions[guildId]?.let { session -> synchronized(session) {
        session.repeat=enabled ?: !session.repeat
        changed(guildId)
        if(session.repeat) "한 곡 반복을 켰습니다. 끝까지 재생된 곡을 다시 재생합니다." else "한 곡 반복을 껐습니다."
    } } ?: "먼저 음성 채널에 연결하세요."

    fun savedQueue(guildId:String):List<MusicSavedTrack> = sessions[guildId]?.let { session -> synchronized(session) {
        (listOfNotNull(session.player.playingTrack)+session.pending.toList()).mapNotNull(::safeSaved)
    } } ?: emptyList()

    private fun canonicalPlaylistUrl(value:String):String? {
        val uri=runCatching { URI(value) }.getOrNull() ?: return null
        if(!isYoutubeUrl(value)) return null
        val list=uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            val pair=part.split('=',limit=2)
            if(pair[0]!="list") null else pair.getOrNull(1)?.let { URLDecoder.decode(it,StandardCharsets.UTF_8) }
        }?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{10,80}"))} ?: return null
        return "https://www.youtube.com/playlist?list=$list"
    }

    /** Loads only playlist metadata; this never joins a voice channel. */
    fun inspectPlaylist(query:String):List<MusicSavedTrack> {
        val url=canonicalPlaylistUrl(query.trim()) ?: return emptyList()
        val result=CompletableFuture<List<MusicSavedTrack>>()
        manager.loadItemOrdered("playlist-inspect",url,object:AudioLoadResultHandler {
            override fun trackLoaded(track:AudioTrack) { result.complete(emptyList()) }
            override fun playlistLoaded(playlist:AudioPlaylist) { result.complete(playlist.tracks.take(100).mapNotNull(::safeSaved)) }
            override fun noMatches() {result.complete(emptyList())}
            override fun loadFailed(exception:FriendlyException) {result.complete(emptyList())}
        })
        return runCatching { result.get(15,TimeUnit.SECONDS) }.getOrDefault(emptyList())
    }

    fun playSaved(guild:Guild,channel:AudioChannel,tracks:List<MusicSavedTrack>,requester:MusicRequester?=null):String {
        if(tracks.isEmpty()) return "재생할 곡이 없습니다."
        val valid=tracks.take(100).filter { isYoutubeUrl(it.url) }
        if(valid.isEmpty()) return "재생 가능한 YouTube 곡이 없습니다."
        join(guild,channel)
        val session=sessions[guild.id] ?: return "요청이 취소되었습니다."
        val generation=synchronized(session) {session.generation}
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90)
        var added=0
        for(track in valid) {
            if(sessions[guild.id]!==session || synchronized(session) {session.generation!=generation || session.pending.size+(if(session.player.playingTrack==null) 0 else 1)>=100}) break
            val remaining=TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())
            if(remaining<=0) break
            val response=playInternal(guild,channel,track.url,requester,session,generation,minOf(15_000,remaining))
            if(response.contains("재생 또는 대기열에 추가")) added++
            if(response.contains("취소") || response.contains("가득")) break
        }
        return if(added==0) "저장한 곡을 불러오지 못했습니다." else "저장한 ${valid.size}곡 중 ${added}곡을 순서대로 추가했습니다."
    }

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
