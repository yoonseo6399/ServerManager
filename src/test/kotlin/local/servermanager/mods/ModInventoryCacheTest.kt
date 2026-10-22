package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.*
import kotlin.test.*

class ModInventoryCacheTest {
    @TempDir lateinit var dir:Path
    private fun old(file:Path)=Files.setLastModifiedTime(file,FileTime.fromMillis(System.currentTimeMillis()-10000))
    @Test fun `unchanged mods avoid reads but same size mtime restoration and inode replacement invalidate hashes`() {
        assumeTrue("unix" in dir.fileSystem.supportedFileAttributeViews())
        repeat(3){n->dir.resolve("mod$n.jar").also {it.writeBytes(ByteArray(1024*1024){n.toByte()});old(it)}}
        val cold=ModInventory.ScanStats();val original=ModInventory.scan(dir,cold)
        assertEquals(3,cold.hashed);assertEquals(3L*1024*1024,cold.bytesRead)
        val warm=ModInventory.ScanStats();assertEquals(original,ModInventory.scan(dir,warm))
        assertEquals(3,warm.reused);assertEquals(0,warm.bytesRead)
        val file=dir.resolve("mod0.jar");val modified=Files.getLastModifiedTime(file)
        file.writeBytes(ByteArray(1024*1024){9});Files.setLastModifiedTime(file,modified)
        val changed=ModInventory.ScanStats();assertNotEquals(original.revision,ModInventory.scan(dir,changed).revision)
        assertEquals(1,changed.hashed);assertEquals(2,changed.reused)
        val replacement=dir.resolve("replacement");replacement.writeBytes(ByteArray(1024*1024){7});Files.setLastModifiedTime(replacement,modified)
        Files.move(replacement,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val replaced=ModInventory.ScanStats();ModInventory.scan(dir,replaced)
        assertEquals(1,replaced.hashed)
        val extra=dir.resolve("new.jar");extra.writeBytes(byteArrayOf(1));old(extra)
        val added=ModInventory.ScanStats();assertEquals(4,ModInventory.scan(dir,added).mods.size);assertEquals(1,added.hashed)
        extra.deleteExisting();assertEquals(3,ModInventory.scan(dir).mods.size)
        dir.resolve("mod1.jar").deleteExisting();Files.createSymbolicLink(dir.resolve("mod1.jar"),file)
        assertFailsWith<IllegalArgumentException>{ModInventory.scan(dir)}
    }
    @Test
    @EnabledIfEnvironmentVariable(named="MOD_SCAN_BENCHMARK",matches="true")
    fun `read only real mod scan benchmark reports cold warm bytes and time`() {
        val target=Path.of(System.getenv("MOD_SCAN_DIRECTORY"))
        val cold=ModInventory.ScanStats();val before=System.nanoTime();val a=ModInventory.scan(target,cold);val coldMs=(System.nanoTime()-before)/1_000_000
        val warm=ModInventory.ScanStats();val next=System.nanoTime();val b=ModInventory.scan(target,warm);val warmMs=(System.nanoTime()-next)/1_000_000
        assertEquals(a,b);assertTrue(warm.reused>0);assertTrue(warm.bytesRead<cold.bytesRead)
        println("MOD_SCAN_BENCHMARK files=${a.mods.size} coldMs=$coldMs warmMs=$warmMs coldBytes=${cold.bytesRead} warmBytes=${warm.bytesRead} reused=${warm.reused}")
    }
}
