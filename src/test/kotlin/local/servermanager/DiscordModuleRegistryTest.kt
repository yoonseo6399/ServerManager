package local.servermanager

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class DiscordModuleRegistryTest {
    @TempDir lateinit var temp:Path
    private val closed=mutableListOf<String>()
    private val guild="100000001";private val role="200000001"

    private inner class Fake(override val id:String,override val commands:Set<String> = setOf(id),override val componentPrefixes:Set<String> = emptySet(),
        override val permissions:List<PermissionDecl> = emptyList(),private val gated:String? = null,private val failClose:Boolean = false) : DiscordModule {
        override val label="fake $id"
        override fun commandPermission(command:String)=gated
        override fun register(jda:JDA,guildId:String) {}
        override fun onCommand(event:SlashCommandInteractionEvent) {}
        override fun close() {closed.add(id);if(failClose)error("boom")}
    }
    private fun registry(superAdmin:Boolean=false,withService:Boolean=true)=
        DiscordModuleRegistry(if(withService)PermissionService(temp) else null) {_,_->superAdmin}

    @Test fun `commands and component prefixes route to their module`() {
        val r=registry().add(Fake("music",setOf("music"),setOf("music:"))).add(Fake("ai",setOf("cloud-agent","local-llm"),setOf("as:")))
        assertEquals("music",r.forCommand("music")?.id);assertEquals("ai",r.forCommand("local-llm")?.id);assertNull(r.forCommand("unknown"))
        assertEquals("music",r.forComponent("music:resume")?.id);assertEquals("ai",r.forComponent("as:allow:1")?.id);assertNull(r.forComponent("sm:1:start"))
    }

    @Test fun `registration rejects duplicate ids claimed commands overlapping prefixes and foreign permissions`() {
        val r=registry().add(Fake("music",setOf("music"),setOf("music:")))
        assertFailsWith<IllegalArgumentException> {r.add(Fake("music",setOf("other")))}
        assertFailsWith<IllegalArgumentException> {r.add(Fake("rival",setOf("music")))}
        assertFailsWith<IllegalArgumentException> {r.add(Fake("rival2",setOf("rival2"),setOf("music:x:")))}
        assertFailsWith<IllegalArgumentException> {r.add(Fake("rival3",setOf("rival3"),setOf("mus")))}
        assertFailsWith<IllegalArgumentException> {r.add(Fake("thief",setOf("thief"),permissions=listOf(PermissionDecl("music.admin","x",PermissionRisk.LOW,"music"))))}
        assertFailsWith<IllegalArgumentException> {r.add(Fake("Bad Id"))}
        assertFailsWith<IllegalArgumentException> {r.add(Fake("empty",emptySet()))}
        assertEquals(listOf("music"),r.all.map {it.id},"failed registrations leave no partial state")
        assertNull(r.forCommand("rival"))
    }

    @Test fun `the bot reserves the mc command and the sm prefix`() {
        val r=registry()
        assertFailsWith<IllegalArgumentException> {r.add(Fake("minecraft",setOf("mc")))}
        assertFailsWith<IllegalArgumentException> {r.add(Fake("panel",setOf("panel"),setOf("sm:")))}
        assertFailsWith<IllegalArgumentException> {r.add(Fake("panel2",setOf("panel2"),setOf("s")))}
        assertTrue(r.all.isEmpty())
    }

    @Test fun `permissions declared by a module are registered with the service`() {
        val service=PermissionService(temp)
        DiscordModuleRegistry(service) {_,_->false}.add(Fake("music",permissions=listOf(PermissionDecl("music.use","음악",PermissionRisk.LOW,"music"))))
        assertEquals(listOf("music.use"),service.declarations().map {it.id})
    }

    @Test fun `denial blocks only explicit deny ceiling and default deny and otherwise lets the module run its own checks`() {
        val service=PermissionService(temp)
        val r=DiscordModuleRegistry(service) {_,_->false}.add(Fake("music",permissions=listOf(
            PermissionDecl("music.use","음악",PermissionRisk.LOW,"music"),PermissionDecl("music.admin","관리",PermissionRisk.MEDIUM,"music",PermissionService.DENY))))
        assertNull(r.denial(guild,setOf(role),"music.use"),"unconfigured legacy permission passes through to the module")
        assertNull(r.denial(guild,setOf(role),null),"module without a permission is not gated")
        assertNotNull(r.denial(guild,setOf(role),"music.admin"),"default deny")
        service.grant(guild,"a",true,role,"music.admin");assertNull(r.denial(guild,setOf(role),"music.admin"))
        service.grant(guild,"a",true,role,"music.use","DENY");assertTrue(r.denial(guild,setOf(role),"music.use")!!.contains("music.use"))
        assertNotNull(r.denial(null,setOf(role),"music.use"),"guild-only")
    }

    @Test fun `super admins pass gates but never the ceiling`() {
        val service=PermissionService(temp)
        val r=DiscordModuleRegistry(service) {_,_->true}.add(Fake("agent",permissions=listOf(
            PermissionDecl("agent.use","에이전트",PermissionRisk.MEDIUM,"agent",PermissionService.DENY),PermissionDecl("agent.exec","실행",PermissionRisk.HIGH,"agent"))))
        assertNull(r.denial(guild,emptySet(),"agent.use"))
        assertNotNull(r.denial(guild,emptySet(),"agent.exec"))
    }

    @Test fun `without a permission service nothing is gated`() {
        assertNull(registry(withService=false).denial(guild,setOf(role),"music.use"))
    }

    @Test fun `close closes every module in reverse order even when one fails`() {
        val r=registry().add(Fake("a")).add(Fake("b",failClose=true)).add(Fake("c"))
        r.close()
        assertEquals(listOf("c","b","a"),closed)
    }
}
