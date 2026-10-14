package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ModManagementTest {
    @TempDir lateinit var temp:Path
    private fun jar(path:Path,id:String,depends:String=""):Path {
        path.parent.createDirectories()
        ZipOutputStream(path.outputStream()).use {z->z.putNextEntry(ZipEntry("fabric.mod.json"));z.write("""{"schemaVersion":1,"id":"$id","version":"1.0.0","depends":{$depends}}""".toByteArray());z.closeEntry()}
        return path
    }
    private fun waitJob(mods:ModManagement,id:String) {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while(mods.page(id).job?.phase !in setOf("COMPLETE","FAILED") && System.nanoTime()<deadline)Thread.sleep(20)
        assertEquals("COMPLETE",mods.page(id).job?.phase,mods.page(id).job?.message)
    }
    private fun sha512(path:Path)=java.security.MessageDigest.getInstance("SHA-512").digest(path.readBytes()).joinToString("") {"%02x".format(it)}
    /** Stand-in for Modrinth: each fixture JAR is known by hash, with its required projects. */
    private class FakeModrinth:ModrinthLookup {
        val known=mutableMapOf<String,Pair<String,List<String>>>()
        override fun versionsFromHashes(hashes:List<String>)=hashes.mapNotNull {h->known[h]?.let {(project,deps)->
            h to RinVersion("v-$project",project,project,"1.0.0","release","2026-01-01T00:00:00Z",setOf("1.21.1"),setOf("fabric"),emptyList(),deps.map {RinDependency(it,null,"required")},"client_and_server")}}.toMap()
        override fun projects(ids:Collection<String>)=ids.map {RinProject(it,it,it,"required",environment="client_and_server")}
    }
    private fun fixture(action:(ServerManager,ModManagement,String)->Unit) {
        val manager=ServerManager();val id=(manager.create(CreateRequest("mod-test-${UUID.randomUUID()}","0.16.14",ServerSocket(0).use {it.localPort},512,loader="Fabric"))["config"] as Instance).id
        val modrinth=FakeModrinth();val mods=ModManagement(manager,ModrinthCatalog(modrinth))
        try {
            manager.root.resolve(id).resolve("installed.ok").writeText("0.16.14");manager.runtime(id).state="STOPPED"
            val source=manager.originalServerDir(id)
            modrinth.known[sha512(jar(source.resolve("mods/lib.jar"),"lib"))]="lib" to emptyList()
            modrinth.known[sha512(jar(source.resolve("mods/suspect.jar"),"suspect"))]="suspect" to listOf("lib")
            modrinth.known[sha512(jar(source.resolve("mods/other.jar"),"other"))]="other" to emptyList()
            source.resolve("world/region").createDirectories();source.resolve("world/level.dat").writeText("original protected world")
            source.resolve("world/region/r.0.0.mca").writeText("untouched")
            mods.refresh(id);waitJob(mods,id);action(manager,mods,id)
        } finally {
            val r=manager.runtime(id);r.process=null;r.state="STOPPED";r.modBusy=false
            mods.close();manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively()
        }
    }
    @Test fun `disable is dependency closed reversible content preserving and stale revision guarded`()=fixture {manager,mods,id->
        val source=manager.originalServerDir(id);val original=FileInventory.collect(source)
        val revision=mods.page(id).inventory!!.revision
        assertEquals(listOf("lib.jar","suspect.jar"),mods.preview(id,ModSelection(revision,setOf("lib.jar"))).affected)
        assertEquals(listOf("suspect.jar"),mods.page(id).inventory!!.dependencyReasons["lib.jar"])
        val request=ModSelection(revision,setOf("suspect.jar"))
        assertEquals(listOf("suspect.jar"),mods.preview(id,request).affected)
        mods.toggle(id,request);waitJob(mods,id)
        assertTrue(source.resolve("mods/lib.jar").exists());assertTrue(source.resolve("mods/suspect.jar.disabled").exists())
        assertEquals("untouched",source.resolve("world/region/r.0.0.mca").readText())
        assertFails {mods.toggle(id,request)}
        var fresh=mods.page(id).inventory!!
        assertEquals(listOf("suspect.jar.disabled"),mods.preview(id,ModSelection(fresh.revision,setOf("suspect.jar.disabled"),true)).affected)
        mods.toggle(id,ModSelection(fresh.revision,setOf("suspect.jar.disabled"),true));waitJob(mods,id)
        assertEquals(original,FileInventory.collect(source));assertFalse(manager.root.resolve(id).resolve("import.pending").exists())
        jar(source.resolve("mods/external.jar.disabled"),"external");mods.refresh(id);waitJob(mods,id);fresh=mods.page(id).inventory!!
        assertEquals(listOf("external.jar.disabled"),mods.preview(id,ModSelection(fresh.revision,setOf("external.jar.disabled"),true)).affected)
        assertNull(fresh.mods.single {it.filename=="external.jar.disabled"}.source)
        assertTrue(manager.root.resolve(id).resolve("mod-sources.json").readText().contains("suspect"))
    }
    @Test fun `isolated manual search keeps original world requires verdict and supports pause resume correction and logs`()=fixture {manager,mods,id->
        val source=manager.originalServerDir(id);val original=FileInventory.collect(source)
        val revision=mods.page(id).inventory!!.revision
        mods.create(id,ModSearchRequest(revision,setOf("suspect.jar","other.jar"),setOf("lib.jar")));waitJob(mods,id)
        var s=modsSession(mods,id);val sandbox=manager.serverDir(id)
        assertNotEquals(source,sandbox);assertFalse(sandbox.resolve("world/level.dat").exists());assertTrue(sandbox.resolve("mods/lib.jar").exists())
        assertEquals(original,FileInventory.collect(source));assertTrue(manager.runtime(id).diagnosticActive)
        assertFails {manager.start(id)};assertFails {manager.assignRunner(id,null)};assertFails {manager.write(id,"mods/other.jar",byteArrayOf(1))}
        assertFails {mods.advance(id)};assertFails {mods.verdict(id,ModVerdict(1,"FAIL"))}
        // Stand-in for a completed process; the search never derives verdicts from exit codes/logs.
        s=modsSession(mods,id);s.trials.last().started=true
        val log=sandbox.resolve("logs/latest.log");log.parent.createDirectories();log.writeText("original failure\n")
        mods.verdict(id,ModVerdict(1,"FAIL","confirmed same issue"));waitJob(mods,id)
        mods.advance(id);waitJob(mods,id)
        assertFalse(sandbox.resolve("mods/suspect.jar").exists());assertTrue(sandbox.resolve("mods/suspect.jar.disabled").exists())
        assertTrue(sandbox.resolve("mods/lib.jar").exists());assertFalse(sandbox.resolve("logs/latest.log").exists())
        assertTrue(mods.logs(id,1).list().any {it.path=="server/logs/latest.log"})
        assertEquals("original failure",mods.logs(id,1).page("server/logs/latest.log").events.single().text)
        s=modsSession(mods,id);s.trials.last().started=true
        mods.verdict(id,ModVerdict(2,"PASS"));waitJob(mods,id)
        mods.advance(id);waitJob(mods,id)
        assertEquals("READY",mods.page(id).diagnostic!!.phase)
        mods.pause(id,false);waitJob(mods,id);assertEquals(source,manager.serverDir(id));assertFalse(manager.runtime(id).diagnosticActive)
        assertFalse(manager.root.resolve(id).resolve("import.pending").exists())
        mods.resume(id);waitJob(mods,id);assertEquals(sandbox,manager.serverDir(id))
        mods.verdict(id,ModVerdict(1,"PASS","original issue not reproduced"));waitJob(mods,id)
        assertEquals("NOT_REPRODUCED",mods.page(id).next!!.status)
        mods.pause(id,true);waitJob(mods,id)
        assertEquals(source,manager.serverDir(id));assertEquals(original,FileInventory.collect(source))
    }
    @Suppress("UNCHECKED_CAST") private fun modsSession(mods:ModManagement,id:String):ModDiagnostic {
        val field=mods.javaClass.getDeclaredField("sessions").apply {isAccessible=true}
        return (field.get(mods) as Map<String,ModDiagnostic>).getValue(id)
    }
    @Test fun `sandbox skips configured nested worlds archives and does not hardlink files`() {
        val source=temp.resolve("source").createDirectory();source.resolve("server.properties").writeText("level-name=saves/custom\n")
        source.resolve("saves/custom").createDirectories();source.resolve("saves/custom/level.dat").writeText("original")
        source.resolve("other-world").createDirectories();source.resolve("other-world/level.dat").writeText("original")
        source.resolve("world.zip").writeText("large backup");source.resolve("config").createDirectory().resolve("settings.txt").writeText("original")
        val target=temp.resolve("sandbox");ModFiles.sandbox(source,target)
        assertFalse(target.resolve("saves").exists());assertFalse(target.resolve("other-world").exists());assertFalse(target.resolve("world.zip").exists())
        target.resolve("config/settings.txt").writeText("test change");assertEquals("original",source.resolve("config/settings.txt").readText())
        Files.createSymbolicLink(source.resolve("link"),source.resolve("config"));assertFails {ModFiles.sandbox(source,temp.resolve("bad-sandbox"))}
    }
}
