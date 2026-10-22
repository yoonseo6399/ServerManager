package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class PiExecutableEvidenceTest {
    @TempDir lateinit var temp:Path
    @Test fun `untrusted direct script bytes reach review without backend execution veto`() {
        val home=temp.resolve("project").createDirectories().toRealPath();val profile=temp.resolve("profile").createDirectories();val script=home.resolve("helper.py")
        val content="from pathlib import Path\n# NOT EXECUTED\nPath('/etc/hosts').write_text('changed')\n";script.writeText(content)
        val policy=PiToolPolicy(profile,home,ProjectAgentTools(profile.resolve("logs")),AgentAccess.OWNER)
        val checked=policy.check("bash",jacksonObjectMapper().readTree("""{"command":"python3 helper.py"}"""))
        val evidence=checked.executableEvidence!!;assertEquals(content,evidence.files.single().content);assertEquals(64,evidence.files.single().sha256.length)
        assertEquals("UNKNOWN",checked.display.scope);policy.verify(checked)
        script.writeText(content.replace("changed","CHANGED"));policy.verify(checked) // No static content/fingerprint veto after reviewer approval.
        assertEquals("UNKNOWN",checked.display.scope,"reading an entrypoint cannot prove all execution effects")
    }
    @Test fun `outside secret symlink oversized and dynamic entrypoints are never silently marked inspected`() {
        val home=temp.resolve("project").createDirectories().toRealPath();val profile=temp.resolve("profile").createDirectories();val outside=temp.resolve("other.py").also {it.writeText("secret")}
        for(command in listOf("python3 ${outside}","python3 .env","python3 -m module","bash -c 'python3 helper.py'","python3 helper.py | cat")) {
            val result=PiExecutableEvidence.inspect(home,profile,command);assertTrue(result.files.isEmpty());assertTrue(result.unresolved.isNotEmpty())
        }
        home.resolve(".env").writeText("private");assertTrue(PiExecutableEvidence.inspect(home,profile,"python3 .env").files.isEmpty())
        home.resolve("big.py").writeText("x".repeat(70000));assertTrue(PiExecutableEvidence.inspect(home,profile,"python3 big.py").files.isEmpty())
        val link=home.resolve("link.py");java.nio.file.Files.createSymbolicLink(link,outside);assertTrue(PiExecutableEvidence.inspect(home,profile,"python3 link.py").files.isEmpty())
        home.resolve("long.py").writeText("# normal\n".repeat(1000));val partial=PiExecutableEvidence.inspect(home,profile,"python3 long.py");assertTrue(partial.files.single().truncated);assertTrue(partial.unresolved.isNotEmpty())
    }
}
