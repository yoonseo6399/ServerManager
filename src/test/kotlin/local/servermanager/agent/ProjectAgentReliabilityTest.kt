package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentReliabilityTest {
    @TempDir lateinit var root:Path
    private val mapper=jacksonObjectMapper()
    private fun tool(name:String,vararg args:Pair<String,Any>)=ProjectAgentTool(name,mapper.valueToTree(args.toMap()))
    private fun run(tools:ProjectAgentTools,state:ProjectAgentProgress,home:Path,turn:Int,call:ProjectAgentTool):AgentToolProof {
        val plan=tools.plan(home,call)
        assertNull(state.before(plan,"요청한 파일 수정"))
        return state.record(turn,plan,tools.execute(home,plan){})
    }

    @Test fun `a creation receipt cannot complete missing b or a read-only action`() {
        val home=root.resolve("project").createDirectories().toRealPath();val tools=ProjectAgentTools(root.resolve("logs"))
        val state=ProjectAgentProgress(home)
        run(tools,state,home,1,tool("write_file","path" to "a.txt","content" to "안녕\n"))
        val ledger=ProjectAgentLedger().also {it.load(listOf("a.txt 파일을 생성한다","b.txt 파일을 생성한다"))}
        assertNull(ledger.updateVerified("R1","done","a.txt 저장 및 바이트 확인",listOf("T1"),state))
        assertNotNull(ledger.updateVerified("R2","done","두 파일 생성 완료라고 모델이 주장",listOf("T1"),state))
        assertFalse(home.resolve("b.txt").exists());assertEquals(listOf("b.txt 파일을 생성한다"),ledger.openTexts())
        run(tools,state,home,2,tool("read_file","path" to "a.txt"))
        assertFalse(state.proves("a.txt 파일을 수정한다",listOf("T2")))
        assertFalse(state.proves("a.txt 파일을 수정한다",listOf("T999")))
        home.resolve("a.txt").writeText("changed externally")
        assertFalse(state.proves("a.txt 파일을 생성한다",listOf("T1")),"stale successful writes cannot prove current file state")
        run(tools,state,home,3,tool("write_file","path" to "a","content" to "first"))
        assertTrue(state.proves("a 파일을 생성한다",listOf("T3")))
        assertFalse(state.proves("b 파일을 생성한다",listOf("T3")))
        assertFalse(state.proves("a 와 b 파일을 생성한다",listOf("T3")))
    }

    @Test fun `merge source deletion waits for preserved destination and actual original bytes survive`() {
        val home=root.resolve("project").createDirectories().toRealPath();val tools=ProjectAgentTools(root.resolve("logs"));val state=ProjectAgentProgress(home)
        val a="안녕\n";val b="잘가\n";home.resolve("a.txt").writeText(a);home.resolve("b.txt").writeText(b)
        run(tools,state,home,1,tool("read_file","path" to "a.txt"));run(tools,state,home,2,tool("read_file","path" to "b.txt"))
        val deleteA=tools.plan(home,tool("delete_path","path" to "a.txt"))
        assertNotNull(state.before(deleteA,"a와 b를 합쳐 ab.txt에 저장하고 원본을 삭제해"))
        run(tools,state,home,3,tool("write_file","path" to "ab.txt","content" to a+b))
        for((turn,name) in listOf(4 to "a.txt",5 to "b.txt")) {
            val plan=tools.plan(home,tool("delete_path","path" to name))
            assertNull(state.before(plan,"a와 b를 합쳐 ab.txt에 저장하고 원본을 삭제해"))
            state.record(turn,plan,tools.execute(home,plan){})
        }
        assertContentEquals("안녕\n잘가\n".toByteArray(),home.resolve("ab.txt").readBytes())
        assertFalse(home.resolve("a.txt").exists());assertFalse(home.resolve("b.txt").exists())
        assertTrue(state.proves("a.txt 삭제",listOf("T4")))
        home.resolve("a.txt").writeText("recreated")
        assertFalse(state.proves("a.txt 삭제",listOf("T4")))
    }

    @Test fun `search read partial edit chain preserves every unrelated byte and CRLF`() {
        val home=root.resolve("project").createDirectories().toRealPath();val tools=ProjectAgentTools(root.resolve("logs"));val state=ProjectAgentProgress(home)
        val file=home.resolve("example.txt");val original="header\r\nvalue=1\r\nfooter\r\n";file.writeText(original)
        val found=tools.execute(home,tools.plan(home,tool("search","pattern" to "value=1","fixed" to true,"type" to "text"))){ }
        assertContains(found.full,"example.txt:2:value=1")
        val edit=tools.plan(home,tool("edit_file","path" to "example.txt","start_line" to 2,"end_line" to 2,"replace" to "value=2"))
        assertNotNull(state.before(edit,"value만 수정해"),"an existing file cannot be edited unread")
        run(tools,state,home,1,tool("read_file","path" to "example.txt","start_line" to 1,"lines" to 3))
        run(tools,state,home,2,tool("edit_file","path" to "example.txt","start_line" to 2,"end_line" to 2,"replace" to "value=2"))
        assertContentEquals("header\r\nvalue=2\r\nfooter\r\n".toByteArray(),file.readBytes())
        assertTrue(state.proves("example.txt를 수정한다",listOf("T2")))
        assertEquals(1,state.currentChanges().size);assertContains(state.currentChanges().single().change,"value=2")
        file.writeText("outside change");assertTrue(state.currentChanges().isEmpty())
        val matches=tools.execute(home,tools.plan(home,tool("search","files" to true,"glob" to "*.txt"))){ }
        assertContains(matches.full,"example.txt")
        val explicit=tools.execute(home,tools.plan(home,tool("search","files" to true,"pattern" to "legacy placeholder","path" to "example.txt"))){ }
        assertContains(explicit.full,"example.txt");assertContains(explicit.full,"not content matches")
    }

    @Test fun `whole-file rewrite is rejected for an ordinary partial change`() {
        val home=root.resolve("project").createDirectories().toRealPath();val tools=ProjectAgentTools(root.resolve("logs"));val state=ProjectAgentProgress(home)
        home.resolve("a.txt").writeText("keep\nvalue=1\nfooter\n")
        run(tools,state,home,1,tool("read_file","path" to "a.txt"))
        val overwrite=tools.plan(home,tool("write_file","path" to "a.txt","content" to "keep\nvalue=2\nfooter\n"))
        assertContains(state.before(overwrite,"value를 수정해")!!,"edit_file")
        assertNull(state.before(overwrite,"파일 전체를 교체해"))
        assertEquals("keep\nvalue=1\nfooter\n",home.resolve("a.txt").readText())
    }

    @Test fun `repeat guard notices reordered JSON arguments and a changed source permits another read`() {
        val home=root.resolve("project").createDirectories().toRealPath();val tools=ProjectAgentTools(root.resolve("logs"));val state=ProjectAgentProgress(home)
        home.resolve("a.txt").writeText("first")
        assertNull(state.attempt(tool("read_file","path" to "a.txt","lines" to 5)))
        run(tools,state,home,1,tool("read_file","path" to "a.txt","lines" to 5))
        assertNull(state.attempt(tool("read_file","lines" to 5,"path" to "a.txt")))
        run(tools,state,home,2,tool("read_file","path" to "a.txt","lines" to 5))
        assertNotNull(state.attempt(tool("read_file","path" to "a.txt","lines" to 5)))
        run(tools,state,home,3,tool("edit_file","path" to "a.txt","find" to "first","replace" to "second"))
        assertNull(state.attempt(tool("read_file","path" to "a.txt","lines" to 5)))
    }

    @Test fun `successful command cannot keep repeating without changing state`() {
        val home=root.resolve("project").createDirectories().toRealPath();val tools=ProjectAgentTools(root.resolve("logs"));val state=ProjectAgentProgress(home)
        val call=tool("build","command" to "/bin/echo checked")
        assertNull(state.attempt(call));run(tools,state,home,1,call)
        assertNotNull(state.attempt(call))
    }

    @Test fun `single file request keeps its exact constraints without extraction inference`() {
        val request="example.txt에서 old를 new로 대치하고 나머지 원문과 마지막 개행을 보존해. 검색, 읽기, 수정, 재조회를 진행하고 결과를 보고해."
        assertEquals(listOf(request),ProjectAgentLedger.extract(request,ProjectAgentInference {_,_,_->error("must not inflate a single outcome")}))
        val command="run_command 도구 사용해서 파이선 코드 그냥 실행해. 조회하자마"
        assertEquals(listOf(command),ProjectAgentLedger.extract(command,ProjectAgentInference {_,_,_->error("must not invent six command requirements")}))
        var calls=0
        assertEquals(listOf("첫 작업 수행","둘째 작업 수행"),ProjectAgentLedger.extract("- example.txt 수정\n- 테스트 실행",ProjectAgentInference {_,_,_->calls++;"""{"items":["첫 작업 수행","둘째 작업 수행"]}"""}))
        assertEquals(1,calls)
    }

    @Test fun `changing line counts cannot repeat the same complete file result forever`() {
        val home=root.resolve("project").createDirectories().toRealPath();home.resolve("a.txt").writeText("keep\n")
        val store=ProjectAgentStore(root.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u",null,home.toString()))
        var actor=0;val events=mutableListOf<ProjectAgentEvent>()
        ProjectAgentEngine(store,{ProjectAgentTools(root.resolve("logs"))}).use {engine->
            engine.run("c","u","a.txt 내용을 확인해",ProjectAgentPreferences(),ProjectAgentInference {messages,_,_->
                if(messages.first().content.startsWith("Context compressor"))compactionFixture("이미 읽은 원문 keep")
                else {actor++;mapper.writeValueAsString(mapOf("message" to "조회","tool" to tool("read_file","path" to "a.txt","lines" to actor*10)))}
            },{}) {events.add(it)}
        }
        assertEquals(4,events.count {it.kind=="tool_end"})
        assertContains(events.last {it.kind=="done"}.text,"같은 조회 결과")
        assertEquals("keep\n",home.resolve("a.txt").readText())
    }

    @Test fun `missing file errors do not pretend that files exceed a size limit`() {
        val home=root.resolve("project").createDirectories().toRealPath();val tools=ProjectAgentTools(root.resolve("logs"))
        val error=assertFails {tools.execute(home,tools.plan(home,tool("read_file","path" to "missing.txt"))){}}
        assertContains(error.message.orEmpty(),"파일이 없습니다");assertFalse(error.message.orEmpty().contains("1MB"))
    }

    @Test fun `a git object and full commit diff cannot expose tracked secret contents`() {
        val home=root.resolve("project").createDirectories().toRealPath()
        fun git(vararg args:String):String {val p=ProcessBuilder(listOf("git")+args).directory(home.toFile()).redirectErrorStream(true).start();val out=p.inputStream.readAllBytes().toString(Charsets.UTF_8);assertEquals(0,p.waitFor(),out);return out.trim()}
        git("init","-q");home.resolve(".env").writeText("DUMMY_SECRET_6739=yes\n");home.resolve("public.txt").writeText("PUBLIC_VISIBLE_8251\n")
        git("add",".");git("-c","user.name=test","-c","user.email=test@example.invalid","commit","-qm","fixture")
        val shell=ProjectAgentShell(home)
        assertFails {shell.validate("git show HEAD:.env")}
        val refused=shell.run("git show HEAD:.env"){}
        assertNotEquals(0,refused.status);assertFalse(refused.output.contains("DUMMY_SECRET_6739"))
        val blob=git("rev-parse","HEAD:.env")
        assertFails {shell.validate("git show $blob")}
        assertNotEquals(0,shell.run("git show $blob"){}.status)
        val output=shell.run("git show HEAD"){}.output
        assertContains(output,"PUBLIC_VISIBLE_8251");assertFalse(output.contains("DUMMY_SECRET_6739"),output)
        assertContains(shell.run("git show HEAD:public.txt"){}.output,"PUBLIC_VISIBLE_8251")
    }
}
