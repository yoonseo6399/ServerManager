package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.*
import java.util.Base64
import kotlin.io.path.*
import kotlin.test.*

class RunnerWorkspaceTest {
    @TempDir lateinit var temp:Path
    private val config=Instance("instance","test",loaderVersion="21.1.249",port=25565,memoryMb=512,javaPath="java",runnerId="runner",fileSyncEnabled=false)
    @Test fun `file access is rooted blocks links and traversal and refuses mutation while running`() {
        val base=temp.resolve("server").createDirectory();val outside=temp.resolve("private.txt").also {it.writeText("secret")};Files.createSymbolicLink(base.resolve("link"),outside)
        var running=false;val service=RunnerWorkspace(base,temp.resolve("history")){check(!running)}
        fun call(op:String,path:String="")=service.execute(WorkspaceRequest(op,config,path))
        val list=jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(call("LIST"));assertEquals(0,list.path("entries").size())
        for(path in listOf("../private.txt","/private.txt","link","link/other","a/../../private.txt"))assertFails {call("STAT",path)}
        call("MKDIR","config");assertTrue(base.resolve("config").isDirectory());running=true
        assertFailsWith<IllegalStateException>{call("DELETE","config")};assertFailsWith<IllegalStateException>{call("WRITE_BEGIN","other.txt")};running=false
        assertFails {call("DELETE","")};assertTrue(base.isDirectory());assertEquals("secret",outside.readText())
    }
    @Test fun `streaming edits use exact offsets revisions and preserve originals in local history`() {
        val base=temp.resolve("server").createDirectory();val file=base.resolve("server.properties").also {it.writeText("original")};val history=temp.resolve("history")
        val service=RunnerWorkspace(base,history){}
        fun call(op:String,path:String="server.properties",offset:Long=0,data:String?=null,revision:String?=null,uploadId:String?=null)=jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(service.execute(WorkspaceRequest(op,config,path,offset=offset,data=data,revision=revision,uploadId=uploadId)))
        val hash=FileInventory.digest(file);val upload=call("WRITE_BEGIN",revision=hash).path("uploadId").asText();assertTrue(service.busy())
        assertFails {call("WRITE_CHUNK",offset=1,data=Base64.getEncoder().encodeToString("new".toByteArray()),uploadId=upload)}
        call("WRITE_CHUNK",data=Base64.getEncoder().encodeToString("new".toByteArray()),uploadId=upload);file.writeText("external change")
        assertFailsWith<IllegalStateException>{call("WRITE_COMMIT",uploadId=upload)};assertEquals("external change",file.readText());call("WRITE_ABORT",uploadId=upload);assertFalse(service.busy())
        val replacement=call("WRITE_BEGIN").path("uploadId").asText();call("WRITE_CHUNK",data=Base64.getEncoder().encodeToString("replacement".toByteArray()),uploadId=replacement);call("WRITE_COMMIT",uploadId=replacement)
        assertEquals("replacement",file.readText());assertEquals("external change",history.listDirectoryEntries().single().readText())
        call("DELETE");assertFalse(file.exists());assertEquals(2,history.listDirectoryEntries().size)
    }
    @Test fun `interrupted replacement restores saved original and completed replacement is retained`() {
        for(completed in listOf(false,true)) {
            val root=temp.resolve(if(completed)"completed" else "interrupted").createDirectory();val base=root.resolve("server").createDirectory();val history=root.resolve("history").createDirectory();val original=history.resolve("saved-old.txt").also {it.writeText("old")};val target=base.resolve("test.txt");val temporary=history.resolve(".remote-upload-test.tmp")
            if(completed)target.writeText("new") else temporary.writeText("new")
            val sha=java.security.MessageDigest.getInstance("SHA-256").digest("new".toByteArray()).joinToString(""){"%02x".format(it)}
            history.resolve(".write-test.json").writeText(jacksonObjectMapper().writeValueAsString(mapOf("path" to "test.txt","temporary" to temporary.name,"backup" to original.name,"size" to 3,"sha256" to sha)))
            RunnerWorkspace(base,history){}.recover();assertEquals(if(completed)"new" else "old",target.readText());assertFalse(temporary.exists());assertTrue(history.listDirectoryEntries(".write-*.json").isEmpty())
        }
    }

}
