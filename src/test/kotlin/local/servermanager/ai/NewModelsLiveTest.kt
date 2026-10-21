package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

/** Explicit opt-in: consumes local RAM and exercises the installed inference backends, never a real project. */
@EnabledIfEnvironmentVariable(named="NEW_MODELS_LIVE",matches="true")
class NewModelsLiveTest {
    @TempDir lateinit var temp:Path
    @Test fun `both selectable backends request a real file and consume its result then unload`() {
        val llm=LocalLlm();val mapper=jacksonObjectMapper();val proof=mutableListOf<Map<String,Any?>>()
        assertTrue(llm.loaded().isEmpty(),"Do not interrupt another local inference")
        try {for(id in listOf("qwen38-27b","qwen38-flash-next")) {
            val marker="ACTUAL_FILE_"+java.util.UUID.randomUUID().toString();val source="first\r\n$marker\r\nlast\r\n";val file=temp.resolve("note.txt").also {it.writeText(source)}
            val messages=mutableListOf(ProjectAgentMessage("system","You are a file assistant. Use read_file to read the requested file, then report its exact ACTUAL_FILE value using report_task. Do not guess or change files.\n${ProjectAgentNativeTools.MARKER}\nRequester access level: READ"),ProjectAgentMessage("user","Read note.txt with read_file and report the exact ACTUAL_FILE value from the result."))
            val first=llm.conversationWithBudget(id,messages,false,LocalContextBudget(output=1024))
            assertFalse(first.limited);val request=mapper.readTree(first.text);assertEquals("read_file",request.path("tool").path("name").asText());assertEquals("note.txt",request.path("tool").path("arguments").path("path").asText())
            messages.add(ProjectAgentMessage("assistant",first.text,first.nativeMessage));messages.add(ProjectAgentMessage("assistant",file.readText(),ProjectAgentNativeTools.result("read_file",file.readText())))
            val final=llm.conversationWithBudget(id,messages,false,LocalContextBudget(output=1024))
            assertFalse(final.limited);assertContains(final.text,marker);assertContentEquals(source.toByteArray(),file.readBytes());assertNotNull(final.tokensPerSecond);assertTrue(final.tokensPerSecond!!>0);assertNotNull(final.memory?.totalBytes);assertNotNull(final.memory?.modelBytes)
            if(id=="qwen38-flash-next"){assertTrue(StrataRuntime.status().running);assertTrue(llm.loaded().isEmpty(),"Ollama must be unloaded before Strata")}
            proof.add(mapOf("id" to id,"read_requested" to true,"actual_file_used" to true,"original_bytes_preserved" to true,"seconds" to first.seconds+final.seconds,"tokensPerSecond" to final.tokensPerSecond,"memory" to final.memory,"context" to final.modelContext))
            println("NEW MODEL LIVE $id read=true actual_value=true bytes_preserved=true tok/s=${final.tokensPerSecond} seconds=${first.seconds+final.seconds}")
        }}finally {llm.unloadAll();Path.of("build/new-models-live.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(proof))}
        assertFalse(StrataRuntime.status().running);assertTrue(llm.loaded().isEmpty());assertEquals(2,proof.size)
    }
    @Test fun `Flash generation can be interrupted and its owned processes are reaped`() {
        val llm=LocalLlm();assertTrue(llm.loaded().isEmpty());assertFalse(StrataRuntime.status().running)
        val failure=java.util.concurrent.atomic.AtomicReference<Exception?>();val finished=java.util.concurrent.CountDownLatch(1)
        val worker=Thread {try {llm.conversationWithBudget("qwen38-flash-next",listOf(ProjectAgentMessage("user","Read this input carefully and summarize it: "+"A sample sentence about a temporary test. ".repeat(100))),true,LocalContextBudget())}catch(e:Exception){failure.set(e)}finally{finished.countDown()}}
        try {
            worker.start();val deadline=System.nanoTime()+120_000_000_000L
            while(!StrataRuntime.status().running && worker.isAlive && System.nanoTime()<deadline)Thread.sleep(250)
            assertTrue(StrataRuntime.status().running);Thread.sleep(1000);val stop=System.nanoTime();worker.interrupt()
            assertTrue(finished.await(5,java.util.concurrent.TimeUnit.SECONDS),"generation interruption must return promptly")
            assertContains(failure.get()?.message.orEmpty(),"중단")
            Path.of("build/new-model-cancel.json").writeText(jacksonObjectMapper().writeValueAsString(mapOf("interrupted" to true,"seconds" to (System.nanoTime()-stop)/1e9)))
        }finally {worker.interrupt();worker.join(5000);llm.unloadAll()}
        assertFalse(StrataRuntime.status().running)
        java.nio.channels.FileChannel.open(StrataRuntime.root.resolve("service.lock"),java.nio.file.StandardOpenOption.WRITE).use {c->c.tryLock().use {assertNotNull(it)}}
    }

}
