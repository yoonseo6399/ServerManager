package local.servermanager

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.*

class TransferPrepProgressTest {
    @Test fun `scan stats count reused and newly read files`() {
        val dir=Files.createTempDirectory("scan-stats")
        repeat(3) {Files.writeString(dir.resolve("f$it.txt"),"data $it")}
        // Files younger than two seconds are never cached, so age them first.
        Files.list(dir).use {paths->paths.forEach {Files.setLastModifiedTime(it,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()-60_000))}}
        val first=FileInventory.ScanStats();FileInventory.collectCached(dir,first)
        assertEquals(3,first.hashed.get());assertEquals(0,first.reused.get())
        val second=FileInventory.ScanStats();FileInventory.collectCached(dir,second)
        assertEquals(0,second.hashed.get());assertEquals(3,second.reused.get())
    }

    @Test fun `preparation steps are validated and sanitized`() {
        val ok=FolderImportProgress("SCANNING",message="m",step="실행기\n파일 목록 작성",stepNo=2,stepTotal=3,reused=5,hashed=1).validated()
        assertEquals("실행기 파일 목록 작성",ok.step)
        assertFails {FolderImportProgress("SCANNING",stepNo=4,stepTotal=3).validated()}
        assertFails {FolderImportProgress("SCANNING",reused=-1).validated()}
        assertEquals(500,FolderImportProgress("SCANNING",message="x".repeat(900)).validated().message.length)
    }
}
