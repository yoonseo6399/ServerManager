package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import java.net.ServerSocket
import kotlin.io.path.*
import kotlin.test.*

class AutoRestartTest {
    @TempDir lateinit var temp:Path
    @Test fun `default off and requested idle restart shutdown diagnostic exits never loop`() {
        val crash=ServerExit(1,false,"UNEXPECTED")
        assertFalse(AutoRestart.eligible(AutoRestartPolicy(),crash))
        val policy=AutoRestartPolicy(true)
        assertTrue(AutoRestart.eligible(policy,crash))
        assertTrue(AutoRestart.eligible(policy,crash.copy(code=0)))
        assertFalse(AutoRestart.eligible(policy,crash.copy(requested=true,reason="MANUAL")))
        assertTrue(AutoRestart.eligible(policy.copy(includeManual=true),crash.copy(requested=true,reason="MANUAL")))
        listOf("IDLE","RESTART","SHUTDOWN").forEach {reason->assertFalse(AutoRestart.eligible(policy.copy(includeManual=true),crash.copy(requested=true,reason=reason)))}
        val b=RestartBudget()
        assertEquals(15,b.delay(1000));assertTrue(b.reserve(1000));assertEquals(30,b.delay(2000))
        assertTrue(b.reserve(2000));assertEquals(60,b.delay(3000));assertTrue(b.reserve(3000));assertFalse(b.reserve(4000))
        val persisted=RestartBudget(b.recent(4000));assertFalse(persisted.reserve(5000));assertEquals(15,persisted.delay(700000));assertTrue(persisted.reserve(700000))
    }
    @Test fun `only current run report is selected and links traversal oversized files cannot attach`() {
        val base=temp.resolve("server").also {it.createDirectories()};val reports=base.resolve("crash-reports").also {it.createDirectories()}
        val old=reports.resolve("crash-old-server.txt").also {it.writeText("old crash")};val before=CrashReports.snapshot(base)
        assertNull(CrashReports.changed(base,before))
        val current=reports.resolve("crash-new-server.txt").also {it.writeText("current crash")}
        assertEquals(current.name,CrashReports.changed(base,before))
        assertContentEquals(current.readBytes(),CrashReports.attachment(base,current.name))
        assertNull(CrashReports.attachment(base,"../secret.txt"));assertNull(CrashReports.attachment(base,"/secret.txt"))
        val huge=reports.resolve("large.txt");huge.outputStream().use {it.write(ByteArray(CrashReports.MAX_BYTES+1))};assertNull(CrashReports.attachment(base,huge.name))
        java.nio.file.Files.createSymbolicLink(reports.resolve("linked.txt"),old)
        assertNull(CrashReports.attachment(base,"linked.txt"))
        val alias=temp.resolve("alias");java.nio.file.Files.createSymbolicLink(alias,base);assertNull(CrashReports.attachment(alias,current.name))
    }
    @Test fun `remote crash waits for cloud and worker then retries once while canceled and stale runs stay stopped`() {
        val manager=ServerManager()
        val i=manager.create(CreateRequest("restart regression","21.1.219",ServerSocket(0).use {it.localPort},512))["config"] as Instance
        var starts=0
        manager.attachRemoteController(object:RemoteController {
            override fun start(instance:Instance){starts++}
            override fun stop(instance:Instance){}
            override fun restart(instance:Instance){}
            override fun command(instance:Instance,value:String){}
        });manager.assignRunner(i.id,"runner")
        val cloud=mock(WebDavSync::class.java)
        `when`(cloud.status(i.id)).thenReturn(WebDavSyncStatus(false,false,null,null,null))
        val desktop=DesktopLifecycle(manager,mock(org.springframework.context.ConfigurableApplicationContext::class.java))
        val auto=AutoRestart(manager,desktop,cloud)
        val signals=mutableListOf<ServerSignal>();val sub=manager.onSignal {signals.add(it)}
        try {
            auto.save(i.id,AutoRestartPolicy(true))
            manager.start(i.id);assertEquals(1,starts)
            assertFailsWith<IllegalArgumentException>{manager.remoteExited(i.id,"other",RunnerExitReport(1))}
            manager.remoteState(i.id,"runner","STARTING");assertTrue(signals.isEmpty())
            repeat(4){manager.remoteState(i.id,"runner","RUNNING")};assertEquals(1,signals.count {it.kind=="start"})
            manager.remoteDisconnected("runner");manager.remoteState(i.id,"runner","RUNNING");assertEquals(1,signals.count {it.kind=="start"})
            manager.remoteExited(i.id,"runner",RunnerExitReport(1,"crash-test-server.txt"))
            manager.remoteState(i.id,"runner","SYNCING");manager.remoteCommitted(i.id,"runner")
            val exit=signals.single {it.kind=="stop"}.exit!!
            assertEquals(1,exit.code);assertFalse(exit.requested);assertEquals("crash-test-server.txt",exit.crashReport);assertContains(exit.summary(),"크래시")
            auto.tick(System.currentTimeMillis()+20000);assertEquals(1,starts)
            manager.remoteCloudReady(i.id);auto.tick(System.currentTimeMillis()+20000);assertEquals(1,starts)
            manager.remoteWorkerReady(i.id,"runner");auto.tick(System.currentTimeMillis()+20000);assertEquals(2,starts)
            auto.tick(System.currentTimeMillis()+30000);assertEquals(2,starts)
            manager.remoteState(i.id,"runner","RUNNING");assertEquals(2,signals.count {it.kind=="start"})
            manager.remoteState(i.id,"runner","SYNCING");manager.remoteCommitted(i.id,"runner");manager.remoteCloudReady(i.id);manager.remoteWorkerReady(i.id,"runner")
            manager.setDiagnosticActive(i.id,true);auto.tick(System.currentTimeMillis()+90000);assertEquals(2,starts)
            auto.cancel(i.id);manager.setDiagnosticActive(i.id,false);auto.tick(System.currentTimeMillis()+90000);assertEquals(2,starts)
            assertFailsWith<IllegalStateException>{manager.remoteExited(i.id,"runner",RunnerExitReport(1))}
        } finally {sub.close();auto.close();manager.shutdown();manager.root.resolve(i.id).toFile().deleteRecursively()}
    }
    @Test fun `actual local process emits ready once then records crash report and separates manual stop`() {
        val manager=ServerManager()
        val javaBin=Path.of(System.getProperty("java.home"),"bin/java")
        val i=manager.create(CreateRequest("process exit regression","1.21.1",ServerSocket(0).use {it.localPort},512,javaBin.toString(),"Vanilla","1.21.1"))["config"] as Instance
        val signals=java.util.concurrent.CopyOnWriteArrayList<ServerSignal>();val sub=manager.onSignal {signals.add(it)}
        val source=temp.resolve("FakeMinecraft.java")
        source.writeText("""import java.nio.file.*;import java.io.*;public class FakeMinecraft {public static void main(String[] args)throws Exception {System.out.println("Done (0.1s)! For help, type help");System.out.println("Done (duplicate)! For help, type help");BufferedReader in=new BufferedReader(new InputStreamReader(System.in));String line;while((line=in.readLine())!=null){if(line.equals("crash")){Files.createDirectories(Path.of("crash-reports"));Files.writeString(Path.of("crash-reports/crash-current-server.txt"),"Minecraft Crash Report - fixture");System.exit(42);}if(line.equals("stop"))return;}}}""")
        val classes=temp.resolve("classes").also {it.createDirectories()}
        fun run(vararg args:String){val p=ProcessBuilder(*args).redirectErrorStream(true).start();assertTrue(p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,p.exitValue(),p.inputStream.bufferedReader().readText())}
        fun await(condition:()->Boolean){val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(!condition()&&System.nanoTime()<until)Thread.sleep(20);assertTrue(condition())}
        try {
            run(javaBin.resolveSibling("javac").toString(),"-d",classes.toString(),source.toString())
            run(javaBin.resolveSibling("jar").toString(),"--create","--file",manager.serverDir(i.id).resolve("server.jar").toString(),"--main-class","FakeMinecraft","-C",classes.toString(),".")
            manager.serverDir(i.id).resolve("server-manager-runtime.json").writeText("""{"loader":"Vanilla","minecraft":"1.21.1","loaderVersion":"1.21.1","requiredJava":21}""")
            manager.start(i.id);await {signals.any {it.kind=="start"}};manager.command(i.id,"say 안녕");manager.command(i.id,"crash")
            await {signals.count {it.kind=="stop"}==1}
            val crash=signals.single {it.kind=="stop"}.exit!!;assertEquals(42,crash.code);assertFalse(crash.requested);assertEquals("crash-current-server.txt",crash.crashReport)
            manager.start(i.id);await {signals.count {it.kind=="start"}==2};manager.stop(i.id,false);await {signals.count {it.kind=="stop"}==2}
            val stopped=signals.last {it.kind=="stop"}.exit!!;assertTrue(stopped.requested);assertEquals("MANUAL",stopped.reason);assertEquals(0,stopped.code);assertNull(stopped.crashReport)
        } finally {sub.close();manager.shutdown();manager.root.resolve(i.id).toFile().deleteRecursively()}
    }
    @Test fun `legacy Discord routes enable starts but never treat import signals as shutdown`() {
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val route=mapper.readValue("""{"guildId":"1","channelId":"2","instanceId":"a"}""",DiscordAlertRoute::class.java)
        assertTrue(route.accepts("start"));assertTrue(route.accepts("stop"));assertTrue(route.accepts("error"));assertFalse(route.accepts("import"))
        assertFalse(route.copy(starts=false).accepts("start"));assertFalse(route.copy(stops=false).accepts("stop"))
        val alerts=DiscordAlerts();alerts.offer(ServerSignal("a","stop","crash",ServerExit(1,false,"UNEXPECTED","crash.txt")))
        assertEquals("crash.txt",alerts.drain().single().first.exit?.crashReport)
    }
}
