package local.servermanager

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Uses the real bootJar: skipped unless build/libs/server-manager-<version>.jar exists (run `bootJar` first). */
class GameHostSpawnTest {
    @Test fun `detached spawn from the packaged jar starts an authenticated host that outlives the launcher and is replaced when idle`() {
        val version=Path.of("VERSION").readText().trim()
        val jar=Path.of("build/libs/server-manager-$version.jar").toAbsolutePath()
        assertTrue(jar.isRegularFile(),"test task must build bootJar first")
        val windows=System.getProperty("os.name").startsWith("Windows")
        val java=Path.of(System.getProperty("java.home"),"bin",if(windows)"java.exe" else "java").toString()
        val home=Files.createTempDirectory("spawn-home")
        assertTrue(GameHostMain.spawnDetached(home,java,jar.toString(),windows),"host did not start")
        val endpoint=GameHostClient.readEndpoint(home)!!
        try {
            assertNotEquals(ProcessHandle.current().pid(),endpoint.pid)
            val client=GameHostClient.connect(home,"spawn-test",retryMillis=10000)
            assertEquals(endpoint.protocol,client.hostProtocol);client.close()
            assertTrue(ProcessHandle.of(endpoint.pid).map {it.isAlive}.orElse(false))
            assertTrue(HostLifecycle.preflight(home).ok)
        } finally {
            assertNull(HostLifecycle.replaceIfIdle(home),"idle real host must be replaceable")
        }
        assertFalse(ProcessHandle.of(endpoint.pid).map {it.isAlive}.orElse(false))
    }
    @Test fun `mac launchd controller job exits while independently submitted host survives`() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val jar=Path.of("build/libs/server-manager-${Path.of("VERSION").readText().trim()}.jar").toAbsolutePath()
        assertTrue(jar.isRegularFile(),"test task must build bootJar first")
        val home=Files.createTempDirectory("launchd-host");val log=home.resolve("controller.log")
        val label="local.servermanager.test-controller."+java.util.UUID.randomUUID().toString()
        val javaBin=Path.of(System.getProperty("java.home"),"bin","java").toString()
        try {
            val submit=ProcessBuilder("/bin/launchctl","submit","-l",label,"-o",log.toString(),"-e",log.toString(),"--",javaBin,"-cp",System.getProperty("java.class.path"),"local.servermanager.DetachedHostLauncherFixture",home.toString(),jar.toString()).start()
            assertTrue(submit.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,submit.exitValue())
            val end=System.currentTimeMillis()+30000
            while(!log.exists() || !log.readText().contains("HOST_STARTED")){assertTrue(System.currentTimeMillis()<end,log.takeIf {it.exists()}?.readText() ?: "no controller log");Thread.sleep(100)}
            Thread.sleep(1000) // controller fixture has returned; now remove its launchd job/process group
            val remove=ProcessBuilder("/bin/launchctl","remove",label).start();remove.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)
            val endpoint=GameHostClient.readEndpoint(home)!!
            assertTrue(ProcessHandle.of(endpoint.pid).orElseThrow().isAlive)
            GameHostClient.connect(home,"after-controller-exit",retryMillis=1000).close()
            assertEquals(jar.toString(),GameHostMain.resolveJarLocation("jar:nested:"+jar.toString()+"/!BOOT-INF/classes/!/"))
            println("MAC-LAUNCHD-PROOF hostPid=${endpoint.pid} controllerJobRemoved=$label")
        } finally {
            ProcessBuilder("/bin/launchctl","remove",label).start().waitFor()
            assertNull(HostLifecycle.replaceIfIdle(home))
            home.resolve("host-launch-label.txt").takeIf {it.exists()}?.readText()?.let {ProcessBuilder("/bin/launchctl","remove",it).start().waitFor()}
        }
    }

}
