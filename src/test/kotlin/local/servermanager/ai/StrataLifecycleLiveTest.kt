package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="STRATA_LIFECYCLE_LIVE",matches="true")
class StrataLifecycleLiveTest {
    @Test fun `stop and restart repeatedly with an interrupted stale run still present`() {
        val llm=LocalLlm();assertTrue(llm.loaded().isEmpty(),"Do not interrupt an existing model");assertFalse(StrataRuntime.status().running)
        val runtime=StrataRuntime.root.resolve("runtime");val runs=mutableListOf<String>()
        fun dirs()=runtime.listDirectoryEntries("run-*").toSet()
        val before=dirs()
        // A previous JVM's stale ready file remains intact; the new process must ignore it.
        val stale=StrataLaunchFiles(runtime);stale.ready.writeText("stale test record")
        try {
            repeat(3) {
                assertEquals(8192,StrataRuntime.start());assertTrue(StrataRuntime.status().running)
                val active=dirs()-before-stale.directory;assertEquals(1,active.size)
                val run=active.single();assertTrue(run.resolve("ready.json").isRegularFile());assertTrue(runs.add(run.fileName.toString()));assertEquals(runs.size,runs.toSet().size)
                if(it==2) {
                    val answer=llm.chat("qwen38-flash-next","Reply with exactly READY_OK and nothing else.",false)
                    assertEquals("READY_OK",answer.text.trim());assertNotNull(answer.tokensPerSecond)
                }
                StrataRuntime.stop();assertFalse(StrataRuntime.status().running);assertEquals(before+stale.directory,dirs());assertEquals("stale test record",stale.ready.readText())
            }
        }finally{StrataRuntime.stop();stale.close()}
        Path.of("build/strata-lifecycle-live.json").writeText(jacksonObjectMapper().writeValueAsString(mapOf("starts" to 3,"stale_ignored" to true,"owned_ready_cleaned" to true,"real_reply" to "READY_OK","distinct_directories" to runs)))
    }
}
