package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class StrataLaunchFilesTest {
    @TempDir lateinit var root:Path
    @Test fun `legacy and interrupted ready files cannot block the next invocation`() {
        val legacy=root.resolve("ready.json").also {it.writeText("old process")}
        val interrupted=StrataLaunchFiles(root);interrupted.ready.writeText("interrupted process")
        val next=StrataLaunchFiles(root)
        assertNotEquals(interrupted.ready,next.ready);assertFalse(next.ready.exists())
        next.ready.writeText("new process");next.close()
        assertFalse(next.directory.exists());assertEquals("interrupted process",interrupted.ready.readText());assertEquals("old process",legacy.readText())
        interrupted.close();assertTrue(legacy.exists())
    }
    @Test fun `cleanup is owned and idempotent across repeated starts and stops`() {
        val unrelated=root.resolve("keep.txt").also {it.writeText("keep")};val directories=mutableSetOf<Path>()
        repeat(4){val files=StrataLaunchFiles(root);assertTrue(directories.add(files.directory));assertFalse(files.ready.exists());files.ready.writeText("ready");files.close();files.close();assertFalse(files.directory.exists())}
        assertEquals("keep",unrelated.readText())
    }
}
