package local.servermanager

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.net.ServerSocket
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

class RemoteConsoleTest {
    private fun fixture(action:(ServerManager,String)->Unit) {
        val manager=ServerManager()
        val id=(manager.create(CreateRequest("remote-console-${UUID.randomUUID()}","21.1.264",ServerSocket(0).use {it.localPort},512))["config"] as Instance).id
        try {manager.assignRunner(id,"runner");action(manager,id)}
        finally {manager.runtime(id).state="STOPPED";manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively()}
    }
    private fun api(manager:ServerManager)=Api(manager,mock(AuthStore::class.java),mock(RunnerRegistry::class.java),mock(GameNetwork::class.java),RemoteFiles(manager))

    @Test fun `early remote crash appears after old install log through the real live window and survives restart`()=fixture {manager,id->
        val profile=manager.profileDir(id)
        profile.resolve("installed.ok").writeText("21.1.264")
        profile.resolve("console.log").writeText("The server installed successfully\n")
        profile.resolve("remote-lease").writeText("runner")
        manager.runtime(id).state="TRANSFERRING"
        val endpoint=api(manager)
        assertEquals(1,endpoint.logWindow(id,"console.log",1,300,null).body!!.total)
        manager.remoteState(id,"runner","STARTING")
        val crash=listOf("Exception in thread \"main\" java.lang.NoClassDefFoundError: org/lwjgl/Version",
            "\tat sodium_service/net.caffeinemc.mods.sodium.PreLaunchChecks.checkEnvironment(PreLaunchChecks.java:25)",
            "Caused by: java.lang.ClassNotFoundException: org.lwjgl.Version")
        crash.forEach {manager.remoteLog(id,"runner","error",it)}
        // The exception is visible before exit upload or file handoff, without switching to an archived file.
        assertEquals(crash,endpoint.logWindow(id,"console.log",2,300,null).body!!.lines.map {it.text})
        manager.remoteExited(id,"runner",RunnerExitReport(1,null))
        manager.remoteState(id,"runner","SYNCING","종료 코드 1 · 변경 파일 인계 중")
        assertEquals(crash,endpoint.logWindow(id,"console.log",2,300,null).body!!.lines.map {it.text})
        assertEquals(listOf(crash[0]),endpoint.logWindow(id,"console.log",1,300,"NoClassDefFoundError").body!!.lines.map {it.text})
        val restarted=ServerManager()
        try {
            assertTrue(restarted.events(id,0).none {it.text.contains("org/lwjgl/Version")})
            assertEquals(crash,api(restarted).logWindow(id,"console.log",2,300,null).body!!.lines.map {it.text})
        } finally {restarted.shutdown()}
    }

    @Test fun `remote console retains exact bounded output ignores noise and other runners and isolates diagnostics`()=fixture {manager,id->
        val file=manager.profileDir(id).resolve("console.log")
        manager.remoteLog(id,"another-runner","error","not authorized")
        manager.remoteLog(id,"runner","output","[MunitionRackDebug] noisy")
        assertFalse(file.exists())
        val text="한글 원문\t"+"x".repeat(5000)
        manager.remoteLog(id,"runner","output",text)
        assertEquals(text.take(4096)+"\n",file.readText())
        val session=UUID.randomUUID().toString()
        manager.runtime(id).diagnosticSession=session
        manager.profileDir(id).createDirectories()
        manager.remoteLog(id,"runner","error","diagnostic-only crash")
        assertEquals("diagnostic-only crash\n",manager.profileDir(id).resolve("console.log").readText())
        assertFalse(file.readText().contains("diagnostic-only"))
    }

    @Test fun `console write failure retains memory output without disconnecting and later writes recover`()=fixture {manager,id->
        val file=manager.profileDir(id).resolve("console.log").createDirectory()
        manager.remoteLog(id,"runner","error","first failure")
        assertTrue(manager.runtime(id).remoteConsoleWriteFailed)
        assertEquals("first failure",manager.events(id,0).last().text)
        file.deleteExisting()
        manager.remoteLog(id,"runner","error","next output")
        assertFalse(manager.runtime(id).remoteConsoleWriteFailed)
        assertEquals("next output\n",file.readText())
    }
}
