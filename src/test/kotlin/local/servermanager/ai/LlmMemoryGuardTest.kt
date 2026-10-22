package local.servermanager

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class LlmMemoryGuardTest {
    @Test fun `warning allows and critical stops before inference but user override allows critical`() {
        LlmMemoryGuard(false){LlmMemory(pressure="주의")}.check()
        val error=assertFailsWith<IllegalStateException> {LlmMemoryGuard(false){LlmMemory(pressure="위험")}.check()}
        assertContains(error.message!!,LlmMemoryGuard.MARKER)
        assertNotNull(LlmMemoryGuard.reason(IllegalStateException("remote failure",error)))
        LlmMemoryGuard(true){error("explicit override must not probe pressure")}.check()
        assertFalse(LocalContextBudget().allowCriticalMemory)
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val wire=mapper.writeValueAsString(LocalContextBudget(16384,2048,true))
        assertTrue(mapper.readValue(wire,LocalContextBudget::class.java).allowCriticalMemory)
    }
    @Test fun `critical during pending load interrupts waiting and override is not persistent`() {
        var samples=0
        val guard=LlmMemoryGuard(false){LlmMemory(pressure=if(samples++==0)"주의" else "위험")}
        assertFailsWith<IllegalStateException> {guard.await(CompletableFuture<String>(),5)}
        assertEquals(2,samples)
        assertEquals("ok",LlmMemoryGuard(true).await(CompletableFuture.completedFuture("ok"),1))
        assertFailsWith<IllegalStateException> {LlmMemoryGuard(false){LlmMemory(pressure="위험")}.check()}
    }
    @Test fun `run and context slash commands expose selectable execution windows`() {
        val command=DiscordProjectAgent.command()
        for(name in listOf("run","context")) {
            val option=command.subcommands.first {it.name==name}.options.first {it.name=="window"}
            assertFalse(option.isRequired);assertEquals(listOf(8192L,16384L,32768L,65536L),option.choices.map {it.asLong})
        }
    }
}
