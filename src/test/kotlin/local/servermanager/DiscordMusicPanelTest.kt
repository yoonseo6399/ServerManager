package local.servermanager

import org.junit.jupiter.api.Test
import kotlin.test.*

class DiscordMusicPanelTest {
    @Test fun `public panel shows requester nickname and Discord avatar`() {
        val requester=MusicRequester("음악 신청자","https://cdn.discordapp.com/embed/avatars/0.png","123456789012345678")
        val track=MusicTrackView("노래",null,null,0,requester=requester)
        val snapshot=MusicSnapshot(track,listOf(track),false,null,null)
        val embed=DiscordMusicPanel.render(snapshot)
        assertEquals("등록: 음악 신청자",embed.author?.name)
        assertEquals(requester.avatarUrl,embed.author?.iconUrl)
        assertTrue(embed.fields.any {it.value?.contains("· 음악 신청자")==true})
        val unsafe=DiscordMusicPanel.render(snapshot.copy(current=track.copy(requester=requester.copy(avatarUrl="https://evil.example/avatar.png"))))
        assertNull(unsafe.author?.iconUrl)
    }
    @Test fun `panel includes only safe YouTube link and thumbnail`() {
        val safe="https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val snapshot=MusicSnapshot(MusicTrackView("Example",safe,"dQw4w9WgXcQ",60_000),emptyList(),false,null,null)
        val embed=DiscordMusicPanel.render(snapshot)
        assertEquals(safe,embed.fields.single {it.name=="YouTube"}.value)
        assertEquals("https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg",embed.thumbnail?.url)
        for (unsafe in listOf(
            "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "https://youtube.com@evil.example/watch?v=dQw4w9WgXcQ",
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "file:///etc/passwd",
            "https://www.youtube.com:8443/watch?v=dQw4w9WgXcQ",
        )) {
            assertNull(DiscordMusicPanel.safeYoutubeUrl(unsafe),unsafe)
            val blocked=DiscordMusicPanel.render(snapshot.copy(current=snapshot.current!!.copy(url=unsafe)))
            assertTrue(blocked.fields.none {it.name=="YouTube"},unsafe)
            assertNull(blocked.thumbnail,unsafe)
        }
    }

    @Test fun `panel bounds untrusted titles and shows queue and paused status`() {
        val longTitle="A".repeat(240)+"\n@everyone"
        val queued=List(12) { MusicTrackView("Queue $it",null,null,0) }
        val embed=DiscordMusicPanel.render(MusicSnapshot(MusicTrackView(longTitle,null,null,0),queued,true,"123456789012345678","offline"))
        assertEquals(200,embed.fields.first {it.name=="현재 곡"}.value!!.length)
        assertContains(embed.fields.first {it.name=="상태"}.value!!,"일시정지")
        assertEquals("대기열 · 12곡",embed.fields.first {it.name?.startsWith("대기열")==true}.name)
        assertFalse(embed.fields.first {it.name?.startsWith("대기열")==true}.value!!.contains("Queue 10"))
        assertEquals("offline",embed.footer?.text)
    }
}
