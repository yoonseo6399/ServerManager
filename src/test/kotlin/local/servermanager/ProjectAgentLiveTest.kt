package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

/** Opt-in only. Effects are pinned to a temporary greeting file; no Discord or production data. */
@EnabledIfEnvironmentVariable(named="PROJECT_AGENT_LIVE_TEST",matches="true")
class ProjectAgentLiveTest {
    @TempDir lateinit var temporary:Path
    @Test fun `real Qwen streams Thinking separately from final answer`() {
        val llm=LocalLlm();val model=System.getenv("PROJECT_AGENT_LIVE_MODEL") ?: "14b";var thinking=""
        try {
            val answer=llm.conversation(model,listOf(ProjectAgentMessage("system","Return ONLY JSON {\"message\":\"안녕하세요\",\"tool\":null}. No tools."),ProjectAgentMessage("user","Say hello.")),true){thinking=it.thinking}
            assertFalse(answer.limited);assertTrue(thinking.isNotBlank());assertTrue(answer.thinking.isNotBlank());assertTrue(answer.text.contains("안녕하세요"))
            println("LIVE-THINKING-PROOF model=$model seconds=${answer.seconds} thinkingStreamed=true finalAnswerSeparate=true")
        }finally {llm.unload(LocalLlm.tag(model))}
    }
    @Test fun `real Qwen edits temporary greeting through independent review and exact human approval`() {
        val home=temporary.resolve("project").createDirectories().toRealPath();val note=home.resolve("note.txt");note.writeText("hello\n")
        val model=System.getenv("PROJECT_AGENT_LIVE_MODEL") ?: "14b"
        val store=ProjectAgentStore(temporary.resolve("settings"));store.addChannel(ProjectAgentChannel("test","test","owner","fixture",home.toString()))
        val settings=store.settings("test","owner",model,false,true)
        val started=System.nanoTime();val events=mutableListOf<ProjectAgentEvent>();var approved=0
        val llm=LocalLlm();val inference=ProjectAgentInference {messages,think,progress->val answer=llm.conversation(model,messages,think,progress);check(!answer.limited);answer.text}
        try {
            ProjectAgentEngine(store,{ProjectAgentTools(temporary.resolve("logs"))}).use {engine->
                engine.run("test","owner","Read note.txt using read_file. Then use write_file to replace its entire content with hi followed by a newline. Use ONLY read_file and write_file, no commands. Finish with a short Korean reply.",settings,inference,{check(System.nanoTime()-started<TimeUnit.SECONDS.toNanos(180))}) {event->
                    events.add(event)
                    event.plan?.let {plan->
                        check(plan.tool.name in setOf("read_file","write_file")) {"Live test forbids this tool: ${plan.tool.name}"}
                        check(plan.resolvedTargets.all {it==note.toString()}) {"Live test forbids any other target"}
                        if(plan.tool.name=="write_file")check(plan.tool.arguments.path("content").asText().trim()=="hi")
                    }
                    if(event.kind=="approval"){approved++;engine.approve("test","owner",event.token!!,true){}}
                    if(event.kind in setOf("message","reviewing","approval","tool_end","denied","done"))println("LIVE-AGENT ${event.kind} turn=${event.turn} ${event.text.take(500)} risk=${event.risk}")
                }
            }
            assertEquals("hi",note.readText().trim());assertTrue(approved>=1);assertTrue(events.any {it.kind=="tool_end" && it.plan?.tool?.name=="write_file"})
            println("LIVE-AGENT-PROOF model=$model approved=$approved seconds=${(System.nanoTime()-started)/1e9} temporaryFileChanged=true independentReview=true")
        }finally {llm.unload(LocalLlm.tag(model))}
    }
}
