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
        assertFailsWith<IllegalArgumentException>{mods.preview(id,ModSelection(revision,setOf("lib.jar")))}
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
        mods.preview(id,ModSelection(fresh.revision,setOf("external.jar.disabled"),true)).also {assertEquals(listOf("external.jar.disabled"),it.affected);assertTrue(it.warnings.single().contains("external.jar.disabled"))}
        assertNull(fresh.mods.single {it.filename=="external.jar.disabled"}.source)
        assertTrue(manager.root.resolve(id).resolve("mod-sources.json").readText().contains("suspect"))
    }
    @Test fun `forced activation preserves files and still rejects running stale and collision changes`()=fixture {manager,mods,id->
        val dir=manager.originalServerDir(id).resolve("mods")
        var snapshot=mods.page(id).inventory!!
        mods.toggle(id,ModSelection(snapshot.revision,setOf("suspect.jar")));waitJob(mods,id)
        Files.delete(dir.resolve("lib.jar"));mods.refresh(id);waitJob(mods,id)
        snapshot=mods.page(id).inventory!!
        val selected=setOf("suspect.jar.disabled")
        assertFailsWith<ModDependencyBlocked>{mods.preview(id,ModSelection(snapshot.revision,selected,true))}
        val request=ModSelection(snapshot.revision,selected,true,true)
        val preview=mods.preview(id,request);assertEquals(selected.toList(),preview.affected);assertTrue(preview.ignoreDependencies)
        val bytes=dir.resolve("suspect.jar.disabled").readBytes()
        manager.runtime(id).state="RUNNING";assertFails {mods.toggle(id,request)};manager.runtime(id).state="STOPPED"
        assertFails {mods.preview(id,request.copy(revision="stale"))}
        assertFails {mods.preview(id,request.copy(enabled=false))}
        assertFails {mods.preview(id,request.copy(files=setOf("../outside.jar.disabled")))}
        mods.toggle(id,request);waitJob(mods,id)
        assertContentEquals(bytes,dir.resolve("suspect.jar").readBytes());assertFalse(dir.resolve("suspect.jar.disabled").exists())
        assertTrue(dir.resolve("other.jar").exists());assertFalse(dir.resolve("lib.jar").exists())
        // A destination collision is a filesystem guard and cannot be overridden.
        Files.copy(dir.resolve("suspect.jar"),dir.resolve("suspect.jar.disabled"));mods.refresh(id);waitJob(mods,id)
        snapshot=mods.page(id).inventory!!
        mods.toggle(id,ModSelection(snapshot.revision,selected,true,true))
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while(mods.page(id).job?.phase !in setOf("COMPLETE","FAILED") && System.nanoTime()<deadline)Thread.sleep(20)
        assertEquals("FAILED",mods.page(id).job?.phase)
        assertContentEquals(bytes,dir.resolve("suspect.jar").readBytes());assertContentEquals(bytes,dir.resolve("suspect.jar.disabled").readBytes())
    }
    @Test fun `forced deactivation changes only selected files and preserves guards and reversible contents`()=fixture {manager,mods,id->
        val source=manager.originalServerDir(id);val dir=source.resolve("mods")
        val original=FileInventory.collect(source);val bytes=dir.resolve("lib.jar").readBytes()
        val snapshot=mods.page(id).inventory!!
        val request=ModSelection(snapshot.revision,setOf("lib.jar"),enabled=false,ignoreDependencies=true)
        assertFailsWith<ModDependencyBlocked>{mods.preview(id,request.copy(ignoreDependencies=false))}
        val preview=mods.preview(id,request)
        assertEquals(listOf("lib.jar"),preview.affected);assertTrue(preview.ignoreDependencies)
        assertTrue(preview.warnings.any {it.contains("서버가 시작되지 않거나 충돌")})
        assertTrue(preview.warnings.any {it.contains("suspect.jar")})
        manager.runtime(id).state="RUNNING";assertFails {mods.toggle(id,request)};manager.runtime(id).state="STOPPED"
        manager.runtime(id).diagnosticActive=true;assertFails {mods.preview(id,request)};manager.runtime(id).diagnosticActive=false
        assertFails {mods.preview(id,request.copy(revision="stale"))}
        assertFails {mods.preview(id,request.copy(files=setOf("../outside.jar")))}
        assertFails {mods.preview(id,request.copy(files=emptySet()))}
        mods.toggle(id,request);waitJob(mods,id)
        assertFalse(dir.resolve("lib.jar").exists());assertContentEquals(bytes,dir.resolve("lib.jar.disabled").readBytes())
        assertTrue(dir.resolve("suspect.jar").exists());assertTrue(dir.resolve("other.jar").exists())
        assertEquals("untouched",source.resolve("world/region/r.0.0.mca").readText())
        val fresh=mods.page(id).inventory!!
        assertEquals(listOf("lib"),fresh.missing["suspect.jar"])
        assertFails {mods.toggle(id,request)}
        mods.toggle(id,ModSelection(fresh.revision,setOf("lib.jar.disabled"),enabled=true));waitJob(mods,id)
        assertEquals(original,FileInventory.collect(source))
        // A forced disable cannot overwrite an existing disabled file.
        Files.copy(dir.resolve("lib.jar"),dir.resolve("lib.jar.disabled"));mods.refresh(id);waitJob(mods,id)
        mods.toggle(id,request.copy(revision=mods.page(id).inventory!!.revision))
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while(mods.page(id).job?.phase !in setOf("COMPLETE","FAILED") && System.nanoTime()<deadline)Thread.sleep(20)
        assertEquals("FAILED",mods.page(id).job?.phase)
        assertContentEquals(bytes,dir.resolve("lib.jar").readBytes());assertContentEquals(bytes,dir.resolve("lib.jar.disabled").readBytes())
    }
    @Test fun `inventory is visible before slow metadata finishes and a private jar remains listed`()=fixture {manager,mods,id->
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        jar(manager.originalServerDir(id).resolve("mods/private.jar"),"private")
        val api=object:ModrinthLookup {
            override fun versionsFromHashes(hashes:List<String>):Map<String,RinVersion> {entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS));throw java.io.IOException("offline")}
            override fun projects(ids:Collection<String>)=emptyList<RinProject>()
        }
        val isolated=ModManagement(manager,ModrinthCatalog(api))
        try {
            isolated.refresh(id);assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(4,isolated.page(id).inventory!!.mods.size);assertEquals("WORKING",isolated.page(id).job?.phase)
            release.countDown();waitJob(isolated,id)
            assertEquals(4,isolated.page(id).inventory!!.mods.size);assertTrue(isolated.page(id).inventory!!.warnings.any {it.contains("offline")})
        } finally {release.countDown();isolated.close()}
    }
    @Test fun `toggle never retries unavailable metadata after the user already loaded the inventory`()=fixture {manager,_,id->
        var calls=0
        val api=object:ModrinthLookup {
            override fun versionsFromHashes(hashes:List<String>):Map<String,RinVersion> {calls++;throw java.io.IOException("offline")}
            override fun projects(ids:Collection<String>)=emptyList<RinProject>()
        }
        val isolated=ModManagement(manager,ModrinthCatalog(api))
        try {
            isolated.refresh(id);waitJob(isolated,id);assertEquals(1,calls)
            val request=ModSelection(isolated.page(id).inventory!!.revision,setOf("other.jar"))
            val bytes=manager.originalServerDir(id).resolve("mods/other.jar").readBytes()
            isolated.toggle(id,request);waitJob(isolated,id)
            assertEquals(1,calls,"A local rename must not wait for a second failed HTTP lookup")
            assertContentEquals(bytes,manager.originalServerDir(id).resolve("mods/other.jar.disabled").readBytes())
            assertTrue(isolated.page(id).inventory!!.mods.any {it.filename=="other.jar.disabled"})
        } finally {isolated.close()}
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
    @Test fun `only dependency rejection exposes the force action error code`() {
        val service=org.mockito.Mockito.mock(ModManagement::class.java)
        val request=ModSelection("r",setOf("main.jar.disabled"),true)
        org.mockito.Mockito.`when`(service.preview("test",request)).thenThrow(ModDependencyBlocked("missing dependency"))
        val mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(ModApi(service)).setControllerAdvice(Errors()).build()
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/instances/test/mods/toggle-preview")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("""{"revision":"r","files":["main.jar.disabled"],"enabled":true}"""))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("MOD_DEPENDENCY_BLOCKED"))
        org.mockito.Mockito.doThrow(IllegalStateException("stale revision")).`when`(service).preview("test",request)
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/instances/test/mods/toggle-preview")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("""{"revision":"r","files":["main.jar.disabled"],"enabled":true}"""))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").doesNotExist())
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
