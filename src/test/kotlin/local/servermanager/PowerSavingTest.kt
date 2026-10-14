package local.servermanager

import org.junit.jupiter.api.Test
import java.time.Instant
import java.net.ServerSocket
import kotlin.io.path.*
import kotlin.test.*

class PowerSavingTest {
    private val start=Instant.parse("2026-10-14T00:00:00Z")
    private val enabled=PowerPolicy(true,30)
    private fun sample(at:Long,count:Int=0)=PlayerSample(count,if(count==0)emptyList() else listOf("Alex"),start.plusMillis(at).toString())
    @Test fun `only 30 minutes of distinct continuous known empty samples become due`() {
        val timer=EmptyPlayerTimer()
        for(at in 0L until 1_800_000L step 10000)assertFalse(timer.observe(enabled,"RUNNING",false,"process-a",sample(at),start.plusMillis(at),at))
        assertTrue(timer.observe(enabled,"RUNNING",false,"process-a",sample(1_800_000),start.plusSeconds(1800),1_800_000))
        assertEquals(1800,timer.emptySeconds)
    }
    @Test fun `occupied unknown stale gaps restarts and diagnostics all reset continuity`() {
        fun warm():EmptyPlayerTimer=EmptyPlayerTimer().also {t->for(at in 0L..1_790_000L step 10000)t.observe(enabled,"RUNNING",false,"p:0",sample(at),start.plusMillis(at),at)}
        for(mode in listOf("occupied","unknown","stale","gap","restart","join","diagnostic","starting","disconnect","off")) {
            val t=warm();val at=if(mode=="gap")1_820_000L else 1_800_000L
            val s=when(mode){"unknown"->null;"occupied"->sample(at,1);"stale"->sample(at-26000);else->sample(at)}
            assertFalse(t.observe(if(mode=="off")PowerPolicy() else enabled,when(mode){"starting"->"STARTING";"disconnect"->"UNKNOWN";else->"RUNNING"},mode=="diagnostic",if(mode in setOf("restart","join"))"new-identity" else "p:0",s,start.plusMillis(at),at),mode)
            assertEquals(0,t.emptySeconds,mode)
        }
    }
    @Test fun `cached observation cannot establish duration and a clock reversal resets it`() {
        val timer=EmptyPlayerTimer();val s=sample(0)
        assertFalse(timer.observe(enabled,"RUNNING",false,"p",s,start,0))
        assertFalse(timer.observe(enabled,"RUNNING",false,"p",s,start.plusSeconds(20),20000));assertEquals(0,timer.emptySeconds)
        assertFalse(timer.observe(enabled,"RUNNING",false,"p",sample(10000),start.plusSeconds(10),10000))
        assertFalse(timer.observe(enabled,"RUNNING",false,"p",sample(20000),start.plusSeconds(20),1))
        assertEquals(0,timer.emptySeconds)
    }
    @Test fun `policy defaults off persists and rejects out of range or an in-flight final check`() {
        val manager=ServerManager();val id=(manager.create(CreateRequest("power-test","21.1.219",ServerSocket(0).use {it.localPort},512))["config"] as Instance).id
        val power=PowerSaving(manager)
        try {
            assertEquals(PowerPolicy(),power.status(id).config)
            assertFalse(manager.root.resolve(id).resolve("power-saving.json").exists())
            assertFailsWith<IllegalArgumentException>{power.save(id,PowerPolicy(true,29))}
            assertFailsWith<IllegalArgumentException>{power.save(id,PowerPolicy(true,61))}
            power.save(id,PowerPolicy(true,60));assertContains(manager.root.resolve(id).resolve("power-saving.json").readText(),"60")
            val second=PowerSaving(manager);try{assertEquals(PowerPolicy(true,60),second.status(id).config)}finally{second.close()}
            manager.runtime(id).idleCheckPending=true
            assertFailsWith<IllegalStateException>{power.save(id,PowerPolicy())}
            manager.runtime(id).idleCheckPending=false
            power.save(id,PowerPolicy());assertEquals("OFF",power.status(id).status)
            manager.assignRunner(id,"device-a");manager.runtime(id).state="SYNCING";manager.runtime(id).stopReason="IDLE"
            manager.remoteCommitted(id,"device-a")
            assertEquals("IDLE",power.status(id).lastStopReason)
            val reloaded=PowerSaving(manager);try{assertEquals("IDLE",reloaded.status(id).lastStopReason)}finally{reloaded.close()}
        } finally {power.close();manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively()}
    }
    @Test fun `remote confirmations bind to selected device and request and rejection resets activity`() {
        val manager=ServerManager();val id=(manager.create(CreateRequest("remote-power","21.1.219",ServerSocket(0).use {it.localPort},512))["config"] as Instance).id
        manager.assignRunner(id,"device-a");val r=manager.runtime(id)
        try {
            r.state="RUNNING";r.idleCheckPending=true;r.idleRequestId="ticket"
            manager.remoteIdleResult(id,"device-b","ticket",true);assertTrue(r.idleCheckPending)
            manager.remoteIdleResult(id,"device-a","old-ticket",true);assertTrue(r.idleCheckPending)
            manager.remoteIdleResult(id,"device-a","ticket",false);assertFalse(r.idleCheckPending);assertEquals(1,r.activityVersion);assertEquals("RUNNING",r.state)
            r.idleCheckPending=true;r.idleRequestId="next"
            manager.remoteIdleResult(id,"device-a","next",true);assertEquals("STOPPING",r.state);assertEquals("IDLE",r.stopReason);assertTrue(r.remoteStopRequested)
            r.state="STOPPED";r.remotePid=null
            manager.remoteIdleResult(id,"device-a","next",true);assertEquals("STOPPED",r.state)
        } finally {manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively()}
    }
    @Test fun `durable runner idle exit survives lost acknowledgement and central memory reset without crash notice or auto restart`() {
        val manager=ServerManager();val id=(manager.create(CreateRequest("idle-exit", "21.1.219",ServerSocket(0).use {it.localPort},512))["config"] as Instance).id
        manager.assignRunner(id,"device-a");val r=manager.runtime(id)
        val signals=mutableListOf<ServerSignal>();val sub=manager.onSignal {signals.add(it)}
        try {
            manager.root.resolve(id).resolve("remote-lease").writeText("device-a")
            r.state="SYNCING"
            manager.remoteExited(id,"device-a",RunnerExitReport(0,reason="IDLE"))
            assertEquals("IDLE",r.stopReason);assertTrue(r.remoteStopRequested)
            // A restart loses in-memory intent while the authenticated exit record remains.
            r.exitReport=null;r.stopReason=null;r.remoteStopRequested=false
            manager.remoteCommitted(id,"device-a")
            val event=signals.single {it.kind=="stop"};val exit=event.exit!!
            assertTrue(exit.requested);assertEquals("IDLE",exit.reason);assertNull(event.error)
            assertContains(event.detail,"절전");assertFalse(event.detail.contains("예기치"))
            assertFalse(AutoRestart.eligible(AutoRestartPolicy(true,includeManual=true),exit))
            assertFalse(manager.root.resolve(id).resolve("remote-exit.json").exists())
            assertFailsWith<IllegalStateException>{manager.remoteExited(id,"device-a",RunnerExitReport(0,reason="IDLE"))}
        } finally {sub.close();manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively()}
    }

}
