package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

class HostReadinessTest {
    private fun label(home:Path)="local.servermanager.game-host."+java.security.MessageDigest.getInstance("SHA-256").digest(home.toAbsolutePath().normalize().toString().toByteArray()).take(12).joinToString(""){"%02x".format(it)}
    private fun javaBin()=Path.of(System.getProperty("java.home"),"bin","java").toString()
    private fun waitFor(check:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(!check()){assertTrue(System.nanoTime()<end,"host did not become ready");Thread.sleep(50)}}
    @Test fun `authenticated idle replacement removes launchd keepalive before starting the new host`() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val home=Files.createTempDirectory("host-replace-launchd");val label=label(home);val log=home.resolve("fixture.log")
        val jar=Path.of("build/libs/server-manager-${Path.of("VERSION").readText().trim()}.jar").toAbsolutePath()
        try {
            val submit=ProcessBuilder(listOf("/bin/launchctl","submit","-l",label,"-o",log.toString(),"-e",log.toString(),"--",javaBin(),"-cp",System.getProperty("java.class.path"),"local.servermanager.GameHostProcessMain",home.toString(),"previous","--game-host")).start()
            assertTrue(submit.waitFor(10,TimeUnit.SECONDS));assertEquals(0,submit.exitValue())
            waitFor {GameHostClient.readEndpoint(home)!=null}
            val first=GameHostClient.connect(home,"replace",retryMillis=3000)
            val old=first.hostPid
            assertEquals("previous",first.hostVersion)
            assertTrue(HostLifecycle.replaceIdleHost(home,first).done)
            val listed=ProcessBuilder("/bin/launchctl","list",label).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            assertTrue(listed.waitFor(5,TimeUnit.SECONDS));assertEquals(113,listed.exitValue(),"old KeepAlive job must be gone, not merely its PID")
            assertTrue(GameHostMain.spawnDetached(home,javaBin(),jar.toString(),false))
            GameHostClient.connect(home,"new",retryMillis=3000).use {c->assertEquals(Path.of("VERSION").readText().trim(),c.hostVersion);assertNotEquals(old,c.hostPid)}
            assertNull(HostLifecycle.replaceIfIdle(home))
            Thread.sleep(400)
            assertNull(GameHostClient.readEndpoint(home),"removed service cannot resurrect an old endpoint")
        } finally {
            ProcessBuilder("/bin/launchctl","remove",label).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(5,TimeUnit.SECONDS)
        }
    }
    @Test fun `startup confirmation rejects stale PID and wrong version then waits for the actual ready endpoint`() {
        val home=Files.createTempDirectory("host-ready-version")
        GameHostService(home,"previous").use {old->
            assertTrue(old.start())
            assertFalse(GameHostMain.waitForEndpoint(home,null,"current",150))
            assertFalse(GameHostMain.waitForEndpoint(home,ProcessHandle.current().pid(),"previous",150))
            assertTrue(GameHostMain.waitForEndpoint(home,null,"previous",150))
        }
        val writer=Thread.ofVirtual().start {Thread.sleep(250);GameHostService(home,"current").use {fresh->assertTrue(fresh.start());Thread.sleep(1200)}}
        assertTrue(GameHostMain.waitForEndpoint(home,null,"current",2500))
        writer.join()
    }
    @Test fun `authenticated recovery clears stale failure once and keeps failures for a different version`() {
        val home=Files.createTempDirectory("host-ready-status");val status=UpdateCoordinator(home)
        status.update(UpdateComponents.GAME_HOST,"previous","current","FAILED",error="start timed out")
        assertFalse(status.reconcileHost("previous","current"))
        assertEquals("FAILED",status.current(UpdateComponents.GAME_HOST)?.stage)
        assertTrue(status.reconcileHost("current","current"))
        assertEquals("current",status.current(UpdateComponents.GAME_HOST)?.current)
        assertEquals("DONE",status.current(UpdateComponents.GAME_HOST)?.stage)
        assertNull(status.current(UpdateComponents.GAME_HOST)?.error)
        val seq=status.snapshot().log.last().seq
        assertFalse(status.reconcileHost("current","current"));assertEquals(seq,status.snapshot().log.last().seq)
        status.update(UpdateComponents.GAME_HOST,"current","future","FAILED",error="future failed")
        assertFalse(status.reconcileHost("current","current"));assertEquals("future",status.current(UpdateComponents.GAME_HOST)?.target)
    }
    @Test fun `launchd removal refuses an unrelated PID even if a local label file says otherwise`() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        val home=Files.createTempDirectory("host-ready-identity");val label=label(home)
        val start=ProcessBuilder("/bin/launchctl","submit","-l",label,"--","/bin/sleep","120").start()
        try {
            assertTrue(start.waitFor(5,TimeUnit.SECONDS));assertEquals(0,start.exitValue())
            assertEquals("HOST_SERVICE_IDENTITY_MISMATCH",GameHostMain.removeLaunchdHost(home,ProcessHandle.current().pid()))
            home.resolve("host-launch-label.txt").writeText("some-unrelated-job")
            assertEquals("HOST_SERVICE_IDENTITY_MISMATCH",GameHostMain.removeLaunchdHost(home,ProcessHandle.current().pid()))
            val query=ProcessBuilder("/bin/launchctl","list",label).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();assertTrue(query.waitFor(5,TimeUnit.SECONDS));assertEquals(0,query.exitValue())
        } finally {ProcessBuilder("/bin/launchctl","remove",label).start().waitFor(5,TimeUnit.SECONDS)}
    }

}
