package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

class PackUpdatesTest {
    @TempDir lateinit var temp:Path
    private fun write(base:Path,path:String,text:String) {base.resolve(path).also {it.parent.createDirectories();it.writeText(text)}}
    private fun clientJar():ByteArray {val out=java.io.ByteArrayOutputStream();ZipOutputStream(out).use {zip->zip.putNextEntry(ZipEntry("fabric.mod.json"));zip.write("""{"id":"client","version":"1","environment":"client"}""".toByteArray());zip.closeEntry()};return out.toByteArray()}
    private fun archive():ByteArray {
        val out=java.io.ByteArrayOutputStream();ZipOutputStream(out).use {zip->
            val entries=mapOf("modrinth.index.json" to """{"formatVersion":1,"game":"minecraft","name":"Update","versionId":"2","dependencies":{"minecraft":"1.21.1","fabric-loader":"0.16.14"},"files":[]}""".toByteArray(),"overrides/mods/new.jar" to "new mod".toByteArray(),"overrides/mods/client.jar" to clientJar(),"overrides/config/nested/test.json" to "new config".toByteArray(),"overrides/config/new.toml" to "new setting".toByteArray(),"overrides/world/level.dat" to "must not replace world".toByteArray(),"overrides/server.properties" to "must not replace properties".toByteArray())
            entries.forEach {(name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}
        };return out.toByteArray()
    }
    private fun waitFor(updates:PackUpdates,id:String,phase:String):PackUpdateStatus {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        while(updates.status(id)?.phase in setOf("PREPARING","APPLYING") && System.nanoTime()<deadline)Thread.sleep(10)
        return updates.status(id)!!.also {assertEquals(phase,it.phase,it.message)}
    }
    private fun fixture(isRemote:Boolean=false,action:(ServerManager,PackUpdates,String,Path)->Unit) {
        val manager=ServerManager();val instance=manager.create(CreateRequest("pack-${UUID.randomUUID()}","0.16.14",ServerSocket(0).use {it.localPort},512,loader="Fabric"))["config"] as Instance
        val id=instance.id;val central=manager.originalServerDir(id);val base=if(isRemote)temp.resolve("runner-${UUID.randomUUID()}").createDirectories() else central
        val api=object:ModrinthLookup {override fun versionsFromHashes(hashes:List<String>)=emptyMap<String,RinVersion>();override fun projects(ids:Collection<String>)=emptyList<RinProject>()}
        val catalog=ModrinthCatalog(api);val importer=MrpackImporter(manager,catalog);val mods=ModManagement(manager,catalog);val updates=PackUpdates(manager,importer,catalog,mods)
        if(isRemote) {
            manager.instance(id).runnerId="runner";val workspace=RunnerWorkspace(base,temp.resolve("history")){}
            manager.attachRemoteController(object:RemoteController {
                override fun supportsLiveFiles(instance:Instance)=true
                override fun workspace(instance:Instance,request:WorkspaceRequest)=jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(workspace.execute(request))
                override fun start(instance:Instance)=error("not used");override fun stop(instance:Instance)=error("not used");override fun restart(instance:Instance)=error("not used");override fun command(instance:Instance,value:String)=error("not used")
            })
            write(central,"mods/central-only.jar","central stale copy")
        }
        write(base,"mods/old.jar","old mod");write(base,"mods/servermanager-sable-bridge-1.0.0.jar","managed bridge");write(base,"config/nested/test.json","original config")
        write(base,"world/level.dat","original world");write(base,"data/players.dat","original data");write(base,"server.properties","original settings")
        try {action(manager,updates,id,base)} finally {updates.close();mods.close();importer.shutdown();manager.runtime(id).state="STOPPED";manager.runtime(id).remotePid=null;manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively()}
    }
    @Test fun `merge preserves existing mods config worlds and data and filters a private client jar`()=fixture {manager,updates,id,base->
        updates.preview(id,archive().inputStream());val ready=waitFor(updates,id,"READY")
        assertEquals(listOf("mods/old.jar"),ready.plan!!.currentMods)
        assertEquals(listOf("mods/client.jar"),ready.plan.excludedClientMods)
        assertTrue("world/level.dat" in ready.plan.ignoredFiles)
        updates.apply(id,PackUpdateRequest(ready.token,"merge"));waitFor(updates,id,"COMPLETE")
        assertEquals("new mod",base.resolve("mods/new.jar").readText());assertTrue(base.resolve("mods/old.jar").exists());assertFalse(base.resolve("mods/client.jar").exists())
        assertEquals("original config",base.resolve("config/nested/test.json").readText());assertFalse(base.resolve("config/new.toml").exists());assertEquals("original world",base.resolve("world/level.dat").readText());assertEquals("original data",base.resolve("data/players.dat").readText());assertEquals("original settings",base.resolve("server.properties").readText())
        assertFalse(manager.root.resolve(id).resolve("import.pending").exists())
    }
    @Test fun `replace updates the real runner includes opted-in client and config and backs up originals`()=fixture(true) {manager,updates,id,base->
        updates.preview(id,archive().inputStream());val ready=waitFor(updates,id,"READY")
        assertEquals(listOf("mods/old.jar"),ready.plan!!.currentMods,"preview must read the runner, never the central copy")
        updates.apply(id,PackUpdateRequest(ready.token,"replace",overwriteConfig=true,excludeClientOnly=false));waitFor(updates,id,"COMPLETE")
        assertFalse(base.resolve("mods/old.jar").exists());assertTrue(base.resolve("mods/new.jar").exists());assertTrue(base.resolve("mods/client.jar").exists());assertTrue(base.resolve("mods/servermanager-sable-bridge-1.0.0.jar").exists())
        assertEquals("new config",base.resolve("config/nested/test.json").readText());assertEquals("new setting",base.resolve("config/new.toml").readText());assertEquals("original world",base.resolve("world/level.dat").readText());assertEquals("original data",base.resolve("data/players.dat").readText())
        assertEquals("central stale copy",manager.originalServerDir(id).resolve("mods/central-only.jar").readText())
        val backup=manager.root.resolve("pack-updates").resolve(ready.token).resolve("backup")
        assertEquals("old mod",backup.resolve("mods/old.jar").readText());assertEquals("original config",backup.resolve("config/nested/test.json").readText())
    }
    @Test fun `stale preview and running server refuse updates before any mutation`()=fixture {manager,updates,id,base->
        updates.preview(id,archive().inputStream());val ready=waitFor(updates,id,"READY")
        write(base,"mods/old.jar","changed after preview")
        updates.apply(id,PackUpdateRequest(ready.token,"replace"));assertContains(waitFor(updates,id,"FAILED").message,"변경되었습니다")
        assertFalse(base.resolve("mods/new.jar").exists());assertEquals("changed after preview",base.resolve("mods/old.jar").readText())
        updates.preview(id,archive().inputStream());val next=waitFor(updates,id,"READY");manager.runtime(id).state="RUNNING"
        updates.apply(id,PackUpdateRequest(next.token,"replace"));assertContains(waitFor(updates,id,"FAILED").message,"종료한 뒤")
        assertFalse(base.resolve("mods/new.jar").exists())
    }
    @Test fun `repeated update previews do not consume new-instance import slots`()=fixture {manager,updates,id,_->
        val imports=manager.root.resolve("imports")
        val before=imports.listDirectoryEntries().map {it.name}.toSet()
        repeat(9){updates.preview(id,archive().inputStream());waitFor(updates,id,"READY")}
        assertEquals(before,imports.listDirectoryEntries().map {it.name}.toSet())
        assertFails {updates.preview(id,"bad archive".byteInputStream())}
        assertNull(updates.status(id),"a failed replacement must not expose the previous ready pack")
    }
    @Test fun `transaction failure restores original files and rejects world paths`() {
        val base=temp.resolve("transaction").createDirectories();val stage=temp.resolve("stage").createDirectories()
        write(base,"mods/a.jar","old a");write(base,"mods/b.jar","old b");write(stage,"mods/a.jar","new a");write(stage,"mods/b.jar","new b")
        var failed=false
        val ws=object:PackWorkspace {
            override fun list(path:String)=Files.list(base.resolve(path)).use {paths->paths.map {FileEntry(it.name,"$path/${it.name}",false,it.fileSize())}.toList()}
            override fun revision(path:String)=FileInventory.digest(base.resolve(path))
            override fun read(path:String,target:Path){Files.copy(base.resolve(path),target)}
            override fun write(path:String,source:Path){if(path=="mods/b.jar" && !failed){failed=true;throw IllegalStateException("injected failure")};source.copyTo(base.resolve(path),overwrite=true)}
            override fun delete(path:String){base.resolve(path).deleteExisting()}
        }
        val expected=listOf("mods/a.jar","mods/b.jar").associateWith(ws::revision)
        assertFailsWith<IllegalStateException>{PackUpdateTransaction.apply(ws,stage,temp.resolve("backup"),expected,expected.keys,emptySet())}
        assertEquals("old a",base.resolve("mods/a.jar").readText());assertEquals("old b",base.resolve("mods/b.jar").readText())
        assertFailsWith<IllegalArgumentException>{PackUpdateTransaction.apply(ws,stage,temp.resolve("blocked"),emptyMap(),setOf("world/level.dat"),emptySet())}
    }
}
