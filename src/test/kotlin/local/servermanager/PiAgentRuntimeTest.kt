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
        private var raw:String?=null;var actorCalls=0;val requests=mutableListOf<JsonNode>()
        override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
            val q=PiWire.decode(messages)
            if(q==null){raw=null;return risk}
            requests.add(q);actorCalls++;val node=script(q);raw=node.toString();return node.path("content").asText("")
        }
        override fun nativeMessage()=raw
        override fun usage()=QwenUsage(input=inputTokens.toLong(),output=10,executionWindow=16384)
    }
    private fun call(name:String,args:Any)=mapper.valueToTree<JsonNode>(mapOf("role" to "assistant","content" to "","tool_calls" to listOf(mapOf("id" to java.util.UUID.randomUUID().toString(),"type" to "function","function" to mapOf("name" to name,"arguments" to mapper.writeValueAsString(args))))))
    private fun answer(text:String)=mapper.valueToTree<JsonNode>(mapOf("role" to "assistant","content" to text,"tool_calls" to emptyList<Any>()))
    @Test fun `native read edit reread preserves bytes after explicit approval`() {
        val (store,engine,limits)=setup();val file=Path.of(store.channel("channel")!!.home).resolve("a.txt");val original="HEAD\r\nvalue=1\r\nTAIL\r\n";file.writeBytes(original.toByteArray());var step=0;var approvals=0
        val model=Model {q->when(step++){0->call("read",mapOf("path" to "a.txt"));1->call("edit",mapOf("path" to "a.txt","edits" to listOf(mapOf("oldText" to "value=1","newText" to "value=2"))));2->call("read",mapOf("path" to "a.txt"));else->answer("수정과 재조회를 완료했습니다.")}}
        engine.use {it.run("channel","user","Tester","a.txt value만2로 수정하고 다시 읽어줘",store.preferences("guild","user"),model,limits,{},{e->if(e.kind=="approval"){approvals++;it.approve("channel","user",e.token!!,true){}}})}
        assertEquals(original.replace("value=1","value=2"),file.readBytes().toString(Charsets.UTF_8));assertEquals(1,approvals);assertEquals(4,model.actorCalls)
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
    @Test fun `runtime credentials are protected even for owner`() {
        val (store,engine,_)=setup();val c=store.channel("channel")!!;val key=store.profileDirectory.resolve("pi-sessions/token.json");key.parent.createDirectories();key.writeText("not a real key");val policy=PiToolPolicy(store.profileDirectory,Path.of(c.home),ProjectAgentTools(temp.resolve("logs")),AgentAccess.OWNER)
        assertFails {policy.check("read",mapper.valueToTree(mapOf("path" to key.toString())))};assertFails {policy.check("edit",mapper.valueToTree(mapOf("path" to key.toString(),"edits" to emptyList<Any>())))};engine.close()
    }
    @Test fun `wire preserves multiple tools and text blocks and rejects images`() {
        val raw=mapper.valueToTree<JsonNode>(mapOf("messages" to listOf(mapOf("role" to "user","content" to listOf(mapOf("type" to "text","text" to "원문"))))))
        val q=PiWire.decode(listOf(ProjectAgentMessage("system",PiWire.PREFIX+raw)))!!;assertEquals("원문",q.path("messages")[0].path("content").asText())
        assertFails {PiWire.decode(listOf(ProjectAgentMessage("system",PiWire.PREFIX+"""{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{}}]}]}""")))}
        val c=mapper.readTree("""{"function":{"name":"read","arguments":{"path":"a.txt"}}}""");val a=mapper.readTree(PiWire.assistant("",listOf(c,c)));assertEquals(2,a.path("tool_calls").size());assertNotEquals(a.path("tool_calls")[0].path("id").asText(),a.path("tool_calls")[1].path("id").asText())
    }
    @Test fun `outside change still asks and approval cannot authorize a changed file`() {
        val (store,engine,limits)=setup();val outside=temp.resolve("outside.txt");outside.writeText("before");var step=0;var asks=0
        val preferences=store.settings("guild","user",null,null,false)
        val model=Model {when(step++){0->call("write",mapOf("path" to outside.toString(),"content" to "not authorized"));else->answer("경로 상태가 바뀌어 실행하지 않았습니다.")}}
        engine.use {it.run("channel","user","외부 파일 변경",preferences,model,{},{e->if(e.kind=="approval"){asks++;assertFails {it.approve("channel","other",e.token!!,true){}};outside.writeText("external concurrent change");it.approve("channel","user",e.token!!,true){}}})}
        assertEquals(1,asks);assertEquals("external concurrent change",outside.readText())
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

}
