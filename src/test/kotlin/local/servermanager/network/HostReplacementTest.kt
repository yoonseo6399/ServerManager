package local.servermanager

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

/**
 * Regression tests for GAME_HOST staying WAITING after a control-layer update (host 0.6.0 -> 0.7.0-beta.x).
 * Written for manual/CI execution; they were not run when they were added.
 */
class HostReplacementTest {
    private val closeables=mutableListOf<AutoCloseable>()
    private val processes=mutableListOf<Process>()
    @AfterEach fun cleanup() {closeables.reversed().forEach {runCatching {it.close()}};processes.forEach {runCatching {it.destroyForcibly()}}}
    private fun <T:AutoCloseable> T.track():T{closeables.add(this);return this}
    private fun javaBin()=Path.of(System.getProperty("java.home"),"bin","java").toString()
    private fun gameCommand(dir:Path)=listOf(javaBin(),"-cp",System.getProperty("java.class.path"),"local.servermanager.FakeGameMain",dir.toString())
    private fun await(message:String,timeout:Long=10000,condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(!condition()){assertTrue(System.currentTimeMillis()<end,message);Thread.sleep(50)}}
    /** A real separate host JVM, because a replacement terminates the process named in endpoint.json. */
    private fun hostProcess(home:Path):Process {
        val host=ProcessBuilder(javaBin(),"-cp",System.getProperty("java.class.path"),"local.servermanager.GameHostProcessMain",home.toString(),"0.6.0","--game-host").redirectErrorStream(true).start().also {processes.add(it)}
        await("host process did not start",20000){GameHostClient.readEndpoint(home)!=null}
        return host
    }

    @Test fun `an idle host that already created its relay role is replaced through the attached controller connection`() {
        val home=Files.createTempDirectory("hr-idle");val host=hostProcess(home)
        val c=GameHostClient.connect(home,"controller",retryMillis=5000).track()
        assertTrue(c.relaySession(),"the controller creates the relay role on every manager session")
        assertEquals(host.pid(),c.hostPid)
        assertTrue("replace-drain" in c.hostCaps)
        // A second connection is refused while the controller is attached; the replacement must not need one.
        assertFailsWith<GameHostRejected> {GameHostClient.connect(home,"extra")}
        val result=HostLifecycle.replaceIdleHost(home,c)
        assertTrue(result.done,"idle host with an idle relay role must be replaceable: ${result.reason}")
        assertTrue(host.waitFor(15,TimeUnit.SECONDS),"old host process must have exited")
        assertNull(GameHostClient.readEndpoint(home))
    }

    @Test fun `a running game is never interrupted and the host keeps accepting starts afterwards`() {
        val home=Files.createTempDirectory("hr-game-home");val game=Files.createTempDirectory("hr-game");val host=hostProcess(home)
        val c=GameHostClient.connect(home,"controller",retryMillis=5000).track()
        val started=c.start("srv",gameCommand(game),game,System.getenv())
        val result=HostLifecycle.replaceIdleHost(home,c)
        assertFalse(result.done);assertEquals("GAME_RUNNING",result.reason)
        assertTrue(host.isAlive && started.process.isAlive)
        c.stop("srv",started.process.pid());assertTrue(started.process.waitFor(10,TimeUnit.SECONDS));c.ackExit("srv",started.process.pid())
        assertTrue(HostLifecycle.replaceIdleHost(home,c).done,"replacement proceeds once the game ended")
    }

    @Test fun `replacement blocks new starts until it is cancelled and expires on its own if the controller disappears`() {
        val home=Files.createTempDirectory("hr-block");val game=Files.createTempDirectory("hr-block-game")
        val service=GameHostService(home,"0.7.0",pingMillis=50,replacementLeaseMillis=400).track();assertTrue(service.start())
        val c=GameHostClient.connect(home,"controller").track()
        c.relaySession()
        val state=c.prepareReplacementState();assertTrue(state.ready);assertEquals("READY",state.state)
        val refused=assertFailsWith<GameHostRejected> {c.start("srv",gameCommand(game),game,System.getenv())}
        assertEquals("HOST_REPLACEMENT_PENDING",refused.reason)
        assertTrue(c.cancelReplacement())
        val started=c.start("srv",gameCommand(game),game,System.getenv());assertTrue(started.process.isAlive)
        c.stop("srv",started.process.pid());assertTrue(started.process.waitFor(10,TimeUnit.SECONDS));c.ackExit("srv",started.process.pid())
        // The controller prepares and then vanishes: the flag must not outlive the lease.
        assertTrue(c.prepareReplacementState().ready)
        await("replacement flag expires",5000){runCatching {c.start("srv",gameCommand(game),game,System.getenv()).process}.isSuccess}
    }

    @Test fun `a start that races with the replacement is either refused or makes the replacement refuse`() {
        val home=Files.createTempDirectory("hr-race");val game=Files.createTempDirectory("hr-race-game")
        val service=GameHostService(home,"0.7.0").track();assertTrue(service.start())
        val c=GameHostClient.connect(home,"controller").track()
        val started=c.start("srv",gameCommand(game),game,System.getenv())
        val refusal=assertFailsWith<GameHostRejected> {c.prepareReplacementState()}
        assertEquals("GAME_RUNNING",refusal.reason)
        c.stop("srv",started.process.pid());started.process.waitFor(10,TimeUnit.SECONDS)
    }

    @Test fun `a process that is not the authenticated host is not terminated`() {
        val home=Files.createTempDirectory("hr-identity");val host=hostProcess(home)
        val c=GameHostClient.connect(home,"controller",retryMillis=5000).track()
        val sleeper=ProcessBuilder(javaBin(),"-cp",System.getProperty("java.class.path"),"local.servermanager.GameHostControllerMainSleep").start().also {processes.add(it)}
        val endpoint=GameHostClient.readEndpoint(home)!!
        (home/"host/endpoint.json").writeText("""{"port":${endpoint.port},"secret":"${endpoint.secret}","pid":${sleeper.pid()},"version":"0.6.0","protocol":1,"minCompatible":1}""")
        assertEquals("HOST_IDENTITY_MISMATCH",HostLifecycle.replaceIdleHost(home,c).reason)
        assertTrue(sleeper.isAlive && host.isAlive)
    }

    @Test fun `wait detail is stored redacted and logged with the wait reason`() {
        val home=Files.createTempDirectory("hr-detail");val coordinator=UpdateCoordinator(home)
        coordinator.update(UpdateComponents.RUNNER_CONTROL,"0.6.0","0.7.0-beta.5","WAITING",waitReason="RELAY_STREAMS_ACTIVE",waitDetail="플레이어 연결 3개가 끝나면 자동으로 적용됩니다")
        val row=coordinator.snapshot().components.first {it.component==UpdateComponents.RUNNER_CONTROL}
        assertEquals("플레이어 연결 3개가 끝나면 자동으로 적용됩니다",row.waitDetail)
        coordinator.update(UpdateComponents.GAME_HOST,"0.6.0","0.7.0-beta.5","WAITING",waitReason="RELAY_STREAMS_ACTIVE",waitDetail="token=abcdef0123456789")
        assertFalse(coordinator.snapshot().components.first {it.component==UpdateComponents.GAME_HOST}.waitDetail!!.contains("abcdef0123456789"))
    }
}
