package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class PermissionServiceTest {
    @TempDir lateinit var temp:Path
    private var now=1_000_000_000L
    private fun service(dir:Path=temp)=PermissionService(dir) {now}.also {
        it.declare(listOf(
            PermissionDecl("music.use","음악",PermissionRisk.LOW,"music"),
            PermissionDecl("music.admin","음악 관리",PermissionRisk.MEDIUM,"music",PermissionService.DENY),
            PermissionDecl("agent.exec","명령 실행",PermissionRisk.HIGH,"agent",PermissionService.DENY)))
    }
    private val guild="100000001";private val role="200000001";private val other="200000002"

    @Test fun `declarations are validated and risk cannot be redefined`() {
        val s=service()
        assertFailsWith<IllegalArgumentException> {s.declare(listOf(PermissionDecl("Bad","x",PermissionRisk.LOW,"bad")))}
        assertFailsWith<IllegalArgumentException> {s.declare(listOf(PermissionDecl("music.x","x",PermissionRisk.LOW,"other")))}
        assertFailsWith<IllegalArgumentException> {s.declare(listOf(PermissionDecl("music.use","음악",PermissionRisk.LOW,"music","MAYBE")))}
        assertFailsWith<IllegalArgumentException> {s.declare(listOf(PermissionDecl("music.use","음악",PermissionRisk.HIGH,"music")))}
        s.declare(listOf(PermissionDecl("music.use","음악",PermissionRisk.LOW,"music")))
        assertEquals(3,s.declarations().size)
    }

    @Test fun `nothing configured means legacy for normal permissions and deny for default deny ones`() {
        val s=service()
        assertNull(s.check(guild,setOf(role),"music.use").allowed)
        assertEquals(false,s.check(guild,setOf(role),"music.admin").allowed)
        assertEquals(false,s.check(guild,setOf(role),"never.declared").allowed)
    }

    @Test fun `an allow rule applies to its role only and an explicit deny always wins`() {
        val s=service()
        s.grant(guild,"discord:1",true,role,"music.admin")
        assertEquals(true,s.check(guild,setOf(role),"music.admin").allowed)
        assertEquals(false,s.check(guild,setOf(other),"music.admin").allowed)
        assertEquals(false,s.check("999999999",setOf(role),"music.admin").allowed,"rules are per guild")
        s.grant(guild,"discord:1",true,other,"music.*","DENY")
        assertEquals(false,s.check(guild,setOf(role,other),"music.admin").allowed,"deny beats allow when a user has both roles")
        assertEquals(false,s.check(guild,setOf(other),"music.use").allowed,"wildcard deny covers every music permission")
    }

    @Test fun `only super admins change rules and the local ceiling cannot be raised from the service API of a guild admin`() {
        val s=service()
        assertFailsWith<IllegalStateException> {s.grant(guild,"discord:1",false,role,"music.use")}
        assertFailsWith<IllegalStateException> {s.revoke(guild,"discord:1",false,role,"music.use")}
        assertFailsWith<IllegalStateException> {s.reset(guild,"discord:1",false)}
        assertEquals(PermissionRisk.MEDIUM,s.ceiling().maxRisk,"default ceiling keeps HIGH permissions unreachable")
    }

    @Test fun `high risk is blocked by the default ceiling even for super admins and for existing rules`() {
        val s=service()
        assertFailsWith<IllegalArgumentException> {s.grant(guild,"discord:1",true,role,"agent.exec","ALLOW",60,true)}
        assertEquals(false,s.check(guild,setOf(role),"agent.exec",superAdmin=true).allowed)
        s.setCeiling(PermissionCeiling(PermissionRisk.HIGH))
        assertFailsWith<IllegalArgumentException> {s.grant(guild,"discord:1",true,role,"agent.exec","ALLOW",60,false)}
        assertFailsWith<IllegalArgumentException> {s.grant(guild,"discord:1",true,role,"agent.exec","ALLOW",null,true)}
        assertFailsWith<IllegalArgumentException> {s.grant(guild,"discord:1",true,role,"agent.exec","ALLOW",PermissionService.HIGH_TTL_MINUTES+1,true)}
        s.grant(guild,"discord:1",true,role,"agent.exec","ALLOW",60,true)
        assertEquals(true,s.check(guild,setOf(role),"agent.exec").allowed)
        s.setCeiling(PermissionCeiling(PermissionRisk.MEDIUM))
        assertEquals(false,s.check(guild,setOf(role),"agent.exec").allowed,"lowering the ceiling disables existing rules at once")
    }

    @Test fun `disabled permissions are refused for everyone including super admins`() {
        val s=service();s.setCeiling(PermissionCeiling(PermissionRisk.HIGH,setOf("music.use")))
        assertEquals(false,s.check(guild,setOf(role),"music.use",superAdmin=true).allowed)
        assertEquals(true,s.check(guild,setOf(role),"music.admin",superAdmin=true).allowed)
    }

    @Test fun `rules expire and expired rules stop applying`() {
        val s=service();s.grant(guild,"discord:1",true,role,"music.admin","ALLOW",10)
        assertEquals(true,s.check(guild,setOf(role),"music.admin").allowed)
        now+=11*60_000
        assertEquals(false,s.check(guild,setOf(role),"music.admin").allowed)
        assertFailsWith<IllegalArgumentException> {s.grant(guild,"discord:1",true,role,"music.admin","ALLOW",0)}
    }

    @Test fun `grant input is validated`() {
        val s=service()
        assertFailsWith<IllegalArgumentException> {s.grant(guild,"discord:1",true,"abc","music.use")}
        assertFailsWith<IllegalArgumentException> {s.grant(guild,"discord:1",true,role,"music.use","MAYBE")}
        assertFailsWith<IllegalArgumentException> {s.grant(guild,"discord:1",true,role,"unknown.thing")}
        assertFailsWith<IllegalArgumentException> {s.grant(guild,"discord:1",true,role,"unknown.*")}
    }

    @Test fun `revoke and reset remove rules and report counts`() {
        val s=service();s.grant(guild,"a",true,role,"music.use");s.grant(guild,"a",true,role,"music.admin");s.grant("999999999","a",true,role,"music.use")
        assertEquals(1,s.revoke(guild,"a",true,role,"music.use"));assertEquals(0,s.revoke(guild,"a",true,role,"music.use"))
        assertEquals(1,s.reset(guild,"a",true));assertEquals(emptyList(),s.grants(guild));assertEquals(1,s.grants("999999999").size)
    }

    @Test fun `a duplicate rule replaces the old one instead of piling up`() {
        val s=service();repeat(3){s.grant(guild,"a",true,role,"music.use")}
        assertEquals(1,s.grants(guild).size)
        s.grant(guild,"a",true,role,"music.use","DENY");assertEquals("DENY",s.grants(guild).single().effect)
    }

    @Test fun `rules the ceiling and the audit survive a restart`() {
        service().apply {grant(guild,"discord:7",true,role,"music.admin");setCeiling(PermissionCeiling(PermissionRisk.HIGH),"local")}
        val again=service()
        assertEquals(true,again.check(guild,setOf(role),"music.admin").allowed)
        assertEquals(PermissionRisk.HIGH,again.ceiling().maxRisk)
        val log=again.audit(guild,10)
        assertTrue(log.any {it.action=="grant" && it.actor=="discord:7"} && log.any {it.action=="ceiling"},log.toString())
    }

    @Test fun `a corrupt policy file does not crash startup and falls back to no rules`() {
        temp.resolve("permission-grants.json").writeText("{not json");temp.resolve("permission-ceiling.json").writeText("???")
        val s=service()
        assertEquals(emptyList(),s.grants(guild));assertEquals(PermissionRisk.MEDIUM,s.ceiling().maxRisk)
    }

    @Test fun `the audit log is bounded`() {
        val s=service()
        repeat(PermissionService.MAX_AUDIT+400) {s.revoke(guild,"a",true,role,"music.use")}
        assertTrue(temp.resolve("permission-audit.jsonl").readLines().size<=PermissionService.MAX_AUDIT+200)
        assertEquals(100,s.audit(guild,1000).size,"read limit is capped")
    }

    @Test fun `the rule count is bounded`() {
        val s=service()
        assertFailsWith<IllegalArgumentException> {repeat(PermissionService.MAX_GRANTS+1){s.grant(guild,"a",true,(300000000L+it).toString(),"music.use")}}
        assertEquals(PermissionService.MAX_GRANTS,s.grants(guild).size)
    }
}
