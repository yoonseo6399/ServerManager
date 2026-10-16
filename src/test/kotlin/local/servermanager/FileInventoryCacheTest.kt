package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.*
import kotlin.test.*

class FileInventoryCacheTest {
    @TempDir lateinit var root:Path
    private fun old(p:Path)=Files.setLastModifiedTime(p,FileTime.fromMillis(System.currentTimeMillis()-60_000))
    @Test fun `cached scan matches full scan and picks up changed or new files`() {
        val a=root.resolve("world/region/r.0.0.mca").also {it.parent.createDirectories();it.writeText("chunk-one")};old(a)
        val b=root.resolve("mods/x.jar").also {it.parent.createDirectories();it.writeText("mod")};old(b)
        assertEquals(FileInventory.collect(root),FileInventory.collectCached(root))
        assertEquals(FileInventory.collect(root),FileInventory.collectCached(root))
        // Renaming a mod (what the mod toggle does) must not disturb the other cached hashes.
        b.moveTo(root.resolve("mods/x.jar.disabled"))
        a.writeText("chunk-two!");root.resolve("new.txt").writeText("new")
        val fresh=FileInventory.collectCached(root)
        assertEquals(FileInventory.collect(root),fresh)
        assertEquals(FileInventory.digest(a),fresh.first.getValue("world/region/r.0.0.mca").sha256)
    }
    @Test fun `files modified in the last seconds are never cached`() {
        val f=root.resolve("live.log").also {it.writeText("a")}
        FileInventory.cachedDigest(f)
        f.writeText("b")
        assertEquals(FileInventory.digest(f),FileInventory.cachedDigest(f))
    }
}
