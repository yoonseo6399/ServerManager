package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Run alone in a fresh test JVM. Downloads fixed engine dependencies, never any model or inference. */
@EnabledIfEnvironmentVariable(named="TURBOQUANT_INSTALL_LIVE",matches="true")
class TurboQuantInstallLiveTest {
    @TempDir lateinit var home:Path
    @Test @Timeout(420) fun `isolated native installation verifies help without loading models`() {
        val original=System.getProperty("user.home")
        System.setProperty("user.home",home.toString())
        try {
            assertTrue(TurboQuantRuntime.status().supported)
            TurboQuantRuntime.install()
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(6)
            while(TurboQuantRuntime.status().installing && System.nanoTime()<deadline)Thread.sleep(250)
            val status=TurboQuantRuntime.status()
            println("TurboQuant isolated support check: $status")
            assertFalse(status.installing);assertTrue(status.ready,status.message);assertFalse(status.running)
            val root=home.resolve(".servermanager-llm/turboquant")
            val help=root.resolve("tqp-v0.4.0/support.log").readText()
            assertContains(help,"turbo4");assertContains(help,"--ctx-size")
            assertFalse(root.resolve("server.log").exists(),"No serving/model process must start")
            assertFalse(root.resolve("api-key").exists())
        }finally {System.setProperty("user.home",original)}
    }
}
