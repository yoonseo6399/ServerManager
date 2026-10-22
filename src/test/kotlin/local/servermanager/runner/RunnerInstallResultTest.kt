package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ExceptionWebSocketHandlerDecorator
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*
import kotlin.test.*

class RunnerInstallResultTest {
    private fun create(manager:ServerManager)=manager.create(CreateRequest("late-install-fixture","21.1.249",ServerSocket(0).use {it.localPort},512))["config"] as Instance
    private fun cleanup(manager:ServerManager,id:String) {
        manager.shutdown()
        manager.root.resolve(id).toFile().deleteRecursively()
        manager.root.resolve("deleted-instances").takeIf {it.exists()}?.listDirectoryEntries("$id-*")?.forEach {it.toFile().deleteRecursively()}
    }
    @Test fun `unknown and deleted install results leave the decorated control session open for heartbeat`() {
        val manager=ServerManager();val instance=create(manager)
        val registry=mock(RunnerRegistry::class.java)
        `when`(registry.active("runner")).thenReturn(RunnerDevice("runner","fixture","MAC","user","key","now"))
        val session=mock(WebSocketSession::class.java);val open=AtomicBoolean(true)
        `when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"))
        `when`(session.isOpen).thenAnswer {open.get()}
        doAnswer {open.set(false);null}.`when`(session).close(any(CloseStatus::class.java))
        val messages=mutableListOf<TextMessage>()
        doAnswer {a->(a.arguments[0] as? TextMessage)?.let {messages.add(it)};null}.`when`(session).sendMessage(any())
        val gateway=RunnerGateway(registry,manager);val handler=ExceptionWebSocketHandlerDecorator(gateway)
        try {
            manager.deleteInstance(instance.id)
            handler.afterConnectionEstablished(session);messages.clear()
            for(id in listOf("never-registered",instance.id,instance.id)) {
                handler.handleMessage(session,TextMessage(jacksonObjectMapper().writeValueAsString(RunnerMessage("install-result",instanceId=id,token="old-ticket",state="OK",detail="stale"))))
            }
            handler.handleMessage(session,TextMessage("""{"type":"heartbeat","version":"fixture-version"}"""))
            assertTrue(open.get(),"A late result must not close the shared WebSocket")
            verify(session,never()).close(any(CloseStatus::class.java))
            assertTrue(messages.any {jacksonObjectMapper().readTree(it.payload).path("type").asText()=="heartbeat-ack"})
            verify(registry).seen("runner","fixture-version")
            assertFalse(manager.root.resolve(instance.id).exists());assertFalse(manager.root.resolve("never-registered").exists())
            assertFailsWith<IllegalArgumentException> {manager.instance(instance.id)}
        } finally {gateway.closeDatagrams();cleanup(manager,instance.id)}
    }
    @Test fun `deletion while a result waits for the runtime lock cannot recreate the instance or throw`() {
        val manager=ServerManager();val instance=create(manager);val runtime=manager.runtime(instance.id)
        val failure=AtomicReference<Throwable?>();val done=CountDownLatch(1)
        val worker=Thread {
            try {manager.remoteInstallResult(instance.id,"runner","old-ticket",true,"late")}
            catch(e:Throwable){failure.set(e)} finally {done.countDown()}
        }
        try {
            synchronized(runtime) {
                worker.start()
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while(worker.state!=Thread.State.BLOCKED && done.count>0 && System.nanoTime()<until)Thread.sleep(5)
                assertEquals(Thread.State.BLOCKED,worker.state,"Fixture must capture the result waiting on the same runtime lock")
                manager.deleteInstance(instance.id)
            }
            assertTrue(done.await(3,TimeUnit.SECONDS));assertNull(failure.get())
            assertFalse(manager.root.resolve(instance.id).exists())
            assertFailsWith<IllegalArgumentException> {manager.runtime(instance.id)}
        } finally {worker.join(3000);cleanup(manager,instance.id)}
    }
}
