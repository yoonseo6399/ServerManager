package local.servermanager

import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.io.path.*
import kotlin.test.*

class FolderImportTest {
    @Test fun `normal upload and download progress remains 64 bit and only an active assigned lease can report`() {
        val manager=ServerManager();val instance=manager.create(CreateRequest("regular-transfer","21.1.219",ServerSocket(0).use {it.localPort},512))["config"] as Instance
        manager.attachRemoteController(object:RemoteController {
            override fun start(instance:Instance){}
            override fun stop(instance:Instance){}
            override fun restart(instance:Instance){}
            override fun command(instance:Instance,value:String){}
        });manager.assignRunner(instance.id,"runner")
        try {
            manager.start(instance.id)
            val size=20L*1024*1024*1024
            val download=FolderImportProgress("UPLOADING",5,size/2,10,size,direction="TO_RUNNER")
            manager.fileTransferProgress(instance.id,"runner",download)
            assertEquals(download,manager.view(instance.id)["fileTransfer"])
            manager.fileTransferProgress(instance.id,"other",download.copy(bytes=0));assertEquals(download,manager.view(instance.id)["fileTransfer"])
            manager.runtime(instance.id).remotePid=123
            manager.remoteState(instance.id,"runner","SYNCING");assertNull(manager.view(instance.id)["pid"])
            manager.fileTransferProgress(instance.id,"runner",download.copy(direction="TO_MANAGER",bytes=size))
            manager.remoteCommitted(instance.id,"runner")
            val complete=manager.view(instance.id)["fileTransfer"] as FolderImportProgress
            assertEquals("COMPLETE",complete.phase);assertEquals(size,complete.bytes)
            manager.fileTransferProgress(instance.id,"runner",download);assertEquals(complete,manager.view(instance.id)["fileTransfer"])
            // A WebSocket SYNCING/failure can arrive after the separate HTTP commit.
            manager.remoteState(instance.id,"runner","SYNCING");assertEquals("STOPPED",manager.view(instance.id)["state"])
            manager.remoteState(instance.id,"runner","SYNC_FAILED","late failure");assertEquals("STOPPED",manager.view(instance.id)["state"])
            manager.remoteState(instance.id,"runner","RUNNING");assertEquals("STOPPED",manager.view(instance.id)["state"])
            manager.remoteState(instance.id,"runner","STOPPING");assertEquals("STOPPED",manager.view(instance.id)["state"])
            assertEquals(complete,manager.view(instance.id)["fileTransfer"])
        } finally {manager.shutdown();manager.root.resolve(instance.id).toFile().deleteRecursively()}
    }
    @Test fun `import reserves stopped instance supports 20 GiB status and finishes without starting or stop alerts`() {
        val manager=ServerManager();val instance=manager.create(CreateRequest("folder-import-test","21.1.219",ServerSocket(0).use {it.localPort},512))["config"] as Instance
        val signals=mutableListOf<String>();var ticket="";var starts=0
        val controller=object:RemoteController {
            override fun start(instance:Instance){starts++}
            override fun stop(instance:Instance){}
            override fun restart(instance:Instance){}
            override fun command(instance:Instance,value:String){}
            override fun importFolder(instance:Instance,requestId:String){ticket=requestId}
        }
        manager.attachRemoteController(controller);manager.assignRunner(instance.id,"runner");val sub=manager.onSignal {signals.add(it.kind)}
        try {
            manager.importRemoteFolder(instance.id);assertEquals("IMPORTING",manager.view(instance.id)["state"]);assertEquals(0,starts)
            assertFailsWith<IllegalStateException>{manager.start(instance.id)}
            assertFailsWith<IllegalStateException>{manager.assignRunner(instance.id,null)}
            assertFailsWith<IllegalStateException>{manager.checkFolderImport(instance.id,"other",ticket)}
            assertFailsWith<IllegalStateException>{manager.checkFolderImport(instance.id,"runner","old")}
            val size=20L*1024*1024*1024
            manager.folderImportProgress(instance.id,"runner",ticket,FolderImportProgress("UPLOADING",800,12L*1024*1024*1024,1200,size))
            assertEquals(size,(manager.view(instance.id)["folderImport"] as FolderImportProgress).totalBytes)
            manager.beginTransferProgress(instance.id,12L*1024*1024*1024)
            manager.runnerFolderProgress(instance.id,"runner",ticket,FolderImportProgress("UPLOADING",100,1024,1200,size))
            assertEquals(800,(manager.view(instance.id)["folderImport"] as FolderImportProgress).files)
            assertEquals(12L*1024*1024*1024,(manager.view(instance.id)["folderImport"] as FolderImportProgress).bytes)
            manager.runnerFolderProgress(instance.id,"runner",ticket,FolderImportProgress("RETRYING",message="연결 대기"))
            val waiting=manager.view(instance.id)["folderImport"] as FolderImportProgress
            assertEquals(800,waiting.files);assertEquals("RETRYING",waiting.phase);assertEquals(0.0,waiting.bytesPerSecond)
            manager.folderImportProgress(instance.id,"runner",ticket,waiting.copy(phase="UPLOADING",files=900))
            manager.runnerFolderProgress(instance.id,"runner",ticket,FolderImportProgress("SCANNING",50,100))
            assertEquals(900,(manager.view(instance.id)["folderImport"] as FolderImportProgress).files)
            manager.remoteDisconnected("runner");assertEquals("UNKNOWN",manager.view(instance.id)["state"])
            manager.checkFolderImport(instance.id,"runner",ticket)
            manager.remoteCommitted(instance.id,"runner",ticket)
            assertEquals("STOPPED",manager.view(instance.id)["state"]);assertEquals("COMPLETE",(manager.view(instance.id)["folderImport"] as FolderImportProgress).phase)
            assertEquals("COMPLETE",manager.folderImportState(instance.id,"runner",ticket));assertEquals(listOf("import"),signals);assertEquals(0,starts)
            assertFalse(manager.root.resolve(instance.id).resolve("remote-lease").exists())
            assertEquals("EXPIRED",manager.folderImportState(instance.id,"runner","old"))
        } finally {sub.close();manager.shutdown();manager.root.resolve(instance.id).toFile().deleteRecursively()}
    }
    @Test fun `failed or unsupported import keeps old files and releases only its own request`() {
        val manager=ServerManager();val instance=manager.create(CreateRequest("folder-failure","21.1.219",ServerSocket(0).use {it.localPort},512))["config"] as Instance
        var ticket=""
        manager.attachRemoteController(object:RemoteController {
            override fun start(instance:Instance){}
            override fun stop(instance:Instance){}
            override fun restart(instance:Instance){}
            override fun command(instance:Instance,value:String){}
            override fun importFolder(instance:Instance,requestId:String){ticket=requestId}
        })
        manager.assignRunner(instance.id,"runner");val file=manager.serverDir(instance.id).resolve("original.dat").also {it.writeText("keep")}
        try {
            manager.importRemoteFolder(instance.id)
            assertFailsWith<IllegalStateException>{manager.folderImportFailed(instance.id,"runner","wrong","old failure")}
            assertEquals("IMPORTING",manager.view(instance.id)["state"])
            manager.folderImportProgress(instance.id,"runner",ticket,FolderImportProgress("UPLOADING",7116,102400,8000,204800))
            manager.folderImportFailed(instance.id,"runner",ticket,"파일 청크 업로드 실패: HTTP 403")
            assertEquals("PENDING",manager.folderImportState(instance.id,"runner",ticket))
            assertTrue(manager.root.resolve(instance.id).resolve("remote-lease").exists())
            val retry=manager.view(instance.id)["folderImport"] as FolderImportProgress
            assertEquals("RETRYING",retry.phase);assertEquals(7116,retry.files);assertEquals(102400L,retry.bytes)
            manager.folderImportFailed(instance.id,"runner",ticket,"source changed")
            assertEquals("FAILED",manager.view(instance.id)["state"]);assertEquals("keep",file.readText())
            assertFalse(manager.root.resolve(instance.id).resolve("remote-lease").exists())
            assertFailsWith<IllegalArgumentException>{FolderImportProgress("UPLOADING",bytes=-1).validated()}
        } finally {manager.shutdown();manager.root.resolve(instance.id).toFile().deleteRecursively()}
    }
}
