package local.servermanager

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import kotlin.test.*

class DiscordModuleApiTest {
    @Test fun `module changes require fresh passkey verification and stale requests do not reach bot`() {
        val bot=mock(DiscordBot::class.java);val api=DiscordApi(bot);val req=MockHttpServletRequest()
        val save=DiscordModuleSave(0,setOf("music"))
        assertEquals(428,assertFailsWith<ResponseStatusException> {api.saveModules(save,req)}.statusCode.value())
        verifyNoInteractions(bot)
        req.getSession(true)!!.setAttribute("sm.verified",System.currentTimeMillis()-301000)
        assertEquals(428,assertFailsWith<ResponseStatusException> {api.saveModules(save,req)}.statusCode.value())
        verifyNoInteractions(bot)
        req.getSession(false)!!.setAttribute("sm.verified",System.currentTimeMillis())
        `when`(bot.saveModules(save)).thenReturn(mapOf("revision" to 1L))
        assertEquals(1L,api.saveModules(save,req)["revision"]);verify(bot).saveModules(save)
    }
}
