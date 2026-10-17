package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ModArtifactsTest {
    @TempDir lateinit var temp:Path
    private fun digest(bytes:ByteArray,algorithm:String)=MessageDigest.getInstance(algorithm).digest(bytes).joinToString(""){"%02x".format(it)}
    private fun jar(name:String,entries:Map<String,String>):Path=temp.resolve(name).also {path->
        ZipOutputStream(path.outputStream()).use {out->entries.forEach {(name,value)->out.putNextEntry(ZipEntry(name));out.write(value.toByteArray());out.closeEntry()}}
    }
    private fun fabric(id:String="example",extra:String="")="""{"schemaVersion":1,"id":"$id","name":"Example","version":"2.0.0","environment":"*"$extra}"""
    private fun planned(bytes:ByteArray,name:String="example",installed:Boolean=false):RinPlannedMod {
        val file=RinFile("$name.jar","https://cdn.modrinth.com/data/project/versions/version/$name.jar",bytes.size.toLong(),digest(bytes,"SHA-512"),digest(bytes,"SHA-1"),true)
        val version=RinVersion("version$name",name,"Example","1.0","release","2026-01-01T00:00:00Z",setOf("1.21.1"),setOf("fabric"),listOf(file),emptyList())
        return RinPlannedMod(RinProject(name,name,"Example","required"),version,file,installed)
    }
    private fun plan(vararg mods:RinPlannedMod)=RinInstallPlan(ModrinthTarget("1.21.1","fabric"),mods.first().project.id,mods.toList(),emptyList())
    @Test fun `inventory is read only identifies disabled and duplicate mods and revisions follow content`() {
        val dir=temp.resolve("mods").createDirectory()
        val active=jar("source.jar",mapOf("fabric.mod.json" to fabric()))
        Files.copy(active,dir.resolve("renamed.jar"));Files.copy(active,dir.resolve("old.jar.DISABLED"));Files.copy(active,dir.resolve("duplicate.jar"))
        val snapshot=ModInventory.scan(dir)
        assertEquals(3,snapshot.mods.size);assertEquals(2,snapshot.mods.count {it.enabled})
        val attached=ModInventory.attach(snapshot,ModSourceLookup(ModSourceCache(),emptyList()))
        assertTrue(attached.warnings.any {it.contains("내용이 같은")});assertTrue(attached.mods.all {it.source==null && it.warning!=null})
        assertEquals(snapshot.revision,attached.revision)
        assertEquals(snapshot,ModInventory.scan(dir))
        Files.move(dir.resolve("renamed.jar"),dir.resolve("renamed.jar.disabled"))
        assertNotEquals(snapshot.revision,ModInventory.scan(dir).revision)
        assertEquals(digest(active.readBytes(),"SHA-512"),snapshot.mods.first().sha512)
        assertEquals(3,dir.listDirectoryEntries().size)
    }
    @Test fun `content linking accepts renamed files and rejects a false size or unrelated hash`() {
        val dir=temp.resolve("mods").createDirectory();val path=jar("source.jar",mapOf("fabric.mod.json" to fabric()));Files.copy(path,dir.resolve("renamed.jar"))
        val snapshot=ModInventory.scan(dir);val mod=planned(path.readBytes())
        val links=ModInventory.link(snapshot) {hashes->assertEquals(listOf(mod.file.sha512),hashes);mapOf(mod.file.sha512 to mod.version)}
        assertEquals("renamed.jar",links.single().filename)
        assertFailsWith<IllegalStateException>{ModInventory.link(snapshot) {mapOf("0".repeat(128) to mod.version)}}
        assertFailsWith<IllegalStateException>{ModInventory.link(snapshot) {mapOf(mod.file.sha512 to mod.version.copy(files=listOf(mod.file.copy(size=1))))}}
    }
    @Test fun `downloads stream to private staging verify all hashes skip installed and never touch source folder`() {
        val bytes=ByteArray(150000){(it%251).toByte()};val first=planned(bytes);val existing=planned(bytes,"existing",true);var requests=0
        val downloads=ModrinthDownloads(open={uri->requests++;assertEquals(first.file.url,uri.toString());ModDownloadResponse(200,bytes.size.toLong(),object:ByteArrayInputStream(bytes){override fun readAllBytes():ByteArray=error("unbounded read")})})
        val staged=downloads.stage(plan(first,existing),temp)
        val folder=staged.directory
        staged.use {
            assertEquals(1,requests);assertEquals(1,it.files.size);assertEquals(digest(bytes,"SHA-256"),it.files.single().sha256)
            assertContentEquals(bytes,folder.resolve(first.file.filename).readBytes());assertFalse(folder.resolve(existing.file.filename).exists())
        }
        assertFalse(folder.exists())
    }
    @Test fun `truncation oversized bodies false hashes HTTP errors and callback failures clean staging`() {
        val bytes="expected bytes".toByteArray();val mod=planned(bytes)
        val cases=listOf(ModDownloadResponse(200,null,ByteArrayInputStream(bytes.copyOf(3))),ModDownloadResponse(200,null,ByteArrayInputStream(bytes+0)),ModDownloadResponse(200,null,ByteArrayInputStream("corrupted data".toByteArray())),ModDownloadResponse(302,null,ByteArrayInputStream(bytes)),ModDownloadResponse(429,null,ByteArrayInputStream(bytes)),ModDownloadResponse(200,1,ByteArrayInputStream(bytes)))
        cases.forEach {response->assertFails {ModrinthDownloads(open={response}).stage(plan(mod),temp)};assertTrue(temp.listDirectoryEntries().isEmpty())}
        assertFails {ModrinthDownloads(open={ModDownloadResponse(200,null,ByteArrayInputStream(bytes))}).stage(plan(mod),temp) {_,_,_->error("cancelled")}}
        assertTrue(temp.listDirectoryEntries().isEmpty())
    }
    @Test fun `deadline closes a blocked response instead of retaining an unbounded network task`() {
        val released=java.util.concurrent.CountDownLatch(1);var closed=false
        val stream=object:java.io.InputStream() {
            override fun read():Int {check(released.await(3,java.util.concurrent.TimeUnit.SECONDS));return -1}
            override fun read(b:ByteArray,off:Int,len:Int)=read()
            override fun close(){closed=true;released.countDown()}
        }
        val downloads=ModrinthDownloads(open={ModDownloadResponse(200,null,stream)},timeout=java.time.Duration.ofMillis(30))
        assertFailsWith<IllegalStateException>{downloads.stage(plan(planned("data".toByteArray())),temp)}
        assertTrue(closed);assertTrue(temp.listDirectoryEntries().isEmpty())
    }
    @Test fun `invalid CDN oversized plans and mod symlinks are rejected before writes or network`() {
        val mod=planned("data".toByteArray());var calls=0
        val downloads=ModrinthDownloads(open={calls++;error("must not fetch")})
        assertFailsWith<IllegalArgumentException>{downloads.stage(plan(mod.copy(file=mod.file.copy(url="https://localhost/private.jar"))),temp)}
        assertFailsWith<IllegalArgumentException>{downloads.stage(plan(mod.copy(file=mod.file.copy(size=257L*1024*1024))),temp)}
        assertEquals(0,calls);assertTrue(temp.listDirectoryEntries().isEmpty())
        if(!System.getProperty("os.name").startsWith("Windows")) {
            val dir=temp.resolve("mods").createDirectory();val outside=temp.resolve("outside.jar").also {it.writeText("keep")}
            Files.createSymbolicLink(dir.resolve("escape.jar"),outside)
            assertFailsWith<IllegalArgumentException>{ModInventory.scan(dir)};assertEquals("keep",outside.readText())
        }
    }
    @Test fun `actual small NeoForge download hashes metadata and content link agree without installation`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("SM_TEST_MODRINTH_DOWNLOAD_LIVE")=="1")
        val api=ModrinthApi();val target=ModrinthTarget("1.21.1","neoforge")
        val plan=ModrinthResolver(api).plan("https://modrinth.com/mod/ferrite-core",target)
        require(plan.mods.size<=8 && plan.mods.sumOf {it.file.size}<=16L*1024*1024) {"Live fixture download budget exceeded"}
        ModrinthDownloads().stage(plan,temp).use {staged->
            assertTrue(staged.files.isNotEmpty())
            val inventory=ModInventory.scan(staged.directory)
            val links=ModInventory.link(inventory,api::versionsFromHashes)
            val sources=ModSources.lookup(api,inventory.mods.map {it.sha512},ModSourceCache(),false).cache.sources
            assertTrue(sources.values.any {it.slug=="ferrite-core" && !it.clientOnly})
            assertEquals(plan.mods.map {it.version.id}.toSet(),links.map {it.version.id}.toSet())
            assertTrue(inventory.mods.all {it.warning==null})
        }
        assertTrue(temp.listDirectoryEntries().isEmpty())
    }
}
