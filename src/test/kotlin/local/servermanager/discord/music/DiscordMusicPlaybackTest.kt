package local.servermanager

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.managers.AudioManager
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.random.Random

class DiscordMusicPlaybackTest {
    @Test fun `last human departure stops music leaves and invalidates pending autoplay after sixty seconds`() {
        val rig=Rig();var seconds=0L
        val playback=DiscordMusicPlayback(rig.manager,idleClock={TimeUnit.SECONDS.toNanos(seconds)},monitorEmptyChannels=false)
        val (guild,channel)=rig.guild("12345678901234567")
        val human=mock(net.dv8tion.jda.api.entities.Member::class.java);val humanUser=mock(net.dv8tion.jda.api.entities.User::class.java)
        val bot=mock(net.dv8tion.jda.api.entities.Member::class.java);val botUser=mock(net.dv8tion.jda.api.entities.User::class.java)
        `when`(human.user).thenReturn(humanUser);`when`(humanUser.isBot).thenReturn(false)
        `when`(bot.user).thenReturn(botUser);`when`(botUser.isBot).thenReturn(true)
        try {
            rig.load(playback,guild,channel,"one"){it.trackLoaded(rig.track("One"))}
            `when`(guild.audioManager.connectedChannel).thenReturn(channel as net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion)
            `when`(channel.members).thenReturn(listOf(bot,human));playback.voiceStateChanged(guild.id)
            seconds=10;`when`(channel.members).thenReturn(listOf(bot));playback.voiceStateChanged(guild.id)
            seconds=69;playback.checkEmptyChannels();verify(guild.audioManager,never()).closeAudioConnection()
            `when`(channel.members).thenReturn(listOf(bot,human));playback.voiceStateChanged(guild.id)
            seconds=70;`when`(channel.members).thenReturn(listOf(bot));playback.voiceStateChanged(guild.id)
            seconds=129;playback.checkEmptyChannels();verify(guild.audioManager,never()).closeAudioConnection()
            seconds=130;playback.checkEmptyChannels()
            verify(guild.audioManager).closeAudioConnection();verify(rig.players.single()).destroy()
            assertNull(playback.snapshot(guild.id).current);assertTrue(playback.snapshot(guild.id).queue.isEmpty())
            rig.requests.first().second.trackLoaded(rig.track("Late"))
            assertNull(playback.snapshot(guild.id).current,"late loads must not restart abandoned playback")
        } finally {playback.close()}
    }

    @Test fun `pending voice connection cannot be taken over by another channel`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val (guild,channel)=rig.guild("12345678901234567")
        playback.join(guild,channel)
        val other=mock(AudioChannel::class.java)
        `when`(other.id).thenReturn("different-channel")
        assertFailsWith<IllegalStateException> {playback.join(guild,other)}
        assertEquals(channel.id,playback.channelId(guild.id))
        verify(guild.audioManager,times(1)).openAudioConnection(channel)
        playback.leave(guild)
        assertNull(playback.channelId(guild.id))
        playback.close()
    }
    private class Rig {
        val manager = mock(AudioPlayerManager::class.java)
        val players = mutableListOf<AudioPlayer>()
        val listeners = mutableListOf<AudioEventAdapter>()
        val requests = CopyOnWriteArrayList<Pair<String, AudioLoadResultHandler>>()

        init {
            `when`(manager.createPlayer()).thenAnswer {
                val player = mock(AudioPlayer::class.java)
                var current: AudioTrack? = null
                var paused = false
                `when`(player.playingTrack).thenAnswer { current }
                `when`(player.isPaused).thenAnswer { paused }
                doAnswer { call -> paused = call.getArgument(0); null }.`when`(player).setPaused(anyBoolean())
                doAnswer { call ->
                    val next = call.getArgument<AudioTrack>(0)
                    val noInterrupt = call.getArgument<Boolean>(1)
                    if (noInterrupt && current != null) false else { current = next; true }
                }.`when`(player).startTrack(any(AudioTrack::class.java), anyBoolean())
                doAnswer { current = null; null }.`when`(player).stopTrack()
                doAnswer { call -> listeners += call.getArgument<AudioEventAdapter>(0); null }.`when`(player).addListener(any())
                players += player
                player
            }
            doAnswer { call ->
                requests += call.getArgument<String>(1) to call.getArgument<AudioLoadResultHandler>(2)
                null
            }.`when`(manager).loadItemOrdered(anyString(), anyString(), any(AudioLoadResultHandler::class.java))
        }

        fun track(title: String): AudioTrack {
            val track = mock(AudioTrack::class.java)
            val videoId=title.padEnd(11,'x').take(11)
            val info = AudioTrackInfo(title, "test", 60_000L, videoId, false, "https://youtube.com/watch?v=$videoId")
            doReturn(info).`when`(track).info
            doReturn(track).`when`(track).makeClone()
            doReturn(60_000L).`when`(track).duration
            return track
        }

        fun guild(id: String): Pair<Guild, AudioChannel> {
            val guild = mock(Guild::class.java)
            val audio = mock(AudioManager::class.java)
            val channel = mock(net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion::class.java)
            `when`(guild.id).thenReturn(id)
            `when`(guild.audioManager).thenReturn(audio)
            `when`(channel.id).thenReturn("voice-$id")
            `when`(channel.name).thenReturn("Music")
            return guild to channel
        }

        fun load(playback: DiscordMusicPlayback, guild: Guild, channel: AudioChannel, query: String, requester:MusicRequester?=null, respond: (AudioLoadResultHandler) -> Unit): String {
            val before = requests.size
            val result = CompletableFuture.supplyAsync { playback.play(guild, channel, query,requester) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (requests.size == before && System.nanoTime() < deadline) Thread.sleep(2)
            assertEquals(before + 1, requests.size, "load request was not submitted")
            respond(requests.last().second)
            return result.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun `requester follows queued tracks through skip and previous`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager);val (guild,channel)=rig.guild("12345678901234567")
        val first=MusicRequester("첫 등록자","https://cdn.discordapp.com/embed/avatars/0.png","12345678901234568")
        val second=MusicRequester("둘째 등록자","https://cdn.discordapp.com/embed/avatars/1.png","12345678901234569")
        rig.load(playback,guild,channel,"one",first){it.trackLoaded(rig.track("One"))}
        rig.load(playback,guild,channel,"two",second){it.trackLoaded(rig.track("Two"))}
        assertEquals(first,playback.snapshot("12345678901234567").current?.requester)
        assertEquals(second,playback.snapshot("12345678901234567").queue.single().requester)
        playback.skip("12345678901234567");assertEquals(second,playback.snapshot("12345678901234567").current?.requester)
        playback.previous("12345678901234567");assertEquals(first,playback.snapshot("12345678901234567").current?.requester)
        assertEquals(second,playback.snapshot("12345678901234567").queue.first().requester)
        playback.close()
    }

    @Test fun `tracks play in requested order and skip advances once`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (guild, channel) = rig.guild("12345678901234567")
        rig.load(playback, guild, channel, "one") { it.trackLoaded(rig.track("One")) }
        rig.load(playback, guild, channel, "two") { it.trackLoaded(rig.track("Two")) }
        rig.load(playback, guild, channel, "three") { it.trackLoaded(rig.track("Three")) }
        assertEquals(listOf("ytsearch:one", "ytsearch:two", "ytsearch:three"), rig.requests.map { it.first })
        assertContains(playback.queue("12345678901234567"), "현재: One")
        assertContains(playback.queue("12345678901234567"), "1. Two\n2. Three")
        playback.skip("12345678901234567")
        assertContains(playback.queue("12345678901234567"), "현재: Two")
        playback.skip("12345678901234567")
        assertContains(playback.queue("12345678901234567"), "현재: Three")
        playback.close()
    }

    @Test fun `stop invalidates a pending load and clears pause state`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (guild, channel) = rig.guild("12345678901234567")
        rig.load(playback, guild, channel, "one") { it.trackLoaded(rig.track("One")) }
        playback.pause("12345678901234567")
        verify(rig.players.single()).setPaused(true)
        val late = CompletableFuture.supplyAsync { playback.play(guild, channel, "late") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (rig.requests.size < 2 && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals(2, rig.requests.size)
        playback.stop("12345678901234567")
        rig.requests.last().second.trackLoaded(rig.track("Late"))
        assertContains(late.get(2, TimeUnit.SECONDS), "취소")
        assertContains(playback.queue("12345678901234567"), "현재: 없음")
        verify(rig.players.single()).setPaused(false)
        playback.close()
    }

    @Test fun `failed load does not discard queued music and guild queues are independent`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (first, firstChannel) = rig.guild("12345678901234568")
        val (second, secondChannel) = rig.guild("12345678901234569")
        rig.load(playback, first, firstChannel, "one") { it.trackLoaded(rig.track("One")) }
        val failure = rig.load(playback, first, firstChannel, "bad") { it.loadFailed(FriendlyException("unavailable", FriendlyException.Severity.COMMON, null)) }
        assertContains(failure, "불러오지 못했습니다")
        rig.load(playback, first, firstChannel, "two") { it.trackLoaded(rig.track("Two")) }
        rig.load(playback, second, secondChannel, "other") { it.trackLoaded(rig.track("Other")) }
        assertContains(playback.queue("12345678901234568"), "현재: One")
        assertContains(playback.queue("12345678901234568"), "1. Two")
        assertContains(playback.queue("12345678901234569"), "현재: Other")
        assertFalse(playback.queue("12345678901234569").contains("Two"))
        playback.stop("12345678901234568")
        assertContains(playback.queue("12345678901234569"), "현재: Other")
        playback.close()
    }

    @Test fun `playback error followed by end event advances only one track`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (guild, channel) = rig.guild("12345678901234567")
        val one = rig.track("One")
        rig.load(playback, guild, channel, "one") { it.trackLoaded(one) }
        rig.load(playback, guild, channel, "two") { it.trackLoaded(rig.track("Two")) }
        rig.load(playback, guild, channel, "three") { it.trackLoaded(rig.track("Three")) }
        rig.listeners.single().onTrackException(rig.players.single(), one, FriendlyException("broken", FriendlyException.Severity.COMMON, null))
        rig.listeners.single().onTrackEnd(rig.players.single(), one, AudioTrackEndReason.LOAD_FAILED)
        assertContains(playback.queue("12345678901234567"), "현재: Two")
        assertContains(playback.queue("12345678901234567"), "1. Three")
        playback.close()
    }

    @Test fun `voice handler provides the same frame it reported available`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (guild, channel) = rig.guild("12345678901234567")
        val frame = mock(AudioFrame::class.java)
        `when`(frame.data).thenReturn(byteArrayOf(1, 2, 3))
        playback.join(guild, channel)
        `when`(rig.players.single().provide()).thenReturn(frame)
        val handler = ArgumentCaptor.forClass(AudioSendHandler::class.java)
        verify(guild.audioManager).setSendingHandler(handler.capture())
        assertTrue(handler.value.canProvide())
        assertContentEquals(byteArrayOf(1, 2, 3), handler.value.provide20MsAudio()!!.array())
        verify(rig.players.single(), times(1)).provide()
        playback.close()
    }

    @Test fun `previous returns to prior track and preserves interrupted current track first in queue`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val (guild,channel)=rig.guild("12345678901234567")
        rig.load(playback,guild,channel,"one") {it.trackLoaded(rig.track("One"))}
        rig.load(playback,guild,channel,"two") {it.trackLoaded(rig.track("Two"))}
        rig.load(playback,guild,channel,"three") {it.trackLoaded(rig.track("Three"))}
        playback.skip("12345678901234567")
        assertEquals("Two",playback.snapshot("12345678901234567").current?.title)
        playback.previous("12345678901234567")
        val snapshot=playback.snapshot("12345678901234567")
        assertEquals("One",snapshot.current?.title)
        assertEquals(listOf("Two","Three"),snapshot.queue.map {it.title})
        assertEquals(channel.id,snapshot.channelId)
        playback.close()
    }

    @Test fun `previous with no history restarts current and history is guild scoped and cleared by stop`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val (first,firstChannel)=rig.guild("12345678901234568")
        val (second,secondChannel)=rig.guild("12345678901234569")
        assertContains(playback.previous("12345678901234568"),"이전 곡이 없습니다")
        rig.load(playback,first,firstChannel,"one") {it.trackLoaded(rig.track("One"))}
        assertContains(playback.previous("12345678901234568"),"처음부터")
        playback.skip("12345678901234568")
        rig.load(playback,second,secondChannel,"other") {it.trackLoaded(rig.track("Other"))}
        assertContains(playback.previous("12345678901234569"),"처음부터")
        playback.stop("12345678901234568")
        assertContains(playback.previous("12345678901234568"),"이전 곡이 없습니다")
        assertEquals("Other",playback.snapshot("12345678901234569").current?.title)
        playback.close()
    }

    @Test fun `change listener reports mutated guild without leaking another guild`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val changed=CopyOnWriteArrayList<String>()
        playback.setChangeListener {changed += it}
        val (first,channel)=rig.guild("12345678901234568")
        rig.load(playback,first,channel,"one") {it.trackLoaded(rig.track("One"))}
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.all {it=="12345678901234568"})
        changed.clear()
        playback.pause("12345678901234568")
        assertEquals(listOf("12345678901234568"),changed.toList())
        assertTrue(playback.snapshot("12345678901234568").paused)
        playback.close()
    }

    @Test fun `queue ids remove only selected pending items and reject stale ids`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val (guild,channel)=rig.guild("12345678901234567")
        rig.load(playback,guild,channel,"one") {it.trackLoaded(rig.track("One"))}
        rig.load(playback,guild,channel,"two") {it.trackLoaded(rig.track("Two"))}
        rig.load(playback,guild,channel,"three") {it.trackLoaded(rig.track("Three"))}
        val ids=playback.snapshot(guild.id).queue.mapNotNull {it.id}
        assertEquals(2,ids.distinct().size)
        assertContains(playback.removeQueued(guild.id,listOf(ids.first())),"1곡")
        assertEquals(listOf("Three"),playback.snapshot(guild.id).queue.map {it.title})
        assertContains(playback.removeQueued(guild.id,listOf(ids.first())),"없습니다")
        playback.close()
    }

    @Test fun `only uninterrupted manual finish records a completion`() {
        val rig=Rig();val library=DiscordMusicLibraryStore();val playback=DiscordMusicPlayback(rig.manager,library)
        val (guild,channel)=rig.guild("12345678901234567")
        val one=rig.track("One")
        rig.load(playback,guild,channel,"one") {it.trackLoaded(one)}
        playback.skip(guild.id)
        rig.listeners.single().onTrackEnd(rig.players.single(),one,AudioTrackEndReason.FINISHED)
        assertTrue(library.stats(guild.id).isEmpty())
        val two=rig.track("Two")
        rig.load(playback,guild,channel,"two") {it.trackLoaded(two)}
        playback.autoplay(guild.id,false)
        rig.listeners.single().onTrackEnd(rig.players.single(),two,AudioTrackEndReason.FINISHED)
        rig.listeners.single().onTrackEnd(rig.players.single(),two,AudioTrackEndReason.FINISHED)
        assertEquals(1,library.stats(guild.id).single().completions)
        playback.close()
    }

    @Test fun `autoplay uses near uniform weights and excludes last song when alternatives exist`() {
        val a=MusicSavedTrack("A","https://www.youtube.com/watch?v=aaaaaaaaaaa")
        val b=MusicSavedTrack("B","https://www.youtube.com/watch?v=bbbbbbbbbbb")
        val stats=listOf(MusicPlayStat(a,1,1),MusicPlayStat(b,1_000_000,2))
        val random=Random(42)
        val picks=(1..10_000).count { chooseAutoplay(stats,null,emptySet(),random)==b }
        assertTrue(picks in 4_950..5_350,"popularity weight should have a small six percent bonus: $picks")
        assertEquals(a,chooseAutoplay(stats,b.url,emptySet(),Random(1)))
        assertNull(chooseAutoplay(stats,null,setOf(a.url,b.url),Random(1)))
    }

    @Test fun `manual finish starts autoplay but autoplay finish does not increase manual statistics`() {
        val rig=Rig();val library=DiscordMusicLibraryStore();val playback=DiscordMusicPlayback(rig.manager,library)
        val (guild,channel)=rig.guild("12345678901234567")
        val manual=rig.track("Manual")
        rig.load(playback,guild,channel,"manual") {it.trackLoaded(manual)}
        rig.listeners.single().onTrackEnd(rig.players.single(),manual,AudioTrackEndReason.FINISHED)
        assertEquals(1,library.stats(guild.id).single().completions)
        assertEquals(2,rig.requests.size,"completion should schedule one autoplay load")
        val automatic=rig.track("Automatic")
        rig.requests.last().second.trackLoaded(automatic)
        assertEquals("Automatic",playback.snapshot(guild.id).current?.title)
        rig.listeners.single().onTrackEnd(rig.players.single(),automatic,AudioTrackEndReason.FINISHED)
        assertEquals(1,library.stats(guild.id).single().completions)
        playback.close()
    }

    @Test fun `stop invalidates an outstanding autoplay result`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager,DiscordMusicLibraryStore())
        val (guild,channel)=rig.guild("12345678901234567")
        val manual=rig.track("Manual")
        rig.load(playback,guild,channel,"manual") {it.trackLoaded(manual)}
        rig.listeners.single().onTrackEnd(rig.players.single(),manual,AudioTrackEndReason.FINISHED)
        assertEquals(2,rig.requests.size)
        playback.stop(guild.id)
        rig.requests.last().second.trackLoaded(rig.track("LateAuto"))
        assertNull(playback.snapshot(guild.id).current)
        playback.close()
    }

    @Test fun `stop rejects finish delivered after player already cleared the track`() {
        val rig=Rig();val library=DiscordMusicLibraryStore();val playback=DiscordMusicPlayback(rig.manager,library)
        val (guild,channel)=rig.guild("12345678901234567")
        val manual=rig.track("Manual")
        rig.load(playback,guild,channel,"manual") {it.trackLoaded(manual)}
        rig.players.single().stopTrack() // simulate the audio engine clearing current before end delivery
        playback.stop(guild.id)
        rig.listeners.single().onTrackEnd(rig.players.single(),manual,AudioTrackEndReason.FINISHED)
        assertTrue(library.stats(guild.id).isEmpty())
        assertEquals(1,rig.requests.size)
        assertNull(playback.snapshot(guild.id).current)
        playback.close()
    }

    @Test fun `watch url with list loads full playlist and inspection never joins voice`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val (guild,channel)=rig.guild("12345678901234567")
        val playlist=mock(AudioPlaylist::class.java)
        `when`(playlist.isSearchResult).thenReturn(false)
        val playlistTracks=listOf(rig.track("One"),rig.track("Two"))
        `when`(playlist.tracks).thenReturn(playlistTracks)
        val url="https://www.youtube.com/watch?v=aaaaaaaaaaa&list=PL12345678901"
        val inspected=CompletableFuture.supplyAsync {playback.inspectPlaylist(url)}
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(rig.requests.isEmpty() && System.nanoTime()<deadline) Thread.sleep(2)
        assertEquals("https://www.youtube.com/playlist?list=PL12345678901",rig.requests.single().first)
        rig.requests.single().second.playlistLoaded(playlist)
        assertEquals(listOf("One","Two"),inspected.get(2,TimeUnit.SECONDS).map {it.title})
        verify(guild.audioManager,never()).openAudioConnection(any())
        rig.load(playback,guild,channel,url) {it.playlistLoaded(playlist)}
        assertEquals(listOf("Two"),playback.snapshot(guild.id).queue.map {it.title})
        playback.close()
    }

    @Test fun `playSaved stops submitting later tracks after stop invalidates first load`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val (guild,channel)=rig.guild("12345678901234567")
        val tracks=listOf(
            MusicSavedTrack("One","https://www.youtube.com/watch?v=aaaaaaaaaaa"),
            MusicSavedTrack("Two","https://www.youtube.com/watch?v=bbbbbbbbbbb"))
        val future=CompletableFuture.supplyAsync {playback.playSaved(guild,channel,tracks)}
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
        while(rig.requests.isEmpty() && System.nanoTime()<deadline) Thread.sleep(2)
        assertEquals(1,rig.requests.size)
        playback.stop(guild.id)
        rig.requests.single().second.trackLoaded(rig.track("One"))
        future.get(2,TimeUnit.SECONDS)
        assertEquals(1,rig.requests.size)
        assertNull(playback.snapshot(guild.id).current)
        playback.close()
    }

    @Test fun `repeat replays the finished track and stops after being turned off`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager,DiscordMusicLibraryStore());val (guild,channel)=rig.guild("12345678901234567")
        val one=rig.track("One")
        rig.load(playback,guild,channel,"one") {it.trackLoaded(one)}
        rig.load(playback,guild,channel,"two") {it.trackLoaded(rig.track("Two"))}
        playback.autoplay(guild.id,false)
        assertEquals("먼저 음성 채널에 연결하세요.",playback.repeat("missing",null))
        assertContains(playback.repeat(guild.id,null),"켰습니다")
        assertTrue(playback.snapshot(guild.id).repeat)
        rig.listeners.single().onTrackEnd(rig.players.single(),one,AudioTrackEndReason.FINISHED)
        assertEquals("One",playback.snapshot(guild.id).current?.title)
        assertEquals(listOf("Two"),playback.snapshot(guild.id).queue.map {it.title})
        playback.skip(guild.id)
        assertEquals("Two",playback.snapshot(guild.id).current?.title)
        assertContains(playback.repeat(guild.id,false),"껐습니다")
        assertFalse(playback.snapshot(guild.id).repeat)
        playback.close()
    }
}
