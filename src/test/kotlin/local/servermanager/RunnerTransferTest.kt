package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class RunnerTransferTest {
    @TempDir lateinit var temp:Path
    private data class LegacyPlan(val transferId:String,val changedFiles:Set<String>)

    @Test fun `legacy strict runner wire format excludes newly added fields`() {
        val (transfer,_,manager)=fixture();val gateway=mock(RunnerGateway::class.java)
        `when`(gateway.authorized("runner","session")).thenReturn(true)
        while(transfer.preparedInventory("instance")==null)Thread.sleep(5)
        val api=RunnerTransferApi(transfer,gateway,manager)
        var reply=api.begin("instance",RunnerTransferBegin(RunnerFileManifest(emptyMap(),emptySet())),"runner","session")
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while(reply is org.springframework.http.ResponseEntity<*> && System.nanoTime()<deadline){Thread.sleep(5);reply=api.begin("instance",RunnerTransferBegin(RunnerFileManifest(emptyMap(),emptySet())),"runner","session")}
        api.close()
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val parsed=mapper.readValue(mapper.writeValueAsBytes(reply),LegacyPlan::class.java)
        assertTrue(parsed.changedFiles.isEmpty())
        val instance=mapper.readTree(mapper.writeValueAsBytes(manager.instance("instance")))
        assertFalse(instance.has("diagnosticSession"))
        assertTrue(mapper.readTree(mapper.writeValueAsBytes(manager.instance("instance").copy(diagnosticSession="fixture"))).has("diagnosticSession"))
    }

    private data class Fixture(val transfer:RunnerTransfer,val base:Path,val manager:ServerManager,val registry:RunnerRegistry)
    private fun fixture():Fixture {
        val base=temp.resolve("instance/server").also { it.createDirectories() }
        val manager=mock(ServerManager::class.java)
        val registry=mock(RunnerRegistry::class.java)
        `when`(manager.root).thenReturn(temp)
        `when`(manager.runtime("instance")).thenReturn(RuntimeState())
        `when`(manager.profileDir("instance")).thenReturn(temp.resolve("instance"))
        `when`(manager.serverDir("instance")).thenReturn(base)
        `when`(manager.instance("instance")).thenReturn(Instance("instance","test",loaderVersion="21.1.252",port=25565,memoryMb=512,javaPath="java",runnerId="runner"))
        `when`(registry.active("runner")).thenReturn(RunnerDevice("runner","Mac","MAC","user","key","now",allowedInstanceIds=setOf("instance")))
        return Fixture(RunnerTransfer(manager,registry),base,manager,registry)
    }

    @Test fun `slow commit returns pending promptly with quick receipt polling and commits once`() {
        val (transfer,base,manager)=fixture();val gateway=mock(RunnerGateway::class.java)
        `when`(gateway.authorized("runner","session")).thenReturn(true)
        val worker=temp.resolve("async-worker").createDirectory();worker.resolve("new.txt").writeText("new")
        val (files,dirs)=FileInventory.collect(worker)
        val plan=transfer.begin("runner","instance",RunnerFileManifest(files,dirs))
        transfer.upload("runner","instance",plan.transferId,"new.txt",0,"new".toByteArray())
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        doAnswer {entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));null}.`when`(manager).remoteCommitted("instance","runner")
        val api=RunnerTransferApi(transfer,gateway,manager)
        try {
            val pending=api.commit("instance",plan.transferId,"runner","session",2) as org.springframework.http.ResponseEntity<*>
            assertEquals(202,pending.statusCode.value());assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val start=System.nanoTime()
            assertEquals(202,(api.state("instance",plan.transferId,"runner","session",2) as org.springframework.http.ResponseEntity<*>).statusCode.value())
            assertEquals(503,(api.state("instance",plan.transferId,"runner","session") as org.springframework.http.ResponseEntity<*>).statusCode.value())
            assertEquals(202,api.manifest("instance","runner","session",2).statusCode.value())
            assertTrue(System.nanoTime()-start<java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
            release.countDown()
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var done=api.commit("instance",plan.transferId,"runner","session",2)
            while(done is org.springframework.http.ResponseEntity<*> && System.nanoTime()<deadline){Thread.sleep(5);done=api.commit("instance",plan.transferId,"runner","session",2)}
            assertEquals(mapOf("committed" to true),done)
            assertEquals(done,api.commit("instance",plan.transferId,"runner","session",2))
            verify(manager,times(1)).remoteCommitted("instance","runner")
            assertEquals("new",base.resolve("new.txt").readText())
        }finally{release.countDown();api.close()}
    }

    @Test fun `only changed file chunks are requested and deletion is preserved`() {
        val (transfer,base,manager)=fixture()
        base.resolve("keep.txt").writeText("keep")
        base.resolve("remove.txt").writeText("remove")
        val worker=temp.resolve("worker").also { it.createDirectories() }
        worker.resolve("keep.txt").writeText("keep")
        worker.resolve("new.txt").writeText("new bytes")
        val (files,dirs)=FileInventory.collect(worker)
        val plan=transfer.begin("runner","instance",RunnerFileManifest(files,dirs))
        assertEquals(setOf("new.txt"),plan.changedFiles)
        transfer.upload("runner","instance",plan.transferId,"new.txt",0,"new bytes".toByteArray())
        transfer.commit("runner","instance",plan.transferId)
        assertEquals("keep",base.resolve("keep.txt").readText())
        assertEquals("new bytes",base.resolve("new.txt").readText())
        assertFalse(base.resolve("remove.txt").exists())
        verify(manager).remoteCommitted("instance","runner")
        val progress=mockingDetails(manager).invocations.filter {it.method.name=="fileTransferProgress"}.map {it.arguments[2] as FolderImportProgress}
        assertTrue(progress.any {it.phase=="UPLOADING" && it.files==1 && it.bytes==9L && it.totalFiles==1 && it.totalBytes==9L})
        assertEquals("VERIFYING",progress.last().phase)
    }

    @Test fun `batch validates all paths before writing and resumes only missing bytes after partial failure`() {
        val (transfer,base,manager,registry)=fixture()
        val source=temp.resolve("batch-source").createDirectory();source.resolve("one").writeText("first");source.resolve("two").writeText("second")
        val manifest=FileInventory.collect(source).let {(f,d)->RunnerFileManifest(f,d)}
        val plan=transfer.begin("runner","instance",manifest,resumeProtocol=1)
        val invalid=TransferBatch.encode(listOf(TransferPart("one",0,"first".toByteArray()),TransferPart("../escape",0,"bad".toByteArray())))
        assertFails {transfer.batch("runner","instance",plan.transferId,invalid)}
        assertFalse(temp.resolve("instance/runner-transfer/${plan.transferId}/one").exists())
        transfer.upload("runner","instance",plan.transferId,"two",0,"sec".toByteArray())
        val restarted=RunnerTransfer(manager,registry);val resumed=restarted.begin("runner","instance",manifest,resumeProtocol=1)
        assertEquals(plan.transferId,resumed.transferId)
        restarted.batch("runner","instance",plan.transferId,TransferBatch.encode(listOf(TransferPart("one",0,"first".toByteArray()),TransferPart("two",3,"ond".toByteArray()))))
        restarted.commit("runner","instance",plan.transferId)
        assertEquals(FileInventory.collect(source),FileInventory.collect(base))
        doAnswer {call->FileInventory.safePath(base,call.getArgument(1))}.`when`(manager).path(eq("instance") ?: "instance",anyString() ?: "")
        val downloaded=TransferBatch.decode(restarted.batchFiles("instance",listOf("one","two")))
        assertEquals(listOf("first","second"),downloaded.map {it.bytes.toString(Charsets.UTF_8)})
        assertFails {restarted.batchFiles("instance",listOf("../escape"))}
    }
    @Test fun `bad hash cannot replace central server data`() {
        val (transfer,base,manager)=fixture()
        base.resolve("world.dat").writeText("original")
        val worker=temp.resolve("worker").also { it.createDirectories() }
        worker.resolve("world.dat").writeText("changed")
        val (files,dirs)=FileInventory.collect(worker)
        val plan=transfer.begin("runner","instance",RunnerFileManifest(files,dirs))
        transfer.upload("runner","instance",plan.transferId,"world.dat",0,"corrupt".toByteArray())
        assertFailsWith<IllegalStateException> { transfer.commit("runner","instance",plan.transferId) }
        assertEquals("original",base.resolve("world.dat").readText())
        verify(manager,never()).remoteCommitted("instance","runner")
    }
    @Test fun `initial import requires its ticket and backs up old files before replacing without a game process`() {
        val (transfer,base,manager)=fixture();val request="fixture-import-request"
        base.resolve("old-world.dat").writeText("original")
        val worker=temp.resolve("existing-source").also {it.createDirectories()};worker.resolve("imported.dat").writeText("existing world")
        val (files,dirs)=FileInventory.collect(worker)
        val plan=transfer.begin("runner","instance",RunnerFileManifest(files,dirs),request)
        verify(manager).checkFolderImport("instance","runner",request)
        transfer.upload("runner","instance",plan.transferId,"imported.dat",0,"existing world".toByteArray())
        transfer.commit("runner","instance",plan.transferId)
        assertEquals("existing world",base.resolve("imported.dat").readText())
        val backup=temp.resolve("instance/runner-backups").listDirectoryEntries().single();assertEquals("original",backup.resolve("files/old-world.dat").readText())
        verify(manager).remoteCommitted("instance","runner",request)
        verify(manager,never()).start(anyString())
    }
    @Test fun `a busy commit does not block a different runner instance upload`() {
        val (transfer,base,manager,registry)=fixture()
        val second=temp.resolve("second/server").also {it.createDirectories()}
        `when`(manager.serverDir("second")).thenReturn(second)
        `when`(manager.profileDir("second")).thenReturn(temp.resolve("second"))
        `when`(manager.instance("second")).thenReturn(Instance("second","second",loaderVersion="21.1.252",port=25565,memoryMb=512,javaPath="java",runnerId="runner"))
        `when`(registry.active("runner")).thenReturn(RunnerDevice("runner","Mac","MAC","user","key","now",allowedInstanceIds=setOf("instance","second")))
        val wanted=temp.resolve("wanted").createDirectory().also {it.resolve("data").writeText("new")}
        val manifest=FileInventory.collect(wanted).let {(f,d)->RunnerFileManifest(f,d)}
        val first=transfer.begin("runner","instance",manifest);val next=transfer.begin("runner","second",manifest)
        transfer.upload("runner","instance",first.transferId,"data",0,"new".toByteArray())
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        doAnswer {call->
            if(call.getArgument<String>(0)=="instance" && call.getArgument<FolderImportProgress>(2).phase=="VERIFYING") {entered.countDown();check(release.await(10,java.util.concurrent.TimeUnit.SECONDS))}
            null
        }.`when`(manager).fileTransferProgress(anyString(),anyString(),any(FolderImportProgress::class.java) ?: FolderImportProgress("VERIFYING"))
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val commit=pool.submit {transfer.commit("runner","instance",first.transferId)}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            pool.submit {transfer.upload("runner","second",next.transferId,"data",0,"new".toByteArray())}.get(3,java.util.concurrent.TimeUnit.SECONDS)
            release.countDown();commit.get(5,java.util.concurrent.TimeUnit.SECONDS)
            transfer.commit("runner","second",next.transferId)
            assertEquals("new",base.resolve("data").readText());assertEquals("new",second.resolve("data").readText())
        } finally {release.countDown();pool.shutdown();assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))}
    }
    @Test fun `interrupted chunks resume after manager restart and completed files are not resent`() {
        val (transfer,base,manager,registry)=fixture()
        val source=temp.resolve("resume-source").createDirectory()
        source.resolve("done.txt").writeText("done");source.resolve("partial.txt").writeText("123456789");source.resolve("empty.txt").writeText("")
        val manifest=FileInventory.collect(source).let {(f,d)->RunnerFileManifest(f,d)}
        val first=transfer.begin("runner","instance",manifest,resumeProtocol=1)
        transfer.upload("runner","instance",first.transferId,"done.txt",0,"done".toByteArray())
        transfer.upload("runner","instance",first.transferId,"partial.txt",0,"1234".toByteArray())
        val restored=RunnerTransfer(manager,registry)
        val resume=restored.begin("runner","instance",manifest,resumeProtocol=1)
        assertEquals(first.transferId,resume.transferId);assertEquals(setOf("partial.txt"),resume.changedFiles)
        assertEquals(mapOf("partial.txt" to 4L),resume.offsets);assertEquals(8L,resume.completedBytes);assertEquals(2,resume.completedFiles)
        assertFails {restored.upload("runner","instance",resume.transferId,"partial.txt",0,"wrong".toByteArray())}
        restored.upload("runner","instance",resume.transferId,"partial.txt",4,"56789".toByteArray());restored.commit("runner","instance",resume.transferId)
        assertEquals(FileInventory.collect(source),FileInventory.collect(base))
        assertEquals("COMPLETE",restored.transferState("runner","instance",resume.transferId))
        RunnerTransfer(manager,registry).commit("runner","instance",resume.transferId)
        verify(manager,times(1)).remoteCommitted("instance","runner")
        assertTrue(temp.resolve("instance/runner-transfer").listDirectoryEntries().isEmpty())
    }
    @Test fun `old runners reuse completed files and corrupted cached files are requested again`() {
        val (transfer,_,manager,registry)=fixture()
        val source=temp.resolve("legacy-resume-source").createDirectory()
        source.resolve("good").writeText("good");source.resolve("bad").writeText("original");source.resolve("part").writeText("123456")
        val manifest=FileInventory.collect(source).let {(f,d)->RunnerFileManifest(f,d)}
        val first=transfer.begin("runner","instance",manifest)
        transfer.upload("runner","instance",first.transferId,"good",0,"good".toByteArray())
        transfer.upload("runner","instance",first.transferId,"bad",0,"original".toByteArray())
        transfer.upload("runner","instance",first.transferId,"part",0,"12".toByteArray())
        val cache=temp.resolve("instance/runner-transfer/${first.transferId}");cache.resolve("bad").writeText("tampered")
        val resumed=RunnerTransfer(manager,registry).begin("runner","instance",manifest)
        assertEquals(setOf("bad","part"),resumed.changedFiles);assertTrue(resumed.offsets.isEmpty())
        assertEquals(4L,resumed.completedBytes);assertEquals(1,resumed.completedFiles)
        assertTrue(cache.resolve("good").exists());assertFalse(cache.resolve("part").exists());assertFalse(cache.resolve("bad").exists())
        cache.resolve("link").toFile().writeText("unrecognized")
        assertFails {RunnerTransfer(manager,registry).begin("runner","instance",manifest,resumeProtocol=1)}
    }
    @Test fun `legacy import cache is adopted only with its current import authorization and hashes`() {
        val (_,_,manager,registry)=fixture();val root=temp.resolve("instance/runner-transfer").createDirectories()
        val id=java.util.UUID.randomUUID().toString();val cache=root.resolve(id).createDirectory();cache.resolve("file").writeText("received")
        val manifest=RunnerFileManifest(mapOf("file" to CloudFile(FileInventory.digest(cache.resolve("file")),8)),emptySet())
        val transfer=RunnerTransfer(manager,registry)
        assertFails {transfer.begin("runner","instance",manifest)}
        val resumed=transfer.begin("runner","instance",manifest,"current-import",1)
        assertEquals(id,resumed.transferId);assertTrue(resumed.changedFiles.isEmpty());assertEquals(8L,resumed.completedBytes)
        verify(manager).checkFolderImport("instance","runner","current-import")
        assertTrue(root.resolve("$id.pending.json").exists())
    }

    @Test fun `interruption after applying files but before writing receipt reconciles without resending`() {
        val (transfer,base,manager,registry)=fixture()
        val source=temp.resolve("receipt-source").createDirectory().also {it.resolve("world.dat").writeText("new world")}
        base.resolve("world.dat").writeText("old world")
        val manifest=FileInventory.collect(source).let {(f,d)->RunnerFileManifest(f,d)}
        val plan=transfer.begin("runner","instance",manifest,"import",1)
        transfer.upload("runner","instance",plan.transferId,"world.dat",0,"new world".toByteArray())
        val before=FileInventory.collect(base).let {(f,d)->RunnerFileManifest(f,d)}
        DeltaTransfer.apply(base,temp.resolve("instance/runner-transfer/${plan.transferId}"),before,manifest,temp.resolve("instance/runner-backups"))
        temp.resolve("instance/remote-lease").writeText("runner")
        val restarted=RunnerTransfer(manager,registry)
        val resumed=restarted.begin("runner","instance",manifest,"import",1)
        assertEquals(plan.transferId,resumed.transferId);assertTrue(resumed.changedFiles.isEmpty())
        assertEquals("COMPLETE",restarted.transferState("runner","instance",plan.transferId))
        verify(manager).remoteCommitted("instance","runner","import")
        assertEquals("new world",base.resolve("world.dat").readText())
    }

    @Test fun `manifest API responds with retry while background hash holds no transfer lock and denies running or unauthorized access`() {
        val (transfer,base,manager)=fixture();val gateway=mock(RunnerGateway::class.java)
        `when`(gateway.authorized("runner","session")).thenReturn(true)
        val manifest=RunnerFileManifest(mapOf("new" to CloudFile(java.security.MessageDigest.getInstance("SHA-256").digest("new".toByteArray()).joinToString(""){"%02x".format(it)},3)),emptySet())
        val plan=transfer.begin("runner","instance",manifest)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val cache=VerifiedInventory({path->entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));FileInventory.collect(path).let {(f,d)->RunnerFileManifest(f,d)}})
        RunnerTransfer::class.java.getDeclaredField("inventories").also {it.isAccessible=true;it.set(transfer,cache)}
        val api=RunnerTransferApi(transfer,gateway,manager)
        try {
            val first=api.manifest("instance","runner","session")
            assertEquals(503,first.statusCode.value());assertEquals("5",first.headers.getFirst("Retry-After"));assertEquals("no-store",first.headers.getFirst("Cache-Control"))
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
            try {pool.submit {transfer.upload("runner","instance",plan.transferId,"new",0,"new".toByteArray())}.get(1,java.util.concurrent.TimeUnit.SECONDS)}finally{pool.shutdownNow()}
            assertFailsWith<org.springframework.web.server.ResponseStatusException>{api.manifest("instance","runner","wrong")}
            release.countDown()
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var response=api.manifest("instance","runner","session")
            while(response.statusCode.value()==503 && System.nanoTime()<until){Thread.sleep(5);response=api.manifest("instance","runner","session")}
            assertEquals(200,response.statusCode.value());assertEquals(emptyMap(),(response.body as RunnerFileManifest).files)
            manager.runtime("instance").state="RUNNING"
            assertFailsWith<IllegalStateException>{api.manifest("instance","runner","session")}
        }finally{release.countDown();transfer.close()}
    }

}
