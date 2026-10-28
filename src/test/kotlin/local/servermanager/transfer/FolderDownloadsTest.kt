package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlin.test.*

class FolderDownloadsTest {
    @TempDir lateinit var temp:Path
    private fun waitFor(service:FolderDownloads,id:String,token:String):FolderDownloadStatus {
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while(service.status(id,token).phase=="WORKING" && System.nanoTime()<deadline)Thread.sleep(10)
        return service.status(id,token).also {assertEquals("READY",it.phase,it.message)}
    }
    private fun zip(service:FolderDownloads,id:String,token:String):Path {
        val target=temp.resolve("${UUID.randomUUID()}.zip")
        target.outputStream().use {service.download(id,token).body!!.writeTo(it)}
        val status=service.status(id,token)
        assertEquals("COMPLETE",status.downloadState);assertEquals(target.fileSize(),status.downloadedBytes)
        return target
    }
    private fun fixture(action:(ServerManager,FolderDownloads,String,Path)->Unit) {
        val manager=ServerManager();val id=(manager.create(CreateRequest("download-${UUID.randomUUID()}","0.16.14",ServerSocket(0).use {it.localPort},512,loader="Fabric"))["config"] as Instance).id
        val service=FolderDownloads(manager,RunnerTransfer(manager,org.mockito.Mockito.mock(RunnerRegistry::class.java)))
        try{action(manager,service,id,manager.originalServerDir(id))}finally{service.close();manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively()}
    }
    @Test fun `world directory downloads as zip preserving all bytes empty folders and originals without following links`()=fixture {manager,service,id,base->
        val world=base.resolve("world");world.resolve("region").createDirectories();world.resolve("empty").createDirectories()
        world.resolve("level.dat").writeBytes(byteArrayOf(0,1,2,3,127,-1));world.resolve("region/한글.mca").writeText("original region")
        val outside=temp.resolve("secret").also {it.writeText("outside")};Files.createSymbolicLink(world.resolve("private"),outside)
        val before=FileInventory.digest(world.resolve("level.dat"))
        val request=service.create(id,"world");val status=waitFor(service,id,request.token)
        assertEquals(2,status.files);assertEquals(status.totalBytes,status.bytes)
        val response=service.download(id,request.token);assertEquals("application/zip",response.headers.contentType.toString())
        ZipFile(zip(service,id,request.token).toFile()).use {zip->
            assertNotNull(zip.getEntry("empty/"));assertNull(zip.getEntry("private"))
            assertContentEquals(world.resolve("level.dat").readBytes(),zip.getInputStream(zip.getEntry("level.dat")).readAllBytes())
            assertEquals("original region",zip.getInputStream(zip.getEntry("region/한글.mca")).reader().readText())
        }
        assertEquals(before,FileInventory.digest(world.resolve("level.dat")));assertEquals("outside",outside.readText())
        assertFails {service.create(id,"../outside")};assertFails {service.status("not-an-instance",request.token)}
    }
    @Test fun `current runner folder zip uses remote chunks rather than the stale central copy`()=fixture {manager,service,id,central->
        val base=temp.resolve("runner").createDirectories();base.resolve("world").createDirectories()
        val content=ByteArray(180000) {(it%251).toByte()};base.resolve("world/region.mca").writeBytes(content)
        central.resolve("world").createDirectories();central.resolve("world/region.mca").writeText("stale copy")
        manager.instance(id).runnerId="runner"
        val workspace=RunnerWorkspace(base,temp.resolve("runner-history")){}
        var reads=0
        manager.attachRemoteController(object:RemoteController {
            override fun supportsLiveFiles(instance:Instance)=true
            override fun workspace(instance:Instance,request:WorkspaceRequest):com.fasterxml.jackson.databind.JsonNode {if(request.op=="READ")reads++;return com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().valueToTree(workspace.execute(request))}
            override fun start(instance:Instance)=error("unused");override fun stop(instance:Instance)=error("unused");override fun restart(instance:Instance)=error("unused");override fun command(instance:Instance,value:String)=error("unused")
        })
        val request=service.create(id,"world");waitFor(service,id,request.token)
        ZipFile(zip(service,id,request.token).toFile()).use {zip->assertContentEquals(content,zip.getInputStream(zip.getEntry("region.mca")).readAllBytes())}
        assertTrue(reads>=3);assertEquals("stale copy",central.resolve("world/region.mca").readText())
    }
    @Test fun `latest central snapshot downloads with runner offline and names its recorded completion time`()=fixture {manager,service,id,base->
        manager.instance(id).runnerId="offline-runner"
        base.resolve("world").createDirectories();base.resolve("world/level.dat").writeText("latest completed central copy")
        manager.root.resolve(id).resolve("latest-snapshot.json").writeText("""{"completedAt":"2026-10-29T01:02:03Z"}""")
        val request=service.create(id,"world","snapshot");val status=waitFor(service,id,request.token)
        assertEquals("snapshot",status.source);assertEquals("2026-10-29T01:02:03Z",status.copiedAt)
        ZipFile(zip(service,id,request.token).toFile()).use {zip->assertEquals("latest completed central copy",zip.getInputStream(zip.getEntry("level.dat")).reader().readText())}
    }
}
