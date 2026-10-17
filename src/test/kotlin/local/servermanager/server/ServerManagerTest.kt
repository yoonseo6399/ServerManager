package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import kotlin.test.*
import java.nio.file.Files
import kotlin.io.path.*

class ServerManagerTest {
    private val manager=ServerManager()
    private val created=mutableListOf<String>()
    private fun instance(port:Int=31001):String {
        val result=manager.create(CreateRequest("test", "21.1.219",port,512))
        return (result["config"] as Instance).id.also { created.add(it) }
    }
    @AfterEach fun cleanup() { manager.shutdown(); created.forEach { manager.root.resolve(it).toFile().deleteRecursively() } }
    @Test fun `file lifecycle preserves backup and detects edit conflicts`() {
        val id=instance()
        manager.mkdir(id,"config")
        manager.write(id,"config/test.txt","first".toByteArray())
        val revision=manager.text(id,"config/test.txt").getValue("revision")
        manager.write(id,"config/test.txt","second".toByteArray(),revision)
        assertFailsWith<IllegalStateException> { manager.write(id,"config/test.txt","stale".toByteArray(),revision) }
        assertEquals("second",manager.text(id,"config/test.txt")["content"])
        assertEquals(1,Files.list(manager.root.resolve(id).resolve("backups")).use { it.count() }.toInt())
        manager.move(id,"config/test.txt","config/renamed.txt")
        manager.delete(id,"config/renamed.txt")
        assertTrue(manager.files(id,"config").isEmpty())
        assertEquals(1,Files.list(manager.root.resolve(id).resolve("trash")).use { it.count() }.toInt())
    }
    @Test fun `paths cannot leave the instance or follow symlinks`() {
        val id=instance()
        assertFailsWith<IllegalArgumentException> { manager.path(id,"../../outside") }
        Files.createSymbolicLink(manager.serverDir(id).resolve("escape"),manager.root)
        assertFailsWith<IllegalArgumentException> { manager.path(id,"escape/secret") }
    }
    @Test fun `streamed upload uses bounded reads and incomplete input never replaces original`() {
        val id=instance();manager.write(id,"config.txt","original".toByteArray())
        assertFailsWith<IllegalArgumentException> {"oversize".byteInputStream().use {manager.writeStream(id,"config.txt",it,limit=3)}}
        assertEquals("original",manager.text(id,"config.txt")["content"])
        val broken=object:java.io.InputStream() {
            override fun read():Int=error("single-byte read not expected")
            override fun read(b:ByteArray,off:Int,len:Int):Int=throw java.io.IOException("interrupted upload")
        }
        assertFailsWith<java.io.IOException>{manager.writeStream(id,"config.txt",broken)}
        assertEquals("original",manager.text(id,"config.txt")["content"])
        var remaining=3*1024*1024
        val generated=object:java.io.InputStream() {
            override fun read():Int=error("single-byte read not expected")
            override fun readAllBytes():ByteArray=error("must not buffer the upload")
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                check(len<=64*1024);if(remaining==0)return -1
                val n=minOf(len,remaining);b.fill(9,off,off+n);remaining-=n;return n
            }
        }
        manager.writeStream(id,"large.bin",generated)
        assertEquals(3L*1024*1024,manager.serverDir(id).resolve("large.bin").fileSize())
        assertTrue(manager.serverDir(id).listDirectoryEntries(".save-*").isEmpty())
        val revision=manager.text(id,"config.txt").getValue("revision")
        manager.writeStream(id,"config.txt",java.io.ByteArrayInputStream(ByteArray(3*1024*1024)))
        assertFailsWith<IllegalStateException>{manager.write(id,"config.txt","stale".toByteArray(),revision)}
    }
    @Test fun `manager startup recovers an interrupted transfer before exposing its server`() {
        val id=instance();manager.write(id,"level.dat","original".toByteArray())
        val base=manager.serverDir(id);val before=FileInventory.collect(base).let {(f,d)->RunnerFileManifest(f,d)}
        val stage=manager.root.resolve(id).resolve("interrupted-upload").createDirectory()
        stage.resolve("level.dat").writeText("new")
        val after=before.copy(files=before.files+("level.dat" to CloudFile(FileInventory.digest(stage.resolve("level.dat")),3)))
        assertFailsWith<Error> {DeltaTransfer.apply(base,stage,before,after,manager.root.resolve(id).resolve("runner-backups"),afterStep={if(it=="install:level.dat")throw Error("simulated process exit")})}
        val restarted=ServerManager()
        try {
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while(restarted.runtime(id).recoveringFiles && System.nanoTime()<deadline)Thread.sleep(10)
            assertFalse(restarted.runtime(id).recoveringFiles)
            assertEquals("original",restarted.text(id,"level.dat")["content"])
        } finally {restarted.shutdown()}
    }
    @Test fun `recovery excludes starts file access and late remote running frames`() {
        val id=instance();manager.assignRunner(id,"runner")
        val r=manager.runtime(id);r.state="SYNCING";r.recoveringFiles=true
        val lease=manager.root.resolve(id).resolve("remote-lease").also {it.writeText("runner")}
        try {
            manager.remoteState(id,"runner","RUNNING")
            assertEquals("SYNCING",r.state)
            assertFailsWith<IllegalStateException>{manager.start(id)}
            assertFailsWith<IllegalStateException>{manager.files(id,"")}
            assertFailsWith<IllegalStateException>{manager.write(id,"level.dat","blocked".toByteArray())}
            assertFailsWith<IllegalStateException>{manager.checkTransferReady(id)}
            assertTrue(lease.exists());assertFalse(manager.serverDir(id).resolve("level.dat").exists())
        } finally {r.recoveringFiles=false;r.state="STOPPED";lease.deleteIfExists()}
    }
    @Test fun `memory validation follows actual host capacity`() {
        val cap=manager.capacity()
        assertEquals(cap.totalMb-cap.reservedMb,cap.maxHeapMb)
        assertTrue(cap.reservedMb>=1024)
        assertFailsWith<IllegalArgumentException> { manager.create(CreateRequest("too big","21.1.219",31002,(cap.maxHeapMb+1).toInt())) }
        assertFailsWith<IllegalArgumentException> { manager.create(CreateRequest("too small","21.1.219",31002,511)) }
    }
    @Test fun `ports are unique and settings remain loopback only`() {
        val id=instance()
        assertTrue(manager.eulaAccepted(id))
        assertFailsWith<IllegalArgumentException> { instance() }
        manager.update(id,CreateRequest("renamed","21.1.219",31003,512))
        val text=manager.serverDir(id).resolve("server.properties").readText()
        assertTrue(text.contains("server-port=31003")); assertTrue(text.contains("server-ip=127.0.0.1"))
    }
    @Test fun `pre-start remote failure releases only its own lease and permits configuration recovery`() {
        val id=instance();manager.assignRunner(id,"runner-a")
        val state=manager.runtime(id);state.state="TRANSFERRING"
        val lease=manager.root.resolve(id).resolve("remote-lease").also {it.writeText("runner-a")}
        manager.remoteStartFailed(id,"runner-b","wrong runner")
        assertTrue(lease.exists());assertEquals("TRANSFERRING",state.state)
        manager.remoteStartFailed(id,"runner-a","Java version mismatch")
        assertFalse(lease.exists());assertEquals("FAILED",state.state)
        manager.update(id,CreateRequest("recover","21.1.219",31003,512))
        state.state="RUNNING";state.remotePid=42;lease.writeText("runner-a")
        manager.remoteStartFailed(id,"runner-a","late frame")
        assertTrue(lease.exists());assertEquals("RUNNING",state.state)
        state.state="STOPPED";state.remotePid=null;lease.deleteIfExists()
    }
    @Test fun `writes blocked during installation and command line injection rejected`() {
        val id=instance(); manager.runtime(id).state="INSTALLING"
        assertFailsWith<IllegalStateException> { manager.write(id,"test.txt",byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { manager.command(id,"list\nstop") }
    }
    @Test fun `running instance heaps reduce the available start budget`() {
        val id=instance()
        val process=ProcessBuilder("/bin/sleep","30").start()
        manager.runtime(id).process=process
        try {
            val cap=manager.capacity()
            assertEquals(512L,cap.committedHeapMb)
            assertEquals(cap.maxHeapMb-512,cap.startableHeapMb)
            assertEquals(0L,manager.capacity(id).committedHeapMb)
        } finally { process.destroyForcibly();process.waitFor();manager.runtime(id).process=null }
    }
    @Test fun `instance deletion archives data and rejects in-flight work`() {
        val id=instance(31007)
        manager.write(id,"world/example.dat","world data".toByteArray())
        manager.runtime(id).state="INSTALLING"
        assertFailsWith<IllegalStateException> { manager.deleteInstance(id) }
        assertTrue(manager.serverDir(id).exists())
        manager.runtime(id).state="STOPPED"
        manager.deleteInstance(id)
        assertFalse(manager.serverDir(id).exists())
        assertTrue(manager.list().none { (it["config"] as Instance).id==id })
        val archive=Files.list(manager.root.resolve("deleted-instances")).use { paths->paths.filter { it.name.startsWith(id) }.findFirst().orElseThrow() }
        assertEquals("world data",archive.resolve("server/world/example.dat").readText())
        archive.toFile().deleteRecursively()
    }
}
