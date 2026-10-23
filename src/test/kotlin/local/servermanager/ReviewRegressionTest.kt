package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

class ReviewRegressionTest {
    @TempDir lateinit var temp:Path
    private fun service()=PermissionService(temp).also {it.declare(listOf(PermissionDecl("test.read","읽기",PermissionRisk.LOW,"test")))}
    @Test fun `corrupt policy denies legacy and super admin and preserves evidence`() {
        for(name in listOf("permission-grants.json","permission-ceiling.json")) {
            val path=temp.resolve(name);path.writeText("{broken")
            val s=service()
            assertEquals(false,s.check("guild",emptySet(),"test.read").allowed)
            assertEquals(false,s.check("guild",emptySet(),"test.read",true).allowed)
            assertContains(s.storageError()!!,name)
            assertFailsWith<IllegalStateException>{s.setCeiling(PermissionCeiling())}
            assertEquals("{broken",path.readText());path.deleteExisting()
        }
        assertNull(service().check("guild",emptySet(),"test.read").allowed)
    }
    @Test fun `unknown ceiling enum and unreadable file shape deny`() {
        val p=temp.resolve("permission-ceiling.json");p.writeText("""{"maxRisk":"SUPER"}""")
        assertEquals(false,service().check("g",emptySet(),"test.read",true).allowed)
        p.deleteExisting();p.createDirectory()
        assertEquals(false,service().check("g",emptySet(),"test.read",true).allowed)
    }
    @Test fun `strict text round trips valid bytes and rejects legacy or damaged encodings`() {
        for(text in listOf("한글\r\n", "\uFEFF한글\n", "hello", "")) {
            val bytes=text.toByteArray();assertContentEquals(bytes,EditableText.decode(bytes).toByteArray())
        }
        assertFailsWith<IllegalArgumentException>{EditableText.decode("한글".toByteArray(charset("EUC-KR")))}
        assertFailsWith<IllegalArgumentException>{EditableText.decode(byteArrayOf(0xc3.toByte(),0x28))}
        assertFailsWith<IllegalArgumentException>{EditableText.decode(byteArrayOf(0))}
    }
    @Test fun `expired archive cleanup excludes active fresh and unrelated paths`() {
        val now=Instant.now()
        fun dir(old:Boolean):Path {val p=temp.resolve(UUID.randomUUID().toString()).createDirectory();val f=p.resolve("pack.mrpack");f.writeText("archive");Files.setLastModifiedTime(f,FileTime.from(now.minusSeconds(if(old)86401 else 30)));return p}
        val expired=dir(true);val active=dir(true);val fresh=dir(false);val other=temp.resolve("other").createDirectory()
        PackArchiveRetention.sweep(temp,setOf(active.name),now)
        assertFalse(expired.exists());assertTrue(active.exists());assertTrue(fresh.exists());assertTrue(other.exists())
        PackArchiveRetention.sweep(temp,emptySet(),now);assertFalse(active.exists())
    }
    @Test fun `cleanup never follows upload directory symlink`() {
        val outside=temp.resolve("outside").createDirectory();outside.resolve("keep").writeText("keep")
        val imports=temp.resolve("imports").createDirectory()
        Files.createSymbolicLink(imports.resolve(UUID.randomUUID().toString()),outside)
        PackArchiveRetention.sweep(imports,emptySet(),Instant.now().plusSeconds(100000))
        assertEquals("keep",outside.resolve("keep").readText())
    }
    @Test fun `archive quota bounds abandoned previews`() {
        repeat(PackArchiveRetention.MAX_ARCHIVES){temp.resolve(UUID.randomUUID().toString()).createDirectory()}
        assertFailsWith<IllegalArgumentException>{PackArchiveRetention.requireCapacity(temp)}
    }
    @Test fun `fallback full copy checks full source size and preserves source`() {
        val source=temp.resolve("source").createDirectory()
        // Sparse file avoids consuming disk while exceeding the old 512 MiB capped check.
        java.io.RandomAccessFile(source.resolve("large.bin").toFile(),"rw").use {it.setLength(600L*1024*1024)}
        val copies=temp.resolve("copies")
        val store=StoppedSnapshots(copies,{650L*1024*1024},false)
        val error=assertFailsWith<IllegalStateException>{store.capture(source,null,null,null)}
        assertContains(error.message!!,"일반 종료 복사본 공간")
        assertEquals(600L*1024*1024,source.resolve("large.bin").fileSize())
        assertTrue(store.list().isEmpty());assertTrue(copies.listDirectoryEntries().isEmpty())
    }
}
