package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.server.ResponseStatusException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

class FileUploadsTest {
 @TempDir lateinit var temp:Path
 private val manager=ServerManager()
 private lateinit var accounts:AuthStore
 private lateinit var uploads:FileUploads
 private lateinit var id:String
 private lateinit var owner:String
 private lateinit var base:Path
 private lateinit var workspace:RunnerWorkspace
 private val mapper=jacksonObjectMapper()
 private val tokens=mutableListOf<String>()
 private var hook:(WorkspaceRequest)->Unit={}
 @BeforeEach fun setup(){
  accounts=AuthStore(temp.resolve("auth"));owner=accounts.accounts().single().id
  id=(manager.create(CreateRequest("Upload fixture","21.1.219",loader="NeoForge"))["config"] as Instance).id
  manager.instance(id).runnerId="runner";base=temp.resolve("runner/server").createDirectories();workspace=RunnerWorkspace(base,temp.resolve("history")){}
  val proxy=Proxy.newProxyInstance(RemoteController::class.java.classLoader,arrayOf(RemoteController::class.java)){_,method,args->when(method.name){
   "workspace"->{val req=args!![1] as WorkspaceRequest;hook(req);mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(workspace.execute(req))}
   "supportsLiveFiles","supportsWorkspace"->true
   else->null
  }} as RemoteController
  manager.attachRemoteController(proxy);uploads=FileUploads(manager,RemoteFiles(manager),accounts)
 }
 @AfterEach fun cleanup(){uploads.close();accounts.close();manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively();tokens.forEach {manager.root.resolve("file-uploads/$it").toFile().deleteRecursively()}}
 private fun create(content:ByteArray,name:String="mod.jar",path:String="mods"):FileUploadStatus {
  val s=uploads.create(owner,id,FileUploadRequest(name,content.size.toLong(),path,UUID.randomUUID().toString()));tokens.add(s.token)
  if(content.isNotEmpty())uploads.append(owner,id,s.token,0,content.inputStream());return s
 }
 private fun await(token:String,phase:String):FileUploadStatus {
  val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
  while(true){val s=uploads.status(owner,id,token);if(s.phase==phase)return s;assertTrue(System.nanoTime()<end,"expected $phase, got $s");Thread.sleep(10)}
 }
 @Test fun `slow remote upload is asynchronous and cancel aborts temp preserving original before next queued file`() {
  base.resolve("mods").createDirectories();base.resolve("mods/mod.jar").writeText("ORIGINAL")
  val entered=CountDownLatch(1);val release=CountDownLatch(1);var blocks=true
  hook={if(it.op=="WRITE_CHUNK"&&blocks){blocks=false;entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}}
  val first=create(ByteArray(200000){(it%255).toByte()});val start=System.nanoTime()
  uploads.complete(owner,id,first.token);assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000)
  assertTrue(entered.await(3,TimeUnit.SECONDS));val second=create("NEXT".toByteArray(),"next.jar");uploads.complete(owner,id,second.token)
  assertEquals("QUEUED",uploads.status(owner,id,second.token).phase)
  assertEquals("CANCELLED",uploads.cancel(owner,id,first.token).phase);release.countDown()
  await(second.token,"COMPLETE");assertEquals("ORIGINAL",base.resolve("mods/mod.jar").readText());assertEquals("NEXT",base.resolve("mods/next.jar").readText())
  assertFalse(workspace.busy());assertTrue(temp.resolve("history").listDirectoryEntries(".remote-upload-*").isEmpty())
 }
 @Test fun `completed replacement preserves backup and repeated complete never applies twice`() {
  base.resolve("mods").createDirectories();base.resolve("mods/mod.jar").writeText("OLD")
  val s=create("NEW".toByteArray());uploads.complete(owner,id,s.token);await(s.token,"COMPLETE");uploads.complete(owner,id,s.token)
  assertEquals("NEW",base.resolve("mods/mod.jar").readText());assertEquals(listOf("OLD"),temp.resolve("history").listDirectoryEntries().map {it.readText()})
 }
 @Test fun `commit boundary refuses cancellation and reports actual completion`() {
  val entered=CountDownLatch(1);val release=CountDownLatch(1)
  hook={if(it.op=="WRITE_COMMIT"){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}}
  val s=create("NEW".toByteArray());uploads.complete(owner,id,s.token);assertTrue(entered.await(3,TimeUnit.SECONDS))
  assertEquals("APPLYING",uploads.status(owner,id,s.token).phase);assertFalse(uploads.status(owner,id,s.token).cancellable)
  assertFailsWith<IllegalStateException>{uploads.cancel(owner,id,s.token)};release.countDown();await(s.token,"COMPLETE")
 }
 @Test fun `permission revocation during transfer aborts before commit and keeps original`() {
  base.resolve("mods").createDirectories();base.resolve("mods/mod.jar").writeText("ORIGINAL")
  val member=accounts.invitation(accounts.invite("admin",mapOf(id to "ADMIN")))!!.userId
  val s=uploads.create(member,id,FileUploadRequest("mod.jar",5,"mods",UUID.randomUUID().toString()));tokens.add(s.token);uploads.append(member,id,s.token,0,"hello".byteInputStream())
  val entered=CountDownLatch(1);val release=CountDownLatch(1);hook={if(it.op=="WRITE_CHUNK"){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}}
  uploads.complete(member,id,s.token);assertTrue(entered.await(3,TimeUnit.SECONDS));accounts.updateUser(member,true,emptyMap());release.countDown()
  val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(workspace.busy()){assertTrue(System.nanoTime()<end);Thread.sleep(10)}
  assertEquals("ORIGINAL",base.resolve("mods/mod.jar").readText())
 }
 @Test fun `receiving and queued cancellation retains status and prevents late chunks and scheduling`() {
  val s=uploads.create(owner,id,FileUploadRequest("data.txt",10,"",UUID.randomUUID().toString()));tokens.add(s.token)
  uploads.append(owner,id,s.token,0,byteArrayOf(1,2).inputStream());assertEquals("CANCELLED",uploads.cancel(owner,id,s.token).phase)
  assertFailsWith<IllegalStateException>{uploads.append(owner,id,s.token,2,byteArrayOf(3).inputStream())}
  assertEquals("CANCELLED",uploads.complete(owner,id,s.token).phase);assertFalse(base.resolve("data.txt").exists());assertFalse(manager.root.resolve("file-uploads/${s.token}/content.part").exists())
  uploads.close();uploads=FileUploads(manager,RemoteFiles(manager),accounts);assertEquals("CANCELLED",uploads.status(owner,id,s.token).phase)
  uploads.dismiss(owner,id,s.token);uploads.close();uploads=FileUploads(manager,RemoteFiles(manager),accounts);assertTrue(uploads.list(owner,id).none {it.token==s.token})
 }
 @Test fun `ownership paths sizes offsets and active limits are checked without changing files`() {
  val s=uploads.create(owner,id,FileUploadRequest("data.txt",5,"",UUID.randomUUID().toString()));tokens.add(s.token)
  val other=accounts.invitation(accounts.invite("other",mapOf(id to "ADMIN")))!!.userId
  assertEquals(404,assertFailsWith<ResponseStatusException>{uploads.status(other,id,s.token)}.statusCode.value())
  assertEquals(409,assertFailsWith<ResponseStatusException>{uploads.append(owner,id,s.token,2,byteArrayOf(1).inputStream())}.statusCode.value())
  assertFailsWith<IllegalArgumentException>{uploads.append(owner,id,s.token,0,ByteArray(6).inputStream())};assertEquals(0L,uploads.status(owner,id,s.token).received)
  assertFailsWith<IllegalStateException>{uploads.complete(owner,id,s.token)}
  for(path in listOf("../outside","/absolute","mods/../../outside"))assertFailsWith<IllegalArgumentException>{uploads.create(owner,id,FileUploadRequest("data.txt",1,path,UUID.randomUUID().toString()))}
  assertFailsWith<IllegalArgumentException>{uploads.create(owner,id,FileUploadRequest("../data.txt",1,"",UUID.randomUUID().toString()))}
  assertFailsWith<IllegalArgumentException>{uploads.create(owner,id,FileUploadRequest("large",FileUploads.MAX+1,"",UUID.randomUUID().toString()))}
  repeat(15){tokens+=uploads.create(owner,id,FileUploadRequest("other-$it",0,"",UUID.randomUUID().toString())).token}
  assertFailsWith<IllegalStateException>{uploads.create(owner,id,FileUploadRequest("limit",0,"",UUID.randomUUID().toString()))}
 }
 @Test fun `restart marks interrupted application uncertain instead of silently retrying`() {
  val s=create("NEW".toByteArray());uploads.close()
  val file=manager.root.resolve("file-uploads/${s.token}/status.json")
  val saved=mapper.readValue(file.readText(),SavedFileUpload::class.java);file.writeText(mapper.writeValueAsString(saved.copy(status=saved.status.copy(phase="APPLYING"))))
  uploads=FileUploads(manager,RemoteFiles(manager),accounts)
  assertEquals("FAILED",uploads.status(owner,id,s.token).phase);assertTrue(uploads.status(owner,id,s.token).message.contains("반영 여부"));assertFalse(base.resolve("mods/mod.jar").exists())
 }
 @Test fun `local uploader has the same cancellation boundary and restores originals`() {
  manager.instance(id).runnerId=null;manager.runtime(id).state="STOPPED"
  val file=manager.serverDir(id).resolve("original.txt");file.writeText("OLD")
  assertFailsWith<IllegalStateException>{manager.writeStream(id,"original.txt","NEW".byteInputStream(),onProgress={if(it>0)error("cancel")})}
  assertEquals("OLD",file.readText());assertTrue(manager.serverDir(id).listDirectoryEntries(".save-*").isEmpty())
  val s=create("NEW".toByteArray(),"original.txt","");uploads.complete(owner,id,s.token);await(s.token,"COMPLETE");assertEquals("NEW",file.readText())
 }
}
