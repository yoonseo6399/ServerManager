package local.servermanager

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.net.ServerSocket
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

class RemoteConsoleTest {
    @TempDir lateinit var temp:Path
    private fun fixture(action:(ServerManager,String)->Unit) {
        val manager=ServerManager()
        val id=(manager.create(CreateRequest("remote-console-${UUID.randomUUID()}","21.1.264",ServerSocket(0).use {it.localPort},512))["config"] as Instance).id
        try {manager.assignRunner(id,"runner");action(manager,id)}
        finally {manager.runtime(id).state="STOPPED";manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively()}
    }
    private fun api(manager:ServerManager)=Api(manager,mock(AuthStore::class.java),mock(RunnerRegistry::class.java),mock(GameNetwork::class.java),RemoteFiles(manager))

    @Test fun `remote crash archive retains output and filtering across manager restart`()=fixture {manager,id->
        val profile=manager.profileDir(id)
        profile.resolve("installed.ok").writeText("21.1.264")
        profile.resolve("console.log").writeText("The server installed successfully\n")
        profile.resolve("remote-lease").writeText("runner")
        manager.runtime(id).state="TRANSFERRING"
        val archive=LogFiles(profile)
        assertEquals(1,archive.window("console.log",1,300,null).total)
        manager.remoteState(id,"runner","STARTING")
        val crash=listOf("Exception in thread \"main\" java.lang.NoClassDefFoundError: org/lwjgl/Version",
            "\tat sodium_service/net.caffeinemc.mods.sodium.PreLaunchChecks.checkEnvironment(PreLaunchChecks.java:25)",
            "Caused by: java.lang.ClassNotFoundException: org.lwjgl.Version")
        crash.forEach {manager.remoteLog(id,"runner","error",it)}
        // The manager archive persists forwarded output independently of runner live reads.
        assertEquals(crash,archive.window("console.log",2,300,null).lines.map {it.text})
        manager.remoteExited(id,"runner",RunnerExitReport(1,null))
        manager.remoteState(id,"runner","SYNCING","종료 코드 1 · 변경 파일 인계 중")
        assertEquals(crash,archive.window("console.log",2,300,null).lines.map {it.text})
        assertEquals(listOf(crash[0]),archive.window("console.log",1,300,"NoClassDefFoundError").lines.map {it.text})
        val restarted=ServerManager()
        try {
            assertTrue(restarted.events(id,0).none {it.text.contains("org/lwjgl/Version")})
            assertEquals(crash,LogFiles(restarted.profileDir(id)).window("console.log",2,300,null).lines.map {it.text})
        } finally {restarted.shutdown()}
    }


    @Test fun `live console API reads runner original and never falls back to stale manager archive`()=fixture {manager,id->
        manager.profileDir(id).resolve("console.log").writeText("stale manager archive\n")
        val logs=temp.resolve("runner").createDirectory()
        val base=logs.resolve("server").createDirectory()
        logs.resolve("console.log").writeText("runner startup\n")
        val workspace=RunnerWorkspace(base,temp.resolve("history"),logs){}
        var available=true
        val controller=mock(RemoteController::class.java) { invocation->
            when(invocation.method.name) {
                "supportsLiveFiles" -> available
                "workspace" -> jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                    workspace.execute(invocation.getArgument<WorkspaceRequest>(1)))
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }
        manager.attachRemoteController(controller)
        val endpoint=api(manager)
        assertEquals(listOf("runner startup"),endpoint.logWindow(id,"console.log",1,300,null).body!!.lines.map {it.text})
        logs.resolve("console.log").appendText("NoClassDefFoundError: org/lwjgl/Version\n")
        assertEquals(listOf("NoClassDefFoundError: org/lwjgl/Version"),endpoint.logWindow(id,"console.log",2,300,null).body!!.lines.map {it.text})
        available=false
        assertFailsWith<IllegalStateException> {endpoint.logWindow(id,"console.log",1,300,null)}
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
