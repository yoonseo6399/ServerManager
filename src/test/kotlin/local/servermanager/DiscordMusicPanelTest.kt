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
        assertContains(embed.fields.first {it.name=="상태"}.value!!,"자동 재생 켜짐")
        assertEquals("대기열 · 12곡 · 1/1페이지",embed.fields.first {it.name?.startsWith("대기열")==true}.name)
        assertTrue(embed.fields.first {it.name?.startsWith("대기열")==true}.value!!.contains("Queue 10"))
        assertEquals("offline",embed.footer?.text)
        val disabled=DiscordMusicPanel.render(MusicSnapshot(null,emptyList(),false,null,null,autoplay=false))
        assertContains(disabled.fields.first {it.name=="상태"}.value!!,"자동 재생 꺼짐")
    }

    @Test fun `selection uses stable track IDs and page bounds`() {
        val tracks=List(100) { MusicTrackView("Track $it",null,null,0,id="id-$it") }
        val snapshot=MusicSnapshot(null,tracks,false,null,null)
        val first=DiscordMusicPanel.componentRows(snapshot,0)
        val firstSelect=first[1].components.single().asStringSelectMenu()
        assertEquals("music:remove",firstSelect.customId)
        assertEquals(25,firstSelect.options.size)
        assertEquals(25,firstSelect.maxValues)
        assertEquals("id-0",firstSelect.options.first().value)
        assertEquals("id-24",firstSelect.options.last().value)
        val firstButtons=first[2].components.map { it.asButton() }
        assertTrue(firstButtons.first().isDisabled)
        assertEquals("music:page:1",firstButtons.last().customId)

        val last=DiscordMusicPanel.componentRows(snapshot,99)
        val lastSelect=last[1].components.single().asStringSelectMenu()
        assertEquals("id-75",lastSelect.options.first().value)
        assertEquals("id-99",lastSelect.options.last().value)
        val lastButtons=last[2].components.map { it.asButton() }
        assertEquals("music:page:2",lastButtons.first().customId)
        assertTrue(lastButtons.last().isDisabled)
        assertContains(DiscordMusicPanel.render(snapshot,99).fields.first { it.name!!.startsWith("대기열") }.value!!,"Track 99")
    }

    @Test fun `missing IDs omit removal menu and short page limits selection count`() {
        val noIds=MusicSnapshot(null,List(30) { MusicTrackView("Track $it",null,null,0) },false,null,null)
        assertEquals(2,DiscordMusicPanel.componentRows(noIds,0).size)
        val two=MusicSnapshot(null,List(26) { MusicTrackView("Track $it",null,null,0,id="id-$it") },false,null,null)
        val select=DiscordMusicPanel.componentRows(two,1)[1].components.single().asStringSelectMenu()
        assertEquals(1,select.options.size)
        assertEquals(1,select.maxValues)
        assertEquals("id-25",select.options.single().value)
    }
}
