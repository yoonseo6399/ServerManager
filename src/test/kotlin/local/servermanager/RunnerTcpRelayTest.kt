package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.*
import org.springframework.web.socket.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class RunnerTcpRelayTest {
    @Test @Timeout(15) fun `selected relay reaches game runner through authenticated central routing and reconnect needs capability`() {
        val mapper=jacksonObjectMapper()
        val manager=mock(ServerManager::class.java);val registry=mock(RunnerRegistry::class.java);val network=mock(GameNetwork::class.java)
        val centralPort=ServerSocket(0).use {it.localPort};val ingressPort=ServerSocket(0).use {it.localPort}
        val config=GameNetworkConfig(enabled=true,port=centralPort,baseDomain="server.example.com",relayRunnerId="relay")
        `when`(network.config()).thenReturn(config);`when`(network.resolve("alpha.server.example.com")).thenReturn("alpha")
        val gateway=RunnerGateway(registry,manager,network)
        val commands=CopyOnWriteArrayList<RunnerMessage>();val gameData=CopyOnWriteArrayList<ByteArray>()
        var supported=false
        `when`(registry.tcpRelaySupported("relay")).thenAnswer {supported}
        doAnswer {supported=it.arguments[1] as Boolean;null}.`when`(registry).tcpRelayCapability(anyString(),anyBoolean())
        fun session(id:String):WebSocketSession {
            `when`(registry.active(id)).thenReturn(RunnerDevice(id,id,"MAC","user","key","now",version="0.4.12-beta.1",allowedInstanceIds=if(id=="game")setOf("alpha") else emptySet()))
            `when`(registry.online(id)).thenReturn(true)
            return mock(WebSocketSession::class.java).also {s->`when`(s.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to id));`when`(s.isOpen).thenReturn(true)}
        }
        val ingressSession=session("relay");val game=session("game")
        lateinit var ingress:TcpRelayPeer
        fun text(session:WebSocketSession,message:RunnerMessage) {gateway.handleMessage(session,TextMessage(mapper.writeValueAsString(message)))}
        ingress=TcpRelayPeer({text(ingressSession,it);true},{id,data,count->gateway.handleMessage(ingressSession,BinaryMessage(ByteBuffer.allocate(4+count).putInt(id).put(data,0,count).flip()));true})
        doAnswer {invocation->
            when(val frame=invocation.arguments[0]) {
                is TextMessage-> {
                    val message=mapper.readValue<RunnerMessage>(frame.payload)
                    when(message.type) {
                        "relay-config"->ingress.listen(if(message.state=="ON")ingressPort else null)
                        "relay-opened"->ingress.ready(message.streamId!!)
                        "relay-closed"->ingress.end(message.streamId!!)
                    }
                }
                is BinaryMessage->{val b=frame.payload.asReadOnlyBuffer();val id=b.int;ingress.receive(id,ByteArray(b.remaining()).also(b::get))}
            };null
        }.`when`(ingressSession).sendMessage(any())
        doAnswer {invocation->
            when(val frame=invocation.arguments[0]) {
                is TextMessage->{val message=mapper.readValue<RunnerMessage>(frame.payload);commands.add(message);if(message.type=="open")text(game,RunnerMessage("opened",streamId=message.streamId))}
                is BinaryMessage->{val buffer=frame.payload.asReadOnlyBuffer();val id=buffer.int;val data=ByteArray(buffer.remaining()).also(buffer::get);gameData.add(data)
                    val reply="game reply".toByteArray();gateway.handleMessage(game,BinaryMessage(ByteBuffer.allocate(4+reply.size).putInt(id).put(reply).flip()))}
            };null
        }.`when`(game).sendMessage(any())
        val instance=Instance("alpha","Alpha",loader="Fabric",loaderVersion="0.16.10",port=25565,memoryMb=512,javaPath="java",runnerId="game")
        try {
            gateway.afterConnectionEstablished(ingressSession);gateway.afterConnectionEstablished(game)
            text(ingressSession,RunnerMessage("relay-capability",state="TCP_V1"));gateway.start(instance)
            Socket("127.0.0.1",ingressPort).use {player->
                player.soTimeout=5000
                val raw=GameNetworkTest().handshake("alpha.server.example.com\u0000FML3\u0000")
                player.getOutputStream().write(raw)
                assertEquals("game reply",player.getInputStream().readNBytes(10).toString(Charsets.UTF_8))
                assertContentEquals(raw,gameData.single())
                assertEquals(1,commands.count {it.type=="start"})
            }
            gateway.completed(instance)
            assertFails {Socket("127.0.0.1",ingressPort).close()}
            gateway.afterConnectionClosed(ingressSession,CloseStatus.NORMAL)
            assertTrue(commands.none {it.type=="stop"})
        } finally {gateway.completed(instance);ingress.close();gateway.closeDatagrams()}
    }
    @Test fun `unselected runner relay requests are rejected without opening a stream`() {
        val registry=mock(RunnerRegistry::class.java);val network=mock(GameNetwork::class.java)
        `when`(network.config()).thenReturn(GameNetworkConfig(enabled=true,baseDomain="server.example.com",relayRunnerId="selected"))
        `when`(registry.active("other")).thenReturn(RunnerDevice("other","other","MAC","user","key","now"))
        val session=mock(WebSocketSession::class.java);`when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "other"));`when`(session.isOpen).thenReturn(true)
        val sent=mutableListOf<RunnerMessage>();doAnswer {val message=it.arguments[0];if(message is TextMessage)sent.add(jacksonObjectMapper().readValue<RunnerMessage>(message.payload));null}.`when`(session).sendMessage(any())
        val gateway=RunnerGateway(registry,mock(ServerManager::class.java),network)
        try {gateway.afterConnectionEstablished(session);sent.clear();gateway.handleMessage(session,TextMessage("""{"type":"relay-open","streamId":-1}"""));assertEquals("relay-closed",sent.single().type)} finally {gateway.closeDatagrams()}
    }
}
