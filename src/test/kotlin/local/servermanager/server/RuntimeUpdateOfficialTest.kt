package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class RuntimeUpdateOfficialTest {
    @TempDir lateinit var root:Path
    @Test fun `official Minecraft upgrade through runner workspace preserves world and installs verified new runtime`() {
        assumeTrue(System.getenv("RUN_REAL_RUNTIME_UPDATE")=="true")
        val server=root.resolve("server").createDirectories();server.resolve("world").createDirectories();server.resolve("world/level.dat").writeText("isolated world sentinel - never started");server.resolve("server.jar").writeText("old runtime sentinel")
        val instance=Instance("fixture","Fixture",loader="Vanilla",minecraft="1.21.1",loaderVersion="1.21.1",port=25565,memoryMb=1024,javaPath=System.getProperty("java.home")+"/bin/java",runnerId="fixture",fileSyncEnabled=false)
        var writes=0;val workspace=RunnerWorkspace(server,root.resolve("history"),root,beforeMutation={writes++}){}
        val mapper=jacksonObjectMapper();val target=RuntimeUpdate.Target("1.21.2","1.21.2")
        val first=workspace.execute(WorkspaceRequest("RUNTIME_START",instance,data=mapper.writeValueAsString(target))) as RuntimeUpdate.Status
        assertTrue(first.state in setOf("PREPARING","INSTALLING"));val deadline=System.nanoTime()+180_000_000_000L
        var status=first
        while(status.state !in setOf("COMPLETE","FAILED")){check(System.nanoTime()<deadline){"Official download test timed out"};Thread.sleep(100);status=workspace.execute(WorkspaceRequest("RUNTIME_STATUS",instance)) as RuntimeUpdate.Status}
        assertEquals("COMPLETE",status.state,status.error);assertEquals(1,writes);assertFalse(workspace.busy())
        assertEquals("isolated world sentinel - never started",server.resolve("world/level.dat").readText());assertTrue(server.resolve("server.jar").fileSize()>1024*1024)
        val next=instance.copy(minecraft="1.21.2",loaderVersion="1.21.2");assertEquals(21,Launchers.requiredJava(next,server));assertEquals("old runtime sentinel",Path.of(status.backup!!).resolve("before/server.jar").readText())
        println("OFFICIAL_RUNTIME_UPDATE_OK: runner workspace; Minecraft 1.21.1 -> 1.21.2; verified official server JAR; world sentinel preserved; no Minecraft process launched")
    }
}
