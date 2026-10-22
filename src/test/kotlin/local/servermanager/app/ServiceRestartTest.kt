package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.nio.file.Path
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

class ServiceRestartTest {
    @TempDir lateinit var home:Path
    private fun fixture(supervised:Boolean=true,action:(ServerManager,DesktopLifecycle)->Unit) {
        val manager=ServerManager();val runtime=mock(DesktopRuntime::class.java)
        `when`(runtime.supervised).thenReturn(supervised);`when`(runtime.home).thenReturn(home)
        val desktop=DesktopLifecycle(manager,mock(ConfigurableApplicationContext::class.java),runtime=runtime)
        try {action(manager,desktop)}finally{manager.shutdown()}
    }
    @Test fun `same version restart requires supervisor fresh auth and runs through the lifecycle gate`()=fixture {_,desktop->
        val audit=mock(AuditLog::class.java);val api=ServiceRestartApi(desktop,audit);val request=MockHttpServletRequest()
        val user=UsernamePasswordAuthenticationToken("owner","unused")
        assertFails {api.restart(user,request)};verifyNoInteractions(audit)
        request.getSession(true)!!.setAttribute("sm.verified",System.currentTimeMillis())
        val done=CountDownLatch(1);desktop.restartProcess={done.countDown()}
        assertEquals("REQUESTED",api.restart(user,request).stage)
        assertFails {api.restart(user,request)}
        desktop.restart();assertTrue(done.await(5,TimeUnit.SECONDS));assertTrue(desktop.stopping)
        verify(audit).append("owner","manager service restart requested",202,"web")
    }
    @Test fun `unsupervised and pending update never exit`() {
        fixture(false){_,d->assertFalse(d.serviceRestartStatus().supported);assertFails {d.requestServiceRestart()}}
        fixture {_,d->home.resolve("updates").createDirectories().resolve("pending.txt").writeText("future");assertEquals("UPDATE_PENDING",d.serviceRestartStatus().reason);assertFails {d.requestServiceRestart()}}
    }
    @Test fun `work starting after acceptance cancels manual restart and permits retry when idle`()=fixture {manager,desktop->
        val id=(manager.create(CreateRequest("restart-fixture","21.1.264",ServerSocket(0).use {it.localPort},512))["config"] as Instance).id
        try {
            manager.runtime(id).state="STOPPED"
            var exits=0;desktop.restartProcess={exits++}
            desktop.requestServiceRestart();manager.runtime(id).modBusy=true;desktop.restart()
            assertEquals(0,exits);assertFalse(desktop.stopping);assertEquals("FAILED",desktop.serviceRestartStatus().stage)
            assertEquals("SERVER_PREPARING",desktop.serviceRestartStatus().reason)
            manager.runtime(id).modBusy=false;assertTrue(desktop.serviceRestartStatus().canRestart)
            val process=mock(Process::class.java);`when`(process.isAlive).thenReturn(true)
            manager.runtime(id).state="RUNNING";manager.runtime(id).process=process
            assertEquals("LEGACY_GAME_RUNNING",desktop.serviceRestartStatus().reason);assertFails {desktop.requestServiceRestart()}
        } finally {manager.runtime(id).process=null;manager.runtime(id).state="STOPPED";manager.root.resolve(id).toFile().deleteRecursively()}
    }
}
