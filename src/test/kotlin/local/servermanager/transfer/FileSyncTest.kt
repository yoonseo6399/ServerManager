package local.servermanager

import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.io.path.*
import kotlin.test.*

class FileSyncTest {
    @Test fun `sync off pins runner blocks central writes and reenabling waits for current runner import`() {
        val manager=ServerManager();val i=manager.create(CreateRequest("sync-policy","21.1.249",ServerSocket(0).use {it.localPort},512))["config"] as Instance
        var ticket="";var starts=0
        manager.attachRemoteController(object:RemoteController {
            override fun start(instance:Instance){starts++}
            override fun stop(instance:Instance){}
            override fun restart(instance:Instance){}
            override fun command(instance:Instance,value:String){}
            override fun supportsWorkspace(instance:Instance)=true
            override fun importFolder(instance:Instance,requestId:String){assertFalse(instance.syncEnabled());ticket=requestId}
        });manager.assignRunner(i.id,"runner")
        try {
            manager.setFileSync(i.id,false);assertFalse(manager.instance(i.id).syncEnabled())
            manager.withRemoteFiles(i.id){
                val received=java.util.concurrent.CountDownLatch(1);val thread=Thread {synchronized(manager.runtime(i.id)){received.countDown()}};thread.start();assertTrue(received.await(1,java.util.concurrent.TimeUnit.SECONDS));thread.join()
                assertFailsWith<IllegalStateException>{manager.start(i.id)}
            }
            assertFailsWith<IllegalStateException>{manager.assignRunner(i.id,"other")};assertFailsWith<IllegalStateException>{manager.assignRunner(i.id,null)}
            assertFailsWith<IllegalStateException>{manager.write(i.id,"server.properties","stale".toByteArray())}
            assertFailsWith<IllegalStateException>{manager.reserveMods(i.id)}
            assertFailsWith<IllegalStateException>{manager.importRemoteFolder(i.id)}
            manager.setFileSync(i.id,true);assertFalse(manager.instance(i.id).syncEnabled());assertTrue(ticket.isNotEmpty());assertEquals(0,starts)
            assertFailsWith<IllegalStateException>{manager.assignRunner(i.id,"other")}
            manager.remoteCommitted(i.id,"runner",ticket);assertTrue(manager.instance(i.id).syncEnabled());manager.assignRunner(i.id,"other");assertEquals("other",manager.instance(i.id).runnerId)
        }finally{manager.shutdown();manager.root.resolve(i.id).toFile().deleteRecursively()}
    }
    @Test fun `remote installation never runs central installer and local only stop releases lease without sync`() {
        val manager=ServerManager();val i=manager.create(CreateRequest("remote-install","21.1.249",ServerSocket(0).use {it.localPort},512))["config"] as Instance;var ticket=""
        manager.attachRemoteController(object:RemoteController {
            override fun start(instance:Instance){}
            override fun stop(instance:Instance){}
            override fun restart(instance:Instance){}
            override fun command(instance:Instance,value:String){}
            override fun supportsWorkspace(instance:Instance)=true
            override fun install(instance:Instance,requestId:String){ticket=requestId}
        });manager.assignRunner(i.id,"runner")
        try {
            manager.setFileSync(i.id,false);manager.install(i.id);assertEquals("INSTALLING",manager.runtime(i.id).state);assertTrue(ticket.isNotEmpty());assertFalse(manager.root.resolve(i.id).resolve("installer.jar").exists())
            manager.remoteInstallResult(i.id,"other",ticket,true,"bad");assertEquals("INSTALLING",manager.runtime(i.id).state)
            manager.remoteInstallResult(i.id,"runner",ticket,true,"done");assertEquals("STOPPED",manager.runtime(i.id).state)
            manager.start(i.id);manager.remoteWorkerReady(i.id,"runner");assertEquals("TRANSFERRING",manager.runtime(i.id).state);manager.remoteState(i.id,"runner","RUNNING");manager.remoteState(i.id,"runner","SYNCING","종료 코드 0")
            manager.remoteExited(i.id,"runner",RunnerExitReport(0));manager.remoteWorkerReady(i.id,"runner");assertEquals("STOPPED",manager.runtime(i.id).state);assertFalse(manager.root.resolve(i.id).resolve("remote-lease").exists());assertTrue(manager.runtime(i.id).remoteWorkerReady);assertTrue(manager.runtime(i.id).remoteCloudReady)
        }finally{manager.shutdown();manager.root.resolve(i.id).toFile().deleteRecursively()}
    }
}
