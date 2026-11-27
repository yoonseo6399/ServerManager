package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import kotlin.io.path.*
import kotlin.test.*

class NativeCrashReportTest {
    @TempDir lateinit var temp:Path
    @Test fun `native fatal log is detected attached listed and summarized without arbitrary root files`() {
        val base=temp.resolve("server").createDirectory();val before=CrashReports.snapshot(base)
        val text="""# A fatal error has been detected by the Java Runtime Environment:
# SIGSEGV (0xb) at pc=0x123
# Problematic frame:
# C  [spark-profiler.so+0x2b1c8] ThreadFilter::accept(int)+0x10
"""
        val file=base.resolve("hs_err_pid123.log").also {it.writeText(text)}
        base.resolve("secret.log").writeText("private")
        assertEquals(file.name,CrashReports.changed(base,before));assertContentEquals(text.toByteArray(),CrashReports.attachment(base,file.name))
        assertFalse(CrashReports.validName("../hs_err_pid123.log"));assertFalse(CrashReports.validName("secret.log"))
        val logs=LogFiles(temp,base);assertEquals(listOf("server/hs_err_pid123.log"),logs.list().map {it.path})
        assertContains(logs.page("server/hs_err_pid123.log").events.joinToString {it.text},"SIGSEGV")
        val summary=DiscordErrorBuffer.fromReport(text.toByteArray())!!
        assertEquals("JVM 네이티브 크래시",summary.title);assertContains(summary.description,"ThreadFilter::accept")
        Files.createSymbolicLink(base.resolve("hs_err_pid456.log"),file)
        assertNull(CrashReports.attachment(base,"hs_err_pid456.log"));assertEquals(1,CrashReports.snapshot(base).size)
    }
}
