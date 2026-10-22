package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.web.socket.*
import java.nio.file.Path
import java.net.http.WebSocket
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class RunnerServiceRestartTest {
    @TempDir lateinit var temp:Path
    @Suppress("UNCHECKED_CAST") private fun <T> field(a:RunnerAgent,n:String)=RunnerAgent::class.java.getDeclaredField(n).also {it.isAccessible=true}.get(a) as T
    @Test fun `worker refuses raw jar and preparation but acknowledges exact supervised request before exit`() {
        for(mode in listOf("raw","preparing","accepted")) {
            val agent=RunnerAgent(temp.resolve(mode),RunnerIdentity("https://localhost:1","runner","fixture","unused","MAC"),supervisedRestart=mode!="raw")
            val socket=mock(WebSocket::class.java);val messages=CopyOnWriteArrayList<String>()
            doAnswer {messages.add(it.arguments[0].toString());CompletableFuture.completedFuture(socket)}.`when`(socket).sendText(any(CharSequence::class.java),anyBoolean())
            field<AtomicReference<WebSocket?>>(agent,"connection").set(socket)
            val preparing=field<MutableSet<String>>(agent,"preparing");if(mode=="preparing")preparing.add("fixture")
            val done=CountDownLatch(1);agent.restartProcess={done.countDown()}
            try {
                assertEquals(when(mode){"raw"->"UNSUPERVISED";"preparing"->"SERVER_PREPARING";else->null},agent.restartRequested("exact-ticket"))
                assertContains(messages.single {it.contains("restart-result")},"exact-ticket")
                if(mode=="accepted"){assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals("BUSY",agent.restartRequested("duplicate"))}else assertEquals(1L,done.count)
            } finally {preparing.clear();agent.shutdownGracefully()}
        }
    }
    @Test fun `gateway tracks accepted request and confirms a new boot but not a same process reconnect`() {
        val registry=mock(RunnerRegistry::class.java);`when`(registry.active("runner")).thenReturn(RunnerDevice("runner","fixture","MAC","owner","key","now"))
        `when`(registry.online("runner")).thenReturn(true)
        val gateway=RunnerGateway(registry,mock(ServerManager::class.java));val mapper=jacksonObjectMapper()
        val session=mock(WebSocketSession::class.java);`when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"));`when`(session.isOpen).thenReturn(true)
        doAnswer {call->val msg=mapper.readValue((call.arguments[0] as TextMessage).payload,RunnerMessage::class.java)
            if(msg.type=="restart-request")gateway.handleMessage(session,TextMessage(mapper.writeValueAsString(RunnerMessage("restart-result",state="ACCEPTED",token=msg.token))))
            null}.`when`(session).sendMessage(any())
        fun capability(boot:String)=gateway.handleMessage(session,TextMessage(mapper.writeValueAsString(RunnerMessage("restart-capability",state="RESTART_V2",detail=boot))))
        try {
            gateway.afterConnectionEstablished(session);val first=UUID.randomUUID().toString();capability(first)
            assertNull(gateway.requestRestart("runner"));assertEquals("RESTARTING",gateway.restartStatus("runner")!!.stage)
            capability(first);assertEquals("RESTARTING",gateway.restartStatus("runner")!!.stage)
            assertFails {gateway.requestRestart("runner")}
            capability(UUID.randomUUID().toString());assertEquals("COMPLETE",gateway.restartStatus("runner")!!.stage)
        }finally{gateway.closeDatagrams()}
    }
}
