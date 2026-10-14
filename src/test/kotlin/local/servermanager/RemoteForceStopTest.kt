package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.mockito.Mockito.*
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.http.WebSocket
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import kotlin.io.path.*
import kotlin.test.*

class RemoteForceStopTest {
    @TempDir lateinit var temp:Path
    @Suppress("UNCHECKED_CAST") private fun <T> field(agent:RunnerAgent,name:String):T=RunnerAgent::class.java.getDeclaredField(name).also {it.isAccessible=true}.get(agent) as T

    @DisabledOnOs(OS.WINDOWS)
    @Test fun `stuck remote stop targets observed pid cancels restart and retains handoff lease`() {
        val manager=ServerManager();val i=manager.create(CreateRequest("force-stop","21.1.249",ServerSocket(0).use {it.localPort},512))["config"] as Instance
        var sent:Pair<String,Long>?=null;var fail=false
        manager.attachRemoteController(object:RemoteController {
            override fun start(instance:Instance){}
            override fun stop(instance:Instance){}
            override fun restart(instance:Instance){}
            override fun command(instance:Instance,value:String){}
            override fun supportsForceStop(instance:Instance)=!fail
            override fun forceStop(instance:Instance,pid:Long){check(!fail);sent=instance.id to pid}
        });manager.assignRunner(i.id,"runner")
        try {
            val r=manager.runtime(i.id);r.state="STOPPING";r.remotePid=77;r.remoteRestartRequested=true
            val lease=manager.root.resolve(i.id).resolve("remote-lease");lease.writeText("runner")
            assertEquals(true,manager.view(i.id)["forceStopSupported"])
            manager.stop(i.id,true)
            assertEquals(i.id to 77L,sent);assertEquals("STOPPING",r.state);assertEquals("FORCED",r.stopReason)
            assertTrue(r.remoteStopRequested);assertFalse(r.remoteRestartRequested);assertTrue(lease.exists())
            r.state="RUNNING";r.stopReason=null;r.remoteStopRequested=false;fail=true
            assertFailsWith<IllegalStateException>{manager.stop(i.id,true)}
            assertEquals("RUNNING",r.state);assertNull(r.stopReason);assertFalse(r.remoteStopRequested)
            r.state="SYNCING";r.remotePid=null
            assertFailsWith<IllegalStateException>{manager.stop(i.id,true)};assertTrue(lease.exists())
            assertContains(ServerExit(137,true,"FORCED").summary(),"강제 종료")
        } finally {manager.shutdown();manager.root.resolve(i.id).toFile().deleteRecursively()}
    }

    @Test fun `gateway rejects old offline and ungranted devices instead of silently sending unsupported force command`() {
        val manager=mock(ServerManager::class.java);val registry=mock(RunnerRegistry::class.java)
        val device=RunnerDevice("runner","fixture","MAC","user","key","now",version="0.4.6",allowedInstanceIds=setOf("a"))
        val instance=Instance("a","A",loaderVersion="21.1.249",port=25565,memoryMb=512,javaPath="java",runnerId="runner")
        `when`(registry.active("runner")).thenReturn(device);`when`(registry.online("runner")).thenReturn(true)
        val session=mock(WebSocketSession::class.java);`when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"));`when`(session.isOpen).thenReturn(true)
        val sent=mutableListOf<WebSocketMessage<*>>();doAnswer {sent.add(it.arguments[0] as WebSocketMessage<*>);null}.`when`(session).sendMessage(any())
        val gateway=RunnerGateway(registry,manager);gateway.afterConnectionEstablished(session);sent.clear()
        assertFalse(gateway.supportsForceStop(instance));assertFailsWith<IllegalStateException>{gateway.forceStop(instance,77)};assertTrue(sent.isEmpty())
        `when`(registry.active("runner")).thenReturn(device.copy(version="0.4.7"))
        assertTrue(gateway.supportsForceStop(instance));gateway.forceStop(instance,77)
        val request=jacksonObjectMapper().readValue<RunnerMessage>((sent.single() as TextMessage).payload)
        assertEquals("force-stop",request.type);assertEquals("a",request.instanceId);assertEquals(77L,request.pid)
        sent.clear();`when`(registry.active("runner")).thenReturn(device.copy(version="0.4.7",allowedInstanceIds=emptySet()))
        assertFailsWith<IllegalStateException>{gateway.forceStop(instance,77)};assertTrue(sent.isEmpty())
        `when`(registry.active("runner")).thenReturn(device.copy(version="0.4.7"));`when`(registry.online("runner")).thenReturn(false)
        assertFailsWith<IllegalStateException>{gateway.forceStop(instance,77)};assertTrue(sent.isEmpty())
    }

    @Test fun `runner force kills only tracked matching process from current channel and leaves exit handoff to waiter`() {
        val agent=RunnerAgent(temp,RunnerIdentity("https://localhost:1","runner","fixture","not-used","MAC"))
        val process=mock(Process::class.java);val child=mock(ProcessHandle::class.java);val output=ByteArrayOutputStream()
        `when`(process.isAlive).thenReturn(true);`when`(process.pid()).thenReturn(77L);`when`(process.outputStream).thenReturn(output);`when`(process.destroyForcibly()).thenReturn(process)
        doAnswer {Stream.of(child)}.`when`(process).descendants()
        field<ConcurrentHashMap<String,Process>>(agent,"processes")["a"]=process
        val dirty=temp.resolve("instances/a/dirty");dirty.parent.createDirectories();dirty.writeText("pending")
        val socket=mock(WebSocket::class.java);val stale=mock(WebSocket::class.java);val sent=mutableListOf<String>()
        doAnswer {sent.add(it.arguments[0].toString());CompletableFuture.completedFuture(socket)}.`when`(socket).sendText(any(CharSequence::class.java),anyBoolean())
        field<AtomicReference<WebSocket?>>(agent,"connection").set(socket)
        val mapper=jacksonObjectMapper()
        fun request(channel:WebSocket,id:String,pid:Long)=agent.onText(channel,mapper.writeValueAsString(RunnerMessage("force-stop",instanceId=id,pid=pid)),true)
        request(stale,"a",77);request(socket,"a",78);request(socket,"other",77)
        verify(process,never()).destroyForcibly();verify(child,never()).destroyForcibly()
        request(socket,"a",77)
        verify(child).destroyForcibly();verify(process).destroyForcibly();assertEquals("",output.toString())
        assertSame(process,field<ConcurrentHashMap<String,Process>>(agent,"processes")["a"]);assertTrue(dirty.exists())
        assertTrue(field<Set<String>>(agent,"stopping").contains("a"))
        val states=sent.map {mapper.readValue<RunnerMessage>(it)}.filter {it.type=="state"}
        assertEquals(listOf("STOPPING"),states.map {it.state});assertEquals(77L,states.single().pid)
    }
}
