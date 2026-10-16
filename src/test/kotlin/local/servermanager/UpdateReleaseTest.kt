package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.*
import java.nio.file.Path
import kotlin.io.path.*

class UpdateReleaseTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private fun release(v:String,beta:Boolean=false,draft:Boolean=false,assets:Boolean=true)=mapOf("tag_name" to "v$v","prerelease" to beta,"draft" to draft,"name" to "Release $v","published_at" to "2026-10-16T00:00:00Z","assets" to if(assets)listOf("update.properties","update.sig","server-manager-$v.jar").map {mapOf("name" to it)} else emptyList())
    private fun catalog(vararg rows:Map<String,Any>)=UpdateReleases.catalog(mapper.writeValueAsBytes(rows),"0.6.1","0.6.3")
    @Test fun `public releases include beta but exclude drafts malformed and mismatched tags`() {
        val rows=catalog(release("0.7.0-beta.2",true),release("0.7.0-beta.10",true),release("0.7.0"),release("0.8.0",draft=true),release("../escape"),release("0.8.0-beta.1"),release("0.9.0",true))
        assertEquals(listOf("0.7.0","0.7.0-beta.10","0.7.0-beta.2"),rows.map {it.version})
        assertTrue(rows.all {it.installable})
        assertEquals("0.7.0-beta.2",UpdateReleases.select(rows,"0.7.0-beta.2",true).version)
        assertFails {UpdateReleases.select(rows,"0.7.0-beta.2",false)}
    }
    @Test fun `downgrade current failed and unsigned releases cannot be installed`() {
        val rows=catalog(release("0.6.0"),release("0.6.1"),release("0.6.3"),release("0.6.4",assets=false),release("0.6.2"))
        for(v in listOf("0.6.0","0.6.1","0.6.3","0.6.4")){assertFalse(rows.single {it.version==v}.installable);assertFails {UpdateReleases.select(rows,v,true)}}
        assertEquals("0.6.2",UpdateReleases.select(rows,"0.6.2",false).version)
        assertFails {UpdateReleases.select(rows,"../../secret",true)}
        assertFails {UpdateReleases.select(rows,"99.0.0",true)}
    }
    @Test fun `manual install needs fresh passkey and preserves explicit beta consent`() {
        val updates=mock(AppUpdates::class.java);val api=UpdateApi(updates);val request=MockHttpServletRequest()
        assertFails {api.install(UpdateSelection("0.7.0-beta.1",true),request)}
        verifyNoInteractions(updates)
        request.getSession(true)!!.setAttribute("sm.verified",System.currentTimeMillis())
        `when`(updates.install("0.7.0-beta.1",true)).thenReturn(mapOf("message" to "WAITING"))
        assertEquals("WAITING",api.install(UpdateSelection("0.7.0-beta.1",true),request)["message"])
        verify(updates).install("0.7.0-beta.1",true)
    }
    @Test fun `automatic check preserves a staged manual release without network or restart`() {
        val desktop=mock(DesktopLifecycle::class.java)
        `when`(desktop.home).thenReturn(temp);`when`(desktop.supervised).thenReturn(true)
        `when`(desktop.updates).thenReturn(UpdateCoordinator(temp))
        val pending=temp.resolve("updates/pending.txt");pending.parent.createDirectories();pending.writeText("99.0.0-beta.1")
        val result=AppUpdates(desktop).checkNow()
        assertEquals("99.0.0-beta.1",result["latest"]);assertContains(result["message"].toString(),"유지")
        assertEquals("99.0.0-beta.1",pending.readText());verify(desktop,never()).restartWhenIdle()
    }
}
