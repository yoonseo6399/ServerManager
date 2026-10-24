package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import java.util.Properties
import kotlin.io.path.*
import kotlin.test.*

class MultiServerPortsTest {
    private val manager=ServerManager()
    private val ids=mutableListOf<String>()
    private val starts=mutableListOf<Instance>()
    private val remote=object:RemoteController {
        override fun sharedGamePort()=6700
        override fun reservedGamePorts(runnerId:String?)=if(runnerId=="relay")setOf(6700,6400,24454,34454) else emptySet()
        override fun start(instance:Instance) {starts.add(instance.copy())}
        override fun stop(instance:Instance) {}
        override fun restart(instance:Instance) {}
        override fun command(instance:Instance,value:String) {}
    }
    init {manager.attachRemoteController(remote)}
    private fun create(port:Int=25565):Instance {
        val i=manager.create(CreateRequest("multi", "21.1.219",port,512))["config"] as Instance
        ids.add(i.id);return i
    }
    @AfterEach fun cleanup(){manager.shutdown();ids.forEach {manager.root.resolve(it).toFile().deleteRecursively()}}
    @Test fun `two servers on one computer get different ports and keep existing server unchanged`() {
        val a=create();val b=create();manager.assignRunner(a.id,"same");manager.runtime(a.id).state="RUNNING"
        manager.assignRunner(b.id,"same")
        assertEquals(25565,manager.instance(a.id).port);assertEquals(25566,manager.instance(b.id).port)
        manager.assignRunner(b.id,"same");assertEquals(25566,manager.instance(b.id).port)
        assertTrue(manager.runtime(b.id).events.any {it.text.contains("25565 → 25566")})
        assertFailsWith<IllegalArgumentException> {manager.update(b.id,CreateRequest("multi","21.1.219",25565,512))}
        assertFailsWith<IllegalStateException> {manager.assignRunner(a.id,"elsewhere")}
        assertEquals("same",manager.instance(a.id).runnerId)
        manager.runtime(a.id).state="STOPPED"
    }
    @Test fun `an existing duplicate configuration is repaired before the remote start is sent`() {
        val a=create();val b=create();manager.assignRunner(a.id,"same");manager.assignRunner(b.id,"same")
        manager.instance(b.id).port=25565 // persisted by an older version before assignment planning existed
        manager.runtime(a.id).state="RUNNING"
        try {
            manager.start(b.id)
            assertEquals(25566,manager.instance(b.id).port);assertEquals(25566,starts.single().port)
            assertEquals(25565,manager.instance(a.id).port)
            assertTrue(manager.runtime(b.id).events.any {it.text.contains("기존 설정")})
        } finally {manager.runtime(a.id).state="STOPPED";manager.runtime(b.id).state="STOPPED"}
    }
    @Test fun `different computers may reuse the same internal port but moving reserves target ports`() {
        val a=create();val b=create();manager.assignRunner(a.id,"one");manager.assignRunner(b.id,"two")
        assertEquals(25565,manager.instance(a.id).port);assertEquals(25565,manager.instance(b.id).port)
        manager.assignRunner(b.id,"one");assertEquals(25566,manager.instance(b.id).port)
    }
    @Test fun `relay TCP and planned voice ports are excluded without changing public ports`() {
        for(port in listOf(6700,6400,24454,34454)) {
            val i=create(port);manager.assignRunner(i.id,"relay");assertEquals(port+1,manager.instance(i.id).port)
        }
        assertEquals(6700,remote.sharedGamePort())
    }
    @Test fun `planned local query ports remain separate before games bind their sockets`() {
        val a=create();val first=Properties().apply {manager.serverDir(a.id).resolve("server.properties").inputStream().use(::load)}.getProperty("query.port").toInt()
        val b=create();val second=Properties().apply {manager.serverDir(b.id).resolve("server.properties").inputStream().use(::load)}.getProperty("query.port").toInt()
        assertNotEquals(first,second)
        assertEquals(first,manager.runtime(a.id).queryPort);assertEquals(second,manager.runtime(b.id).queryPort)
    }
    @Test fun `port allocator wraps and reports exhaustion`() {
        assertEquals(1025,ServerPortPlan.available(65535,setOf(65535,1024)))
        assertEquals(25565,ServerPortPlan.available(25565,emptySet()))
        assertFailsWith<IllegalStateException> {ServerPortPlan.available(25565,(1024..65535).toSet())}
        Unit
    }
}
