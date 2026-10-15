package local.servermanager

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordMusicAccessTest {
    private val guild = "123456789012345678"
    private val voice = "223456789012345678"
    private val otherVoice = "323456789012345678"
    private val enabled = DiscordConfig(enabled = true, grants = listOf(DiscordGrant(guild, "423456789012345678", "test")))

    private fun allowed(
        action: String,
        config: DiscordConfig = enabled,
        guildId: String? = guild,
        clientCurrent: Boolean = true,
        userChannelId: String? = voice,
        botChannelId: String? = voice,
    ) = DiscordMusicAccess.allowed(config, guildId, clientCurrent, action, userChannelId, botChannelId)

    @Test fun `music commands fail closed without an enabled current guild connection`() {
        for (action in listOf("play", "pause", "resume", "skip", "stop", "queue", "join", "leave")) {
            assertFalse(allowed(action, config = enabled.copy(enabled = false)))
            assertFalse(allowed(action, guildId = null))
            assertFalse(allowed(action, clientCurrent = false))
        }
        assertFalse(allowed("unknown"))
    }

    @Test fun `queue is readable outside voice while controls require users voice channel`() {
        assertTrue(allowed("queue", userChannelId = null, botChannelId = null))
        for (action in listOf("play", "pause", "resume", "skip", "stop", "join", "leave")) {
            assertFalse(allowed(action, userChannelId = null, botChannelId = null), action)
        }
    }

    @Test fun `connected bot accepts only same voice channel controls`() {
        for (action in listOf("play", "pause", "resume", "skip", "stop", "join", "leave")) {
            assertTrue(allowed(action), action)
            assertFalse(allowed(action, userChannelId = otherVoice), action)
        }
        assertTrue(allowed("play", botChannelId = null))
        assertTrue(allowed("join", botChannelId = null))
    }

    @Test fun `query rejects empty and unsafe URLs while allowing search and YouTube links`() {
        assertFalse(DiscordMusicAccess.validQuery(""))
        assertFalse(DiscordMusicAccess.validQuery("   "))
        assertFalse(DiscordMusicAccess.validQuery("https://example.com/watch?v=abc"))
        assertFalse(DiscordMusicAccess.validQuery("file:///etc/passwd"))
        assertFalse(DiscordMusicAccess.validQuery("http://127.0.0.1:8080/private"))
        assertTrue(DiscordMusicAccess.validQuery("jazz piano live"))
        assertTrue(DiscordMusicAccess.validQuery("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(DiscordMusicAccess.validQuery("https://youtu.be/dQw4w9WgXcQ"))
    }
}
