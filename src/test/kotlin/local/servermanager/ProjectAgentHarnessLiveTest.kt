package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

/**
 * Opt-in only (PROJECT_AGENT_LIVE_TEST=true, PROJECT_AGENT_LIVE_MODEL=30b). Talks to a real Ollama Qwen and touches nothing
 * but a temporary project; no Discord, no production data. Each test prints one LIVE-HARNESS-PROOF line with turns and seconds.
 */
@EnabledIfEnvironmentVariable(named="PROJECT_AGENT_LIVE_TEST",matches="true")
class ProjectAgentHarnessLiveTest {
    @TempDir lateinit var temporary:Path
    private val model=System.getenv("PROJECT_AGENT_LIVE_MODEL") ?: "30b"
    private val source="""
        package demo

        class Calculator {
            fun add(a:Int,b:Int):Int = a + b
            // TODO handle overflow
            fun divide(a:Int,b:Int):Int = a / b
            fun total(values:List<Int>):Int = values.sum()
        }
    """.trimIndent()+"\n"

    private fun fixture(): Triple<ProjectAgentStore,Path,ProjectAgentPreferences> {
        val home=temporary.resolve("project").createDirectories().toRealPath()
        home.resolve("src").createDirectories().resolve("Calculator.kt").writeText(source)
        home.resolve("README.md").writeText("# demo\nA tiny calculator.\n")
        val store=ProjectAgentStore(temporary.resolve("settings"));store.addChannel(ProjectAgentChannel("test","test","friend","fixture",home.toString()))
        return Triple(store,home,store.settings("test","friend",model,false,true))
    }
    private fun run(tier:String,turns:Int,request:String,store:ProjectAgentStore,prefs:ProjectAgentPreferences,onEvent:(ProjectAgentEvent,ProjectAgentEngine)->Unit):Pair<List<ProjectAgentEvent>,Double> {
        val llm=LocalLlm();val events=mutableListOf<ProjectAgentEvent>();val started=System.nanoTime()
        val inference=ProjectAgentInference {messages,think,progress->val answer=llm.conversation(model,messages,think,progress);check(!answer.limited);answer.text}
        try {
            ProjectAgentEngine(store,{ProjectAgentTools(temporary.resolve("logs"))}).use {engine->
                engine.run("test","friend","friend",request,prefs,inference,ProjectAgentLimits(turns,tier),{check(System.nanoTime()-started<TimeUnit.MINUTES.toNanos(15)) {"live harness test time limit"}}) {event->
                    events.add(event);onEvent(event,engine)
                    if(event.kind in setOf("message","tool_end","denied","done"))println("LIVE-HARNESS ${event.kind} turn=${event.turn} ${event.text.take(400)}")
                }
            }
        } finally {llm.unload(LocalLlm.tag(model))}
        return events to (System.nanoTime()-started)/1e9
    }

    @Test fun `read tier locates code with grep and sed and reports the real line`() {
        val (store,home,prefs)=fixture()
        val (events,seconds)=run(AgentAccess.READ,12,"Calculator.kt 에서 TODO 주석이 몇 번째 줄에 있는지 grep 으로 찾아서 줄 번호와 내용을 알려줘.",store,prefs) {event,_->
            event.plan?.let {check(it.tool.name in ProjectAgentTools.readTools) {"read tier proposed ${it.tool.name}"}}
        }
        assertEquals(source,home.resolve("src/Calculator.kt").readText(),"read tier must not change files")
        assertTrue(events.any {it.kind=="tool_end" && it.plan?.tool?.name=="sh"},"the model should use the sh harness")
        val answer=events.last {it.kind=="done"}.text
        assertTrue(answer.contains("5"),"answer should name line 5: $answer")
        println("LIVE-HARNESS-PROOF read model=$model turns=${events.maxOf {it.turn}} seconds=$seconds tools=${events.count {it.kind=="tool_end"}}")
    }
    @Test fun `edit tier makes one small approved edit_file change and verifies it`() {
        val (store,home,prefs)=fixture();var approvals=0
        val (events,seconds)=run(AgentAccess.EDIT,16,"divide 함수가 b가 0일 때 ArithmeticException 대신 0을 돌려주도록 고쳐줘. edit_file 로 해당 줄만 수정하고, 끝나면 git 이 아닌 grep 이나 sed 로 결과를 확인해서 알려줘.",store,prefs) {event,engine->
            event.plan?.let {check(it.tool.name in ProjectAgentTools.allowed(AgentAccess.EDIT)) {"edit tier proposed ${it.tool.name}"}}
            if(event.kind=="approval"){
                approvals++;check(event.plan!!.tool.name=="edit_file" || event.plan.tool.name=="write_file")
                check(event.plan.resolvedTargets.all {it==home.resolve("src/Calculator.kt").toRealPath().toString()}) {"live test forbids any other target"}
                engine.approve("test","friend",event.token!!,true) {}
            }
        }
        val after=home.resolve("src/Calculator.kt").readText()
        assertTrue(after.contains("b == 0") || after.contains("b==0") || after.contains("if (b"),"edit not applied:\n$after")
        assertTrue(after.contains("fun add(a:Int,b:Int):Int = a + b") && after.contains("fun total"),"unrelated code must be untouched:\n$after")
        assertTrue(approvals>=1 && events.any {it.kind=="tool_end" && it.plan?.tool?.name=="edit_file"})
        println("LIVE-HARNESS-PROOF edit model=$model turns=${events.maxOf {it.turn}} seconds=$seconds approvals=$approvals")
    }
}
