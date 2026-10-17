package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentSearchTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private lateinit var home:Path
    private fun project():ProjectAgentShell {
        home=temp.resolve("project").createDirectories().toRealPath()
        home.resolve("src").createDirectories()
        home.resolve("src/A.kt").writeText("fun alpha() {}\n// TODO first\nfun beta() {}\n// TODO second\n")
        home.resolve("src/B.java").writeText("class B {}\n// todo lower\n")
        home.resolve("README.md").writeText("TODO readme\n")
        home.resolve(".env").writeText("TODO secret-token\n")
        home.resolve("build").createDirectories().resolve("gen.kt").writeText("// TODO generated\n")
        return ProjectAgentShell(home)
    }

    @Test fun `rg is recursive and numbered by default and skips build output and secret files`() {
        val r=project().run("rg TODO")
        assertEquals(0,r.status)
        assertTrue(r.output.contains("src/A.kt:2:// TODO first") && r.output.contains("README.md:1:TODO readme"),r.output)
        assertFalse(r.output.contains("gen.kt") || r.output.contains("secret-token") || r.output.contains("B.java"))
    }

    @Test fun `rg supports glob type ignore case context and max count`() {
        val shell=project()
        assertEquals(listOf("src/A.kt"),shell.run("rg -l TODO -g '*.kt'").output.lines().filter {it.isNotBlank()})
        assertTrue(shell.run("rg -t java -i todo").output.contains("B.java:2:// todo lower"))
        assertTrue(shell.run("rg -C 1 beta src").output.let {it.contains("fun beta") && it.contains("TODO")})
        assertEquals(1,shell.run("rg -m 1 TODO src/A.kt").output.lines().count {it.contains("TODO")})
        assertTrue(shell.run("rg -e alpha -e beta src").output.let {it.contains("alpha") && it.contains("beta")})
    }

    @Test fun `rg works as a pipeline filter and rg files lists project files with an optional glob`() {
        val shell=project()
        assertTrue(shell.run("cat src/A.kt | rg beta").output.contains("fun beta"))
        val files=shell.run("rg --files -g '*.kt'").output
        assertTrue(files.contains("A.kt") && !files.contains("gen.kt") && !files.contains("B.java"),files)
    }

    @Test fun `unsupported rg switches are refused instead of silently ignored`() {
        val shell=project()
        for(bad in listOf("rg --hidden TODO","rg --no-ignore TODO","rg -g '!*.kt' TODO","rg -t cobol TODO","rg --pre cat TODO","rg","rg TODO ../outside")) {
            val r=shell.run(bad);assertEquals(2,r.status,bad);assertTrue(r.output.isNotBlank(),bad)
        }
    }

    @Test fun `the search tool is read only sandboxed for every tier and finds matches`() {
        project()
        for(tier in listOf(AgentAccess.OWNER,AgentAccess.EDIT,AgentAccess.READ)) {
            val tools=ProjectAgentTools(temp.resolve("logs")).also {it.tier=tier}
            val plan=tools.plan(home,ProjectAgentTool("search",mapper.createObjectNode().put("pattern","TODO").put("glob","*.kt")))
            assertTrue(plan.readOnly && plan.sandboxed,tier)
            val result=tools.execute(home,plan,active={})
            assertTrue(result.text.contains("A.kt:2") && !result.text.contains("secret"),result.text)
        }
    }

    @Test fun `search treats the pattern as data and validates its arguments`() {
        project();val tools=ProjectAgentTools(temp.resolve("logs"))
        val nasty="foo'; rm -rf / #"
        val result=tools.execute(home,tools.plan(home,ProjectAgentTool("search",mapper.createObjectNode().put("pattern",nasty).put("fixed",true))),active={})
        assertFalse(result.text.contains("A.kt") || result.text.contains("README"),result.text);assertTrue(home.resolve("src/A.kt").exists())
        assertFailsWith<IllegalArgumentException> {tools.plan(home,ProjectAgentTool("search",mapper.createObjectNode().put("pattern","")))}
        assertFailsWith<IllegalArgumentException> {tools.plan(home,ProjectAgentTool("search",mapper.createObjectNode().put("pattern","x".repeat(501))))}
        val files=tools.execute(home,tools.plan(home,ProjectAgentTool("search",mapper.createObjectNode().put("files",true).put("pattern","-").put("glob","*.md"))),active={})
        assertTrue(files.text.contains("README.md"))
        val friend=ProjectAgentTools(temp.resolve("logs")).also {it.tier=AgentAccess.READ}
        assertFailsWith<IllegalArgumentException> {friend.plan(home,ProjectAgentTool("search",mapper.createObjectNode().put("pattern","x").put("path","../outside")))}
    }
}
