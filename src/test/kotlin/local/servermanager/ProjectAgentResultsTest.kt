package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentResultsTest {
    @TempDir lateinit var temp:Path
    private fun text(lines:Int)=(1..lines).joinToString("\n") {"line-$it ${"x".repeat(40)}"}

    @Test fun `saved output is read back verbatim by line range with a continuation hint`() {
        val results=ProjectAgentResults(temp)
        val id=results.save("chan1",text(300))
        assertTrue(ProjectAgentResults.ID.matches(id))
        val page=results.read("chan1",id,1,20)
        assertTrue(page.contains("1: line-1 ")) ;assertTrue(page.contains("start_line:"))
        val tail=results.read("chan1",id,290,50)
        assertTrue(tail.contains("300: line-300")) ;assertTrue(tail.contains("끝까지 읽었습니다"))
    }

    @Test fun `a page never exceeds the context budget`() {
        val results=ProjectAgentResults(temp);val id=results.save("c",text(400))
        assertTrue(results.read("c",id,1,400).length<ProjectAgentResults.PAGE_CHARS+300)
    }

    @Test fun `ids are validated and results are separated by scope`() {
        val results=ProjectAgentResults(temp);val id=results.save("a",text(5))
        assertFailsWith<IllegalArgumentException> {results.read("a","../../etc/passwd",1,5)}
        assertFailsWith<IllegalArgumentException> {results.read("a","out-zz",1,5)}
        assertFailsWith<IllegalArgumentException> {results.read("b",id,1,5)}
        assertFailsWith<IllegalArgumentException> {results.read("a",id,0,5)}
        assertFailsWith<IllegalArgumentException> {results.read("a",id,99,5)}
        assertFailsWith<IllegalArgumentException> {results.save("../..","t")}
    }

    @Test fun `old and excess results are pruned`() {
        var clock=1_000_000_000L
        val results=ProjectAgentResults(temp){clock}
        val first=results.save("p","one")
        (temp.resolve("results/p/$first.txt")).setLastModifiedTime(java.nio.file.attribute.FileTime.fromMillis(clock-ProjectAgentResults.TTL_MILLIS-1))
        repeat(ProjectAgentResults.MAX_FILES+3) {results.save("p","n$it")}
        assertFalse(temp.resolve("results/p/$first.txt").exists())
        assertTrue(temp.resolve("results/p").listDirectoryEntries().size<=ProjectAgentResults.MAX_FILES)
    }

    @Test fun `long command output is stored not summarized and read_result pages it`() {
        val home=temp.resolve("home").createDirectories().toRealPath()
        val tools=ProjectAgentTools(temp.resolve("logs"),null,"chan9")
        val mapper=jacksonObjectMapper()
        val script=if(System.getProperty("os.name").startsWith("Windows"))"for /l %i in (1,1,400) do @echo row-%i-padding-padding-padding" else "i=1; while [ \$i -le 400 ]; do echo row-\$i-padding-padding-padding; i=\$((i+1)); done"
        val call=ProjectAgentTool("run_command",mapper.createObjectNode().put("command",script))
        val plan=tools.plan(home,call)
        val result=tools.execute(home,plan,{},{})
        assertNotNull(result.stored)
        assertTrue(result.text.contains("read_result"));assertTrue(result.text.length<2000)
        val read=tools.execute(home,tools.plan(home,ProjectAgentTool("read_result",mapper.createObjectNode().put("id",result.stored).put("start_line",5).put("lines",3))),{},{})
        assertTrue(read.text.contains("row-"))
    }

    @Test fun `read_result is a sandboxed read-only tool for every tier`() {
        val home=temp.resolve("h").createDirectories().toRealPath()
        val mapper=jacksonObjectMapper()
        for(tier in listOf(AgentAccess.OWNER,AgentAccess.EDIT,AgentAccess.READ)) {
            val tools=ProjectAgentTools(temp.resolve("l"),null,"s").also {it.tier=tier}
            val plan=tools.plan(home,ProjectAgentTool("read_result",mapper.createObjectNode().put("id","out-0123456789abcdef")))
            assertTrue(plan.readOnly);assertTrue(plan.sandboxed)
        }
        assertFailsWith<IllegalArgumentException> {ProjectAgentTools(temp.resolve("l")).plan(home,ProjectAgentTool("read_result",mapper.createObjectNode().put("id","../x")))}
    }
}
