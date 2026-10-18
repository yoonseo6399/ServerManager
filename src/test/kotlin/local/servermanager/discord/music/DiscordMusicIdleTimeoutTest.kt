package local.servermanager

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DiscordMusicIdleTimeoutTest {
    @Test fun `requires continuous sixty seconds and resets on people channel move or disconnect`() {
        var now=0L;val idle=DiscordMusicIdleTimeout {TimeUnit.SECONDS.toNanos(now)}
        assertFalse(idle.expired("voice-a",false))
        now=59;assertFalse(idle.expired("voice-a",false))
        assertFalse(idle.expired("voice-a",true))
        now=60;assertFalse(idle.expired("voice-a",false))
        now=119;assertFalse(idle.expired("voice-a",false))
        now=120;assertTrue(idle.expired("voice-a",false))
        assertFalse(idle.expired("voice-b",false))
        now=179;assertFalse(idle.expired("voice-b",false))
        assertFalse(idle.expired(null,false))
        now=180;assertFalse(idle.expired("voice-b",false))
        now=240;assertTrue(idle.expired("voice-b",false))
    }
}
