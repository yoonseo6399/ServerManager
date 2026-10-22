package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentHarnessTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private lateinit var home:Path
    private fun call(name:String,args:Map<String,Any>)=ProjectAgentTool(name,mapper.valueToTree(args))
    private fun tools(tier:String)=ProjectAgentTools(temp.resolve("logs")).also {it.tier=tier}
    private fun project():Path {
        home=temp.resolve("project").createDirectories().toRealPath()
        home.resolve("A.kt").writeText("fun one() = 1\nfun two() = 2\nfun three() = 3\n")
        home.resolve(".env").writeText("TOKEN=secret\n")
        temp.resolve("outside.txt").writeText("outside")
        return home
    }

    @Test fun `read tier can only propose read tools and the shell plan is sandboxed`() {
        project();val t=tools(AgentAccess.READ)
        val plan=t.plan(home,call("sh",mapOf("command" to "grep -n two A.kt")))
        assertTrue(plan.readOnly && plan.sandboxed);assertEquals("INSIDE",plan.scope)
        val result=t.execute(home,plan,active={});assertEquals(0,result.exitCode);assertTrue(result.text.contains("2:fun two() = 2"))
        for(name in listOf("edit_file","write_file","delete_path","run_command","build","test","git","make_new_project","save_attachment","upload_file")) {
            assertFailsWith<IllegalArgumentException>(name) {t.plan(home,call(name,mapOf("path" to "A.kt","command" to "ls","name" to "x","content" to "x","find" to "one","replace" to "uno")))}
        }
        assertFalse(AgentAccess.READ.let {ProjectAgentTools.allowed(it)}.contains("edit_file"))
    }
    @Test fun `friend tiers stay inside the project and never read secret files`() {
        project()
        for(tier in listOf(AgentAccess.READ,AgentAccess.EDIT)) {
            val t=tools(tier)
            assertFailsWith<IllegalArgumentException> {t.plan(home,call("read_file",mapOf("path" to "../outside.txt")))}
            assertFailsWith<IllegalArgumentException> {t.plan(home,call("read_file",mapOf("path" to temp.resolve("outside.txt").toString())))}
            assertFailsWith<IllegalArgumentException> {t.plan(home,call("read_file",mapOf("path" to ".env")))}
            assertFailsWith<IllegalArgumentException> {t.plan(home,call("list_files",mapOf("cwd" to "..")))}
            val search=t.plan(home,call("search_text",mapOf("text" to "TOKEN")));assertFalse(t.execute(home,search,active={}).full.contains("secret"))
        }
        assertFailsWith<IllegalArgumentException> {tools(AgentAccess.EDIT).plan(home,call("edit_file",mapOf("path" to "../outside.txt","find" to "outside","replace" to "x")))}
        assertEquals("outside",temp.resolve("outside.txt").readText())
        // The owner keeps the old unrestricted behaviour: outside paths are only classified, then handled by approval.
        assertEquals("OUTSIDE",tools(AgentAccess.OWNER).plan(home,call("read_file",mapOf("path" to "../outside.txt"))).scope)
    }
    @Test fun `edit tier applies an approved unique replacement and shows the exact lines`() {
        project();val t=tools(AgentAccess.EDIT)
        val plan=t.plan(home,call("edit_file",mapOf("path" to "A.kt","find" to "fun two() = 2","replace" to "fun two() = 22")))
        assertFalse(plan.readOnly);assertFalse(plan.sandboxed)
        assertTrue(plan.description.contains("- fun two() = 2") && plan.description.contains("+ fun two() = 22"),plan.description)
        assertEquals("fun one() = 1\nfun two() = 2\nfun three() = 3\n",home.resolve("A.kt").readText(),"planning must not change the file")
        t.execute(home,plan,active={})
        assertEquals("fun one() = 1\nfun two() = 22\nfun three() = 3\n",home.resolve("A.kt").readText())
    }
    @Test fun `a file changed after planning is not edited`() {
        project();val t=tools(AgentAccess.EDIT)
        val plan=t.plan(home,call("edit_file",mapOf("path" to "A.kt","find" to "fun one() = 1","replace" to "fun one() = 11")))
        home.resolve("A.kt").writeText("fun one() = 1\nsomething else entirely\n")
        assertFails {t.execute(home,plan,active={})}
        assertEquals("fun one() = 1\nsomething else entirely\n",home.resolve("A.kt").readText())
    }
    @Test fun `edit modes report precise errors instead of guessing`() {
        val original="alpha\nbeta\nbeta\ngamma\n"
        fun edit(vararg pairs:Pair<String,Any>)=ProjectAgentEdit.apply(original,mapper.valueToTree(mapOf(*pairs)))
        val ambiguous=assertFailsWith<IllegalArgumentException> {edit("find" to "beta","replace" to "B")}
        assertTrue(ambiguous.message!!.contains("2곳"));assertEquals("alpha\nB\nB\ngamma\n",edit("find" to "beta","replace" to "B","replace_all" to true).text)
        assertTrue(assertFailsWith<IllegalArgumentException> {edit("find" to "  alpha","replace" to "x")}.message!!.contains("없습니다"))
        assertEquals("alpha\nBETA\ngamma\n",edit("start_line" to 2,"end_line" to 3,"replace" to "BETA\n").text)
        assertEquals("alpha\ngamma\n",edit("start_line" to 2,"end_line" to 3,"replace" to "").text)
        assertEquals("first\nalpha\nbeta\nbeta\ngamma\n",edit("insert_after_line" to 0,"replace" to "first\n").text)
        assertEquals("alpha\nbeta\nbeta\ngamma\nlast\n",edit("insert_after_line" to 4,"replace" to "last\n").text)
        assertFailsWith<IllegalArgumentException> {edit("start_line" to 3,"end_line" to 9,"replace" to "x")}
        assertFailsWith<IllegalArgumentException> {edit("find" to "alpha","replace" to "alpha")}
        assertFailsWith<IllegalArgumentException> {edit("path" to "x","replace" to "x")}
    }
    @Test fun `crlf files keep their line endings`() {
        val original="a\r\nb\r\nc\r\n"
        val edited=ProjectAgentEdit.apply(original,mapper.valueToTree(mapOf("find" to "b\nc","replace" to "B\nC")))
        assertEquals("a\r\nB\r\nC\r\n",edited.text)
    }

    private val allow="""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"ok"}"""
    private fun setup():Triple<ProjectAgentStore,ProjectAgentChannel,ProjectAgentPreferences> {
        project()
        val store=ProjectAgentStore(temp.resolve("settings"));val session=ProjectAgentChannel("guild","channel","friend","demo",home.toString())
        store.addChannel(session);return Triple(store,session,store.settings("guild","friend","14b",true,true))
    }
    @Test fun `Pi READ tool loop stops at actor turn limit without a helper model`() {
        PiTestFixture(temp,tier=AgentAccess.READ).use {f->f.home.resolve("A.kt").writeText("fun one() = 1\n")
            val model=f.model {PiTestFixture.call("list_files",emptyMap<String,Any>())}
            assertFails {f.run(model,turns=3)};assertEquals(3,model.actorCalls);assertTrue(model.reviews.isEmpty());assertEquals(3,f.events.count {it.kind=="tool_end"})
        }
    }
    @Test fun `Pi READ cannot advertise or execute a native write`() {
        PiTestFixture(temp,tier=AgentAccess.READ).use {f->val file=f.home.resolve("A.kt");file.writeText("keep");var step=0
            val model=f.model {q->assertTrue(q.path("tools").none {it.path("function").path("name").asText() in setOf("write","edit","bash")});if(step++==0)PiTestFixture.call("write",mapOf("path" to "A.kt","content" to "pwned"))else PiTestFixture.answer("읽기 전용")}
            f.run(model);assertEquals("keep",file.readText());assertTrue(f.events.none {it.kind=="tool_start" || it.kind=="approval"})
        }
    }
    @Test fun `limits outside the allowed range are refused`() {
        val (store,_,prefs)=setup()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {e->
            assertFailsWith<IllegalArgumentException> {e.run("channel","friend","friend","x",prefs,ProjectAgentInference {_,_,_->"{}"},ProjectAgentLimits(0),{},{})}
            assertFailsWith<IllegalArgumentException> {e.run("channel","friend","friend","x",prefs,ProjectAgentInference {_,_,_->"{}"},ProjectAgentLimits(AgentAccess.MAX_TURNS+1),{},{})}
            assertFailsWith<IllegalArgumentException> {e.run("channel","friend","friend","x",prefs,ProjectAgentInference {_,_,_->"{}"},ProjectAgentLimits(5,"ADMIN"),{},{})}
        }
    }
    @Test fun `Pi announces and then calls a real tool within its native loop`() {
        PiTestFixture(temp,tier=AgentAccess.READ).use {f->f.home.resolve("A.kt").writeText("fixture");var step=0
            val model=f.model {q->if(step++==0)PiTestFixture.call("list_files",emptyMap<String,Any>()).also {(it as com.fasterxml.jackson.databind.node.ObjectNode).put("content","목록을 확인할게요")}else {assertContains(q.toString(),"A.kt");PiTestFixture.answer("홈에는 A.kt가 있습니다.")}}
            f.run(model);assertEquals(2,model.actorCalls);assertEquals(1,f.events.count {it.kind=="tool_end"})
        }
    }
    @Test fun `Pi text-only assistant completion is not reparsed or nudged by custom harness`() {
        PiTestFixture(temp,tier=AgentAccess.READ).use {f->val model=f.model {PiTestFixture.answer("확인해보겠습니다.")};f.run(model)
            assertEquals(1,model.actorCalls);assertEquals("확인해보겠습니다.",f.events.last().text);assertTrue(f.events.none {it.kind=="tool_start"})
        }
    }
}
