package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class DiscordTest {
    @TempDir lateinit var temp:Path
    private val guild="123456789012345678"
    private val role="223456789012345678"
    private val token="fake_discord_token_for_local_tests_only_1234567890"
    private fun config()=DiscordConfig(true,listOf(DiscordGrant(guild,role,"a",true),DiscordGrant(guild,"323456789012345678","b",false)))
    @Test fun `roles are scoped to guild server and action and default deny`() {
        val c=config()
        assertTrue(DiscordAccess.allowed(c,guild,setOf(role),"a",true))
        assertFalse(DiscordAccess.allowed(c,"923456789012345678",setOf(role),"a",true))
        assertFalse(DiscordAccess.allowed(c,guild,setOf(role),"b",false))
        assertFalse(DiscordAccess.allowed(c,null,setOf(role),"a"))
        assertFalse(DiscordAccess.allowed(c,guild,emptySet(),"a"))
        assertTrue(DiscordAccess.allowed(c,guild,setOf("323456789012345678"),"b"))
        assertFalse(DiscordAccess.allowed(c,guild,setOf("323456789012345678"),"b",true))
        assertFalse(DiscordAccess.allowed(c.copy(enabled=false),guild,setOf(role),"a",true))
        assertFalse(DiscordAccess.allowed(c,guild,setOf(role),"a","stop"))
        assertFalse(DiscordAccess.allowed(c,guild,setOf(role),"a","restart"))
        val elevated=c.copy(superAdmins=listOf(DiscordSuperAdmin(guild,"423456789012345678")))
        assertTrue(DiscordAccess.allowed(elevated,guild,setOf("423456789012345678"),"any-server","restart"))
        assertFalse(DiscordAccess.allowed(elevated,"923456789012345678",setOf("423456789012345678"),"a","start"))
    }
    @Test fun `panel IDs persist without log text and are scoped by route`() {
        val route=DiscordAlertRoute(guild,"523456789012345678","a",panel=true)
        val key=DiscordPanelStore.key(route)
        val store=DiscordPanelStore(temp.resolve("panels"))
        store.change(key) { it.copy(panelId="123",errorIds=listOf("456")) }
        assertEquals("123",DiscordPanelStore(temp.resolve("panels")).get(key).panelId)
        assertTrue(DiscordPanelStore(temp.resolve("panels")).get(DiscordPanelStore.key(route.copy(instanceId="b"))).errorIds.isEmpty())
        assertFalse(temp.resolve("panels/panel-messages.json").readText().contains("error detected"))
    }
    @Test fun `token encrypted private persisted masked and removable with optimistic revision`() {
        val store=DiscordSettingsStore(temp)
        store.save(DiscordSave(config(),0,token)) { true }
        assertFalse(temp.resolve("settings.json").readText().contains(token))
        assertFalse(jacksonObjectMapper().writeValueAsString(store.view()).contains(token))
        assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(temp.resolve("settings.json"))))
        assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(temp.resolve("token.key"))))
        assertEquals(token,DiscordSettingsStore(temp).token())
        assertFailsWith<IllegalStateException> { store.save(DiscordSave(config(),0,token)) { true } }
        store.save(DiscordSave(config(),1,token="")) { true };assertEquals(token,store.token())
        store.save(DiscordSave(DiscordConfig(),2,clearToken=true)) { true }
        assertNull(DiscordSettingsStore(temp).token());assertFalse(store.view().tokenConfigured)
    }
    @Test fun `bad or missing token invalid grants and tampered ciphertext fail closed`() {
        val store=DiscordSettingsStore(temp)
        assertFailsWith<IllegalArgumentException> { store.save(DiscordSave(config(),0)) { true } }
        assertFailsWith<IllegalArgumentException> { store.save(DiscordSave(config(),0,"secret with spaces")) { true } }
        assertFailsWith<IllegalArgumentException> { store.save(DiscordSave(config(),0,token)) { false } }
        assertFailsWith<IllegalArgumentException> { store.save(DiscordSave(config().copy(grants=listOf(DiscordGrant(guild,guild,"a"))),0,token)) { true } }
        store.save(DiscordSave(config(),0,token)) { true }
        temp.resolve("token.key").writeBytes(ByteArray(32))
        val error=assertFailsWith<IllegalStateException> { store.token() }
        assertFalse(error.message!!.contains(token))
    }
    @Test fun `ordinary error bursts never enqueue standalone messages while lifecycle alerts remain`() {
        val a=DiscordAlerts()
        repeat(7000){a.offer(ServerSignal("a","error","ordinary error"))}
        assertTrue(a.drain().isEmpty())
        a.offer(ServerSignal("a","start","ready"));a.offer(ServerSignal("a","stop","unexpected exit",ServerExit(1,false,"UNEXPECTED")))
        assertEquals(listOf("start","stop"),a.drain().map {it.first.kind})
        a.offer(ServerSignal("a","import","not a shutdown"));assertTrue(a.drain().isEmpty())
        a.offer(ServerSignal("a","stop","discard"));a.clear();assertTrue(a.drain().isEmpty())
    }
    @Test fun `commands authorize before control deduplicate and revoke without reconnecting`() {
        DiscordSettingsStore(temp.resolve("security/discord")).save(DiscordSave(config(),0,token)) { true }
        val manager=mock(ServerManager::class.java)
        `when`(manager.root).thenReturn(temp)
        `when`(manager.onSignal(any<(ServerSignal)->Unit>() ?: {})).thenReturn(AutoCloseable {})
        val i=Instance("a","친구 서버",loaderVersion="21.1.252",port=25565,memoryMb=512,javaPath="java")
        `when`(manager.instance("a")).thenReturn(i)
        `when`(manager.view("a")).thenReturn(mapOf("state" to "STOPPED"))
        `when`(manager.list()).thenReturn(listOf(mapOf("config" to i)))
        val desktop=DesktopLifecycle(manager,mock(org.springframework.context.ConfigurableApplicationContext::class.java))
        val bot=DiscordBot(manager,AuditLog(temp.resolve("audit.jsonl")),desktop)
        try {
            assertContains(bot.execute(guild,emptySet(),"u","denied","a","start"),"권한이 없습니다")
            verify(manager,never()).startAs(anyString(),any(StartActor::class.java) ?: StartActor("SYSTEM","fixture"))
            assertTrue(bot.choices(guild,emptySet(),false,"").isEmpty())
            assertEquals("a",bot.choices(guild,setOf(role),true,"친구").single().second)
            assertContains(bot.execute(guild,setOf(role),"u","one","a","start"),"시작을 요청")
            assertContains(bot.execute(guild,setOf(role),"u","one","a","start"),"이미 처리")
            verify(manager,times(1)).startAs(eq("a") ?: "a",any(StartActor::class.java) ?: StartActor("SYSTEM","fixture"))
            assertContains(bot.execute(guild,setOf(role),"u","two","a","start"),"10초")
            bot.saveModules(DiscordModuleSave(0,setOf("minecraft")))
            assertContains(bot.execute(guild,setOf(role),"u","disabled","a","start"),"모듈이 꺼져")
            verify(manager,times(1)).startAs(eq("a") ?: "a",any(StartActor::class.java) ?: StartActor("SYSTEM","fixture"))
            bot.saveModules(DiscordModuleSave(1,emptySet()))
            bot.permissions.grant(guild,"local-test",true,role,"minecraft.control","DENY")
            assertContains(bot.execute(guild,setOf(role),"u","fresh-role-denial","a","start"),"권한이 없습니다")
            bot.permissions.revoke(guild,"local-test",true,role,"minecraft.control")
            desktop.stopping=true
            assertContains(bot.execute(guild,setOf(role),"another","maintenance","a","start"),"업데이트 중")
            verify(manager,times(1)).startAs(eq("a") ?: "a",any(StartActor::class.java) ?: StartActor("SYSTEM","fixture"))
            desktop.stopping=false
            bot.save(DiscordSave(DiscordConfig(),1))
            assertContains(bot.execute(guild,setOf(role),"u","three","a","start"),"권한이 없습니다")
            verify(manager,times(1)).startAs(eq("a") ?: "a",any(StartActor::class.java) ?: StartActor("SYSTEM","fixture"))
        } finally { bot.close() }
    }
    @Test fun `stop and restart use fresh action permissions and never force stop`() {
        val privileged=config().copy(grants=listOf(DiscordGrant(guild,role,"a",canStart=true,canStop=true,canRestart=true)))
        DiscordSettingsStore(temp.resolve("security/discord")).save(DiscordSave(privileged,0,token)) { true }
        val manager=mock(ServerManager::class.java)
        `when`(manager.root).thenReturn(temp)
        `when`(manager.onSignal(any<(ServerSignal)->Unit>() ?: {})).thenReturn(AutoCloseable {})
        `when`(manager.instance("a")).thenReturn(Instance("a","서버",loaderVersion="21.1.252",port=25565,memoryMb=512,javaPath="java"))
        `when`(manager.view("a")).thenReturn(mapOf("state" to "RUNNING"))
        val bot=DiscordBot(manager,AuditLog(temp.resolve("audit.jsonl")),DesktopLifecycle(manager,mock(org.springframework.context.ConfigurableApplicationContext::class.java)))
        try {
            assertContains(bot.execute(guild,emptySet(),"u","deny-stop","a","stop"),"권한이 없습니다")
            assertContains(bot.execute(guild,setOf(role),"u","ok-stop","a","stop"),"정상 종료")
            assertContains(bot.execute(guild,setOf(role),"u","ok-restart","a","restart"),"재시작")
            verify(manager,times(1)).stop("a",false)
            verify(manager,times(1)).restartAs(eq("a") ?: "a",any(StartActor::class.java) ?: StartActor("SYSTEM","fixture"))
            bot.save(DiscordSave(privileged.copy(enabled=false,grants=emptyList()),1))
            assertContains(bot.execute(guild,setOf(role),"u","revoked","a","restart"),"권한이 없습니다")
        } finally { bot.close() }
    }
}
