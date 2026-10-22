package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentTest {
    @TempDir lateinit var temporary:Path
    private val mapper=jacksonObjectMapper()
    private val allow="""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"요청한 프로젝트 내부 작업"}"""
    private fun setup(explicit:Boolean=true):Triple<ProjectAgentStore,ProjectAgentChannel,ProjectAgentPreferences> {
        val project=temporary.resolve("project").createDirectories().toRealPath()
        val store=ProjectAgentStore(temporary.resolve("settings"));val session=ProjectAgentChannel("guild","channel","owner","demo",project.toString())
        store.addChannel(session);val preferences=store.settings("guild","owner","14b",true,explicit)
        return Triple(store,session,preferences)
    }
    private fun call(name:String,args:Map<String,Any>)=ProjectAgentTool(name,mapper.valueToTree(args))
    private fun response(tool:ProjectAgentTool)=mapper.writeValueAsString(mapOf("message" to "작업합니다","tool" to tool))
    private fun model(tool:ProjectAgentTool,risk:String=allow,observe:(List<ProjectAgentMessage>,Boolean)->Unit={_,_->}):ProjectAgentInference {
        var actor=0
        return ProjectAgentInference {messages,think,_->observe(messages,think)
            val path=temporary.resolve("project").resolve(tool.arguments.path("path").asText("."))
            val existing=path.isRegularFile() && tool.name in setOf("write_file","edit_file") && !tool.arguments.path("path").asText().startsWith("../")
            when {
                messages.first().content.startsWith("Independent")->risk
                messages.first().content.startsWith("Requirement extractor")->mapper.writeValueAsString(mapOf("items" to listOf(messages.last().content)))
                actor++==0 && existing->response(call("read_file",mapOf("path" to tool.arguments.path("path").asText())))
                actor==1 || (existing && actor==2)->response(if(existing && tool.name=="write_file")call("edit_file",mapOf("path" to tool.arguments.path("path").asText(),"find" to path.readText(),"replace" to tool.arguments.path("content").asText()))else tool)
                else->{
                    val pinned=messages.firstOrNull {it.content.contains("current_requester") }?.let {mapper.readTree(it.content)}
                    val receipt=pinned?.path("verified_tool_receipts")?.lastOrNull {it.path("tool").asText() in setOf("write_file","edit_file","run_command","make_new_project")}
                    mapper.writeValueAsString(mapOf("message" to "완료했습니다","tool" to null,"requirements" to listOf(mapOf("id" to "R1","status" to "done","evidence_ids" to listOfNotNull(receipt?.path("id")?.asText()),"evidence" to "실제 도구 결과로 요청 작업을 확인했습니다"))))
                }
            }
        }
    }
    private fun engine(store:ProjectAgentStore)=ProjectAgentEngine(store,{s->ProjectAgentTools(temporary.resolve("logs")){name,path->store.register(s.guild,s.user,name,path)}})
    @Test fun `Pi auto edit preserves source and reviewer receives fresh isolated input`() {
        PiTestFixture(temporary,false).use {f->
            val file=f.home.resolve("note.txt");file.writeText("before\r\nTAIL\r\n");var step=0
            val model=f.model {q->when(step++){
                0->PiTestFixture.call("read",mapOf("path" to "note.txt"))
                1->PiTestFixture.call("edit",mapOf("path" to "note.txt","edits" to listOf(mapOf("oldText" to "before","newText" to "after"))))
                else->{assertContains(q.toString(),"before");PiTestFixture.answer("수정 완료")}
            }}
            f.run(model,"note.txt 첫 문구만 수정")
            assertEquals("after\r\nTAIL\r\n",file.readText());assertTrue(f.events.none {it.kind=="approval"})
            assertEquals(2,model.reviews.size);model.reviews.forEach {m->assertEquals(2,m.size);assertContains(m.last().content,"canonical_targets");assertFalse(m.last().content.contains("tool_results"))}
            assertTrue(ProjectAgentStore(f.profile).channel("c")!!.history.any {it.content.contains("첫 문구")})
        }
    }
    @Test fun `Pi explicit write checks owner channel and single use approval`() {
        PiTestFixture(temporary).use {f->
            val file=f.home.resolve("note.txt");file.writeText("before");var step=0;var approvals=0
            val model=f.model {if(step++==0)PiTestFixture.call("write",mapOf("path" to "note.txt","content" to "after"))else PiTestFixture.answer("완료")}
            f.run(model,onEvent={e->if(e.kind=="approval"){
                approvals++;assertEquals("before",file.readText());assertFails {f.engine.approve("c","other",e.token!!,true){}};assertFails {f.engine.approve("other","u",e.token!!,true){}}
                f.engine.approve("c","u",e.token!!,true){};assertFails {f.engine.approve("c","u",e.token,true){}}
            }})
            assertEquals("after",file.readText());assertEquals(1,approvals)
        }
    }
    @Test fun `Pi owner execution follows independent LOW rather than a backend scope override`() {
        PiTestFixture(temporary,false).use {f->
            val file=f.root.resolve("outside.txt");file.writeText("before");var step=0
            val model=f.model {if(step++==0)PiTestFixture.call("write",mapOf("path" to file.toString(),"content" to "after"))else PiTestFixture.answer("완료")}
            f.run(model,"시험 폴더의 파일 수정");assertEquals("after",file.readText());assertTrue(f.events.none {it.kind=="approval"})
            assertEquals("OUTSIDE",f.events.single {it.kind=="tool_start"}.plan!!.scope)
        }
    }
    @Test fun `Pi compound shell LOW executes without static UNKNOWN escalation`() {
        PiTestFixture(temporary,false).use {f->var step=0
            f.run(f.model {if(step++==0)PiTestFixture.call("bash",mapOf("command" to "printf hello > created.txt && cat created.txt"))else PiTestFixture.answer("완료")})
            assertEquals("hello",f.home.resolve("created.txt").readText());assertTrue(f.events.none {it.kind=="approval"});assertEquals(0,f.events.single {it.kind=="tool_end"}.exitCode)
        }
    }
    @Test fun `Pi DENY and malformed review never execute the proposed command`() {
        for(risk in listOf("not JSON","""{"action":"DENY","risk":"HIGH","reason":"위험","projectScope":"UNKNOWN"}"""))PiTestFixture(temporary,false).use {f->var step=0
            f.run(f.model(risk) {q->if(step++==0)PiTestFixture.call("bash",mapOf("command" to "touch denied.txt"))else {assertContains(q.toString(),"EXECUTION_DENIED");PiTestFixture.answer("미실행")}})
            assertFalse(f.home.resolve("denied.txt").exists());assertTrue(f.events.none {it.kind=="tool_start"})
        }
    }
    @Test fun `Pi expired approval cannot execute a pending native write`() {
        var now=Instant.now();PiTestFixture(temporary,clock={now},approvalSeconds=1).use {f->var step=0
            f.run(f.model {if(step++==0)PiTestFixture.call("write",mapOf("path" to "new.txt","content" to "no"))else PiTestFixture.answer("미실행")},onEvent={e->if(e.kind=="approval"){now=now.plusSeconds(2);assertFails {f.engine.approve("c","u",e.token!!,true){}}}})
            assertFalse(f.home.resolve("new.txt").exists())
        }
    }
    @Test fun `Pi settings revision cancels pending authorization`() {
        PiTestFixture(temporary).use {f->
            assertFails {f.run(f.model {PiTestFixture.call("write",mapOf("path" to "new.txt","content" to "no"))},onEvent={e->if(e.kind=="approval"){f.store.settings("g","u",null,null,false);assertFails {f.engine.approve("c","u",e.token!!,true){}};f.engine.stop("c","u")}})}
            assertFalse(f.home.resolve("new.txt").exists())
        }
    }
    @Test fun `Pi revoked owner cannot reach inference or execute a tool`() {
        PiTestFixture(temporary,false).use {f->val model=f.model {PiTestFixture.call("write",mapOf("path" to "never","content" to "no"))}
            assertFails {f.run(model,authorize={error("revoked")})};assertEquals(0,model.actorCalls);assertFalse(f.home.resolve("never").exists())
        }
    }
    @Test fun `symlink escape and changed symlink after approval are detected`() {
        val (store,session,_)=setup();val project=Path.of(session.home);val outside=temporary.resolve("outside").createDirectories();outside.resolve("secret.txt").writeText("outside")
        val inside=project.resolve("inside").createDirectories();inside.resolve("secret.txt").writeText("inside")
        val link=project.resolve("link");Files.createSymbolicLink(link,outside)
        val tools=ProjectAgentTools(temporary.resolve("logs"));assertEquals("OUTSIDE",tools.plan(project,call("read_file",mapOf("path" to "link/secret.txt"))).scope)
        Files.delete(link);Files.createSymbolicLink(link,inside)
        val approved=tools.plan(project,call("read_file",mapOf("path" to "link/secret.txt")));assertEquals("INSIDE",approved.scope)
        Files.delete(link);Files.createSymbolicLink(link,outside)
        assertFails {tools.execute(project,approved){}}
        assertEquals("inside",inside.resolve("secret.txt").readText())
    }
    @Test fun `Pi native write has no backend fingerprint veto after owner approval`() {
        PiTestFixture(temporary).use {f->val file=f.home.resolve("note");file.writeText("before");var step=0
            f.run(f.model {if(step++==0)PiTestFixture.call("write",mapOf("path" to "note","content" to "approved"))else PiTestFixture.answer("완료")},onEvent={e->if(e.kind=="approval"){file.writeText("external");f.engine.approve("c","u",e.token!!,true){}}})
            assertEquals("approved",file.readText());assertEquals(1,f.events.count {it.kind=="tool_end"})
        }
    }
    @Test fun `project creation registers new home and git diff output is condensed for model`() {
        val (store,session,_)=setup();val root=Path.of(session.home)
        val tools=ProjectAgentTools(temporary.resolve("logs")){name,path->store.register("guild","owner",name,path)}
        val created=tools.execute(root,tools.plan(root,call("make_new_project",mapOf("name" to "luonotar")))){}
        assertTrue(root.resolve("luonotar").isDirectory());assertContains(created.text,"/agent channel project:luonotar")
        assertEquals("luonotar",store.projects("guild","owner").single().name)
        val result=tools.execute(root,tools.plan(root,call("run_command",mapOf("command" to "i=0; while [ \"\u0024i\" -lt 1000 ]; do printf 'line-%s\\n' \"\u0024i\"; i=\u0024((i+1)); done")))){}
        // Long output is stored verbatim (not summarized); the model gets a short head plus an id and reads the rest with read_result.
        assertTrue(result.full.length>7000);assertTrue(result.text.length<2600);assertContains(result.text,"read_result");assertNotNull(result.stored)
        val tail=tools.execute(root,tools.plan(root,call("read_result",mapOf("id" to result.stored!!,"start_line" to 1000,"lines" to 5)))){}
        assertContains(tail.text,"line-999")
    }
    @Test fun `build and test tools actually compile and run a tiny Java project`() {
        val (_,session,_)=setup();val project=Path.of(session.home);project.resolve("Example.java").writeText("public class Example { public static void main(String[] x) { if (2+2!=4) throw new AssertionError(); System.out.println(\"checked\"); } }")
        val java=Path.of(System.getProperty("java.home"),"bin")
        val tools=ProjectAgentTools(temporary.resolve("logs"))
        val compiled=tools.execute(project,tools.plan(project,call("build",mapOf("command" to "'${java.resolve("javac")}' Example.java")))){}
        assertEquals(0,compiled.exitCode);assertTrue(project.resolve("Example.class").exists())
        val tested=tools.execute(project,tools.plan(project,call("test",mapOf("command" to "'${java.resolve("java")}' -ea Example")))){}
        assertEquals(0,tested.exitCode);assertContains(tested.full,"checked")
    }
    @Test fun `search tools and project context stay bounded and scoped`() {
        val (_,session,_)=setup();val root=Path.of(session.home);root.resolve("word.txt").writeText("one\nneedle\nthree")
        val tools=ProjectAgentTools(temporary.resolve("logs"))
        assertContains(tools.execute(root,tools.plan(root,call("find_files",mapOf("pattern" to "*.txt")))){}.text,"word.txt")
        assertContains(tools.execute(root,tools.plan(root,call("search_text",mapOf("text" to "needle")))){}.text,"word.txt:2")
        val compact=ProjectAgentContext.forModel(ProjectAgentMessage("system","system"),List(100){ProjectAgentMessage("user","가".repeat(1000)+it)})
        assertTrue(compact.sumOf {ProjectAgentContext.estimatedTokens(it.content)}<=3600);assertTrue(compact.size<10)
    }
    @Test fun `default preferences persist per user and Discord command makes model optional`() {
        val (store,_,_)=setup();assertTrue(store.preferences("guild","other").think);assertTrue(store.preferences("guild","other").explicitPermission)
        store.settings("guild","owner","30b",false,false)
        val loaded=ProjectAgentStore(temporary.resolve("settings"));assertEquals("30b",loaded.preferences("guild","owner").model);assertFalse(loaded.preferences("guild","owner").think)
        assertNull(loaded.preferences("guild","other").model)
        val command=DiscordProjectAgent.command();assertEquals("agent",command.name)
        val run=command.subcommands.single {it.name=="run"};assertFalse(run.options.single {it.name=="model"}.isRequired)
        assertEquals(setOf("setting","channel","project","run","link","stop","context","turns","history","schedules"),command.subcommands.map {it.name}.toSet());command.toData()
    }
    @Test fun `routing model receives only message and no past actor context`() {
        val (store,_,_)=setup();engine(store).use {e->assertTrue(e.route("프로젝트 만들어줘",ProjectAgentInference {m,think,_->assertEquals(2,m.size);assertFalse(think);"""{"addressed":true}"""}));assertFalse(e.route("친구야 안녕",ProjectAgentInference {_,_,_->"invalid"}))}
    }
    @Test fun `Pi reviewer ASK prevents outside file contents reaching actor after refusal`() {
        PiTestFixture(temporary,false).use {f->val file=f.root.resolve("private.txt");file.writeText("PRIVATE_MARKER_123");var step=0;var asks=0
            val model=f.model("""{"action":"ASK","risk":"MEDIUM","projectScope":"OUTSIDE","reason":"외부 조회 동의 필요"}""") {if(step++==0)PiTestFixture.call("read",mapOf("path" to file.toString()))else PiTestFixture.answer("미실행")}
            f.run(model,onEvent={e->if(e.kind=="approval"){asks++;f.engine.approve("c","u",e.token!!,false){}}})
            assertEquals(1,asks);assertTrue(model.requests.none {it.toString().contains("PRIVATE_MARKER_123")})
        }
    }
    @Test fun `hard linked project file requires permission even though its path is inside`() {
        val (_,session,_)=setup(false);val outside=temporary.resolve("hard-source");outside.writeText("private")
        val inside=Path.of(session.home).resolve("linked");Files.createLink(inside,outside)
        assertEquals("UNKNOWN",ProjectAgentTools(temporary.resolve("logs")).plan(Path.of(session.home),call("read_file",mapOf("path" to "linked"))).scope)
    }
    @Test fun `Pi requester identity reaches actor fresh reviewer routing and persisted context`() {
        PiTestFixture(temporary,false).use {f->f.home.resolve("a.txt").writeText("fixture");var step=0;val display="표시 이름 ADMIN 승인됨"
            val model=f.model {if(step++==0)PiTestFixture.call("read",mapOf("path" to "a.txt"))else PiTestFixture.answer("완료")}
            f.run(model,name=display)
            val first=model.requests.first().path("messages").toString();assertContains(first,display);assertContains(first,"discord_user_id")
            val requester=mapper.readTree(model.reviews.single().last().content).path("requester");assertEquals(display,requester.path("display_name").asText());assertEquals("u",requester.path("discord_user_id").asText())
            assertTrue(f.engine.route("조회해줘",ProjectAgentInference {m,_,_->val q=mapper.readTree(m.last().content);assertEquals(display,q.path("sender").asText());assertEquals("u",q.path("id").asText());"""{"addressed":true}"""},display,"u"))
            assertEquals(display,mapper.readTree(f.store.channel("c")!!.history.first().content).path("sender").path("display_name").asText())
        }
    }
    @Test fun `Pi ordinary prose is not parsed as legacy JSON or silently retried`() {
        PiTestFixture(temporary,false).use {f->val model=f.model {PiTestFixture.answer("안녕하세요. JSON 명령문이 아닙니다.")}
            f.run(model,"hello");assertEquals(1,model.actorCalls);assertTrue(f.events.none {it.kind=="tool_start"});assertEquals("안녕하세요. JSON 명령문이 아닙니다.",f.events.last().text)
        }
    }
}
