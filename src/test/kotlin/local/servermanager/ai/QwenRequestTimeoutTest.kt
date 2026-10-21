package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class QwenRequestTimeoutTest {
    @TempDir lateinit var temp:Path
    @Test fun `timeout persists separately so old settings keep runner and model after rollback`() {
        val settings=QwenSettings(temp);assertEquals(20,settings.config().requestTimeoutMinutes)
        settings.save(QwenConfig("runner","qwen38-27b",60));assertEquals(QwenConfig("runner","qwen38-27b",60),QwenSettings(temp).config())
        val legacy=jacksonObjectMapper().readTree(temp.resolve("qwen.json").readText());assertEquals(setOf("runnerId","model"),legacy.fieldNames().asSequence().toSet())
        assertFalse(settings.valid(QwenConfig(requestTimeoutMinutes=0)));assertFalse(settings.valid(QwenConfig(requestTimeoutMinutes=241)))
        temp.resolve("qwen-execution.json").writeText("{\"requestTimeoutMinutes\":0}");assertEquals(20,settings.config().requestTimeoutMinutes);assertEquals("runner",settings.config().runnerId)
    }
    @Test fun `timeout profile validates and roundtrips to inference runner without changing helper budget`() {
        val budget=LocalContextBudget(16384,2048,requestTimeoutSeconds=3600)
        val mapper=jacksonObjectMapper();val roundtrip=mapper.readValue(mapper.writeValueAsString(budget),LocalContextBudget::class.java)
        assertEquals(3600,roundtrip.requestTimeoutSeconds);assertEquals(3600,budget.helper().requestTimeoutSeconds)
        assertFails {LocalContextBudget(requestTimeoutSeconds=0)};assertFails {LocalContextBudget(requestTimeoutSeconds=14401)}
    }
}
