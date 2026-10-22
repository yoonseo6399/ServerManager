package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class PiIntentOutputTest {
    @TempDir lateinit var temp:Path
    private val json=jacksonObjectMapper()
    private fun repo(home:Path,name:String="repo"):Path {
        val p=home.resolve(name).createDirectories();p.resolve(".git/hooks").createDirectories()
        p.resolve(".git/config").writeText("[core]\nrepositoryformatversion = 0\nbare = false\n[remote \"origin\"]\nurl = https://example.com/test.git\nfetch = +refs/heads/*:refs/remotes/origin/*\n")
        return p
    }
    @Test fun `finite read loops and pipes are inside while scripts and escapes remain unknown`() {
        val home=temp.resolve("p").createDirectories().toRealPath();repo(home,"Luonotar");repo(home,"PyProject")
        val command="""cd "$home" && for d in Luonotar PyProject; do echo "== ${'$'}d =="; git -C "${'$'}d" log -1 --format='%h %ad %s' --date=iso 2>/dev/null; git -C "${'$'}d" status -sb 2>/dev/null | head -5; done"""
        val result=assertNotNull(PiBashAudit.inspect(home,command));assertEquals("INSIDE",result.scope);assertTrue(result.readOnly)
        val fetch=assertNotNull(PiBashAudit.inspect(home,"cd Luonotar && git fetch --dry-run 2>&1 | head -5; echo '---'; git log -3 --format='%h %ad %s' --date=iso"))
        assertTrue(fetch.requiresIntent)
        home.resolve("PyProject/.git").toFile().deleteRecursively();assertEquals("INSIDE",PiBashAudit.inspect(home,command)?.scope)
        for(c in listOf("for d in \$(pwd); do cat ${'$'}d; done","for d in Luonotar; do eval ${'$'}d; done","git -C Luonotar push origin main","cat x > /tmp/other","python3 -c 'print(1)'"))assertNull(PiBashAudit.inspect(home,c),c)
        assertNull(PiBashAudit.inspect(home,"""for d in Luonotar; do echo "; done"; rm /etc/hosts; done"""))
        home.resolve("Luonotar/.git/hooks/post-merge").writeText("#!/bin/sh\necho hook\n")
        assertNull(PiBashAudit.inspect(home,"cd Luonotar && git pull --ff-only"))
    }
    @Test fun `medium prior consent applies only to verified inside operations and automatic mode`() {
        val home=temp.resolve("p").createDirectories().toRealPath();repo(home);val profile=temp.resolve("profile").createDirectories()
        val policy=PiToolPolicy(profile,home,ProjectAgentTools(profile.resolve("logs")),AgentAccess.OWNER)
        val yes=ProjectAgentRisk("ASK","MEDIUM","사용자가 저장소 최신화를 요청함","INSIDE",true)
        val checked=policy.check("bash",json.valueToTree(mapOf("command" to "cd repo && git pull --ff-only")))
        val effective=PiApprovalDecision.effective(checked,yes)
        assertEquals("ALLOW",effective.action);assertFalse(PiApprovalDecision.human(false,checked.display,effective,false));assertTrue(PiApprovalDecision.human(true,checked.display,effective,false))
        val no=PiApprovalDecision.effective(checked,yes.copy(userAuthorized=false));assertTrue(PiApprovalDecision.human(false,checked.display,no,false))
        for(command in listOf("git -C repo push origin main","gh pr create","python3 helper.py")) {
            val unknown=policy.check("bash",json.valueToTree(mapOf("command" to command)));assertTrue(PiApprovalDecision.human(false,unknown.display,PiApprovalDecision.effective(unknown,yes),false))
        }
        assertEquals("DENY",PiApprovalDecision.effective(checked,yes.copy(action="DENY",risk="HIGH")).action)
        home.resolve("repo/.git/config").appendText("[core]\nhooksPath = /tmp/hooks\n");assertFails {policy.verify(checked)}
    }
    @Test fun `only unfinished authenticated human intent survives resume and never unrelated work`() {
        val store=PiHumanIntent(temp);val c=ProjectAgentChannel("g","c","u",null,temp.toString())
        store.begin(c,2,"repo를 원격 최신화해줘");store.begin(c,2,"재개해")
        assertEquals(listOf("repo를 원격 최신화해줘","재개해"),PiHumanIntent(temp).requests(c,2))
        assertTrue(store.requests(c.copy(user="other"),2).isEmpty());assertTrue(store.requests(c.copy(contextEpoch=1),2).isEmpty());assertTrue(store.requests(c,3).isEmpty())
        store.begin(c,2,"파일 목록만 조회해");assertEquals(listOf("파일 목록만 조회해"),store.requests(c,2))
        store.finish(c.channel);store.begin(c,2,"계속해");assertEquals(listOf("계속해"),store.requests(c,2))
        store.clear(c.channel);assertTrue(store.requests(c,2).isEmpty())
    }
    @Test fun `output setting persists independently and precision tools are excluded`() {
        val store=ProjectAgentStore(temp);store.settings("g","u",null,null,null,512)
        assertEquals(512,ProjectAgentStore(temp).preferences("g","u").toolOutputLimit)
        assertFalse(temp.resolve("project-agent.json").readText().contains("toolOutputLimit"))
        assertEquals(2400,store.preferences("g","other").toolOutputLimit)
        assertFails {store.settings("g","u",null,null,null,12)}
        for(name in listOf("read","edit","write","read_result","read_attachment","read_chat_history","ask_user"))assertFalse(PiToolOutput.limited(name))
        for(name in listOf("bash","list_files","search","list_forum_posts","make_new_project"))assertTrue(PiToolOutput.limited(name))
        assertTrue(PiToolOutput.preview("x".repeat(9000),"out-0123456789abcdef",512).length<=512);assertTrue(PiToolOutput.live("x".repeat(9000),512).length<=512)
        val schema=json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(ProjectAgentFormats.forMessages(listOf(ProjectAgentMessage("system","Independent request-aware command-risk reviewer"))))
        assertEquals("boolean",schema.path("properties").path("userAuthorized").path("type").asText())
        val setting=DiscordProjectAgent.command().subcommands.single {it.name=="setting"}.options.single {it.name=="tool_output_limit"};assertEquals(512L,setting.minValue);assertEquals(8000L,setting.maxValue)
    }
}
