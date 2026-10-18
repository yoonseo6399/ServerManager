package local.servermanager

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.net.ServerSocket
import kotlin.io.path.*
import kotlin.test.*

class DiscordErrorSummaryTest {
    @TempDir lateinit var temp:Path
    @Test fun `summary preserves exception message and nested causes without frames or mixins`() {
        val b=DiscordErrorBuffer()
        b.offer("[12:30:00] [Server thread/ERROR] [minecraft/MinecraftServer]: Exception in server tick loop",true,1000)
        b.offer("java.lang.RuntimeException: Mod startup failed",true,1001)
        repeat(7000){assertFalse(b.offer("\tat TRANSFORMER/net.mod.Example.run(Example.java:22) {pl:mixin:APP:foo:Mixin from mod noise}",true,1002))}
        b.offer("Caused by: java.lang.IllegalStateException: Registry missing",true,1003)
        b.offer("Caused by: java.lang.NoClassDefFoundError: sample/Dependency",false,1004)
        val result=b.recent(1005)!!
        assertEquals("java.lang.RuntimeException",result.errorName);assertEquals("Mod startup failed",result.description)
        assertEquals(listOf("java.lang.IllegalStateException: Registry missing","java.lang.NoClassDefFoundError: sample/Dependency"),result.causes)
        assertFalse(result.fields().joinToString().contains("Mixin"));assertNull(b.recent(50000))
        assertTrue(b.offer("[12:30:01] [Server Watchdog/FATAL]: native timeout",false,50000))
        assertEquals("native timeout",b.snapshot()?.description)
        b.offer("[12:30:01] [Server thread/ERROR]: Another failure",true,50001)
        assertEquals("Another failure",b.snapshot()?.description);assertTrue(b.snapshot()!!.causes.isEmpty())
    }
    @Test fun `crash report description and first exception survive section labels and thread dumps`() {
        val report="""---- Minecraft Crash Report ----
Description: Watching Server
java.lang.Error: A server tick took too long
    at net.minecraft.Server.tick(Server.java:1) {pl:mixin:lots}
Caused by: java.lang.IllegalStateException: Stalled
Stacktrace:
java.lang.Thread.State: RUNNABLE
-- System Details --
java.lang.RuntimeException: unrelated mod diagnostics
""".toByteArray()
        val r=DiscordErrorBuffer.fromReport(report)!!
        assertEquals("java.lang.Error",r.errorName);assertEquals("Watching Server · A server tick took too long",r.description)
        assertEquals(listOf("java.lang.IllegalStateException: Stalled"),r.causes)
        assertNull(DiscordErrorBuffer.fromReport("plain text without exception".toByteArray()))
    }
    @Test fun `deep cause after a large mixin stack is retained without saving parsed output`() {
        val report="Description: Server tick\njava.lang.RuntimeException: crash\n"+
            "    at TRANSFORMER/mod.Example.tick(File.java:1) {pl:mixin:lots}\n".repeat(10000)+
            "Caused by: java.lang.IllegalStateException: actual deepest cause\n"
        val summary=DiscordErrorBuffer.fromReport(report.toByteArray())!!
        assertEquals("java.lang.RuntimeException",summary.errorName);assertContains(summary.causes.single(),"actual deepest cause")
        assertFalse(summary.fields().joinToString().contains("pl:mixin"))
    }
    @Test fun `fields are bounded secrets redacted and deepest cause retained`() {
        val b=DiscordErrorBuffer()
        b.offer("Exception in thread \"main\" java.lang.RuntimeException: token=private password:secret https://user:pass@example.com/path `@everyone`"+"x".repeat(8000),true)
        repeat(20){b.offer("Caused by: com.example.CustomFault: cause $it",true)}
        assertFalse(b.offer("Caused by: "+"A.".repeat(3000)+"BrokenError: oversized class"))
        val r=b.snapshot()!!;assertEquals(3,r.causes.size);assertContains(r.causes.last(),"19")
        assertEquals("java.lang.RuntimeException",r.errorName)
        assertFalse(r.description.contains("private"));assertFalse(r.description.contains("password:secret"));assertFalse(r.description.contains("user:pass"))
        assertTrue(r.fields().all {it.second.length<=1024});assertFalse(r.fields().joinToString().contains("at TRANSFORMER"))
    }
    @Test fun `nonfatal errors increment panel count without a queued notification and exit keeps immutable summary`() {
        val manager=ServerManager();val i=manager.create(CreateRequest("fatal summary","21.1.219",ServerSocket(0).use {it.localPort},512))["config"] as Instance
        manager.attachRemoteController(object:RemoteController {
            override fun start(instance:Instance){}
            override fun stop(instance:Instance){}
            override fun restart(instance:Instance){}
            override fun command(instance:Instance,value:String){}
        });manager.assignRunner(i.id,"runner")
        val guild="123456789012345678";val channel="223456789012345678"
        DiscordSettingsStore(temp.resolve("security/discord")).save(DiscordSave(DiscordConfig(true,alerts=listOf(DiscordAlertRoute(guild,channel,i.id))),0,"fake_summary_test_token_12345678901234567890")){true}
        val botManager=mock(ServerManager::class.java)
        `when`(botManager.root).thenReturn(temp)
        doAnswer {call->@Suppress("UNCHECKED_CAST") val listener=call.arguments[0] as (ServerSignal)->Unit;manager.onSignal(listener)}.`when`(botManager).onSignal(any<(ServerSignal)->Unit>() ?: {})
        val bot=DiscordBot(botManager,AuditLog(temp.resolve("audit")),DesktopLifecycle(botManager,mock(org.springframework.context.ConfigurableApplicationContext::class.java)))
        val signals=mutableListOf<ServerSignal>();val sub=manager.onSignal {signals.add(it)}
        try {
            manager.start(i.id);manager.remoteState(i.id,"runner","RUNNING")
            manager.remoteLog(i.id,"runner","error","[12:00:00] [Server thread/ERROR] [noisy/LOGGER]: retry failed")
            manager.remoteLog(i.id,"runner","error","java.lang.RuntimeException: fatal test")
            manager.remoteLog(i.id,"runner","output","Caused by: java.lang.LinkageError: missing library")
            assertEquals("RUNNING",manager.view(i.id)["state"])
            val module=DiscordBot::class.java.getDeclaredField("minecraft").also {it.isAccessible=true}.get(bot)
            val q=DiscordMinecraftModule::class.java.getDeclaredField("alerts").also {it.isAccessible=true}.get(module) as DiscordAlerts
            assertEquals(listOf("start"),q.drain().map {it.first.kind})
            val times=DiscordMinecraftModule::class.java.getDeclaredField("errorTimes").also {it.isAccessible=true}.get(module) as Map<*,*>
            assertEquals(3,(times[i.id] as ArrayDeque<*>).size)
            manager.remoteState(i.id,"runner","SYNCING","종료 코드 1 · 변경 파일 인계 중")
            manager.remoteCommitted(i.id,"runner")
            val stop=signals.single {it.kind=="stop"}
            assertEquals("java.lang.RuntimeException",stop.error?.errorName);assertContains(stop.error!!.causes.single(),"LinkageError")
            manager.runtime(i.id).discordError.clear();assertEquals("fatal test",stop.error?.description)
            assertEquals(listOf("stop"),q.drain().map {it.first.kind})
            val route=DiscordAlertRoute(guild,channel,i.id,errors=true,stops=false)
            assertTrue(route.accepts(stop));assertFalse(route.copy(errors=false).accepts(stop))
        } finally {sub.close();bot.close();manager.shutdown();manager.root.resolve(i.id).toFile().deleteRecursively()}
    }
}
