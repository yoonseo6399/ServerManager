package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*
import kotlin.test.*

class VerifiedInventoryTest {
    @TempDir lateinit var root:Path
    private fun await(cache:VerifiedInventory,id:String="a",path:Path=root,context:()->String={"stopped"}):RunnerFileManifest {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(System.nanoTime()<deadline){cache.request(id,path,context)?.let {return it};Thread.sleep(5)}
        error("Inventory did not finish")
    }
    @Test fun `slow hash returns immediately and concurrent polls share one scan without blocking another instance`() {
        root.resolve("data").writeText("world")
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        VerifiedInventory({p->calls.incrementAndGet();entered.countDown();check(release.await(5,TimeUnit.SECONDS));FileInventory.collect(p).let {(f,d)->RunnerFileManifest(f,d)}}).use {cache->
            val start=System.nanoTime();assertNull(cache.request("a",root){"stopped"});assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1))
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            repeat(20){assertNull(cache.request("a",root){"stopped"})};assertEquals(1,calls.get())
            release.countDown();val result=await(cache);repeat(5){assertEquals(result,await(cache))};assertEquals(1,calls.get())
        }
    }
    @Test fun `cache rejects changed added deleted files same-size restored mtime different root and execution epoch`() {
        root.resolve("file").writeText("old")
        VerifiedInventory().use {cache->
            var context="run-1";fun manifest()=await(cache,context={context})
            val original=manifest();val stamp=root.resolve("file").getLastModifiedTime()
            root.resolve("file").writeText("new");Files.setLastModifiedTime(root.resolve("file"),stamp)
            // Unix ctime or a normal mtime change must invalidate exact-size replacements.
            if(!System.getProperty("os.name").startsWith("Windows"))assertNotEquals(original,manifest())
            else {root.resolve("file").writeText("longer");assertNotEquals(original,manifest())}
            root.resolve("added").writeText("add");assertContains(manifest().files,"added")
            root.resolve("file").deleteExisting();assertFalse("file" in manifest().files)
            context="run-2";assertNull(cache.request("a",root){context});manifest()
            val other=root.resolve("other").createDirectory();other.resolve("x").writeText("x")
            assertNull(cache.request("a",other){context});assertEquals(setOf("x"),await(cache,path=other,context={context}).files.keys)
        }
    }
    @Test fun `modification during hashing invalidation failure and symbolic links never publish stale manifest`() {
        root.resolve("file").writeText("old")
        var mutate=true
        VerifiedInventory({p->val m=FileInventory.collect(p).let {(f,d)->RunnerFileManifest(f,d)};if(mutate){mutate=false;p.resolve("file").writeText("different")};m}).use {cache->
            assertFailsWith<IllegalStateException>{await(cache)}
            assertEquals(FileInventory.digest(root.resolve("file")),await(cache).files.getValue("file").sha256)
            cache.invalidate("a");assertNull(cache.request("a",root){"stopped"});await(cache)
            if(!System.getProperty("os.name").startsWith("Windows")) {
                Files.createSymbolicLink(root.resolve("link"),root.resolve("file"))
                assertFailsWith<IllegalArgumentException>{await(cache)}
            }
        }
    }
    @Test fun `bounded workers and entries reject excess pending work without spawning threads`() {
        val release=CountDownLatch(1)
        VerifiedInventory({p->check(release.await(5,TimeUnit.SECONDS));RunnerFileManifest(emptyMap(),emptySet())},maxEntries=2).use {cache->
            try {repeat(20){assertNull(cache.request(it.toString(),root){"stopped"})}}finally{release.countDown()}
            await(cache,"0");await(cache,"1")
        }
    }
}
