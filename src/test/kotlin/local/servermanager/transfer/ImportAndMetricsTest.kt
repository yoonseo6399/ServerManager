package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

class ImportAndMetricsTest {
    @TempDir lateinit var temp: Path
    private val mapper=jacksonObjectMapper()
    private fun archive(deps:Map<String,String> = mapOf("minecraft" to "1.21.1","neoforge" to "21.1.252"),extra:Map<String,String> = emptyMap()):Path {
        val file=temp.resolve("${System.nanoTime()}.mrpack")
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries=mapOf("modrinth.index.json" to mapper.writeValueAsString(mapOf("formatVersion" to 1,"game" to "minecraft","name" to "Test pack","versionId" to "1","dependencies" to deps,"files" to emptyList<Any>())))+extra
            entries.forEach { (name,text) -> zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry() }
        };return file
    }
    @Test fun `version catalog parses numeric order beta and keeps cache during outage`() {
        var offline=false
        val catalog=VersionCatalog(temp.resolve("versions.json")) { if(offline) error("offline");"<version>21.1.9</version><version>21.1.12-beta</version><version>21.1.12</version><version>21.2.1</version>" }
        assertEquals(listOf("21.1.12","21.1.12-beta","21.1.9"),catalog.get().versions)
        offline=true
        val result=catalog.get(true)
        assertTrue(result.stale);assertTrue(result.warning!!.contains("offline"));assertEquals(3,result.versions.size)
        assertEquals(3,VersionCatalog(temp.resolve("versions.json")) { error("no network") }.get().versions.size)
    }
    @Test fun `uncached empty upstream response is a visible error`() {
        assertFailsWith<IllegalStateException> { VersionCatalog(temp.resolve("empty.json")) { "<metadata/>" }.get() }
    }
    @Test fun `server overrides win and client overrides are never extracted`() {
        val zip=archive(extra=mapOf("overrides/config/example.txt" to "common","server-overrides/config/example.txt" to "server","client-overrides/config/client.txt" to "client"))
        val plan=PackFormat.inspect(zip);assertEquals(2,plan.overrides.size)
        val out=temp.resolve("stage");PackFormat.overrides(zip,out)
        assertEquals("server",out.resolve("config/example.txt").readText())
        assertFalse(out.resolve("config/client.txt").exists())
    }
    @Test fun `unsupported loaders and traversal are rejected before instance creation`() {
        assertFailsWith<IllegalArgumentException> { PackFormat.inspect(archive(deps=mapOf("minecraft" to "1.21.1","quilt-loader" to "0.26.0"))) }
        assertFailsWith<IllegalArgumentException> { PackFormat.inspect(archive(extra=mapOf("overrides/../../escape" to "bad"))) }
        for(path in listOf("../escape","/tmp/escape","C:/escape","a\\b")) assertFailsWith<IllegalArgumentException> { PackFormat.relative(path) }
        assertFailsWith<IllegalArgumentException> { PackFormat.url("http://localhost/private") }
    }
    @Test fun `files require matching size and both hashes`() {
        val path=temp.resolve("mod.jar");path.writeText("payload")
        fun hash(name:String)=MessageDigest.getInstance(name).digest(path.readBytes()).joinToString("") { "%02x".format(it) }
        val file=PackFile("mods/mod.jar",mapOf("sha1" to hash("SHA-1"),"sha512" to hash("SHA-512")),listOf("https://cdn.modrinth.com/mod.jar"),path.fileSize(),"required")
        PackFormat.verify(path,file)
        path.writeText("corrupt")
        assertFailsWith<IllegalArgumentException> { PackFormat.verify(path,file) }
    }
    private fun mod(name:String):Path=temp.resolve("stage/mods/$name.jar").also {it.parent.createDirectories();it.writeText("jar $name")}
    private fun hash(path:Path)=MessageDigest.getInstance("SHA-512").digest(path.readBytes()).joinToString("") { "%02x".format(it) }
    private fun source(project:String,clientOnly:Boolean,vararg required:String)=ModSource(project,project,project,versionId="v",versionNumber="1",clientOnly=clientOnly,dependencies=required.map { ModDependency(it,"required") })
    @Test fun `client exclusion uses Modrinth environments and keeps required dependencies`() {
        val declared=mod("client");val shader=mod("shader");val lib=mod("clientlib");val server=mod("server")
        val sources=mapOf(hash(declared) to source("clientmod",false),hash(shader) to source("shader",true,"clientlib"),hash(lib) to source("clientlib",true),hash(server) to source("servermod",false,"clientmod"))
        val plan=PackPlan("test","1","1.21.1","21.1.252",listOf(PackFile("mods/client.jar",emptyMap(),emptyList(),1,"unsupported")),emptyList(),emptyList())
        val log=mutableListOf<String>()
        ClientModFilter.exclude(temp.resolve("stage"),plan,emptySet(),{ ModSourceLookup(ModSourceCache(sources),emptyList()) }) { log.add(it) }
        // The server mod requires the pack-declared client file, so it stays; Modrinth client-only files go.
        assertTrue(declared.exists());assertTrue(server.exists())
        assertFalse(shader.exists());assertFalse(lib.exists())
        assertTrue(log.any { it.contains("필수 의존성이라 유지") })
        server.deleteExisting()
        ClientModFilter.exclude(temp.resolve("stage"),plan,emptySet(),{ ModSourceLookup(ModSourceCache(sources),listOf("offline")) }) { log.add(it) }
        assertFalse(declared.exists());assertTrue("offline" in log)
    }
    @Test fun `client-only providers required by a kept client-only mod are not protected`() {
        val shader=mod("shader");val lib=mod("clientlib")
        val sources=mapOf(hash(shader) to source("shader",true,"clientlib"),hash(lib) to source("clientlib",false))
        val plan=PackPlan("test","1","1.21.1","21.1.252",listOf(PackFile("mods/clientlib.jar",emptyMap(),emptyList(),1,"unsupported")),emptyList(),emptyList())
        ClientModFilter.exclude(temp.resolve("stage"),plan,emptySet(),{ ModSourceLookup(ModSourceCache(sources),emptyList()) }) {}
        assertFalse(shader.exists());assertFalse(lib.exists())
    }
    @Test fun `known retained client dependencies are transitive and unknown consumers do not block verified exclusions`() {
        val main=mod("main");val client=mod("client");val lib=mod("lib")
        val sources=mapOf(hash(main) to source("main",false,"client"),hash(client) to source("client",true,"lib"),hash(lib) to source("lib",true))
        val plan=PackPlan("test","1","1.21.1","21.1.252",emptyList(),emptyList(),emptyList())
        ClientModFilter.exclude(temp.resolve("stage"),plan,emptySet(),{ModSourceLookup(ModSourceCache(sources),emptyList())}) {}
        assertTrue(client.exists());assertTrue(lib.exists())
        main.deleteExisting();mod("private")
        ClientModFilter.exclude(temp.resolve("stage"),plan,emptySet(),{ModSourceLookup(ModSourceCache(sources),emptyList())}) {}
        assertFalse(client.exists());assertFalse(lib.exists())
    }
    @Test fun `private uploads and missing dependencies do not prevent known client exclusions`() {
        val server=mod("server");val privateMod=mod("private");val client=mod("client")
        val sources=mapOf(hash(server) to source("server",false,"missing"),hash(client) to source("client",true))
        val log=mutableListOf<String>()
        val plan=PackPlan("test","1","1.21.1","21.1.252",emptyList(),emptyList(),emptyList())
        ClientModFilter.exclude(temp.resolve("stage"),plan,emptySet(),{ModSourceLookup(ModSourceCache(sources),listOf("partial query failed"))}) {log.add(it)}
        assertTrue(server.exists());assertTrue(privateMod.exists());assertFalse(client.exists())
        assertTrue(log.any {it.contains("미확인")});assertTrue(log.any {it.contains("partial query failed")})
    }
    @Test fun `explicit pack client declaration is excluded even when all lookups are unknown`() {
        val client=mod("declared");val privateMod=mod("private")
        val plan=PackPlan("test","1","1.21.1","21.1.252",listOf(PackFile("mods/declared.jar",emptyMap(),emptyList(),1,"unsupported")),emptyList(),emptyList())
        ClientModFilter.exclude(temp.resolve("stage"),plan,emptySet(),{ModSourceLookup(ModSourceCache(),listOf("offline"))}) {}
        assertFalse(client.exists());assertTrue(privateMod.exists())
    }
    @Test fun `process resource sampling reports real RSS and successive CPU samples`() {
        val metrics=ProcessMetrics();val handle=ProcessHandle.current()
        val first=metrics.sample(handle);val second=metrics.sample(handle)
        assertTrue(first.rssMb!!>0);assertNotNull(first.cpuPercent)
        assertNotNull(second.cpuPercent);assertTrue(second.cpuPercent>=0);assertNotNull(second.uptimeSeconds)
    }
    @Test fun `ps fallback parses CPU without Java process info`() {
        assertEquals(125.5 to 2.0,ProcessMetrics.parsePs(" 125.5 2048\n"))
        assertEquals(0.0 to 0.5,ProcessMetrics.parsePs("0.0 512"))
        assertNull(ProcessMetrics.parsePs("permission denied"))
    }
}
