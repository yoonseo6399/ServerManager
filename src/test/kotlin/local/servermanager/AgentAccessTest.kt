package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class AgentAccessTest {
    @TempDir lateinit var temp:Path
    private val guild="111111111111111111"
    private val friends="222222222222222222"
    private val editors="333333333333333333"
    private fun store()=AgentAccessStore(temp.resolve("security"))

    @Test fun `owner gets every model and the global cap while an unlisted member gets nothing`() {
        val data=AgentAccessData(turnCap=40)
        val owner=AgentAccess.resolve(data,guild,emptySet(),true,"actor")!!
        assertTrue(owner.owner);assertEquals("actor",owner.actor);assertEquals(40,owner.maxTurns);assertTrue(owner.allows("14b") && owner.allows("30b"))
        assertNull(AgentAccess.resolve(data,guild,setOf(friends),false))
    }
    @Test fun `friend roles are unioned but never exceed the cap and roles of another guild do not count`() {
        val s=store();s.setRole(AgentRolePolicy(guild,friends,setOf("14b"),"READ",10));s.setRole(AgentRolePolicy(guild,editors,setOf("30b"),"EDIT",25))
        val both=AgentAccess.resolve(s.view(),guild,setOf(friends,editors),false)!!
        assertEquals(setOf("14b","30b"),both.models);assertEquals(AgentAccess.EDIT,both.tier);assertEquals(25,both.maxTurns);assertFalse(both.owner);assertNull(both.actor)
        val readOnly=AgentAccess.resolve(s.view(),guild,setOf(friends),false)!!
        assertEquals(setOf("14b"),readOnly.models);assertFalse(readOnly.allows("30b"));assertEquals(AgentAccess.READ,readOnly.tier)
        assertNull(AgentAccess.resolve(s.view(),"999999999999999999",setOf(friends),false))
    }
    @Test fun `turn selection is clamped by role and cap and the member setting is remembered`() {
        val s=store();s.setRole(AgentRolePolicy(guild,friends,setOf("14b"),"READ",20))
        val grant=AgentAccess.resolve(s.view(),guild,setOf(friends),false)!!
        assertEquals(AgentAccess.DEFAULT_TURNS.coerceAtMost(20),AgentAccess.turns(s.view(),grant,guild,"u",null))
        assertEquals(20,AgentAccess.turns(s.view(),grant,guild,"u",99));assertEquals(1,AgentAccess.turns(s.view(),grant,guild,"u",0))
        s.setUserTurns(guild,"u",7);assertEquals(7,AgentAccess.turns(s.view(),grant,guild,"u",null));assertEquals(3,AgentAccess.turns(s.view(),grant,guild,"u",3))
        assertFailsWith<IllegalArgumentException> {s.setUserTurns(guild,"u",101)}
    }
    @Test fun `lowering the cap lowers roles and personal settings and survives a restart`() {
        val s=store();s.setRole(AgentRolePolicy(guild,friends,setOf("14b"),"READ",60));s.setUserTurns(guild,"u",50)
        s.setLimits(15,null)
        assertEquals(15,s.view().turnCap);assertEquals(15,s.view().roles.single().maxTurns);assertEquals(15,s.view().userTurns.getValue("$guild:u"));assertEquals(15,s.view().defaultTurns)
        val reloaded=store().view();assertEquals(15,reloaded.turnCap);assertEquals(friends,reloaded.roles.single().roleId)
        assertFailsWith<IllegalArgumentException> {s.setRole(AgentRolePolicy(guild,friends,setOf("14b"),"READ",16))}
    }
    @Test fun `invalid roles models tiers and unknown projects are refused`() {
        val s=store()
        assertFailsWith<IllegalArgumentException> {s.setRole(AgentRolePolicy(guild,guild))}
        assertFailsWith<IllegalArgumentException> {s.setRole(AgentRolePolicy(guild,friends,setOf("70b")))}
        assertFailsWith<IllegalArgumentException> {s.setRole(AgentRolePolicy(guild,friends,tier="OWNER"))}
        assertFailsWith<IllegalArgumentException> {s.setRole(AgentRolePolicy(guild,friends,projects=setOf("missing")))}
        assertTrue(s.view().roles.isEmpty())
    }
    @Test fun `removing a shared project also removes it from every role and a role can be removed`() {
        val s=store();val folder=temp.resolve("shared").createDirectories()
        s.addProject(guild,"demo",folder);s.setRole(AgentRolePolicy(guild,friends,setOf("14b"),"EDIT",10,setOf("demo")))
        assertEquals(setOf("demo"),AgentAccess.resolve(s.view(),guild,setOf(friends),false)!!.projects)
        assertTrue(s.removeProject(guild,"demo"));assertTrue(AgentAccess.resolve(s.view(),guild,setOf(friends),false)!!.projects.isEmpty())
        assertTrue(s.removeRole(guild,friends));assertNull(AgentAccess.resolve(s.view(),guild,setOf(friends),false));assertFalse(s.removeRole(guild,friends))
        assertFailsWith<IllegalArgumentException> {s.addProject(guild,"bad name",folder)};assertFailsWith<IllegalArgumentException> {s.addProject(guild,"ok",temp.resolve("nope"))}
    }
}
