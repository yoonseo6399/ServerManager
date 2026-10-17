package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Real Ollama, temp files only, no Discord sends. Deliberately opt-in. */
@EnabledIfEnvironmentVariable(named="PROJECT_AGENT_ROUTING_LIVE",matches="true")
class ProjectAgentRoutingLiveTest {
    @TempDir lateinit var temp:Path
    @Test fun `real Qwen reads source then answers conversation memory without file tools`() {
        val size=System.getenv("QWEN_LIVE_SIZE") ?: "30b";val llm=LocalLlm()
        val home=temp.resolve("project").createDirectories().toRealPath();val file=home.resolve("안녕.txt")
        val marker="RAW_SOURCE_6C92";file.writeText("$marker\n"+"원문을 읽은 경우에만 이 내용을 보고하세요. 실제 파일 데이터입니다.\n".repeat(30))
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("live","channel","owner","fixture",home.toString()))
        val preferences=ProjectAgentPreferences(model=size,think=true,explicitPermission=true)
        var calls=0;var summaries=0;var rawSeen=false
        val inference=ProjectAgentInference {messages,think,progress->
            calls++;if(messages.first().content.startsWith("Tool-result"))summaries++
            if(messages.any {it.content.contains("UNTRUSTED TOOL RESULT read_file") && it.content.contains(marker)})rawSeen=true
            println("LIVE-REQUEST call=$calls type=${messages.first().content.take(28)} think=$think")
            val answer=llm.conversation(size,messages,think,progress)
            println("LIVE-REPLY call=$calls seconds=${answer.seconds} input=${answer.inputTokens} output=${answer.tokens} limited=${answer.limited}")
            println("LIVE-TEXT call=$calls ${answer.text.take(400)}")
            check(!answer.limited) {"실모델 출력 한도 초과"};answer.text
        }
        val events=mutableListOf<ProjectAgentEvent>();val started=System.nanoTime()
        try {
            ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
                fun emit(event:ProjectAgentEvent){
                    events.add(event)
                    event.plan?.let {p->check(p.tool.name=="read_file" && p.resolvedTargets==listOf(file.toString())) {"Live test forbids tool/target: ${p.tool.name}"}}
                    if(event.kind=="approval")engine.approve("channel","owner",event.token!!,true){}
                    if(event.kind in setOf("message","tool_end","done","denied","status"))println("LIVE-EVENT ${event.kind} turn=${event.turn} ${event.text.take(400)}")
                }
                engine.run("channel","owner","테스터","안녕.txt를 read_file 도구로 실제로 읽고 파일에 있는 RAW_SOURCE_ 로 시작하는 표식을 그대로 알려줘. 도구는 read_file만 사용하고 원문을 요약해 읽지 마.",preferences,inference,{},::emit)
                assertTrue(events.any {it.kind=="tool_end" && it.plan?.tool?.name=="read_file"});assertTrue(rawSeen);assertEquals(0,summaries)
                assertTrue(events.last().kind=="done" && events.last().text.contains(marker))
                events.clear()
                engine.run("channel","owner","테스터","파일을 다시 읽으라는 말이 아니야. 지금 문맥에 있는 우리 대화에서 내가 방금 무엇을 요청했는지 알려줘. 파일 도구를 쓰지 말고 대화 기록만 보고 답해줘.",preferences,inference,{},::emit)
                assertFalse(events.any {it.kind=="tool_start"});assertTrue(events.last().kind=="done" && !events.last().text.startsWith("작업 중단"))
            }
            println("LIVE-PROOF model=$size actualFileRead=true rawSourceSeen=true readSummaryCalls=$summaries memoryFollowupTools=0 calls=$calls seconds=${(System.nanoTime()-started)/1e9}")
        }finally{llm.unload(LocalLlm.tag(size))}
    }
    @Test fun `real Qwen chains read append write reread and preserves original content`() {
        val size=System.getenv("QWEN_LIVE_SIZE") ?: "30b";val llm=LocalLlm()
        val home=temp.resolve("chain-project").createDirectories().toRealPath();val note=home.resolve("chain.txt")
        val original="existing-line\nvalue=42\n";val addition="CHAIN_VERIFIED_7D31";note.writeText(original)
        val store=ProjectAgentStore(temp.resolve("chain-settings"));store.addChannel(ProjectAgentChannel("live","chain","owner","fixture",home.toString()))
        val events=mutableListOf<ProjectAgentEvent>();var approvals=0;var calls=0;val started=System.nanoTime()
        val model=ProjectAgentInference {messages,think,progress->
            calls++;println("CHAIN-REQUEST call=$calls type=${messages.first().content.take(25)}")
            val answer=llm.conversation(size,messages,think,progress)
            println("CHAIN-REPLY call=$calls seconds=${answer.seconds} text=${answer.text.take(500)}")
            check(!answer.limited);answer.text
        }
        try {
            ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("chain-logs"))}).use {engine->
                engine.run("chain","owner","테스터","chain.txt를 먼저 read_file로 읽어줘. 기존 내용을 모두 보존하면서 파일 끝에 CHAIN_VERIFIED_7D31 한 줄과 줄바꿈을 추가하도록 write_file로 저장해줘. 저장 후 반드시 read_file로 다시 읽어서 기존 내용 보존과 추가된 문구를 검증한 뒤 최종 보고해줘. 도구는 read_file과 write_file만 사용해.",ProjectAgentPreferences(model=size,think=true,explicitPermission=true),model,{}) {event->
                    events.add(event)
                    event.plan?.let {plan->
                        check(plan.tool.name in setOf("read_file","write_file") && plan.resolvedTargets==listOf(note.toString())) {"Live chain forbids another tool/target"}
                        if(plan.tool.name=="write_file")check(plan.tool.arguments.path("content").asText()==original+addition+"\n") {"Live chain refuses loss of original content or a wrong edit"}
                    }
                    if(event.kind=="approval"){approvals++;engine.approve("chain","owner",event.token!!,true){}}
                    if(event.kind in setOf("tool_end","done","status","denied"))println("CHAIN-EVENT ${event.kind} turn=${event.turn} ${event.text.take(300)}")
                }
            }
            val order=events.filter {it.kind=="tool_end"}.map {it.plan!!.tool.name}
            val write=order.indexOf("write_file")
            assertContentEquals((original+addition+"\n").toByteArray(Charsets.UTF_8),note.readBytes(),"Original bytes and exact requested position must match before tool-sequence checks")
            assertTrue(write>0 && order.take(write).contains("read_file") && order.drop(write+1).contains("read_file"),"Actual tool chain: $order")
            assertEquals(original+addition+"\n",note.readText());assertTrue(approvals>0)
            assertTrue(events.last().kind=="done" && !events.last().text.startsWith("작업 중단"))
            println("CHAIN-PROOF model=$size tools=$order approvals=$approvals originalPreserved=true addedLineVerified=true calls=$calls seconds=${(System.nanoTime()-started)/1e9}")
        }finally{llm.unload(LocalLlm.tag(size))}
    }

}
