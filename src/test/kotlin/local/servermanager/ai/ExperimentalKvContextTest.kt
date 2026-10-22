package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** No inference process, model loading, network, install or global OS mutation. */
class ExperimentalKvContextTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    @Test fun `64K settings preserve output budget and old defaults and survive reload`() {
        assertFalse(LocalContextBudget().experimentalKvCompression)
        val budget=LocalContextBudget(65536,2048,experimentalKvCompression=true)
        assertEquals(63232,budget.input);assertTrue(budget.helper().experimentalKvCompression)
        assertEquals(budget,mapper.readValue(mapper.writeValueAsString(budget),LocalContextBudget::class.java))
        val settings=QwenSettings(temp.resolve("qwen"))
        settings.save(QwenConfig(model="qwen38-27b",experimentalKvCompression=true))
        assertTrue(QwenSettings(temp.resolve("qwen")).config().experimentalKvCompression)
        settings.save(QwenConfig());assertFalse(settings.config().experimentalKvCompression)
        val store=ProjectAgentStore(temp.resolve("agent"))
        store.addChannel(ProjectAgentChannel("g","c","u",null,temp.toString()))
        store.context("c","u",window=65536,output=2048,limit=50000)
        val loaded=ProjectAgentStore(temp.resolve("agent")).channel("c")!!
        assertEquals(65536,loaded.executionWindow);assertEquals(50000,loaded.contextLimit)
        assertFails {LocalContextBudget(65537)};assertFails {store.context("c","u",limit=64000)}
    }
    @Test fun `Strata runtime config sets exact16K and rotation cache without changing install source`() {
        val source=temp.resolve("installed.json")
        source.writeText("""{"args":["--pack","/pack","--max-context","8192","--kv","int8"],"model_name":"original"}""")
        val original=source.readText()
        val files=StrataLaunchFiles(temp)
        val generated=files.prepareConfig(source,16384,true)
        val body=mapper.readTree(generated.readText());val args=body.path("args").map {it.asText()}
        assertEquals(1,args.count {it=="--max-context"});assertEquals("16384",args[args.indexOf("--max-context")+1])
        assertEquals("q4_0",args[args.indexOf("--kv")+1]);assertEquals("original",body.path("model_name").asText())
        assertEquals(original,source.readText());files.close();assertFalse(generated.exists());assertTrue(source.exists())
        StrataLaunchFiles(temp).use {fresh->
            val off=mapper.readTree(fresh.prepareConfig(source,8192,false).readText()).path("args").map {it.asText()}
            assertEquals("fp16",off[off.indexOf("--kv")+1]);assertFails {fresh.prepareConfig(source,65536,true)}
        }
    }
    @Test fun `native command pins64K single slot turbo4 loopback and key file without a secret argv`() {
        val args=TurboQuantRuntime.arguments(temp.resolve("llama-server"),temp.resolve("model.gguf"),65536,"fixture",temp.resolve("key"))
        fun value(option:String)=args[args.indexOf(option)+1]
        assertEquals("65536",value("--ctx-size"));assertEquals("65536",value("--fit-ctx"))
        assertEquals("1",value("--parallel"));assertEquals("q8_0",value("--cache-type-k"));assertEquals("turbo4",value("--cache-type-v"))
        assertEquals("127.0.0.1",value("--host"));assertContains(args,"--api-key-file");assertFalse(args.contains("--api-key"))
        assertFalse(args.contains("--kv-stream-arena-mib"),"CUDA-only streaming is not a Metal feature")
    }
    @Test fun `Pi envelope accepts a long64K candidate but rejects an oversized one`() {
        fun decode(text:String)=PiWire.decode(listOf(ProjectAgentMessage("system",PiWire.PREFIX+mapper.writeValueAsString(mapOf("messages" to listOf(mapOf("role" to "user","content" to text)))))))
        assertNotNull(decode("context ".repeat(40000)))
        assertFails {decode("x".repeat(PiWire.MAX_REQUEST_BYTES))}
    }
}
