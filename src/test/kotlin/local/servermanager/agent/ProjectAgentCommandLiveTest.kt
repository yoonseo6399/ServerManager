package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Reproduce explicit command discovery/execution without touching the user's project or Discord. */
@EnabledIfEnvironmentVariable(named="PROJECT_AGENT_COMMAND_LIVE",matches="true")
class ProjectAgentCommandLiveTest {
    @TempDir lateinit var temp:Path
    @Test fun `requested python command is selected executed once and reported from actual output`() {
        val model=System.getenv("PROJECT_AGENT_LIVE_MODEL") ?: "30b"
        val evidence=Path.of("build/bot-followup-evidence").createDirectories()
        val mapper=jacksonObjectMapper();val home=temp.resolve("project").createDirectories().toRealPath()
        val first="first original\n";val second="second original\n"
        home.resolve("a.txt").writeText(first);home.resolve("b.txt").writeText(second)
        home.resolve("merge_files.py").writeText("from pathlib import Path\np=Path('.')\np.joinpath('ab.txt').write_bytes(p.joinpath('a.txt').read_bytes()+p.joinpath('b.txt').read_bytes())\nprint('COMMAND_PROBE_6812: merged')\n")
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u","probe",home.toString()))
        val prefs=store.settings("g","u",model,true,true);val events=mutableListOf<ProjectAgentEvent>();val started=System.nanoTime()
        val llm=LocalLlm(trace={kind,value->evidence.resolve("command-wire-$model.jsonl").toFile().appendText(mapper.writeValueAsString(mapOf("kind" to kind,"json" to mapper.readTree(value)))+"\n")})
        val inference=object:ProjectAgentInference {
            var native:String?=null
            override fun nativeTools()=true
            override fun nativeMessage()=native
            override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
                val main=messages.first().content.startsWith("You are 루오노타르")
                val answer=llm.conversationWithBudget(model,messages,think,LocalContextBudget().let {if(main)it else it.helper()},progress)
                native=answer.nativeMessage
                evidence.resolve("command-actor-$model.jsonl").toFile().appendText(mapper.writeValueAsString(mapOf("purpose" to messages.first().content.take(80),"messages" to messages,"response" to answer.text,"native" to answer.nativeMessage,"input" to answer.inputTokens,"output" to answer.tokens,"seconds" to answer.seconds))+"\n")
                return answer.text
            }
        }
        try {
            ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
                engine.run("c","u","시험 사용자","run_command 도구를 사용해서 python3 merge_files.py를 한 번 실행해. 조회 도구는 쓰지 마. 실제 출력과 종료 코드만 보고해.",prefs,inference,ProjectAgentLimits(12,AgentAccess.OWNER),{
                    check((System.nanoTime()-started)/1e9<300){"5 minute live task limit"}
                }) {event->
                    events.add(event)
                    if(event.kind=="approval") {
                        assertEquals("run_command",event.plan!!.tool.name)
                        assertEquals("python3 merge_files.py",event.plan.tool.arguments.path("command").asText())
                        engine.approve("c","u",event.token!!,true){}
                    }
                    if(event.kind in setOf("tool_end","done"))println("COMMAND LIVE ${event.kind} turn=${event.turn} tool=${event.plan?.tool?.name} ${event.text}")
                }
            }
            val calls=events.filter {it.kind=="tool_end"}
            val bytesOk=home.resolve("ab.txt").exists() && home.resolve("ab.txt").readBytes().contentEquals((first+second).toByteArray())
            println("COMMAND FILE PROOF model=$model saved_expected_bytes=$bytesOk original_a_preserved=${home.resolve("a.txt").readText()==first} original_b_preserved=${home.resolve("b.txt").readText()==second} sequence=${calls.map {it.plan?.tool?.name}}")
            assertTrue(bytesOk,"requested command did not create expected merged output")
            assertEquals(first,home.resolve("a.txt").readText());assertEquals(second,home.resolve("b.txt").readText())
            assertEquals(listOf("run_command"),calls.map {it.plan?.tool?.name})
            assertContains(calls.single().text,"COMMAND_PROBE_6812")
            val done=events.last {it.kind=="done"}.text;assertFalse(done.startsWith("작업 중단"),done);assertContains(done,"COMMAND_PROBE_6812")
        }finally {llm.unload(LocalLlm.tag(model))}
    }
}
