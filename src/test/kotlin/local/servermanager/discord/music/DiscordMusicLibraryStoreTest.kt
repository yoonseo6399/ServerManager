package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class DiscordMusicLibraryStoreTest {
    @TempDir lateinit var temp:Path
    private val guildA="123456789012345678"
    private val guildB="223456789012345678"
    private val owner="323456789012345678"
    private fun track(id:String="dQw4w9WgXcQ",title:String="Song")=MusicSavedTrack(title,"https://www.youtube.com/watch?v=$id")

    @Test fun `playlist stats and autoplay persist and remain scoped to one guild`() {
        val path=temp.resolve("library")
        val store=DiscordMusicLibraryStore(path)
        assertTrue(store.autoplayEnabled(guildA))
        store.setAutoplay(guildA,false)
        val saved=store.save(guildA,"Favorites",listOf(track()),owner)
        store.recordCompletion(guildA,track())
        store.recordCompletion(guildA,track())
        store.recordCompletion(guildB,track("jNQXAC9IVRw","Other"))
        val reloaded=DiscordMusicLibraryStore(path)
        assertFalse(reloaded.autoplayEnabled(guildA))
        assertTrue(reloaded.autoplayEnabled(guildB))
        assertEquals(saved,reloaded.playlist(guildA,"Favorites"))
        assertNull(reloaded.playlist(guildB,"Favorites"))
        assertEquals(2,reloaded.stats(guildA).single().completions)
        assertEquals("Other",reloaded.stats(guildB).single().track.title)
        assertTrue(reloaded.delete(guildA,"Favorites"))
        assertFalse(reloaded.delete(guildA,"Favorites"))
        assertTrue(DiscordMusicLibraryStore(path).playlists(guildA).isEmpty())
        assertEquals("rwx------",PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
        assertEquals("rw-------",PosixFilePermissions.toString(Files.getPosixFilePermissions(path.resolve("music-library.json"))))
    }

    @Test fun `replacement and ranking retain metadata without crossing guilds`() {
        val store=DiscordMusicLibraryStore()
        val first=track()
        val second=track("jNQXAC9IVRw","Second")
        store.save(guildA,"List",listOf(first),owner)
        store.save(guildA,"List",listOf(second),owner)
        assertEquals(listOf(second),store.playlists(guildA).single().tracks)
        store.recordCompletion(guildA,first)
        repeat(3) {store.recordCompletion(guildA,second)}
        assertEquals(listOf(second,first),store.stats(guildA).map {it.track})
        assertTrue(store.stats(guildB).isEmpty())
    }

    @Test fun `skip preferences persist with canonical URLs and survive later completions without crossing guilds`() {
        val path=temp.resolve("library")
        val store=DiscordMusicLibraryStore(path)
        store.recordCompletion(guildA,track())
        store.recordCompletion(guildB,track())
        store.recordSkip(guildA,MusicSavedTrack("Song","https://youtu.be/dQw4w9WgXcQ"))
        store.recordSkip(guildA,track())
        store.recordCompletion(guildA,track())
        val reloaded=DiscordMusicLibraryStore(path)
        assertEquals(2,reloaded.stats(guildA).single().skips)
        assertEquals(2,reloaded.stats(guildA).single().completions)
        assertEquals(0,reloaded.stats(guildB).single().skips)
        reloaded.recordSkip(guildA,track("jNQXAC9IVRw","Unfinished"))
        assertEquals(1,reloaded.stats(guildA).size,"skipping an unfinished song must not make it eligible")
    }

    @Test fun `libraries saved before skip preferences load with zero penalty`() {
        val path=temp.resolve("legacy");Files.createDirectories(path)
        path.resolve("music-library.json").writeText("""{"$guildA":{"stats":[{"track":{"title":"Song","url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"},"completions":3,"lastCompletedAt":123}]}}""")
        val store=DiscordMusicLibraryStore(path)
        assertEquals(0,store.stats(guildA).single().skips)
        store.recordSkip(guildA,track())
        assertEquals(1,DiscordMusicLibraryStore(path).stats(guildA).single().skips)
    }

    @Test fun `playlist bounds and unsafe URLs are rejected without altering saved data`() {
        val store=DiscordMusicLibraryStore()
        store.save(guildA,"Good",listOf(track()),owner)
        for (name in listOf("", " ", "a".repeat(41), "bad\nname")) {
            assertFailsWith<IllegalArgumentException> {store.save(guildA,name,listOf(track()),owner)}
        }
        for (url in listOf(
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "https://youtube.com@evil.example/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com:8443/watch?v=dQw4w9WgXcQ",
            "file:///etc/passwd",
            "https://www.youtube.com/watch?v=bad",
        )) assertFailsWith<IllegalArgumentException> {store.save(guildA,"Bad",listOf(MusicSavedTrack("X",url)),owner)}
        assertFailsWith<IllegalArgumentException> {store.save(guildA,"Oversized",List(101) {track()},owner)}
        assertFailsWith<IllegalArgumentException> {store.save(guildA,"Empty",emptyList(),owner)}
        assertEquals(listOf("Good"),store.playlists(guildA).map {it.name})
        repeat(49) {store.save(guildA,"List $it",listOf(track()),owner)}
        assertFailsWith<IllegalArgumentException> {store.save(guildA,"Excess",listOf(track()),owner)}
        assertEquals(50,store.playlists(guildA).size)
    }

    @Test fun `completion counts equivalent video URLs once and bounds distinct history`() {
        val store=DiscordMusicLibraryStore()
        store.recordCompletion(guildA,track())
        store.recordCompletion(guildA,MusicSavedTrack("Song","https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(2,store.stats(guildA).single().completions)
        repeat(1000) { index ->
            val id=index.toString().padStart(11,'0')
            store.recordCompletion(guildA,track(id,"Song $index"))
        }
        assertEquals(1000,store.stats(guildA).size)
        assertFalse(store.stats(guildA).any {it.track.url.contains("dQw4w9WgXcQ")})
    }

    @Test fun `symlink file and directory are refused without touching target`() {
        val target=temp.resolve("target.json")
        target.writeText("protected")
        val directory=temp.resolve("library")
        Files.createDirectories(directory)
        Files.createSymbolicLink(directory.resolve("music-library.json"),target)
        assertFailsWith<IllegalArgumentException> {DiscordMusicLibraryStore(directory)}
        assertEquals("protected",target.readText())
        val link=temp.resolve("linked")
        Files.createSymbolicLink(link,directory)
        assertFailsWith<IllegalArgumentException> {DiscordMusicLibraryStore(link)}
    }
}
