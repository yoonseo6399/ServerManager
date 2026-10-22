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
    @Suppress("UNCHECKED_CAST") private fun <T> field(agent:RunnerAgent,name:String)=RunnerAgent::class.java.getDeclaredField(name).also {it.isAccessible=true}.get(agent) as T
    @Test fun `real runner workspace reads bypass transfer lock and live mod mutations are reported`() {
        val cfg=config();val home=temp.resolve("agent");val base=home.resolve("instances/${cfg.id}/server").createDirectories()
        base.resolve("mods").createDirectory().resolve("live.jar").writeText("actual runner mod");base.resolve("live.txt").writeText("actual runner file")
        val agent=RunnerAgent(home,RunnerIdentity("https://localhost:1","runner","fixture","unused","MAC"))
        val socket=mock(java.net.http.WebSocket::class.java);val messages=java.util.concurrent.CopyOnWriteArrayList<String>()
        doAnswer {messages.add(it.arguments[0].toString());CompletableFuture.completedFuture(socket)}.`when`(socket).sendText(any(CharSequence::class.java),anyBoolean())
        field<java.util.concurrent.atomic.AtomicReference<java.net.http.WebSocket?>>(agent,"connection").set(socket)
        val process=mock(Process::class.java);`when`(process.isAlive).thenReturn(true);field<MutableMap<String,Process>>(agent,"processes")[cfg.id]=process
        val lock=Any();field<MutableMap<String,Any>>(agent,"operationLocks")[cfg.id]=lock
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val pool=Executors.newSingleThreadExecutor()
        pool.submit {synchronized(lock){entered.countDown();release.await(5,TimeUnit.SECONDS)}}
        val method=RunnerAgent::class.java.getDeclaredMethod("workspace",String::class.java,String::class.java,String::class.java).also {it.isAccessible=true}
        fun call(request:WorkspaceRequest):com.fasterxml.jackson.databind.JsonNode {method.invoke(agent,cfg.id,"request",mapper.writeValueAsString(request));return mapper.readTree(messages.last {it.contains("workspace-result")})}
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));val start=System.nanoTime()
            val response=call(WorkspaceRequest("STAT",cfg,"live.txt"));assertEquals("OK",response.path("state").asText());assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2))
            release.countDown()
            val revision=ModInventory.scan(base.resolve("mods")).revision
            val result=call(WorkspaceRequest("MOD_TOGGLE",cfg,data=mapper.writeValueAsString(ModSelection(revision,setOf("live.jar"))),mutationToken="approved-write"))
            assertEquals("OK",result.path("state").asText());assertEquals("actual runner mod",base.resolve("mods/live.jar.disabled").readText())
            assertEquals("approved-write",home.resolve("instances/${cfg.id}/local-edits.pending").readText())
        }finally{release.countDown();pool.shutdownNow();field<MutableMap<String,Process>>(agent,"processes").clear();agent.shutdownGracefully()}
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
    @Test fun `real runner HTTP resumes failed snapshot while newer original and game stay untouched`()=managerFixture {manager,cfg->
        manager.start(cfg.id);val token=manager.root.resolve(cfg.id).resolve("remote-run").readText()
        val registry=mock(RunnerRegistry::class.java);`when`(registry.active("runner")).thenReturn(RunnerDevice("runner","test","MAC","owner","key","now",allowedInstanceIds=setOf(cfg.id)))
        val transfer=RunnerTransfer(manager,registry);var fail=true
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") {exchange->
            var status=200
            val query=exchange.requestURI.rawQuery.orEmpty().split('&').filter {it.isNotEmpty()}.associate {val pair=it.split('=',limit=2);pair[0] to java.net.URLDecoder.decode(pair.getOrElse(1){""},Charsets.UTF_8)}
            val op=exchange.requestURI.path.substringAfterLast('/')
            val result:Any=try {when(op) {
                "capabilities" -> mapOf("stoppedSnapshotProtocol" to 1,"batchProtocol" to 0)
                "snapshot-ready" -> {manager.snapshotReady(cfg.id,"runner",mapper.readValue(exchange.requestBody,SnapshotReady::class.java));mapOf("ok" to true)}
                "snapshot-progress" -> mapOf("ok" to true)
                "transfer-state" -> mapOf("state" to transfer.transferState("runner",cfg.id,query.getValue("transferId")))
                "begin" -> {val request=mapper.readValue(exchange.requestBody,RunnerTransferBegin::class.java);transfer.begin("runner",cfg.id,request.manifest,resumeProtocol=request.resumeProtocol,generation=request.generation)}
                "chunk" -> if(fail){fail=false;status=500;mapOf("error" to "injected connection failure")}else {transfer.upload("runner",cfg.id,query.getValue("transferId"),query.getValue("path"),query.getValue("offset").toLong(),exchange.requestBody.readAllBytes());mapOf("ok" to true)}
                "commit" -> {transfer.commit("runner",cfg.id,query.getValue("transferId"));mapOf("ok" to true)}
                else -> {status=404;mapOf("error" to op)}
            }}catch(e:Throwable){status=409;mapOf("error" to (e.message ?: e.javaClass.simpleName))}
            val bytes=mapper.writeValueAsBytes(result);exchange.sendResponseHeaders(status,bytes.size.toLong());exchange.responseBody.use {it.write(bytes)}
        }
        server.start();val home=temp.resolve("http-agent");val root=home.resolve("instances/${cfg.id}").createDirectories();val base=root.resolve("server").createDirectory()
        base.resolve("world.txt").writeText("stopped original")
        root.resolve("dirty").writeText("99999999|1");root.resolve("exit.json").writeText(mapper.writeValueAsString(RunnerExitReport(0)))
        val agent=RunnerAgent(home,RunnerIdentity("http://127.0.0.1:${server.address.port}","runner","fixture","unused","MAC"))
        field<MutableMap<String,Instance>>(agent,"configs")[cfg.id]=cfg.copy(runToken=token)
        RunnerAgent::class.java.getDeclaredField("sessionToken").also {it.isAccessible=true}.set(agent,"session")
        val upload=RunnerAgent::class.java.getDeclaredMethod("uploadStopped",String::class.java).also {it.isAccessible=true}
        try {
            upload.invoke(agent,cfg.id);assertEquals(1,StoppedSnapshots(root.resolve("stopped-snapshots")).list().size);assertFalse(root.resolve("dirty").exists());assertEquals("STOPPED",manager.runtime(cfg.id).state)
            manager.start(cfg.id);manager.remoteState(cfg.id,"runner","RUNNING");manager.runtime(cfg.id).remotePid=4567
            val process=mock(Process::class.java);`when`(process.isAlive).thenReturn(true);field<MutableMap<String,Process>>(agent,"processes")[cfg.id]=process
            base.resolve("world.txt").writeText("new live original")
            upload.invoke(agent,cfg.id)
            assertTrue(StoppedSnapshots(root.resolve("stopped-snapshots")).list().isEmpty())
            assertEquals("stopped original",manager.serverDir(cfg.id).resolve("world.txt").readText());assertEquals("new live original",base.resolve("world.txt").readText())
            assertEquals("RUNNING",manager.runtime(cfg.id).state);assertEquals(4567,manager.runtime(cfg.id).remotePid)
        }finally{field<MutableMap<String,Process>>(agent,"processes").clear();agent.shutdownGracefully();server.stop(0);transfer.close()}
    }

}
