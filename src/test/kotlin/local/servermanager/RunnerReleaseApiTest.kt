package local.servermanager
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import kotlin.test.*

class RunnerReleaseApiTest {
    @Test fun `selection requires fresh authentication validates consent before reserving and sends exact target`() {
        val registry=mock(RunnerRegistry::class.java);val gateway=mock(RunnerGateway::class.java);val updates=mock(AppUpdates::class.java)
        val api=RunnerApi(registry,mock(AuthStore::class.java),mock(AuditLog::class.java),gateway,updates)
        val auth=UsernamePasswordAuthenticationToken("user","unused");val req=MockHttpServletRequest()
        val device=RunnerDevice("runner","fixture","MAC","user","key","now",version="0.6.2")
        assertFails {api.update("runner",RunnerUpdateRequest(version="0.7.0-beta.2",allowPrerelease=true),auth,req)};verifyNoInteractions(registry,gateway,updates)
        req.getSession(true)!!.setAttribute("sm.verified",System.currentTimeMillis())
        `when`(registry.validateUpdate("user","runner",false,"0.7.0-beta.2")).thenReturn(device)
        `when`(updates.releasesFor("0.6.2",false)).thenReturn(listOf(PublishedUpdate("0.7.0-beta.2","Beta",true,"now","https://github.com",true,null)))
        assertFails {api.update("runner",RunnerUpdateRequest(version="0.7.0-beta.2"),auth,req)}
        verify(registry,never()).requestUpdate("user","runner",false,"0.7.0-beta.2");verifyNoInteractions(gateway)
        `when`(gateway.requestUpdate("runner",false,"0.7.0-beta.2",true,false)).thenReturn(true)
        api.update("runner",RunnerUpdateRequest(version="0.7.0-beta.2",allowPrerelease=true),auth,req)
        verify(registry).requestUpdate("user","runner",false,"0.7.0-beta.2");verify(gateway).requestUpdate("runner",false,"0.7.0-beta.2",true,false)
    }
}
