package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Timeout
import java.nio.file.*
import kotlin.io.path.*
import kotlin.test.*

/** Real pinned Pi SDK/stock editor; deterministic model transport so authorization is independently testable. */
@Timeout(300)
class PiAgentRuntimeTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    companion object {val shared=PiRuntime(Path.of("build/pi-functional-runtime").toAbsolutePath())}
    private fun setup(tier:String=AgentAccess.OWNER):Triple<ProjectAgentStore,ProjectAgentEngine,ProjectAgentLimits> {
        val profile=temp.resolve("profile").also {it.createDirectories()};val home=temp.resolve("project").also {it.createDirectories()}
        val store=ProjectAgentStore(profile);store.addChannel(ProjectAgentChannel("guild","channel","user",null,home.toString(),contextLimit=7000,executionWindow=16384,outputTokens=2048))
        return Triple(store,ProjectAgentEngine(store,{ProjectAgentTools(profile.resolve("logs"))},runtime=shared),ProjectAgentLimits(12,tier))
    }
    private class Model(val inputTokens:Int=100,val risk:String="""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"요청한 프로젝트 내부의 작은 변경"}""",val script:(JsonNode)->JsonNode):ProjectAgentInference {
        private var raw:String?=null;var actorCalls=0;var riskCalls=0;val riskRequests=mutableListOf<List<ProjectAgentMessage>>();val requests=mutableListOf<JsonNode>()
        override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
            val q=PiWire.decode(messages)
            if(q==null){riskCalls++;riskRequests.add(messages);raw=null;return risk}
            requests.add(q);actorCalls++;val node=script(q);raw=node.toString();return node.path("content").asText("")
        }
        override fun nativeMessage()=raw
        override fun usage()=QwenUsage(input=inputTokens.toLong(),output=10,executionWindow=16384)
    }
    private fun call(name:String,args:Any)=mapper.valueToTree<JsonNode>(mapOf("role" to "assistant","content" to "","tool_calls" to listOf(mapOf("id" to java.util.UUID.randomUUID().toString(),"type" to "function","function" to mapOf("name" to name,"arguments" to mapper.writeValueAsString(args))))))
    private fun answer(text:String)=mapper.valueToTree<JsonNode>(mapOf("role" to "assistant","content" to text,"tool_calls" to emptyList<Any>()))
    @Test fun `only native bash is offered and resumed git inspection uses exact approval`() {
        val (store,engine,limits)=setup();val home=Path.of(store.channel("channel")!!.home)
        val repo=home.resolve("repo").createDirectories()
        fun git(vararg args:String) {val p=ProcessBuilder(listOf("/usr/bin/git")+args).directory(repo.toFile()).redirectErrorStream(true).start();val output=p.inputStream.bufferedReader().readText();assertEquals(0,p.waitFor(),output)}
        git("init");git("remote","add","origin","https://example.invalid/fixture.git")
        var step=0;var asks=0;val events=mutableListOf<ProjectAgentEvent>()
        val model=Model {q->
            val names=q.path("tools").map {it.path("function").path("name").asText()}
            assertContains(names,"bash");assertFalse("sh" in names)
            when(step++) {
                0->call("sh",mapOf("command" to "cd repo && git status --short"))
                1->{assertContains(q.path("messages").toString(),"sh");assertFalse(q.path("messages").filter {it.path("role").asText()=="tool"}.toString().contains("EXECUTION_DENIED"));call("bash",mapOf("command" to "cd repo && git status --short && git remote -v"))}
                else->{assertContains(q.path("messages").toString(),"https://example.invalid/fixture.git");answer("승인 후 원격 설정 확인 완료")}
            }
        }
        val prefs=store.settings("guild","user",null,null,true)
        engine.use {e->
            e.run("channel","user","Tester","git 상태와 원격 확인",prefs,model,limits,{},{event->events.add(event);if(event.kind=="approval"){asks++;e.approve("channel","user",event.token!!,true){}}})
            val resumed=Model {q->val names=q.path("tools").map {it.path("function").path("name").asText()};assertContains(names,"bash");assertFalse("sh" in names);assertContains(q.path("messages").toString(),"원격 설정 확인 완료");answer("기억합니다")}
            e.run("channel","user","이전 결과 기억해?",prefs,resumed,{},{})
        }
        assertEquals(1,asks);assertEquals(1,model.riskCalls)
        assertTrue(events.none {it.kind=="denied"});assertEquals(0,events.single {it.kind=="tool_end" && it.plan?.tool?.name=="bash"}.exitCode)
    }
    @Test fun `retired shell is not advertised or executable for any role`() {
        for(tier in listOf(AgentAccess.OWNER,AgentAccess.EDIT,AgentAccess.READ)) {
            assertFalse(PiToolPolicy.extras(tier).any {it["name"]=="sh"})
            val (store,engine,_)=setup(tier)
            engine.use {
                val home=Path.of(store.channel("channel")!!.home)
                val tools=ProjectAgentTools(temp.resolve("logs")).also {it.tier=tier}
                val policy=PiToolPolicy(store.profileDirectory,home,tools,tier)
                assertFailsWith<PiToolInputException> {policy.check("sh",mapper.valueToTree(mapOf("command" to "pwd")))}
            }
        }
    }
    @Test fun `native bash output is archived paged and read source remains exact`() {
        val (store,engine,limits)=setup();val home=Path.of(store.channel("channel")!!.home)
        val raw=(1..180).joinToString("\r\n") {"row-$it="+"x".repeat(45)}+"\r\n";home.resolve("output.txt").writeText(raw)
        val prefs=store.settings("guild","user",null,null,false,512);var step=0;var id="";val events=mutableListOf<ProjectAgentEvent>()
        val model=Model {q->when(step++) {
            0->call("bash",mapOf("command" to "cat output.txt"))
            1->{val result=q.path("messages").last {it.path("role").asText()=="tool"}.path("content").asText();assertTrue(result.length<=512);assertContains(result,"read_result");id=Regex("out-[0-9a-f]{16}").find(result)!!.value;call("read_result",mapOf("id" to id,"start_line" to 150,"lines" to 5))}
            2->{assertContains(q.path("messages").toString(),"row-150");call("read",mapOf("path" to "output.txt"))}
            else->{val last=q.path("messages").last {it.path("role").asText()=="tool"}.path("content").asText();assertTrue(last.length>512);assertContains(last,"row-180");answer("원문과 이어읽기 확인")}
        }}
        engine.use {e->e.run("channel","user","Tester","출력 확인",prefs,model,limits,{},{event->events.add(event);assertNotEquals("approval",event.kind)})}
        assertEquals(raw,events.single {it.kind=="stored_output"}.output)
        val page=events.single {it.kind=="result_read"}.readProgress!!;assertEquals(id,page.id);assertEquals(150,page.start);assertEquals(154,page.current);assertEquals(180,page.total)
        val archived=home.parent.resolve("profile/logs/results/shared/$id.txt");assertContentEquals(raw.toByteArray(),archived.readBytes())
    }
    @Test fun `resumed context bounds old bash results with the new user setting`() {
        val (store,engine,limits)=setup();val home=Path.of(store.channel("channel")!!.home);home.resolve("long.txt").writeText("OLD-RESULT-".repeat(500));var step=0
        val first=Model {when(step++){0->call("bash",mapOf("command" to "cat long.txt"));else->answer("결과 확인")}}
        engine.use {e->
            e.run("channel","user","이전 결과 확인",store.settings("guild","user",null,null,false,8000),first,{},{})
            val next=Model {q->val results=q.path("messages").filter {it.path("role").asText()=="tool"};assertTrue(results.isNotEmpty());assertTrue(results.all {it.path("content").asText().length<=512});answer("새 제한 적용")}
            e.run("channel","user","이전 결과 기억해",store.settings("guild","user",null,null,false,512),next,{},{event->assertNotEquals("stored_output",event.kind)})
        }
    }
    @Test fun `explicitly requested medium inside operation needs no second approval in automatic mode`() {
        val (store,engine,limits)=setup();val home=Path.of(store.channel("channel")!!.home);home.resolve("a.txt").writeText("CONTENT");var step=0
        val risk="""{"action":"ASK","risk":"MEDIUM","projectScope":"INSIDE","userAuthorized":true,"reason":"현재 사용자가 정확히 요청한 파일 조회"}"""
        val model=Model(risk=risk) {when(step++){0->call("bash",mapOf("command" to "cat a.txt"));else->answer("완료")}}
        engine.use {e->e.run("channel","user","a.txt를 조회해",store.settings("guild","user",null,null,false),model,{},{event->assertNotEquals("approval",event.kind)})}
        assertEquals(1,model.riskCalls)
    }
    @Test fun `large native output retains full text beyond upstream default truncation`() {
        val (store,engine,limits)=setup();val home=Path.of(store.channel("channel")!!.home)
        val raw=(1..1800).joinToString("\n") {"row-$it="+"x".repeat(55)}+"\n";home.resolve("big.txt").writeText(raw);var step=0;val events=mutableListOf<ProjectAgentEvent>()
        val model=Model {q->if(step++==0)call("bash",mapOf("command" to "cat big.txt"))else {assertTrue(q.path("messages").last {it.path("role").asText()=="tool"}.path("content").asText().length<=512);answer("원문 보관 확인")}}
        engine.use {e->e.run("channel","user","큰 출력 확인",store.settings("guild","user",null,null,false,512),model,{},{events.add(it)})}
        assertEquals(raw,events.single {it.kind=="stored_output"}.output)
    }
    @Test fun `explicit pull performs actual fast forward over local HTTP without a second approval`() {
        val (store,engine,limits)=setup();val home=Path.of(store.channel("channel")!!.home);val seed=temp.resolve("seed").createDirectories();val origin=temp.resolve("origin.git");val working=home.resolve("repo")
        fun git(cwd:Path,vararg args:String) {val p=ProcessBuilder(listOf("/usr/bin/git","-c","user.name=Fixture","-c","user.email=fixture@example.invalid")+args).directory(cwd.toFile()).redirectErrorStream(true).start();val out=p.inputStream.bufferedReader().readText();assertEquals(0,p.waitFor(),out)}
        git(seed,"init","-b","main");seed.resolve("data.txt").writeText("BEFORE");git(seed,"add",".");git(seed,"commit","-m","initial")
        git(temp,"clone","--bare",seed.toString(),origin.toString());git(home,"clone",origin.toString(),working.toString())
        seed.resolve("data.txt").writeText("AFTER");git(seed,"commit","-am","update");git(seed,"push",origin.toString(),"main");git(origin,"update-server-info")
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") {exchange->try {val file=temp.resolve(exchange.requestURI.path.removePrefix("/")).normalize();if(!file.startsWith(origin)||!file.isRegularFile()){exchange.sendResponseHeaders(404,-1)}else {val bytes=file.readBytes();exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}}}finally{exchange.close()}}
        server.start()
        try {
            git(working,"remote","set-url","origin","http://127.0.0.1:${server.address.port}/origin.git")
            var step=0;val model=Model(risk="""{"action":"ASK","risk":"MEDIUM","projectScope":"INSIDE","userAuthorized":true,"reason":"사용자가 해당 저장소의 원격 최신화를 명시 요청함"}""") {if(step++==0)call("bash",mapOf("command" to "cd repo && git fetch --all --prune 2>&1 | tail -5; git branch -a | grep main; git pull --ff-only && git checkout -b beta21 && git status -sb"))else answer("업데이트 완료")}
            engine.use {e->e.run("channel","user","repo를 원격 최신 파일로 업데이트해줘",store.settings("guild","user",null,null,false),model,{},{event->assertNotEquals("approval",event.kind)})}
            assertEquals("AFTER",working.resolve("data.txt").readText());assertTrue(working.resolve(".git/refs/heads/beta21").exists());assertEquals(1,model.riskCalls)
            assertEquals(listOf("repo를 원격 최신 파일로 업데이트해줘"),mapper.readTree(model.riskRequests.single().last().content).path("authenticated_human_requests").map {it.asText()})
        } finally {server.stop(0);engine.close()}
    }
    @Test fun `extra tool outputs use the same archive and preview policy`() {
        val (store,engine,limits)=setup();val home=Path.of(store.channel("channel")!!.home)
        repeat(80){home.resolve("file-${it.toString().padStart(3,'0')}-"+"long-name".repeat(4)+".txt").writeText("x")};var step=0;val events=mutableListOf<ProjectAgentEvent>()
        val model=Model {q->if(step++==0)call("list_files",emptyMap<String,Any>())else {val result=q.path("messages").last {it.path("role").asText()=="tool"}.path("content").asText();assertTrue(result.length<=512);assertContains(result,"read_result");answer("목록 확인")}}
        engine.use {e->e.run("channel","user","목록 조회",store.settings("guild","user",null,null,false,512),model,{},{events.add(it)})}
        val archived=events.single {it.kind=="stored_output"};assertContains(archived.output!!,"file-079-");assertTrue(archived.output!!.length>512)
    }
    @Test fun `per request window updates Pi budget without mutating channel settings`() {
        val (store,engine,limits)=setup();val model=Model {answer("완료")};val events=mutableListOf<ProjectAgentEvent>()
        engine.use {it.runWindow("channel","user","Tester","인사",store.preferences("guild","user"),model,limits,8192,3600,{},{e->events.add(e)})}
        assertEquals(16384,store.channel("channel")!!.executionWindow)
        assertTrue(events.any {it.kind=="status" && it.text.contains("자동 압축 기준 5888") && it.text.contains("모델 요청 제한 60분")})
    }
    @Test fun `owner natural approval grants exactly displayed operation and cannot be reused`() {
        val (store,engine,limits)=setup();val file=Path.of(store.channel("channel")!!.home).resolve("approved.txt")
        val model=Model {q->if(q.path("messages").toString().contains("APPROVED_CONTENT"))answer("완료")else call("write",mapOf("path" to "approved.txt","content" to "APPROVED_CONTENT"))}
        engine.use {e->e.run("channel","user","Tester","파일 생성",store.preferences("guild","user"),model,limits,{},{event->if(event.kind=="approval") {
            assertFalse(e.approveText("channel","other","실행해"){})
            assertFalse(e.approveText("channel","user","실행하지 마라고 했잖아"){})
            assertTrue(e.approveText("channel","user","하라고"){})
            assertFalse(e.approveText("channel","user","허용"){})
        }})}
        assertEquals("APPROVED_CONTENT",file.readText())
    }
    @Test fun `question accepts custom text outside the proposed choices and grants no execution permission`() {
        val (store,engine,limits)=setup();var asked=false;var received=""
        val model=Model {q->if(q.path("messages").toString().contains("CUSTOM_CHOICE_725"))answer("답변 확인")else call("ask_user",mapOf("question" to "어떤 이름으로 할까요?","choices" to listOf("A","B")))}
        engine.use {e->e.run("channel","user","Tester","질문해줘",store.preferences("guild","user"),model,limits,{},{event->if(event.kind=="question") {
            asked=true;assertTrue(e.answerQuestion("channel","user",null,"CUSTOM_CHOICE_725"){})
        };if(event.kind=="question_answered")received=event.text})}
        assertTrue(asked);assertEquals("CUSTOM_CHOICE_725",received);assertTrue(model.requests.last().toString().contains("execution_approved"))
    }
    @Test fun `script content is independently reviewed and denial reason returns without executing it`() {
        val (store,engine,limits)=setup();val home=Path.of(store.channel("channel")!!.home);val marker=home.resolve("should-not-exist")
        val script="from pathlib import Path\nPath('should-not-exist').write_text('executed')\n# fixture intent: change /etc/hosts\n";home.resolve("helper.py").writeText(script)
        val prefs=store.settings("guild","user","14b",false,false);var actor=0;var inspected=false;var raw:String?=null
        val model=object:ProjectAgentInference {
            override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
                val q=PiWire.decode(messages)
                if(q==null){val payload=mapper.readTree(messages.last().content);val file=payload.path("executable_evidence").path("files").single();assertEquals(script,file.path("content").asText());assertEquals(64,file.path("sha256").asText().length);assertFalse(think);inspected=true;return """{"action":"DENY","risk":"HIGH","projectScope":"OUTSIDE","reason":"허가 없는 /etc/hosts 시스템 파일 변경 위험"}"""}
                raw=if(actor++==0)call("bash",mapOf("command" to "python3 helper.py")).toString()else {assertContains(q.toString(),"EXECUTION_DENIED");assertContains(q.toString(),"/etc/hosts");answer("위험한 시스템 파일 변경이 포함되어 실행하지 않았습니다.").toString()};return mapper.readTree(raw).path("content").asText()
            }
            override fun nativeMessage()=raw
        }
        engine.use {it.run("channel","user","Tester","요청해서 만든 helper.py를 실행해줘",prefs,model,limits,{},{e->assertNotEquals("tool_start",e.kind)})}
        assertTrue(inspected);assertFalse(marker.exists())
    }
    @Test fun `native read edit reread preserves bytes after explicit approval`() {
        val (store,engine,limits)=setup();val file=Path.of(store.channel("channel")!!.home).resolve("a.txt");val original="HEAD\r\nvalue=1\r\nTAIL\r\n";file.writeBytes(original.toByteArray());var step=0;var approvals=0;val changes=mutableListOf<ProjectAgentFileChange>()
        val model=Model {q->when(step++){0->call("read",mapOf("path" to "a.txt"));1->call("edit",mapOf("path" to "a.txt","edits" to listOf(mapOf("oldText" to "value=1","newText" to "value=2"))));2->call("read",mapOf("path" to "a.txt"));else->answer("수정과 재조회를 완료했습니다.")}}
        engine.use {it.run("channel","user","Tester","a.txt value만2로 수정하고 다시 읽어줘",store.preferences("guild","user"),model,limits,{},{e->changes.addAll(e.changes);if(e.kind=="approval"){approvals++;it.approve("channel","user",e.token!!,true){}}})}
        assertEquals(original.replace("value=1","value=2"),file.readBytes().toString(Charsets.UTF_8));assertEquals(1,approvals);assertEquals(4,model.actorCalls);assertEquals(original,changes.single().before);assertContains(changes.single().diff,"+value=2")
        assertTrue(store.piState(store.channel("channel")!!).resolve("current-session.txt").exists());assertTrue(model.requests.first().path("messages").toString().contains("Tester"))
    }
    @Test fun `native editor cannot execute after denial and session can resume`() {
        val (store,engine,limits)=setup();val file=Path.of(store.channel("channel")!!.home).resolve("a.txt").also {it.writeText("original")};var step=0;var approvals=0
        val model=Model {when(step++){0->call("write",mapOf("path" to "a.txt","content" to "must not write"));else->answer("거절되어 수정하지 않았습니다.")}}
        engine.use {it.run("channel","user","Tester","파일 변경",store.preferences("guild","user"),model,limits,{},{e->if(e.kind=="approval"){approvals++;it.approve("channel","user",e.token!!,false){}}})
            val second=Model {q->assertTrue(q.path("messages").toString().contains("거절되어"));answer("이전 대화를 기억합니다.")};it.run("channel","user","다음 대화",store.preferences("guild","user"),second,{},{})}
        assertEquals("original",file.readText());assertEquals(1,approvals)
    }
    @Test fun `friend cannot invoke bash or leave project and reset changes native state`() {
        val (store,engine,_)=setup(AgentAccess.READ);val c=store.channel("channel")!!;val tools=ProjectAgentTools(temp.resolve("logs")).also {it.tier=AgentAccess.READ};val policy=PiToolPolicy(store.profileDirectory,Path.of(c.home),tools,AgentAccess.READ)
        assertFails {policy.check("bash",mapper.readTree("""{"command":"touch escaped"}"""))}
        assertFails {policy.check("write",mapper.readTree("""{"path":"a.txt","content":"x"}"""))}
        assertFails {policy.check("read",mapper.valueToTree(mapOf("path" to temp.resolve("outside.txt").toString())))}
        val before=store.piState(c);val next=store.context("channel","user",window=16384);assertEquals(before,store.piState(next));val reset=store.context("channel","user",reset=true);assertNotEquals(before,store.piState(reset));assertEquals(c.home,reset.home);engine.close()
    }
    @Test fun `owner file names do not trigger backend denial before review`() {
        val (store,engine,_)=setup();val c=store.channel("channel")!!;val key=store.profileDirectory.resolve("pi-sessions/token.json");key.parent.createDirectories();key.writeText("not a real key");val policy=PiToolPolicy(store.profileDirectory,Path.of(c.home),ProjectAgentTools(temp.resolve("logs")),AgentAccess.OWNER)
        assertNotNull(policy.check("read",mapper.valueToTree(mapOf("path" to key.toString()))));assertNotNull(policy.check("edit",mapper.valueToTree(mapOf("path" to key.toString(),"edits" to emptyList<Any>()))));engine.close()
    }
    @Test fun `wire preserves multiple tools and text blocks and rejects images`() {
        val raw=mapper.valueToTree<JsonNode>(mapOf("messages" to listOf(mapOf("role" to "user","content" to listOf(mapOf("type" to "text","text" to "원문"))))))
        val q=PiWire.decode(listOf(ProjectAgentMessage("system",PiWire.PREFIX+raw)))!!;assertEquals("원문",q.path("messages")[0].path("content").asText())
        assertFails {PiWire.decode(listOf(ProjectAgentMessage("system",PiWire.PREFIX+"""{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{}}]}]}""")))}
        val c=mapper.readTree("""{"function":{"name":"read","arguments":{"path":"a.txt"}}}""");val a=mapper.readTree(PiWire.assistant("",listOf(c,c)));assertEquals(2,a.path("tool_calls").size());assertNotEquals(a.path("tool_calls")[0].path("id").asText(),a.path("tool_calls")[1].path("id").asText())
    }
    @Test fun `reviewer outside ask requires approval without backend fingerprint veto`() {
        val (store,engine,limits)=setup();val outside=temp.resolve("outside.txt");outside.writeText("before");var step=0;var asks=0
        val preferences=store.settings("guild","user",null,null,false)
        val model=Model(risk="""{"action":"ASK","risk":"MEDIUM","projectScope":"OUTSIDE","reason":"외부 변경 확인"}""") {when(step++){0->call("write",mapOf("path" to outside.toString(),"content" to "approved write"));else->answer("승인 후 변경했습니다.")}}
        engine.use {it.run("channel","user","외부 파일 변경",preferences,model,{},{e->if(e.kind=="approval"){asks++;assertFails {it.approve("channel","other",e.token!!,true){}};outside.writeText("external concurrent change");it.approve("channel","user",e.token!!,true){}}})}
        assertEquals(1,asks);assertEquals("approved write",outside.readText())
    }
    @Test fun `stop kills a live native shell and permits the next request`() {
        val (store,engine,limits)=setup();val started=java.util.concurrent.CountDownLatch(1);var output="";var step=0
        val model=Model {when(step++){0->call("bash",mapOf("command" to "sleep 25 & child=\$!; printf 'STARTED:%s:%s\\n' \$\$ \"\$child\"; wait \"\$child\"","timeout" to 60));else->answer("done")}}
        val pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        engine.use {e->
            val task=pool.submit {runCatching {e.run("channel","user","셸 실행",store.preferences("guild","user"),model,{},{event->if(event.kind=="approval")e.approve("channel","user",event.token!!,true){};if(event.kind=="tool_output_snapshot" && event.text.contains("STARTED:")){output=event.text;started.countDown()}})}}
            assertTrue(started.await(10,java.util.concurrent.TimeUnit.SECONDS),"Native shell did not start")
            val ids=Regex("STARTED:([0-9]+):([0-9]+)").find(output)!!.groupValues.drop(1).map {it.toLong()}
            e.stop("channel","user");task.get(5,java.util.concurrent.TimeUnit.SECONDS)
            repeat(20){if(ids.none {java.lang.ProcessHandle.of(it).map {p->p.isAlive}.orElse(false)})return@repeat;Thread.sleep(100)}
            assertTrue(ids.none {java.lang.ProcessHandle.of(it).map {p->p.isAlive}.orElse(false)},"Native shell/child survived stop")
            val next=Model {answer("재개했습니다.")};e.run("channel","user","새 요청",store.preferences("guild","user"),next,{},{})
        };pool.close()
    }

    @Test fun `malformed independent review fails closed even in automatic mode`() {
        val (store,engine,_)=setup();val file=Path.of(store.channel("channel")!!.home).resolve("a.txt").also {it.writeText("original")};var step=0;var denied=0
        val model=Model(risk="not valid JSON") {when(step++){0->call("write",mapOf("path" to "a.txt","content" to "must not write"));else->answer("검토 실패로 변경하지 않았습니다.")}}
        val preferences=store.settings("guild","user",null,null,false)
        engine.use {e->e.run("channel","user","파일 변경",preferences,model,{},{event->if(event.kind=="denied")denied++;assertNotEquals("tool_start",event.kind)})}
        assertEquals("original",file.readText());assertEquals(1,denied)
    }
    @Test fun `native automatic and manual compaction survive reopen and reset`() {
        val (store,engine,limits)=setup();store.context("channel","user",limit=4500)
        val summary="Goal: 기억 보존. Exact project name 은하수, identifier CODE_742. No pending actions."
        val first=Model(inputTokens=5000) {q->if(q.path("tools").size()==0)answer(summary)else answer("은하수 CODE_742 " + "세션 보존 확인 ".repeat(300))}
        engine.use {e->
            e.run("channel","user","프로젝트 은하수와 CODE_742를 기억해",store.preferences("guild","user"),first,{},{})
            assertTrue(first.requests.any {it.path("tools").size()==0},"Pi did not automatically compact")
            val state=store.piState(store.channel("channel")!!);val session=Path.of(state.resolve("current-session.txt").readText())
            assertTrue(session.readLines().any {mapper.readTree(it).path("type").asText()=="compaction"})
            val follow=Model {q->assertTrue(q.path("messages").toString().contains("CODE_742"));answer("은하수 CODE_742 기억합니다. " + "새로운 대화자료 ".repeat(400))}
            e.run("channel","user","기억을 확인해",store.preferences("guild","user"),follow,{},{})
            val before=store.channel("channel")!!.history
            e.compact("channel","user",Model {answer(summary)}){}
            assertEquals(before,store.channel("channel")!!.history,"Manual compact introduced a fake user request")
            assertTrue(session.readLines().count {mapper.readTree(it).path("type").asText()=="compaction"}>=2)
            store.context("channel","user",reset=true)
            val fresh=Model {q->assertFalse(q.path("messages").toString().contains("CODE_742"));answer("새 세션입니다.")}
            e.run("channel","user","안녕",store.preferences("guild","user"),fresh,{},{})
        }
    }

    @Test fun `automatic low compound bash executes once with stable call id exit code and reviewer reason`() {
        val (store,engine,_)=setup();val home=Path.of(store.channel("channel")!!.home);home.resolve("a.txt").writeText("ORIGINAL");var step=0;val events=mutableListOf<ProjectAgentEvent>()
        val model=Model {when(step++){0->call("bash",mapOf("command" to "pwd && cat a.txt"));else->answer("확인 완료")}}
        val pref=store.settings("guild","user",null,null,false)
        engine.use {it.run("channel","user","파일 확인",pref,model,{},{event->events.add(event);assertNotEquals("approval",event.kind)})}
        assertEquals(1,model.riskCalls);val start=events.single {it.kind=="tool_start"};val end=events.single {it.kind=="tool_end"}
        assertNotNull(start.callId);assertEquals(start.callId,end.callId);assertEquals(0,end.exitCode);assertContains(end.plan!!.description,"pwd && cat a.txt");assertNotNull(end.risk);assertContains(end.text,"ORIGINAL")
    }
    @Test fun `silent rm result keeps command exit status and exact removed file`() {
        val (store,engine,_)=setup();val file=Path.of(store.channel("channel")!!.home).resolve("scratch.txt").also {it.writeText("fixture")};var step=0;val events=mutableListOf<ProjectAgentEvent>()
        val model=Model {when(step++){0->call("bash",mapOf("command" to "rm -- scratch.txt"));else->answer("삭제 완료")}}
        engine.use {it.run("channel","user","scratch.txt 삭제",store.preferences("guild","user"),model,{},{event->events.add(event);if(event.kind=="approval")it.approve("channel","user",event.token!!,true){}})}
        assertFalse(file.exists());val end=events.single {it.kind=="tool_end"};assertEquals(0,end.exitCode);assertContains(end.plan!!.description,"rm -- scratch.txt");assertContains(end.text,"종료 코드 0")
    }
    @Test fun `denied git reaches acting model as not executed and cannot be retried`() {
        val (store,engine,_)=setup();var step=0;var asks=0
        val model=Model(risk="""{"action":"ASK","risk":"MEDIUM","projectScope":"INSIDE","reason":"push 명시 승인 필요"}""") {q->when(step++){0->call("bash",mapOf("command" to "git push origin main"));else->{val results=q.path("messages").filter {it.path("role").asText()=="tool"}.joinToString {it.path("content").asText()};assertContains(results,"EXECUTION_DENIED");assertContains(results,"실행되지 않았습니다");answer("승인이 거절되어 push하지 않았습니다.")}}}
        val pref=store.settings("guild","user",null,null,false)
        engine.use {it.run("channel","user","push 해줘",pref,model,{},{event->if(event.kind=="approval"){asks++;it.approve("channel","user",event.token!!,false){}};assertNotEquals("tool_start",event.kind)})}
        assertEquals(1,asks)
    }
    @Test fun `model denial stays denied even for ordinary outside read`() {
        val (store,engine,_)=setup();val outside=temp.resolve("outside.txt").also {it.writeText("OUTSIDE_VERIFIED")};var step=0;var asks=0
        val model=Model(risk="""{"action":"DENY","risk":"HIGH","reason":"외부 경로","projectScope":"OUTSIDE"}""") {q->when(step++){0->call("read",mapOf("path" to outside.toString()));else->{assertContains(q.path("messages").toString(),"EXECUTION_DENIED");answer("거부되어 읽지 않았습니다.")}}}
        val pref=store.settings("guild","user",null,null,false)
        engine.use {it.run("channel","user","외부 파일 읽기",pref,model,{},{event->if(event.kind=="approval"){asks++;it.approve("channel","user",event.token!!,true){}}})};assertEquals(0,asks);assertEquals(2,model.actorCalls)
    }
    @Test fun `choice answer is data only and grants no permission to following outside write`() {
        val (store,engine,_)=setup();val outside=temp.resolve("outside.txt").also {it.writeText("KEEP")};var step=0;var questions=0;var approvals=0
        val model=Model(risk="""{"action":"ASK","risk":"MEDIUM","projectScope":"OUTSIDE","reason":"외부 변경 확인"}""") {q->when(step++){0->call("ask_user",mapOf("question" to "어떤 파일을 변경할까요?","choices" to listOf("외부 파일","프로젝트 파일")));1->{assertContains(q.path("messages").toString(),"execution_approved");call("write",mapOf("path" to outside.toString(),"content" to "must not write"))};else->answer("질문 답변은 받았지만 실행 승인이 없어 변경하지 않았습니다.")}}
        val pref=store.settings("guild","user",null,null,false)
        engine.use {e->e.run("channel","user","파일 선택 후 처리",pref,model,{},{event->if(event.kind=="question"){questions++;assertFails {e.answerQuestion("channel","other",event.token!!,"0") {}};assertTrue(e.answerQuestion("channel","user",event.token!!,"0") {});assertFails {e.answerQuestion("channel","user",event.token!!,"0") {}}};if(event.kind=="approval"){approvals++;e.approve("channel","user",event.token!!,false){}}})}
        assertEquals(1,questions);assertEquals(1,approvals);assertEquals("KEEP",outside.readText())
    }

}
