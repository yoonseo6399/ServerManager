package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class DiscordPermissionModuleTest {
    @TempDir lateinit var temp:Path
    private val guild="100000001";private val role="200000001"
    private fun service()=PermissionService(temp).also {it.declare(listOf(
        PermissionDecl("music.use","음악",PermissionRisk.LOW,"music"),PermissionDecl("agent.exec","명령 실행",PermissionRisk.HIGH,"agent",PermissionService.DENY)))}
    private fun module(s:PermissionService,admin:Boolean)=DiscordPermissionModule(s,{_,_->admin})
    private fun run(m:DiscordPermissionModule,sub:String,admin:Boolean)=m.run(sub,guild,"42",setOf(role),admin) {null}

    @Test fun `only super admins may use the permission command`() {
        val s=service();val m=module(s,false)
        for(sub in listOf("list","rules","audit","reset"))assertFailsWith<IllegalStateException>(sub) {run(m,sub,false)}
    }

    @Test fun `list shows risk default deny the local ceiling and the callers status`() {
        val s=service();val text=run(module(s,true),"list",true)
        assertTrue(text.contains("`music.use`") && text.contains("LOW") && text.contains("`agent.exec`") && text.contains("HIGH") && text.contains("기본 거부"),text)
        assertTrue(text.contains("위험도 상한: MEDIUM") && text.contains("로컬 설정"),text)
    }

    @Test fun `rules audit and reset reflect service state`() {
        val s=service();val m=module(s,true)
        assertTrue(run(m,"rules",true).contains("규칙이 없습니다"))
        s.grant(guild,"discord:42",true,role,"music.use")
        assertTrue(run(m,"rules",true).contains("✅ 허용 <@&$role> `music.use`"))
        assertTrue(run(m,"audit",true).contains("grant"))
        assertTrue(run(m,"reset",true).contains("1개"))
        assertTrue(run(m,"rules",true).contains("규칙이 없습니다"))
    }

    @Test fun `the module declares no commands besides permission and no permissions of its own`() {
        val m=module(service(),true)
        assertEquals(setOf("permission"),m.commands);assertTrue(m.permissions.isEmpty());assertNull(m.commandPermission("permission"))
    }
}
