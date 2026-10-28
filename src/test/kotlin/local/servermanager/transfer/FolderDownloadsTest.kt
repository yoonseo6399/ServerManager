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
    private fun fixture(action:(ServerManager,FolderDownloads,String,Path)->Unit) {
        val manager=ServerManager();val id=(manager.create(CreateRequest("download-${UUID.randomUUID()}","0.16.14",ServerSocket(0).use {it.localPort},512,loader="Fabric"))["config"] as Instance).id
        val service=FolderDownloads(manager)
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
        ZipFile(response.body!!.file).use {zip->
            assertNotNull(zip.getEntry("empty/"));assertNull(zip.getEntry("private"))
            assertContentEquals(world.resolve("level.dat").readBytes(),zip.getInputStream(zip.getEntry("level.dat")).readAllBytes())
            assertEquals("original region",zip.getInputStream(zip.getEntry("region/한글.mca")).reader().readText())
        }
        assertEquals(before,FileInventory.digest(world.resolve("level.dat")));assertEquals("outside",outside.readText())
        assertFails {service.create(id,"../outside")};assertFails {service.status("not-an-instance",request.token)}
    }
    @Test fun `latest central snapshot downloads with runner offline and names its recorded completion time`()=fixture {manager,service,id,base->
        manager.instance(id).runnerId="offline-runner"
        base.resolve("world").createDirectories();base.resolve("world/level.dat").writeText("latest completed central copy")
        manager.root.resolve(id).resolve("latest-snapshot.json").writeText("""{"completedAt":"2026-10-29T01:02:03Z"}""")
        val request=service.create(id,"world","snapshot");val status=waitFor(service,id,request.token)
        assertEquals("snapshot",status.source);assertEquals("2026-10-29T01:02:03Z",status.copiedAt)
        ZipFile(service.download(id,request.token).body!!.file).use {zip->assertEquals("latest completed central copy",zip.getInputStream(zip.getEntry("level.dat")).reader().readText())}
    }
}
