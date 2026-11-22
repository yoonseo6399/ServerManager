package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class LogSearchTest {
    @TempDir lateinit var directory:Path
    private fun completed(search:LogSearch,id:String):LogSearch.View {
        val deadline=System.nanoTime()+30_000_000_000L
        while(true){val value=search.view(id);if(value.state in setOf("COMPLETE","FAILED","CANCELLED")){assertEquals("COMPLETE",value.state,value.error);return value};check(System.nanoTime()<deadline);Thread.sleep(10)}
    }
    @Test fun `whole million-line file is searched without scrolling or retaining raw results`() {
        directory.resolve("console.log").bufferedWriter().use {out->for(n in 1..1_200_000)out.appendLine(if(n==1_199_999)"[12:00:00] [Server thread/INFO] [Test]: far-away needle" else "ordinary line $n")}
        val search=LogSearch(directory);val job=search.start(LogSearch.Request("console.log","needle"));val done=completed(search,job.id)
        assertEquals(1_200_000L,done.scanned);assertEquals(1L,done.matches);assertEquals(1_199_999L,done.rows.single().no);assertEquals(done.totalBytes,done.bytes)
        search.cancel(job.id)
    }
    @Test fun `hidden logger and keyword records including stack continuations never match`() {
        directory.resolve("console.log").writeText("[12:00:00] [Server thread/ERROR] [Hidden]: needle hidden\n\tat pkg.Class.needle(File.java:1)\n[12:00:01] [Server thread/ERROR] [Visible]: discard needle\n\tat pkg.Class.needle(File.java:2)\n[12:00:02] [Server thread/ERROR] [Visible]: needle visible\n")
        val filters=LogFilterConfig(rules=LogFilterRules(listOf("discard"),listOf("hidden"),false))
        val search=LogSearch(directory);val done=completed(search,search.start(LogSearch.Request("console.log","needle",filters=filters)).id)
        assertEquals(1L,done.matches);assertEquals(5L,done.rows.single().no);assertEquals("Visible",done.rows.single().logger)
    }
    @Test fun `only-debug filters leave information logs searchable and gzip uses the same rules`() {
        val folder=directory.resolve("server/logs").createDirectories();java.util.zip.GZIPOutputStream(folder.resolve("old.log.gz").outputStream()).bufferedWriter().use {it.write("[12:00:00] [Server thread/DEBUG] [Hidden]: needle\n[12:00:00] [Server thread/INFO] [Hidden]: needle\n")}
        val search=LogSearch(directory);val result=completed(search,search.start(LogSearch.Request("server/logs/old.log.gz","needle",filters=LogFilterConfig(rules=LogFilterRules(mods=listOf("Hidden"))))).id)
        assertEquals(1L,result.matches);assertEquals(2L,result.rows.single().no)
    }
    @Test fun `escaped search pages fit the nested runner websocket response budget`() {
        directory.resolve("console.log").bufferedWriter().use {out->repeat(200){out.appendLine("[12:00:00] [Server thread/INFO] [Visible]: needle "+"\\".repeat(1000))}}
        val search=LogSearch(directory);val done=completed(search,search.start(LogSearch.Request("console.log","needle")).id)
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper();val nested=mapper.writeValueAsString(mapOf("detail" to mapper.writeValueAsString(done)))
        assertTrue(nested.toByteArray().size<200000);assertEquals(200L,done.matches);assertNotNull(done.next);search.cancel(done.id)
    }
    @Test fun `replacement invalidates search and cancellation removes it`() {
        directory.resolve("console.log").writeText("needle\n");val search=LogSearch(directory);val first=search.start(LogSearch.Request("console.log","needle"));completed(search,first.id)
        directory.resolve("next").writeText("replacement\n");java.nio.file.Files.move(directory.resolve("next"),directory.resolve("console.log"),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertFailsWith<IllegalStateException>{search.view(first.id)}
        search.cancel(first.id);assertFailsWith<IllegalStateException>{search.view(first.id)}
        assertFailsWith<IllegalArgumentException>{search.start(LogSearch.Request("../console.log","x"))}
    }
    @Test fun `live file resets on launch and survives a controller reattach without losing old history`() {
        directory.resolve("console.log").writeText("old history\n")
        val first=LiveConsole.open(directory,"100:one",true);first.appendText("current\n")
        assertEquals("current\n",LiveConsole.open(directory,"100:one").readText())
        assertEquals("",LiveConsole.open(directory,"101:two",true).readText());assertEquals("old history\n",directory.resolve("console.log").readText())
    }
    @Test fun `migration ignores reattachment separators and never imports unknown cumulative history`() {
        directory.resolve("console.log").writeText("old\n--- 2026-11-21T10:00:00Z ---\nlast session\n--- 2026-11-21T11:00:00Z · 상주 서비스에서 다시 연결 ---\ncontinued\n")
        assertContains(LiveConsole.open(directory,"legacy").readText(),"last session")
        assertFalse(directory.resolve("live.log").readText().contains("old"))
        directory.resolve("console.log").writeText("unknown history\n");directory.resolve(".live-console-session").deleteExisting()
        assertEquals("",LiveConsole.open(directory,"other").readText())
    }
}
