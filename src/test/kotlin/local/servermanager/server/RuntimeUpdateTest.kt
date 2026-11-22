package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class RuntimeUpdateTest {
    @TempDir lateinit var root:Path
    private fun metadata(game:String="1.21.2",entry:String?=null)="""{"loader":"Vanilla","minecraft":"$game","loaderVersion":"$game","requiredJava":21${if(entry!=null)",\"entryJar\":\"$entry\"" else ""}}"""
    private fun files(base:Path,values:Map<String,String>){values.forEach {(name,value)->base.resolve(name).also {it.parent.createDirectories()}.writeText(value)}}
    @Test fun `only runtime changes while world mods config scripts and JVM flags are preserved`() {
        val server=root.resolve("server").createDirectories();val stage=root.resolve("stage").createDirectories()
        val data=mapOf("world/level.dat" to "world bytes","mods/keep.jar" to "mod bytes","config/settings.toml" to "config bytes","kubejs/script.js" to "script bytes","user_jvm_args.txt" to "user flags")
        files(server,data+mapOf("server.jar" to "old jar"));files(stage,data.mapValues {"installer must not replace this"}+mapOf("server.jar" to "new jar","server-manager-runtime.json" to metadata(),"libraries/test/a.jar" to "library"))
        RuntimeUpdate.promote(server,stage,root.resolve("backup"))
        data.forEach {(name,value)->assertEquals(value,server.resolve(name).readText())};assertEquals("new jar",server.resolve("server.jar").readText());assertEquals("old jar",root.resolve("backup/before/server.jar").readText());assertContains(root.resolve("backup/transaction.json").readText(),"COMPLETE")
    }
    @Test fun `mid-commit failure restores overwritten files and removes only newly created runtime files`() {
        val server=root.resolve("server").createDirectories();val stage=root.resolve("stage").createDirectories()
        files(server,mapOf("server.jar" to "original","server-manager-runtime.json" to metadata("1.21.1"),"world/level.dat" to "unchanged"))
        files(stage,mapOf("server.jar" to "new","server-manager-runtime.json" to metadata(),"libraries/new.jar" to "new library"))
        assertFailsWith<IllegalStateException>{RuntimeUpdate.promote(server,stage,root.resolve("backup")){if(it=="server.jar")error("injected commit failure")}}
        assertEquals("original",server.resolve("server.jar").readText());assertEquals(metadata("1.21.1"),server.resolve("server-manager-runtime.json").readText());assertFalse(server.resolve("libraries/new.jar").exists());assertEquals("unchanged",server.resolve("world/level.dat").readText());assertContains(root.resolve("backup/transaction.json").readText(),"ROLLED_BACK")
    }
    @Test fun `custom registered entry jar is promoted without touching other arbitrary jars`() {
        val server=root.resolve("server").createDirectories();val stage=root.resolve("stage").createDirectories();files(stage,mapOf("chosen.jar" to "trusted entry","other.jar" to "unrelated","server-manager-runtime.json" to metadata(entry="chosen.jar")))
        RuntimeUpdate.promote(server,stage,root.resolve("backup"));assertEquals("trusted entry",server.resolve("chosen.jar").readText());assertFalse(server.resolve("other.jar").exists())
    }
    @Test fun `Minecraft downgrade same version and loader mismatch are rejected`() {
        val i=Instance("test","Test",loader="Vanilla",minecraft="1.21.2",loaderVersion="1.21.2",port=25565,memoryMb=1024,javaPath="java")
        assertFailsWith<IllegalArgumentException>{RuntimeUpdate.validate(i,RuntimeUpdate.Target("1.21.1","1.21.1"))};assertFailsWith<IllegalArgumentException>{RuntimeUpdate.validate(i,RuntimeUpdate.Target("1.21.2","1.21.2"))};assertFailsWith<IllegalArgumentException>{RuntimeUpdate.validate(i,RuntimeUpdate.Target("1.21.3","1.21.2"))}
        assertTrue(RuntimeUpdate.compareMinecraft("26.1","1.21.11")>0)
    }
    @Test fun `background preparation fails without changing original files and exposes a useful status`() {
        val server=root.resolve("server").createDirectories();files(server,mapOf("server.jar" to "old"));val i=Instance("test","Test",loader="Vanilla",minecraft="1.21.1",loaderVersion="1.21.1",port=25565,memoryMb=1024,javaPath="java")
        val updater=RuntimeUpdate(server,root.resolve("backup")){_,_,log->log("download attempt");error("fixture download failure")};updater.start(i,RuntimeUpdate.Target("1.21.2","1.21.2"))
        val deadline=System.nanoTime()+5_000_000_000L;while(updater.busy()){check(System.nanoTime()<deadline);Thread.sleep(5)}
        assertEquals("FAILED",updater.status().state);assertEquals("fixture download failure",updater.status().error);assertContains(updater.status().logs,"download attempt");assertEquals("old",server.resolve("server.jar").readText())
    }
    @Test fun `symlink destinations are rejected before any runtime file is changed`() {
        val server=root.resolve("server").createDirectories();val stage=root.resolve("stage").createDirectories();val outside=root.resolve("outside").createDirectories();java.nio.file.Files.createSymbolicLink(server.resolve("libraries"),outside)
        files(stage,mapOf("libraries/new.jar" to "new","server-manager-runtime.json" to metadata()));assertFailsWith<IllegalArgumentException>{RuntimeUpdate.promote(server,stage,root.resolve("backup"))};assertFalse(outside.resolve("new.jar").exists())
    }
}
