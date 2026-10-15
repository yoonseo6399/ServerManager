package local.servermanager

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
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

class DiscordMusicPlaybackTest {
    @Test fun `pending voice connection cannot be taken over by another channel`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val (guild,channel)=rig.guild("g1")
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
            val info = AudioTrackInfo(title, "test", 60_000L, title, false, "https://youtube.com/watch?v=test")
            doReturn(info).`when`(track).info
            doReturn(track).`when`(track).makeClone()
            doReturn(60_000L).`when`(track).duration
            return track
        }

        fun guild(id: String): Pair<Guild, AudioChannel> {
            val guild = mock(Guild::class.java)
            val audio = mock(AudioManager::class.java)
            val channel = mock(AudioChannel::class.java)
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
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager);val (guild,channel)=rig.guild("g1")
        val first=MusicRequester("첫 등록자","https://cdn.discordapp.com/embed/avatars/0.png","first")
        val second=MusicRequester("둘째 등록자","https://cdn.discordapp.com/embed/avatars/1.png","second")
        rig.load(playback,guild,channel,"one",first){it.trackLoaded(rig.track("One"))}
        rig.load(playback,guild,channel,"two",second){it.trackLoaded(rig.track("Two"))}
        assertEquals(first,playback.snapshot("g1").current?.requester)
        assertEquals(second,playback.snapshot("g1").queue.single().requester)
        playback.skip("g1");assertEquals(second,playback.snapshot("g1").current?.requester)
        playback.previous("g1");assertEquals(first,playback.snapshot("g1").current?.requester)
        assertEquals(second,playback.snapshot("g1").queue.first().requester)
        playback.close()
    }

    @Test fun `tracks play in requested order and skip advances once`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (guild, channel) = rig.guild("g1")
        rig.load(playback, guild, channel, "one") { it.trackLoaded(rig.track("One")) }
        rig.load(playback, guild, channel, "two") { it.trackLoaded(rig.track("Two")) }
        rig.load(playback, guild, channel, "three") { it.trackLoaded(rig.track("Three")) }
        assertEquals(listOf("ytsearch:one", "ytsearch:two", "ytsearch:three"), rig.requests.map { it.first })
        assertContains(playback.queue("g1"), "현재: One")
        assertContains(playback.queue("g1"), "1. Two\n2. Three")
        playback.skip("g1")
        assertContains(playback.queue("g1"), "현재: Two")
        playback.skip("g1")
        assertContains(playback.queue("g1"), "현재: Three")
        playback.close()
    }

    @Test fun `stop invalidates a pending load and clears pause state`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (guild, channel) = rig.guild("g1")
        rig.load(playback, guild, channel, "one") { it.trackLoaded(rig.track("One")) }
        playback.pause("g1")
        verify(rig.players.single()).setPaused(true)
        val late = CompletableFuture.supplyAsync { playback.play(guild, channel, "late") }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (rig.requests.size < 2 && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals(2, rig.requests.size)
        playback.stop("g1")
        rig.requests.last().second.trackLoaded(rig.track("Late"))
        assertContains(late.get(2, TimeUnit.SECONDS), "취소")
        assertContains(playback.queue("g1"), "현재: 없음")
        verify(rig.players.single()).setPaused(false)
        playback.close()
    }

    @Test fun `failed load does not discard queued music and guild queues are independent`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (first, firstChannel) = rig.guild("first")
        val (second, secondChannel) = rig.guild("second")
        rig.load(playback, first, firstChannel, "one") { it.trackLoaded(rig.track("One")) }
        val failure = rig.load(playback, first, firstChannel, "bad") { it.loadFailed(FriendlyException("unavailable", FriendlyException.Severity.COMMON, null)) }
        assertContains(failure, "불러오지 못했습니다")
        rig.load(playback, first, firstChannel, "two") { it.trackLoaded(rig.track("Two")) }
        rig.load(playback, second, secondChannel, "other") { it.trackLoaded(rig.track("Other")) }
        assertContains(playback.queue("first"), "현재: One")
        assertContains(playback.queue("first"), "1. Two")
        assertContains(playback.queue("second"), "현재: Other")
        assertFalse(playback.queue("second").contains("Two"))
        playback.stop("first")
        assertContains(playback.queue("second"), "현재: Other")
        playback.close()
    }

    @Test fun `playback error followed by end event advances only one track`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (guild, channel) = rig.guild("g1")
        val one = rig.track("One")
        rig.load(playback, guild, channel, "one") { it.trackLoaded(one) }
        rig.load(playback, guild, channel, "two") { it.trackLoaded(rig.track("Two")) }
        rig.load(playback, guild, channel, "three") { it.trackLoaded(rig.track("Three")) }
        rig.listeners.single().onTrackException(rig.players.single(), one, FriendlyException("broken", FriendlyException.Severity.COMMON, null))
        rig.listeners.single().onTrackEnd(rig.players.single(), one, AudioTrackEndReason.LOAD_FAILED)
        assertContains(playback.queue("g1"), "현재: Two")
        assertContains(playback.queue("g1"), "1. Three")
        playback.close()
    }

    @Test fun `voice handler provides the same frame it reported available`() {
        val rig = Rig()
        val playback = DiscordMusicPlayback(rig.manager)
        val (guild, channel) = rig.guild("g1")
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
        val (guild,channel)=rig.guild("g1")
        rig.load(playback,guild,channel,"one") {it.trackLoaded(rig.track("One"))}
        rig.load(playback,guild,channel,"two") {it.trackLoaded(rig.track("Two"))}
        rig.load(playback,guild,channel,"three") {it.trackLoaded(rig.track("Three"))}
        playback.skip("g1")
        assertEquals("Two",playback.snapshot("g1").current?.title)
        playback.previous("g1")
        val snapshot=playback.snapshot("g1")
        assertEquals("One",snapshot.current?.title)
        assertEquals(listOf("Two","Three"),snapshot.queue.map {it.title})
        assertEquals("voice-g1",snapshot.channelId)
        playback.close()
    }

    @Test fun `previous with no history restarts current and history is guild scoped and cleared by stop`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val (first,firstChannel)=rig.guild("first")
        val (second,secondChannel)=rig.guild("second")
        assertContains(playback.previous("first"),"이전 곡이 없습니다")
        rig.load(playback,first,firstChannel,"one") {it.trackLoaded(rig.track("One"))}
        assertContains(playback.previous("first"),"처음부터")
        playback.skip("first")
        rig.load(playback,second,secondChannel,"other") {it.trackLoaded(rig.track("Other"))}
        assertContains(playback.previous("second"),"처음부터")
        playback.stop("first")
        assertContains(playback.previous("first"),"이전 곡이 없습니다")
        assertEquals("Other",playback.snapshot("second").current?.title)
        playback.close()
    }

    @Test fun `change listener reports mutated guild without leaking another guild`() {
        val rig=Rig();val playback=DiscordMusicPlayback(rig.manager)
        val changed=CopyOnWriteArrayList<String>()
        playback.setChangeListener {changed += it}
        val (first,channel)=rig.guild("first")
        rig.load(playback,first,channel,"one") {it.trackLoaded(rig.track("One"))}
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.all {it=="first"})
        changed.clear()
        playback.pause("first")
        assertEquals(listOf("first"),changed.toList())
        assertTrue(playback.snapshot("first").paused)
        playback.close()
    }
}
