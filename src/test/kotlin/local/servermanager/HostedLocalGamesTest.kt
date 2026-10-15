package local.servermanager

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Mac-local games (no runner assigned) get the same protection: the desktop manager can restart while the game lives. */
class HostedLocalGamesTest {
    private val closeables=mutableListOf<AutoCloseable>()
    private val cleanup=mutableListOf<()->Unit>()
    @AfterEach fun tearDown(){closeables.reversed().forEach {runCatching {it.close()}};cleanup.forEach {runCatching {it()}}}
    private fun <T:AutoCloseable> T.track():T{closeables.add(this);return this}
    private fun javaBin()=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows"))"java.exe" else "java").toString()
    private fun await(message:String,timeout:Long=15000,condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(!condition()){assertTrue(System.currentTimeMillis()<end,message);Thread.sleep(30)}}
    private fun events(m:ServerManager,id:String)=@Suppress("UNCHECKED_CAST") (m.eventPage(id,null,null,300)["events"] as List<Event>).map {it.text}

    @Test fun `manager restart keeps the local game pid and console and the next manager re-attaches`() {
        val home=Files.createTempDirectory("hl-home");val game=Files.createTempDirectory("hl-game")
        val service=GameHostService(home,"0.5.2").track();assertTrue(service.start())
        val first=ServerManager();val id=(first.create(CreateRequest("hosted local","21.1.252",31991,512,javaBin()))["config"] as Instance).id
        cleanup.add {first.root.resolve(id).toFile().deleteRecursively()}
        // A game the host already owns for this server (what a launch through HostedGames produces).
        val launcher=HostedGames(home,"manager-1",{false},retryMillis=2000).track()
        val command=listOf(javaBin(),"-cp",System.getProperty("java.class.path"),"local.servermanager.FakeGameMain",game.toString(),"chatter")
        val started=launcher.connection()!!.start(id,command,game,System.getenv());val pid=started.process.pid()
        launcher.close() // the launcher in a real run is a fresh HostedGames per manager process
        first.localLauncher=HostedGames(home,"manager-1b",{false},retryMillis=2000).track()
        assertEquals(listOf(id),first.adoptHostedGames())
        await("console reaches the manager"){events(first,id).any {it.startsWith("Done (")}}
        await("running"){first.view(id)["state"]=="RUNNING"}
        assertEquals(pid,first.view(id)["pid"])
        val lifecycle=DesktopLifecycle(first,org.mockito.Mockito.mock(org.springframework.context.ConfigurableApplicationContext::class.java))
        assertTrue(lifecycle.updateDecision().apply,"hosted game must not block manager app replacement")
        assertEquals("HOST_PROTOCOL_INCOMPATIBLE",lifecycle.updateDecision(2,3).waitReason)
        first.runtime(id).fileTransfer=FolderImportProgress("COMPLETE")
        assertTrue(lifecycle.updateDecision().apply,"completed progress is history, not an active transfer")
        first.runtime(id).fileTransfer=FolderImportProgress("UPLOADING")
        assertEquals("TRANSFER_ACTIVE",lifecycle.updateDecision().waitReason)
        first.runtime(id).fileTransfer=null
        first.command(id,"list");await("echo from the hosted game"){events(first,id).contains("echo:list")}
        // Manager shuts down (update/restart). The game must not be stopped.
        first.shutdown();(first.localLauncher as AutoCloseable).close();first.localLauncher=null
        Thread.sleep(500)
        assertTrue(ProcessHandle.of(pid).map {it.isAlive}.orElse(false),"hosted game must survive the manager shutting down")
        // A new manager process attaches to the same host and the same PID.
        val second=ServerManager()
        second.localLauncher=HostedGames(home,"manager-2",{false},retryMillis=5000).track()
        assertEquals(listOf(id),second.adoptHostedGames())
        await("re-attached and running"){second.view(id)["state"]=="RUNNING" && second.view(id)["pid"]==pid}
        second.command(id,"after-restart");await("command after manager restart"){events(second,id).contains("echo:after-restart")}
        assertEquals(1,(game/"launches.txt").readLines().filter {it.isNotBlank()}.size,"never launched twice")
        // Normal stop through the manager ends the game and releases the host record.
        second.command(id,"stop");await("stopped",timeout=20000){second.view(id)["state"]=="STOPPED"}
        await("exit acknowledged"){service.statuses().none {it.id==id}}
        second.shutdown()
    }

    @Test fun `host mode waits for the next normal stop when an old-layout game is alive`() {
        val home=Files.createTempDirectory("mode")
        val store=HostModeStore(home){null}
        assertEquals("SERVICE",store.evaluate(emptyList()).mode)
        val pending=store.evaluate(listOf("srv-b","srv-a"))
        assertEquals("PENDING_NORMAL_STOP",pending.mode);assertEquals(listOf("srv-a","srv-b"),pending.legacyGames)
        assertEquals("PENDING_NORMAL_STOP",HostModeStore(home){null}.read().mode,"pending state survives a restart")
        assertEquals("SERVICE",HostModeStore(home){null}.evaluate(emptyList()).mode,"after the normal stop the split is applied")
        val pinned=HostModeStore(Files.createTempDirectory("mode2")){if(it=="SERVER_MANAGER_GAME_HOST")"off" else null}.evaluate(emptyList())
        assertEquals("LEGACY",pinned.mode);assertNotNull(pinned.reason)
    }

    @Test fun `an unreachable host falls back to the old layout with a stated reason and never half-starts a game`() {
        val home=Files.createTempDirectory("nohost");var spawned=0
        val launcher=HostedGames(home,"c",{spawned++;false},retryMillis=300).track()
        assertNull(launcher.launch("x",listOf("true"),home,emptyMap()),"caller must use the legacy in-process launch")
        assertEquals(1,spawned);assertNotNull(launcher.lastError)
        assertEquals(emptyList(),launcher.adoptable())
    }

    @Test fun `closing the controller never stops hosted games`() {
        val home=Files.createTempDirectory("hl2-home");val game=Files.createTempDirectory("hl2-game")
        val service=GameHostService(home,"0.5.2").track();assertTrue(service.start())
        val launcher=HostedGames(home,"c",{false},retryMillis=2000)
        val pid=launcher.connection()!!.start("keep",listOf(javaBin(),"-cp",System.getProperty("java.class.path"),"local.servermanager.FakeGameMain",game.toString()),game,System.getenv()).process.pid()
        launcher.close();Thread.sleep(400)
        assertTrue(ProcessHandle.of(pid).map {it.isAlive}.orElse(false))
        assertEquals(listOf("keep"),HostedGames(home,"c2",{false},retryMillis=3000).track().adoptable())
        ProcessHandle.of(pid).ifPresent {it.destroyForcibly()}
    }
}
