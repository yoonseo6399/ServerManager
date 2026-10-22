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
    @Test fun `model low verdict stays low for all bash grammar without a backend override`() {
        val home=temp.resolve("p").createDirectories().toRealPath();val profile=temp.resolve("profile").createDirectories()
        val policy=PiToolPolicy(profile,home,ProjectAgentTools(profile.resolve("logs")),AgentAccess.OWNER)
        val low=ProjectAgentRisk("ALLOW","LOW","프로젝트 조회","INSIDE")
        for(command in listOf("git fetch --all --prune 2>&1 | tail -5; echo '---'; git branch -a | grep -i beta","git checkout -b beta21 && git status -sb","for d in A B; do git -C \"${'$'}d\" status; done","python3 helper.py")) {
            val checked=policy.check("bash",json.valueToTree(mapOf("command" to command)))
            val effective=PiApprovalDecision.effective(checked,low)
            assertEquals(low,effective);assertFalse(PiApprovalDecision.human(false,checked.display,effective,false))
            assertTrue(PiApprovalDecision.human(true,checked.display,effective,false))
        }
    }
    @Test fun `medium consent and denial belong to reviewer not backend paths`() {
        val home=temp.resolve("p").createDirectories().toRealPath();val profile=temp.resolve("profile").createDirectories()
        val policy=PiToolPolicy(profile,home,ProjectAgentTools(profile.resolve("logs")),AgentAccess.OWNER)
        val checked=policy.check("bash",json.valueToTree(mapOf("command" to "git fetch --all --prune")))
        val yes=ProjectAgentRisk("ASK","MEDIUM","사전 요청과 일치","INSIDE",true)
        val effective=PiApprovalDecision.effective(checked,yes)
        assertEquals("ALLOW",effective.action);assertEquals("MEDIUM",effective.risk)
        assertFalse(PiApprovalDecision.human(false,checked.display,effective,false))
        assertTrue(PiApprovalDecision.human(true,checked.display,effective,false))
        assertTrue(PiApprovalDecision.human(false,checked.display,yes.copy(userAuthorized=false),true),"Gradle/backend trust cannot bypass the model")
        val outside=policy.check("write",json.valueToTree(mapOf("path" to temp.resolve("outside.txt").toString(),"content" to "x")))
        val authorized=PiApprovalDecision.effective(outside,yes.copy(projectScope="OUTSIDE"))
        assertEquals("ALLOW",authorized.action);assertFalse(PiApprovalDecision.human(false,outside.display,authorized,false))
        val deny=yes.copy(action="DENY",risk="HIGH");assertEquals(deny,PiApprovalDecision.effective(outside,deny))
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
