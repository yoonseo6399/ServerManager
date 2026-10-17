package local.servermanager

import moe.kyokobot.libdave.NativeDaveFactory
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.managers.AudioManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Opt-in public YouTube read and native codec check; never logs in to Discord or sends audio. */
@EnabledIfEnvironmentVariable(named="DISCORD_MUSIC_LIVE_TEST",matches="true")
class DiscordMusicLiveTest {
    @Test fun `bundled DAVE native supports this Java runtime`() {
        NativeDaveFactory.ensureAvailable()
        assertTrue(NativeDaveFactory().maxSupportedProtocolVersion()>0)
    }

    @Test @Timeout(60) fun `YouTube search produces an Opus audio frame without a Discord connection`() {
        val guild=mock(Guild::class.java)
        val audio=mock(AudioManager::class.java)
        val channel=mock(AudioChannel::class.java)
        `when`(guild.id).thenReturn("123456789012345678")
        `when`(guild.audioManager).thenReturn(audio)
        `when`(channel.id).thenReturn("223456789012345678")
        `when`(channel.name).thenReturn("Offline diagnostic")
        var handler:AudioSendHandler?=null
        doAnswer {handler=it.getArgument(0);null}.`when`(audio).setSendingHandler(any())
        DiscordMusicPlayback().use {playback ->
            for(query in listOf("YouTube Audio Library no copyright","https://www.youtube.com/watch?v=yPEVG7YFBeQ")) {
                val result=playback.play(guild,channel,query)
                assertContains(result,"추가했습니다",message=result)
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
                while(handler?.canProvide()!=true && System.nanoTime()<deadline)Thread.sleep(20)
                assertTrue(handler?.isOpus==true)
                assertTrue((handler?.provide20MsAudio()?.remaining() ?: 0)>0,playback.queue(guild.id))
                playback.stop(guild.id)
            }
        }
    }
}
