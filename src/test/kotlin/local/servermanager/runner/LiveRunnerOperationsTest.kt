package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.*
import kotlin.io.path.*
import kotlin.test.*

class LiveRunnerOperationsTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private fun config()=Instance(UUID.randomUUID().toString(),"live",loader="Fabric",loaderVersion="0.16.14",port=25565,memoryMb=512,javaPath="java",runnerId="runner")
    @Test fun `stopped copies survive source rewrites and queue reconstruction without hard links`() {
        val source=temp.resolve("source").createDirectory();source.resolve("world").createDirectory().resolve("data.txt").writeText("stopped world")
        val store=StoppedSnapshots(temp.resolve("copies"));val first=store.capture(source,"run-1",RunnerExitReport(1),"rev-1")
        source.resolve("world/data.txt").writeText("new running world")
        assertEquals("stopped world",first.path.resolve("server/world/data.txt").readText())
        assertNotEquals(Files.getAttribute(source.resolve("world/data.txt"),"basic:fileKey"),Files.getAttribute(first.path.resolve("server/world/data.txt"),"basic:fileKey"))
        val second=store.capture(source,"run-2",RunnerExitReport(0),"rev-2")
        val restored=StoppedSnapshots(temp.resolve("copies")).list()
        assertEquals(listOf(first.ready,second.ready),restored.map {it.ready})
        store.remove(first);assertEquals("new running world",store.list().single().path.resolve("server/world/data.txt").readText())
        val outside=temp.resolve("outside").also {it.writeText("private")};Files.createSymbolicLink(source.resolve("link"),outside)
        assertFails {store.capture(source,"run-3",null,null)};assertEquals(1,store.list().size)
    }
    @Test fun `live files and mods preserve originals reject stale edits and enforce root`() {
        val base=temp.resolve("server").createDirectory();base.resolve("mods").createDirectory().resolve("sample.jar").writeText("jar fixture")
        val file=base.resolve("config.txt").also {it.writeText("first\r\nlast\r\n")};var marker:String?=null
        // Production live policy does not gate writes on Minecraft being alive; the callback only coordinates maintenance.
        val workspace=RunnerWorkspace(base,temp.resolve("history"),beforeMutation={marker=it}){}
        val cfg=config()
        fun call(op:String,path:String="",data:String?=null,revision:String?=null,upload:String?=null)=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(workspace.execute(WorkspaceRequest(op,cfg,path,data=data,revision=revision,uploadId=upload,mutationToken="consent-1")))
        val upload=call("WRITE_BEGIN","config.txt",revision=FileInventory.digest(file)).path("uploadId").asText()
        val exact="first\r\nadded\r\nlast\r\n"
        call("WRITE_CHUNK","config.txt",java.util.Base64.getEncoder().encodeToString(exact.toByteArray()),upload=upload)
        call("WRITE_COMMIT","config.txt",upload=upload);assertEquals(exact,file.readText());assertEquals("consent-1",marker)
        assertTrue(temp.resolve("history").listDirectoryEntries().any {it.isRegularFile() && it.readText()=="first\r\nlast\r\n"})
        assertFails {call("WRITE_BEGIN","config.txt",revision="stale")}
        assertFails {call("READ","../outside")}
        val mods=call("MOD_SCAN");val revision=mods.path("revision").asText()
        call("MOD_TOGGLE",data=mapper.writeValueAsString(ModSelection(revision,setOf("sample.jar"),false)))
        assertFalse(base.resolve("mods/sample.jar").exists());assertEquals("jar fixture",base.resolve("mods/sample.jar.disabled").readText())
        assertFails {call("MOD_TOGGLE",data=mapper.writeValueAsString(ModSelection(revision,setOf("sample.jar"),false)))}
    }
    private fun managerFixture(action:(ServerManager,Instance)->Unit) {
        val manager=ServerManager();val cfg=(manager.create(CreateRequest("snapshot-${UUID.randomUUID()}","0.16.14",java.net.ServerSocket(0).use {it.localPort},512,loader="Fabric"))["config"] as Instance)
        manager.instance(cfg.id).runnerId="runner";manager.runtime(cfg.id).state="STOPPED"
        val controller=mock(RemoteController::class.java);`when`(controller.supportsLiveFiles(manager.instance(cfg.id))).thenReturn(true);manager.attachRemoteController(controller)
        try{action(manager,manager.instance(cfg.id))}finally{manager.shutdown();manager.root.resolve(cfg.id).toFile().deleteRecursively()}
    }
    private fun generation(n:Int)="%019d-%s".format(n,UUID.randomUUID())
    @Test fun `old completion and repeated ready cannot stop new game or clear its lease`()=managerFixture {manager,cfg->
        manager.start(cfg.id)
        val root=manager.root.resolve(cfg.id);val token=root.resolve("remote-run").readText()
        val ready=SnapshotReady(generation(1),token,RunnerExitReport(1),null)
        manager.snapshotReady(cfg.id,"runner",ready);assertEquals("STOPPED",manager.runtime(cfg.id).state);assertTrue(manager.backgroundPending(cfg.id))
        assertFails {manager.assignRunner(cfg.id,"other")}
        manager.start(cfg.id);val nextToken=root.resolve("remote-run").readText();assertNotEquals(token,nextToken)
        manager.remoteState(cfg.id,"runner","RUNNING");manager.runtime(cfg.id).remotePid=9876
        manager.snapshotReady(cfg.id,"runner",ready);manager.snapshotCommitted(cfg.id,"runner",ready.generation);manager.snapshotCommitted(cfg.id,"runner",ready.generation)
        assertEquals("RUNNING",manager.runtime(cfg.id).state);assertEquals(9876,manager.runtime(cfg.id).remotePid);assertEquals(nextToken,root.resolve("remote-run").readText());assertTrue(root.resolve("remote-lease").exists())
        assertFails {manager.snapshotReady(cfg.id,"runner",SnapshotReady(generation(2),token,null,null))}
        assertFails {manager.snapshotCommitted(cfg.id,"other",ready.generation)}
    }
    @Test fun `multiple stops and pending edits are ordered and stale mirrors cannot erase new edits`()=managerFixture {manager,cfg->
        val root=manager.root.resolve(cfg.id)
        fun stopCopy(n:Int):SnapshotReady {
            manager.start(cfg.id);val ready=SnapshotReady(generation(n),root.resolve("remote-run").readText(),RunnerExitReport(0),"edit-$n")
            root.resolve("runner-edits.pending").writeText("edit-$n");manager.snapshotReady(cfg.id,"runner",ready);return ready
        }
        val a=stopCopy(1);val b=stopCopy(2)
        assertFails {manager.checkSnapshot(cfg.id,"runner",b.generation)}
        manager.snapshotCommitted(cfg.id,"runner",a.generation);assertEquals("edit-2",root.resolve("runner-edits.pending").readText())
        manager.checkSnapshot(cfg.id,"runner",b.generation);manager.snapshotCommitted(cfg.id,"runner",b.generation);assertFalse(manager.backgroundPending(cfg.id))
        val edit=SnapshotReady(generation(3),null,null,"idle-edit",editOnly=true);root.resolve("runner-edits.pending").writeText("idle-edit")
        manager.snapshotReady(cfg.id,"runner",edit);assertEquals("STOPPED",manager.runtime(cfg.id).state)
        manager.snapshotCommitted(cfg.id,"runner",edit.generation);assertFalse(manager.backgroundPending(cfg.id))
    }
    @Test fun `generation transfer mirrors files while active game state remains unchanged`()=managerFixture {manager,cfg->
        manager.start(cfg.id);val ready=SnapshotReady(generation(1),manager.root.resolve(cfg.id).resolve("remote-run").readText(),RunnerExitReport(0),null)
        manager.snapshotReady(cfg.id,"runner",ready);manager.start(cfg.id);manager.remoteState(cfg.id,"runner","RUNNING");manager.runtime(cfg.id).remotePid=321
        val registry=mock(RunnerRegistry::class.java);`when`(registry.active("runner")).thenReturn(RunnerDevice("runner","test","MAC","owner","key","now",allowedInstanceIds=setOf(cfg.id)))
        val transfer=RunnerTransfer(manager,registry)
        try {
            val manifest=RunnerFileManifest((1..8).associate {"file-$it.txt" to CloudFile(java.security.MessageDigest.getInstance("SHA-256").digest("value-$it".toByteArray()).joinToString(""){b->"%02x".format(b)},7)},emptySet())
            val plan=transfer.begin("runner",cfg.id,manifest,generation=ready.generation)
            val pool=Executors.newFixedThreadPool(4)
            try{pool.invokeAll((1..8).map {n->Callable {transfer.upload("runner",cfg.id,plan.transferId,"file-$n.txt",0,"value-$n".toByteArray())}}).forEach {it.get()}}finally{pool.shutdownNow()}
            assertFails {transfer.upload("runner",cfg.id,plan.transferId,"file-1.txt",0,"value-1".toByteArray())}
            transfer.commit("runner",cfg.id,plan.transferId);transfer.commit("runner",cfg.id,plan.transferId)
            assertEquals("RUNNING",manager.runtime(cfg.id).state);assertEquals(321,manager.runtime(cfg.id).remotePid)
            assertEquals("value-8",manager.serverDir(cfg.id).resolve("file-8.txt").readText())
        }finally{transfer.close()}
    }
}
