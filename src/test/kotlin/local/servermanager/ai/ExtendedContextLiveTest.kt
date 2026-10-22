package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

/** Explicitly authorized RAM-consuming experiment. No project tools execute. */
@EnabledIfEnvironmentVariable(named="EXTENDED_CONTEXT_LIVE",matches="true")
class ExtendedContextLiveTest {
    @Test @Timeout(1800) fun `27B64K real short and long retrieval with compressed cache`()=check("qwen38-27b",65536,2300)
    @Test @Timeout(1800) fun `Flash16K real short and long retrieval with rotated cache`()=check("qwen38-flash-next",16384,430)
    private fun check(id:String,window:Int,lines:Int) {
        val llm=LocalLlm();val json=jacksonObjectMapper()
        val output=Path.of("build/extended-context-live").createDirectories().resolve("$id.json")
        val records=linkedMapOf<String,Any?>("model" to id,"requestedWindow" to window,"compressed" to true,"before" to LlmMemory.sample())
        assertTrue(llm.loaded().isEmpty(),"Never interrupt another Ollama request/model")
        try {
            if(id=="qwen38-27b" && !TurboQuantRuntime.status().ready){
                TurboQuantRuntime.install();val deadline=System.nanoTime()+360_000_000_000L
                while(TurboQuantRuntime.status().installing && System.nanoTime()<deadline)Thread.sleep(250)
                assertTrue(TurboQuantRuntime.status().ready,TurboQuantRuntime.status().message)
            }
            fun ask(text:String):LocalLlm.Answer {
                val wire=PiWire.PREFIX+json.writeValueAsString(mapOf("messages" to listOf(mapOf("role" to "user","content" to text)),"tools" to emptyList<Any>()))
                return llm.conversationWithBudget(id,listOf(ProjectAgentMessage("system",wire)),false,LocalContextBudget(window,512,requestTimeoutSeconds=1200,experimentalKvCompression=true))
            }
            val first=ask("Reply with exactly KV_READY and nothing else.")
            records["short"]=first.copy(nativeMessage=null,thinking="")
            records["afterShort"]=LlmMemory.sample();output.writeText(json.writerWithDefaultPrettyPrinter().writeValueAsString(records))
            assertContains(first.text,"KV_READY");assertFalse(first.limited)
            val marker="KV_MEMORY_"+UUID.randomUUID().toString().replace("-","")
            val filler=(0 until lines).map {"Record $it: the silver orchard contains twelve blue baskets and the station keeps a separate daily inventory."}.toMutableList()
            filler.add(lines/3,"The unique verification phrase is $marker. Remember this exact phrase.")
            val text="Read the records and find the unique verification phrase.\n"+filler.joinToString("\n")+"\nReturn only the exact unique verification phrase, with no explanation."
            val final=ask(text)
            records["long"]=final.copy(nativeMessage=null,thinking="");records["expected"]=marker
            records["needleMatched"]=marker in final.text;records["afterLong"]=LlmMemory.sample()
            assertContains(final.text,marker);assertFalse(final.limited);assertNotNull(final.inputTokens)
            assertTrue(final.inputTokens!!>if(window==65536)40000 else 7000,"Long-input test must actually consume a large prompt")
        }catch(e:Throwable){records["failure"]=e.message;throw e}
        finally {
            runCatching {llm.unloadAll()}.onFailure {records["cleanupError"]=it.message}
            records["afterCleanup"]=LlmMemory.sample();output.writeText(json.writerWithDefaultPrettyPrinter().writeValueAsString(records))
        }
    }
}
