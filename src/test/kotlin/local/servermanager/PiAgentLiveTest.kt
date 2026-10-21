package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.net.URI
import kotlin.io.path.*
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="PI_AGENT_LIVE",matches="true")
class PiAgentLiveTest {
    @TempDir lateinit var temp:Path
    @Test @Timeout(900) fun `real qwen27 pi preserves original bytes and inserts requested line`() {
        val profile=temp.resolve("profile").also {it.createDirectories()};val home=temp.resolve("project").also {it.createDirectories()};val file=home.resolve("notes.txt")
        val original="HEAD\r\nvalue=1\r\nTAIL\r\n";file.writeBytes(original.toByteArray());val marker="PI_LIVE_VERIFIED_8F27"
        val store=ProjectAgentStore(profile);store.addChannel(ProjectAgentChannel("guild","channel","user",null,home.toString(),contextLimit=10000,executionWindow=16384,outputTokens=2048));val prefs=store.settings("guild","user","qwen38-27b",false,true)
        val llm=LocalLlm(URI(System.getenv("PI_AGENT_TEST_OLLAMA_URL") ?: "http://127.0.0.1:11435"));val frames=mutableListOf<ProjectAgentEvent>();val usage=mutableListOf<QwenUsage>();var raw:String?=null;var limited=false;var current:QwenUsage?=null
        val model=object:ProjectAgentInference {
            override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
                val a=llm.conversationWithBudget("qwen38-27b",messages,think,LocalContextBudget(16384,2048),progress);raw=a.nativeMessage;limited=a.limited;current=QwenUsage(a.inputTokens,a.tokens,a.inputTokens?.toInt() ?: 0,a.modelContext,executionWindow=16384,inputBudget=14080,outputReserved=2048,tokensPerSecond=a.tokensPerSecond,memory=a.memory);usage.add(current!!);return a.text
            }
            override fun usage()=current
            override fun nativeMessage()=raw
            override fun limited()=limited
        }
        val before=LlmMemory.sample();val start=System.nanoTime();val events=java.util.Collections.synchronizedList(frames)
        try {
            ProjectAgentEngine(store,{ProjectAgentTools(profile.resolve("logs"))},runtime=PiAgentRuntimeTest.shared).use {engine->
                engine.run("channel","user","실행 검증 사용자","notes.txt를 read로 읽고, edit로 value=1 줄 바로 다음에 $marker 한 줄을 추가한 뒤 read로 다시 확인해줘. 다른 원문과 CRLF, 마지막 개행은 그대로 유지해. 실제 도구로 수정한 후 한국어로 짧게 보고해줘.",prefs,model,ProjectAgentLimits(12),{},{e->events.add(e);if(e.kind=="approval")engine.approve("channel","user",e.token!!,true){}})
            }
            val actual=file.readBytes();val expected=original.replace("value=1\r\n","value=1\r\n$marker\r\n").toByteArray();assertContentEquals(expected,actual)
            assertTrue(events.any {it.kind=="tool_start" && it.plan?.tool?.name=="edit"});assertTrue(events.count {it.kind=="tool_start" && it.plan?.tool?.name=="read"}>=2)
            assertTrue(events.any {it.kind=="done"})
        }finally {
            runCatching {llm.unloadAll()};val out=Path.of("build/pi-live-evidence").also {it.createDirectories()};out.resolve("result.json").writeText(jacksonObjectMapper().writeValueAsString(mapOf("model" to "qwen3.8:27b-q4_K_M","framework" to "pi1.3.0","seconds" to (System.nanoTime()-start)/1e9,"beforeMemory" to before,"afterMemory" to LlmMemory.sample(),"original" to original,"actual" to file.readBytes().toString(Charsets.UTF_8),"expected" to original.replace("value=1\r\n","value=1\r\n$marker\r\n"),"events" to events,"usage" to usage)))
        }
    }
}
