package local.servermanager

import org.junit.jupiter.api.Test
import kotlin.test.*

class DiscordMusicChannelAccessTest {
    private val config=DiscordConfig(true,superAdmins=listOf(DiscordSuperAdmin("guild","owner")))
    @Test fun `channel configuration requires channel manager or configured role in same guild`() {
        assertTrue(DiscordMusicChannelAccess.manage(config,"guild",emptySet(),true))
        assertTrue(DiscordMusicChannelAccess.manage(config,"guild",setOf("owner"),false))
        assertFalse(DiscordMusicChannelAccess.manage(config,"guild",emptySet(),false))
        assertFalse(DiscordMusicChannelAccess.manage(config,"other",setOf("owner"),true))
        assertFalse(DiscordMusicChannelAccess.manage(config.copy(enabled=false),"guild",setOf("owner"),true))
    }
    @Test fun `buttons must match guild channel and current panel message`() {
        val record=MusicChannelRecord("guild","channel","panel")
        assertTrue(DiscordMusicChannelAccess.matches(record,"guild","channel","panel"))
        assertFalse(DiscordMusicChannelAccess.matches(record,"other","channel","panel"))
        assertFalse(DiscordMusicChannelAccess.matches(record,"guild","other","panel"))
        assertFalse(DiscordMusicChannelAccess.matches(record,"guild","channel","old-panel"))
    }
    @Test fun `privileged message content intent is independent from other gateway flags`() {
        assertFalse(DiscordMusicGateway.allowsMessageContent(0))
        assertFalse(DiscordMusicGateway.allowsMessageContent((1L shl 15) or (1L shl 13)))
        assertTrue(DiscordMusicGateway.allowsMessageContent(1L shl 18))
        assertTrue(DiscordMusicGateway.allowsMessageContent(1L shl 19))
    }
}
