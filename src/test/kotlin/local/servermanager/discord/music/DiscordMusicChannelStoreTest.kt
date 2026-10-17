package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class DiscordMusicChannelStoreTest {
    @TempDir lateinit var temp:Path
    private val first="123456789012345678"
    private val second="223456789012345678"
    private val channelA="323456789012345678"
    private val channelB="423456789012345678"
    private val panel="523456789012345678"

    @Test fun `registered channel and panel persist per guild across restart and removal`() {
        val directory=temp.resolve("music")
        val store=DiscordMusicChannelStore(directory)
        store.put(MusicChannelRecord(first,channelA,panel))
        store.put(MusicChannelRecord(second,channelB))
        val reloaded=DiscordMusicChannelStore(directory)
        assertEquals(MusicChannelRecord(first,channelA,panel),reloaded.get(first))
        assertEquals(MusicChannelRecord(second,channelB),reloaded.get(second))
        assertEquals(2,reloaded.all().size)
        assertEquals(MusicChannelRecord(first,channelA,panel),reloaded.remove(first))
        val afterRemoval=DiscordMusicChannelStore(directory)
        assertNull(afterRemoval.get(first))
        assertEquals(channelB,afterRemoval.get(second)?.channelId)
    }

    @Test fun `invalid IDs are rejected without changing a saved record`() {
        val directory=temp.resolve("music")
        val store=DiscordMusicChannelStore(directory)
        store.put(MusicChannelRecord(first,channelA))
        for (bad in listOf("", "0", "-12345678901234567", "12345678901234567x", "99999999999999999999")) {
            assertFailsWith<IllegalArgumentException> {store.put(MusicChannelRecord(first,bad))}
            assertFailsWith<IllegalArgumentException> {store.put(MusicChannelRecord(bad,channelB))}
            assertFailsWith<IllegalArgumentException> {store.put(MusicChannelRecord(first,channelB,bad))}
        }
        assertEquals(channelA,DiscordMusicChannelStore(directory).get(first)?.channelId)
    }

    @Test fun `symbolic link storage is rejected without modifying its target`() {
        val outside=temp.resolve("outside.json")
        outside.writeText("protected")
        val directory=temp.resolve("music")
        Files.createDirectories(directory)
        Files.createSymbolicLink(directory.resolve("music-channels.json"),outside)
        assertFailsWith<IllegalArgumentException> {DiscordMusicChannelStore(directory)}
        assertEquals("protected",outside.readText())
        val linkedDirectory=temp.resolve("linked-music")
        Files.createSymbolicLink(linkedDirectory,directory)
        assertFailsWith<IllegalArgumentException> {DiscordMusicChannelStore(linkedDirectory)}
    }
}
