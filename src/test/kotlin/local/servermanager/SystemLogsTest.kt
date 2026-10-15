package local.servermanager

import org.junit.jupiter.api.Test
import kotlin.test.*

class SystemLogsTest {
    @Test fun `pagination advances across filtered rows and matches time component and text`() {
        val logs=SystemLogBuffer(10)
        logs.append(1000,"INFO","network.Relay","Ready")
        logs.append(2000,"WARN","network.Relay","Retry")
        logs.append(3000,"ERROR","storage.Store","Disk failed")
        val first=logs.page(limit=1)
        assertEquals(listOf("Ready"),first.events.map {it.message});assertEquals(1L,first.nextCursor)
        assertEquals(listOf("Retry","Disk failed"),logs.page(after=first.nextCursor).events.map {it.message})
        val filtered=logs.page(level="WARN",component="RELAY",since="1970-01-01T00:00:02Z",query="retry")
        assertEquals(listOf(2L),filtered.events.map {it.seq});assertEquals(3L,filtered.nextCursor)
        assertTrue(logs.page(after=filtered.nextCursor,level="WARN").events.isEmpty())
        assertEquals(3L,logs.page(query="missing").nextCursor)
    }
    @Test fun `rotation and future cursors explicitly reset and sessions differ`() {
        val logs=SystemLogBuffer(2)
        repeat(4){logs.append(it.toLong(),"INFO","network.Relay","row $it")}
        val page=logs.page(after=1);assertTrue(page.reset);assertEquals(3L,page.oldestCursor);assertEquals(listOf(3L,4L),page.events.map {it.seq})
        assertFalse(logs.page(after=2).reset);assertTrue(logs.page(after=999).reset)
        assertNotEquals(logs.sessionId,SystemLogBuffer().sessionId)
        assertTrue(SystemLogBuffer().page().events.isEmpty())
    }
    @Test fun `invalid query bounds are rejected`() {
        val logs=SystemLogBuffer()
        for(run in listOf<()->Unit>({logs.page(after=-1)},{logs.page(limit=0)},{logs.page(limit=501)},{logs.page(level="DEBUG")},{logs.page(component="x".repeat(161))},{logs.page(query="x".repeat(201))},{logs.page(since="not-a-time")}))assertFails {run()}
    }
    @Test fun `authentication content and private URLs never leave the diagnostic buffer`() {
        val logs=SystemLogBuffer()
        listOf("AuthApi","GoogleCalendar","Gmail","Qwen","LocalLlm","MainAgent","http.headers").forEach {logs.append(0,"ERROR",it,"private text")}
        logs.append(0,"DEBUG","network.Relay","debug")
        logs.append(0,"INFO","network.Relay","Authorization: Bearer very-secret")
        logs.append(0,"WARN","network.Relay","https://person:credential@example.com/private/mail?code=private#fragment")
        logs.append(0,"ERROR","network.Relay","request.body personal mail")
        val page=logs.page();assertEquals(3,page.events.size)
        val output=page.events.joinToString {it.message}
        for(secret in listOf("very-secret","credential","private/mail","code=private","fragment","personal mail"))assertFalse(secret in output,secret)
        assertTrue("https://example.com/[경로 숨김]" in output)
        assertEquals("no control",SystemLogBuffer.redact("no\u0000 control"))
        assertTrue(SystemLogBuffer.redact("x".repeat(9000)).length<=4000)
    }
}
