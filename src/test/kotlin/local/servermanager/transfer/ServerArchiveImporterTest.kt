package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.*
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*

class ServerArchiveImporterTest {
    @TempDir lateinit var dir:Path
    private val manager=ServerManager()
    private val before=manager.list().map {(it["config"] as Instance).id}.toSet()
    private val importer=ServerArchiveImporter(manager)
    private val tokens=mutableListOf<String>()
    @AfterEach fun cleanup(){importer.shutdown();manager.shutdown();manager.list().map {(it["config"] as Instance).id}.filter {it !in before}.forEach {manager.root.resolve(it).toFile().deleteRecursively()};tokens.forEach {manager.root.resolve("archive-uploads/$it").toFile().deleteRecursively()}}
    private fun zip(files:Map<String,ByteArray>):ByteArray=java.io.ByteArrayOutputStream().also {bytes->ZipOutputStream(bytes).use {out->files.forEach {(name,data)->out.putNextEntry(ZipEntry(name));out.write(data);out.closeEntry()}}}.toByteArray()
    private fun upload(bytes:ByteArray):ServerArchivePreview {
        val u=importer.create("owner",ServerArchiveUploadRequest("server.zip",bytes.size.toLong()));tokens.add(u.token)
        var offset=0L;while(offset<bytes.size){val n=minOf(17,bytes.size-offset.toInt());offset=importer.append("owner",u.token,offset,bytes.copyOfRange(offset.toInt(),offset.toInt()+n).inputStream()).received}
        return importer.preview("owner",u.token)
    }
    private fun request(token:String,port:Int=32031,jar:String?="custom.jar")=ServerArchiveImportRequest(token,"Imported","Vanilla","1.21.1","1.21.1",jar,21,port,512)
    private fun await(id:String){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(manager.runtime(id).state=="IMPORTING"){assertTrue(System.nanoTime()<end,"archive import timed out");Thread.sleep(10)}}
    private fun jar():ByteArray {
        val javaSource=dir.resolve("ImportedServer.java");javaSource.writeText("""public class ImportedServer {public static void main(String[] a)throws Exception{System.out.println("Done (0.1s)! For help, type help");java.io.BufferedReader r=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));for(String s;(s=r.readLine())!=null;){if(s.equals("stop"))break;System.out.println(s);}}}""")
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),javaSource.toString()))
        return java.io.ByteArrayOutputStream().also {bytes->JarOutputStream(bytes,Manifest().apply {mainAttributes[Attributes.Name.MANIFEST_VERSION]="1.0";mainAttributes[Attributes.Name.MAIN_CLASS]="ImportedServer"}).use {out->out.putNextEntry(JarEntry("ImportedServer.class"));out.write(dir.resolve("ImportedServer.class").readBytes());out.closeEntry()}}.toByteArray()
    }
    @Test fun `ZIP preserves world mods and unknown settings in a new instance and only starts on an explicit start`() {
        val existing=manager.create(CreateRequest("Original","21.1.219",32030,512))["config"] as Instance
        manager.serverDir(existing.id).resolve("keep.txt").writeText("DO NOT TOUCH")
        val world=byteArrayOf(0,1,2,3,-1);val mod=byteArrayOf(12,34,56)
        val preview=upload(zip(mapOf("Server/server.properties" to "level-name=world\nserver-port=1\nserver-ip=0.0.0.0\ncustom-flag=keep\n".toByteArray(),"Server/world/level.dat" to world,"Server/mods/private.jar.disabled" to mod,"Server/custom.jar" to jar())))
        val id=(importer.import("owner",request(preview.token))["config"] as Instance).id;await(id)
        assertEquals("STOPPED",manager.runtime(id).state);assertNull(manager.runtime(id).process)
        assertContentEquals(world,manager.serverDir(id).resolve("world/level.dat").readBytes());assertContentEquals(mod,manager.serverDir(id).resolve("mods/private.jar.disabled").readBytes())
        val props=java.util.Properties().apply {manager.serverDir(id).resolve("server.properties").inputStream().use(::load)}
        assertEquals("32031",props.getProperty("server-port"));assertEquals("127.0.0.1",props.getProperty("server-ip"));assertEquals("keep",props.getProperty("custom-flag"))
        assertEquals("DO NOT TOUCH",manager.serverDir(existing.id).resolve("keep.txt").readText());assertFalse(manager.serverDir(id).resolve("Server").exists())
        assertEquals("COMPLETE",manager.runtime(id).archiveImport?.phase)
        val arguments=Launchers.arguments(manager.instance(id),manager.serverDir(id));assertTrue(arguments.any {it.endsWith("custom.jar")})
        manager.start(id)
        try {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(manager.runtime(id).state=="STARTING"){assertTrue(System.nanoTime()<end);Thread.sleep(10)};assertEquals("RUNNING",manager.runtime(id).state);val process=manager.runtime(id).process!!;manager.stop(id,false);assertTrue(process.waitFor(10,TimeUnit.SECONDS))}
        finally {manager.runtime(id).process?.takeIf {it.isAlive}?.destroyForcibly()}
    }
    @Test fun `upload offset retries ownership chunk quota and cleanup are enforced`() {
        val u=importer.create("owner",ServerArchiveUploadRequest("server.zip",10));tokens.add(u.token)
        assertEquals(5L,importer.append("owner",u.token,0,byteArrayOf(1,2,3,4,5).inputStream()).received)
        assertEquals(409,assertFailsWith<ResponseStatusException>{importer.append("owner",u.token,0,byteArrayOf(1).inputStream())}.statusCode.value())
        assertEquals(404,assertFailsWith<ResponseStatusException>{importer.status("other",u.token)}.statusCode.value())
        assertFailsWith<IllegalArgumentException>{importer.append("owner",u.token,5,ByteArray(6).inputStream())}
        assertEquals(5L,importer.status("owner",u.token).received);assertTrue(manager.root.resolve("archive-uploads/${u.token}").listDirectoryEntries(".chunk-*").isEmpty())
        assertFailsWith<IllegalArgumentException>{importer.preview("owner",u.token)}
        importer.cancel("owner",u.token);assertFalse(manager.root.resolve("archive-uploads/${u.token}").exists())
    }
    @Test fun `missing launcher is imported without running an installer and original false EULA is retained`() {
        val p=upload(zip(mapOf("server.properties" to "level-name=world".toByteArray(),"eula.txt" to "eula=false\n".toByteArray(),"world/level.dat" to "world".toByteArray())))
        val id=(importer.import("owner",request(p.token,32032,null))["config"] as Instance).id;await(id)
        assertEquals("NEW",manager.runtime(id).state);assertNull(manager.runtime(id).process);assertFalse(manager.eulaAccepted(id));assertFalse(manager.root.resolve(id).resolve("installed.ok").exists())
        assertEquals("world",manager.serverDir(id).resolve("world/level.dat").readText());assertTrue(manager.runtime(id).archiveImport!!.message.contains("런처 설치"))
    }
    @Test fun `unsafe world location fails before publishing and start remains blocked`() {
        val p=upload(zip(mapOf("server.properties" to "level-name=../../outside\n".toByteArray(),"world/level.dat" to "original".toByteArray())))
        val id=(importer.import("owner",request(p.token,32033,null))["config"] as Instance).id;await(id)
        assertEquals("IMPORT_FAILED",manager.runtime(id).state);assertFalse(manager.serverDir(id).resolve("world/level.dat").exists());assertFalse(manager.root.resolve(id).resolve("installed.ok").exists())
        assertFailsWith<IllegalArgumentException>{manager.start(id)}
        assertFailsWith<IllegalArgumentException>{importer.import("owner",request(p.token,32034,null))};Unit
    }
    @Test fun `binary chunk uploads accept a real ZIP larger than the multipart 256 MiB limit`() {
        val payload=ByteArray(1024*1024);val count=257;val crc=CRC32();repeat(count){crc.update(payload)}
        val file=dir.resolve("large.zip")
        ZipOutputStream(file.outputStream()).use {out->
            out.putNextEntry(ZipEntry("server.properties"));out.write("level-name=world".toByteArray());out.closeEntry()
            out.putNextEntry(ZipEntry("world/region/fixture.mca").apply {method=ZipEntry.STORED;size=count*payload.size.toLong();compressedSize=size;this.crc=crc.value});repeat(count){out.write(payload)};out.closeEntry()
        }
        assertTrue(file.fileSize()>256L*1024*1024)
        val upload=importer.create("owner",ServerArchiveUploadRequest("large.zip",file.fileSize()));tokens.add(upload.token)
        var offset=0L;var chunks=0
        file.inputStream().use {input->while(true){val bytes=input.readNBytes(upload.chunkSize);if(bytes.isEmpty())break;offset=importer.append("owner",upload.token,offset,bytes.inputStream()).received;chunks++}}
        val preview=importer.preview("owner",upload.token);assertEquals(2,preview.plan.files);assertEquals(file.fileSize(),offset);assertTrue(chunks>32)
        println("SERVER-ARCHIVE-UPLOAD-PROOF bytes=$offset chunks=$chunks multipartIndependent=true boundedChunk=${upload.chunkSize}")
        importer.cancel("owner",upload.token)
    }
    @Test fun `invalid settings leave preview reusable and abandoned upload expires`() {
        val p=upload(zip(mapOf("server.properties" to "".toByteArray())))
        assertFailsWith<IllegalArgumentException>{importer.import("owner",request(p.token).copy(memoryMb=1,entryJar=null))}
        assertEquals(p.token,importer.preview("owner",p.token).token)
        val dir=manager.root.resolve("archive-uploads/${p.token}");java.nio.file.Files.setLastModifiedTime(dir.resolve("upload.json"),java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(86401)))
        importer.cleanupExpired();assertFalse(dir.exists())
        assertFailsWith<IllegalArgumentException>{importer.create("owner",ServerArchiveUploadRequest("server.7z",10))}
        assertFailsWith<IllegalArgumentException>{importer.create("owner",ServerArchiveUploadRequest("server.zip",ServerArchiveFormat.MAX_ARCHIVE+1))};Unit
    }
}
