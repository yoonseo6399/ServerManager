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
        assumeTrue(jar.isRegularFile(),"bootJar not built")
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
}
