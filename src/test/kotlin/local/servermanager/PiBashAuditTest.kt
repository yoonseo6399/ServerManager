package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class PiBashAuditTest {
    @TempDir lateinit var temp:Path
    private val json=jacksonObjectMapper()
    @Test fun `literal compound commands resolve every target and detect path changes`() {
        val root=temp.resolve("p").createDirectories();root.resolve("a.txt").writeText("original")
        val audit=PiBashAudit.inspect(root,"pwd && ls -la && cat 'a.txt'")!!
        assertEquals("INSIDE",audit.scope);assertTrue(audit.readOnly);assertTrue(audit.targets.contains(root.resolve("a.txt").toRealPath().toString()))
        root.resolve("a.txt").writeText("concurrent change");assertNotEquals(audit,PiBashAudit.inspect(root,"pwd && ls -la && cat 'a.txt'"))
        val outside=temp.resolve("outside.txt").also {it.writeText("x")};assertEquals("OUTSIDE",PiBashAudit.inspect(root,"cat '$outside'")!!.scope)
    }
    @Test fun `unproven shell effects never acquire inside status`() {
        val root=temp.resolve("p").createDirectories();root.resolve("a.txt").writeText("x")
        for(command in listOf("git push origin main","gh pr create --title ok","./gradlew build","rm -rf .","bash -c 'echo hi'","cat \$(echo a.txt)","cat a.txt > b.txt","cat a*","find . -exec rm {} +","python3 -c 'print(1)'")) {
            assertNull(PiBashAudit.inspect(root,command),command)
        }
        val outside=temp.resolve("outside.txt").also {it.writeText("x")};root.resolve("link.txt").createSymbolicLinkPointingTo(outside)
        assertNotEquals("INSIDE",PiBashAudit.inspect(root,"cat link.txt")?.scope)
    }
    @Test fun `low reviewer cannot waive git or outside approval and ordinary outside files remain approvable`() {
        val root=temp.resolve("p").createDirectories();val profile=temp.resolve("profile").createDirectories();val p=PiToolPolicy(profile,root,ProjectAgentTools(profile.resolve("logs")),AgentAccess.OWNER)
        val low=ProjectAgentRisk("ALLOW","LOW","model says safe","INSIDE")
        for(cmd in listOf("git push origin main","gh pr create --title ok")) {
            val checked=p.check("bash",json.valueToTree(mapOf("command" to cmd)));val effective=PiApprovalDecision.effective(checked,low)
            assertEquals("ASK",effective.action);assertTrue(PiApprovalDecision.human(false,checked.display,effective,false))
        }
        val outside=temp.resolve("outside.txt").also {it.writeText("x")};val checked=p.check("read",json.valueToTree(mapOf("path" to outside.toString())))
        val effective=PiApprovalDecision.effective(checked,ProjectAgentRisk("DENY","HIGH","outside path","OUTSIDE"))
        assertEquals("ASK",effective.action);assertTrue(PiApprovalDecision.human(false,checked.display,effective,false))
    }
}
