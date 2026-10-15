package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketMessage
import org.springframework.web.socket.WebSocketSession
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Collections
import kotlin.test.*

class RunnerGatewayTest {
    @Test fun `authenticated running report restores relay after manager restart without starting Minecraft`() {
        val manager=mock(ServerManager::class.java);val registry=mock(RunnerRegistry::class.java);val network=mock(GameNetwork::class.java)
        val port=java.net.ServerSocket(0).use {it.localPort}
        val instance=Instance("recover","test",loaderVersion="21.1.249",port=25565,memoryMb=512,javaPath="java",runnerId="runner")
        val runtime=RuntimeState().apply {state="RUNNING";remotePid=42L}
        val device=RunnerDevice("runner","fixture","MAC","user","key","now",version="0.4.10",allowedInstanceIds=setOf("recover"))
        `when`(registry.active("runner")).thenReturn(device);`when`(registry.online("runner")).thenReturn(true)
        `when`(manager.instance("recover")).thenReturn(instance);`when`(manager.runtime("recover")).thenReturn(runtime)
        `when`(network.config()).thenReturn(GameNetworkConfig(enabled=true,port=port,baseDomain="server.example.com"))
        val session=mock(WebSocketSession::class.java);`when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"));`when`(session.isOpen).thenReturn(true)
        val sent=mutableListOf<WebSocketMessage<*>>();doAnswer {sent.add(it.arguments[0] as WebSocketMessage<*>);null}.`when`(session).sendMessage(any())
        val gateway=RunnerGateway(registry,manager,network);gateway.afterConnectionEstablished(session);sent.clear()
        val running=TextMessage(jacksonObjectMapper().writeValueAsString(RunnerMessage("state","recover","RUNNING","0.4.10",pid=42L)))
        try {
            `when`(registry.active("runner")).thenReturn(device.copy(allowedInstanceIds=emptySet()))
            gateway.handleMessage(session,running)
            java.net.ServerSocket(port).close() // No listener from an ungranted report.
            `when`(registry.active("runner")).thenReturn(device)
            gateway.handleMessage(session,running);gateway.handleMessage(session,running)
            java.net.Socket("127.0.0.1",port).close()
            assertTrue(sent.isEmpty(),"Recovery must not send a start/stop command")
        }finally{gateway.completed(instance);gateway.closeDatagrams()}
    }
    @Test fun `heartbeat replies keep long uploads alive and stale sessions cannot renew them`() {
        val manager=mock(ServerManager::class.java);val registry=mock(RunnerRegistry::class.java)
        `when`(registry.active("runner")).thenReturn(RunnerDevice("runner","fixture","MAC","user","key","now"))
        val sent=mutableListOf<WebSocketMessage<*>>()
        fun session()=mock(WebSocketSession::class.java).also {s->
            `when`(s.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"));`when`(s.isOpen).thenReturn(true)
            doAnswer {sent.add(it.arguments[0] as WebSocketMessage<*>);null}.`when`(s).sendMessage(any())
        }
        val gateway=RunnerGateway(registry,manager);val old=session();gateway.afterConnectionEstablished(old)
        val current=session();gateway.afterConnectionEstablished(current);sent.clear()
        gateway.handleMessage(old,TextMessage("{\"type\":\"heartbeat\",\"version\":\"0.4.0-beta.8\"}"))
        assertTrue(sent.isEmpty())
        gateway.handleMessage(current,TextMessage("{\"type\":\"heartbeat\",\"version\":\"0.4.0-beta.8\"}"))
        val reply=jacksonObjectMapper().readValue<RunnerMessage>((sent.single() as TextMessage).payload)
        assertEquals("heartbeat-ack",reply.type)
        verify(registry).seen("runner","0.4.0-beta.8")
    }
    @Test fun `workspace responses require the current runner and instance and reads do not accept unrelated replies`() {
        val manager=mock(ServerManager::class.java);val registry=mock(RunnerRegistry::class.java);val instance=Instance("a","test",loaderVersion="21.1.249",port=25565,memoryMb=512,javaPath="java",runnerId="runner",fileSyncEnabled=false)
        val device=RunnerDevice("runner","fixture","MAC","user","key","now",version="0.4.5",allowedInstanceIds=setOf("a"));`when`(registry.active("runner")).thenReturn(device);`when`(registry.online("runner")).thenReturn(true)
        val mapper=jacksonObjectMapper();val session=mock(WebSocketSession::class.java);`when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"));`when`(session.isOpen).thenReturn(true)
        val gateway=RunnerGateway(registry,manager)
        doAnswer {args->val msg=args.arguments[0] as? TextMessage;if(msg!=null){val value=mapper.readValue<RunnerMessage>(msg.payload);if(value.type=="workspace"){
            gateway.handleMessage(session,TextMessage(mapper.writeValueAsString(RunnerMessage("workspace-result","wrong","OK",token=value.token,detail="{\"size\":999}"))))
            gateway.handleMessage(session,TextMessage(mapper.writeValueAsString(RunnerMessage("workspace-result","a","OK",token=value.token,detail="{\"size\":12}"))))
        }};null}.`when`(session).sendMessage(any())
        gateway.afterConnectionEstablished(session);assertEquals(12,gateway.workspace(instance,WorkspaceRequest("STAT",instance,"test.txt")).path("size").asInt())
        `when`(registry.active("runner")).thenReturn(device.copy(allowedInstanceIds=emptySet()));assertFails {gateway.workspace(instance,WorkspaceRequest("STAT",instance,"test.txt"))}
    }
    @Test fun `remote update commands use current signed session and reject legacy runners`() {
        val manager=mock(ServerManager::class.java);val registry=mock(RunnerRegistry::class.java)
        val device=RunnerDevice("runner","fixture","MAC","user","key","now",version="0.4.4")
        `when`(registry.active("runner")).thenReturn(device);`when`(registry.online("runner")).thenReturn(true)
        val session=mock(WebSocketSession::class.java);`when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"));`when`(session.isOpen).thenReturn(true)
        val sent=mutableListOf<WebSocketMessage<*>>();doAnswer {sent.add(it.arguments[0] as WebSocketMessage<*>);null}.`when`(session).sendMessage(any())
        val gateway=RunnerGateway(registry,manager);gateway.afterConnectionEstablished(session);sent.clear()
        `when`(registry.remoteUpdateSupported("runner")).thenReturn(true)
        assertTrue(gateway.requestUpdate("runner",true))
        val command=jacksonObjectMapper().readValue<RunnerMessage>((sent.single() as TextMessage).payload)
        assertEquals("update-request",command.type);assertEquals("PAUSE_TRANSFER",command.detail)
        gateway.handleMessage(session,TextMessage(jacksonObjectMapper().writeValueAsString(RunnerMessage("update-status",detail=jacksonObjectMapper().writeValueAsString(RunnerUpdateStatus("DOWNLOADING","0.4.5",5,10))))))
        verify(registry).updateStatus("runner",RunnerUpdateStatus("DOWNLOADING","0.4.5",5,10))
        `when`(registry.active("runner")).thenReturn(device.copy(version="0.4.3"));`when`(registry.remoteUpdateSupported("runner")).thenReturn(false);assertFails {gateway.requestUpdate("runner",false)}
    }
    @Test fun `diagnostics reject old runners before sending start and carry isolated context on new runners`() {
        val manager=mock(ServerManager::class.java);val registry=mock(RunnerRegistry::class.java)
        val instance=Instance("a","A",loaderVersion="21.1.252",port=ServerSocket(0).use {it.localPort},memoryMb=512,javaPath="java",runnerId="runner",diagnosticSession=java.util.UUID.randomUUID().toString())
        val device=RunnerDevice("runner","fixture","MAC","user","key","now",version="0.4.2",allowedInstanceIds=setOf("a"))
        `when`(registry.active("runner")).thenReturn(device);`when`(registry.online("runner")).thenReturn(true)
        val session=mock(WebSocketSession::class.java);`when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"));`when`(session.isOpen).thenReturn(true)
        val messages=mutableListOf<WebSocketMessage<*>>();doAnswer {messages.add(it.arguments[0] as WebSocketMessage<*>);null}.`when`(session).sendMessage(any())
        val gateway=RunnerGateway(registry,manager);gateway.afterConnectionEstablished(session);messages.clear()
        try {
            assertFailsWith<IllegalStateException>{gateway.start(instance)};assertTrue(messages.isEmpty())
            `when`(registry.active("runner")).thenReturn(device.copy(version="0.4.3"))
            gateway.start(instance)
            val message=jacksonObjectMapper().readValue<RunnerMessage>((messages.single() as TextMessage).payload)
            assertEquals(instance.diagnosticSession,jacksonObjectMapper().readValue<Instance>(message.detail!!).diagnosticSession)
        } finally {gateway.completed(instance)}
    }
    @Test fun `idle requests require a current authorized worker and never fall back to unchecked stop`() {
        val manager=mock(ServerManager::class.java);val registry=mock(RunnerRegistry::class.java)
        val instance=Instance("a","A",loaderVersion="21.1.252",port=25565,memoryMb=512,javaPath="java",runnerId="runner")
        val device=RunnerDevice("runner","fixture","MAC","user","key","now",version="0.4.0-beta.5",allowedInstanceIds=setOf("a"))
        `when`(registry.active("runner")).thenReturn(device);`when`(registry.online("runner")).thenReturn(true)
        val session=mock(WebSocketSession::class.java);`when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"));`when`(session.isOpen).thenReturn(true)
        val messages=mutableListOf<WebSocketMessage<*>>();doAnswer {messages.add(it.arguments[0] as WebSocketMessage<*>);null}.`when`(session).sendMessage(any())
        val gateway=RunnerGateway(registry,manager);gateway.afterConnectionEstablished(session);messages.clear()
        assertFalse(gateway.stopIfEmpty(instance,"ticket"));assertTrue(messages.isEmpty())
        `when`(registry.active("runner")).thenReturn(device.copy(version="0.4.0-beta.6"))
        assertTrue(gateway.stopIfEmpty(instance,"ticket"))
        val sent=jacksonObjectMapper().readValue<RunnerMessage>((messages.single() as TextMessage).payload)
        assertEquals("idle-stop",sent.type);assertEquals("a",sent.instanceId);assertEquals("ticket",sent.detail)
        messages.clear();`when`(registry.active("runner")).thenReturn(device.copy(version="0.4.0-beta.6",allowedInstanceIds=emptySet()))
        assertFails {gateway.stopIfEmpty(instance,"ticket")};assertTrue(messages.isEmpty())
        gateway.completed(instance)
    }
    @Test fun `two computers share one public port without mixing streams and one stop leaves the other online`() {
        val port=ServerSocket(0).use {it.localPort}
        val manager=mock(ServerManager::class.java);val registry=mock(RunnerRegistry::class.java);val network=mock(GameNetwork::class.java)
        `when`(network.config()).thenReturn(GameNetworkConfig(enabled=true,sableUdpEnabled=true,port=port,baseDomain="server.example.com"))
        `when`(network.resolve("alpha.server.example.com")).thenReturn("alpha")
        `when`(network.resolve("beta.server.example.com")).thenReturn("beta")
        val gateway=RunnerGateway(registry,manager,network);val mapper=jacksonObjectMapper()
        val sent=mutableMapOf<String,MutableList<WebSocketMessage<*>>>()
        fun session(id:String,instance:String,platform:String):WebSocketSession {
            val device=RunnerDevice(id,id,platform,"user","key","now",version="0.4.6",allowedInstanceIds=setOf(instance))
            `when`(registry.active(id)).thenReturn(device);`when`(registry.online(id)).thenReturn(true)
            val session=mock(WebSocketSession::class.java);`when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to id));`when`(session.isOpen).thenReturn(true)
            val messages=Collections.synchronizedList(mutableListOf<WebSocketMessage<*>>());sent[id]=messages
            doAnswer {messages.add(it.arguments[0] as WebSocketMessage<*>);null}.`when`(session).sendMessage(any())
            gateway.afterConnectionEstablished(session);return session
        }
        val a=session("mac","alpha","MAC");val b=session("windows","beta","WINDOWS")
        val alpha=Instance("alpha","Alpha",loader="Fabric",loaderVersion="0.16.10",port=25565,memoryMb=512,javaPath="java",runnerId="mac")
        val beta=alpha.copy(id="beta",name="Beta",loader="Paper",loaderVersion="133",runnerId="windows")
        gateway.start(alpha);gateway.start(beta)
        fun <T> await(value:()->T?):T {
            val deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos()
            while(System.nanoTime()<deadline){value()?.let {return it};Thread.sleep(10)}
            error("중계 응답 시간 초과")
        }
        fun open(player:Socket,id:String,session:WebSocketSession,host:String):Pair<Int,ByteArray> {
            player.soTimeout=3000
            val raw=GameNetworkTest().handshake(host);player.getOutputStream().write(raw)
            val frame=await {synchronized(sent.getValue(id)){sent.getValue(id).filterIsInstance<TextMessage>().map {mapper.readValue<RunnerMessage>(it.payload)}.firstOrNull {it.type=="open"}}}
            val stream=frame.streamId!!
            gateway.handleMessage(session,TextMessage(mapper.writeValueAsString(RunnerMessage("opened",streamId=stream))))
            val binary=await {synchronized(sent.getValue(id)){sent.getValue(id).filterIsInstance<BinaryMessage>().firstOrNull {it.payload.asReadOnlyBuffer().int==stream}}}
            val bytes=binary.payload.asReadOnlyBuffer();assertEquals(stream,bytes.int);assertContentEquals(raw,ByteArray(bytes.remaining()).also {bytes.get(it)})
            return stream to raw
        }
        fun downstream(session:WebSocketSession,stream:Int,text:String)=gateway.handleMessage(session,BinaryMessage(ByteBuffer.allocate(4+text.length).putInt(stream).put(text.toByteArray()).flip()))
        try {
            Socket("127.0.0.1",port).use { pa -> Socket("127.0.0.1",port).use {pb ->
                val (sa,_)=open(pa,"mac",a,"alpha.server.example.com\u0000FML3\u0000")
                val (sb,_)=open(pb,"windows",b,"beta.server.example.com")
                assertNotEquals(sa,sb)
                fun dataSocket(runner:String):Pair<WebSocketSession,MutableList<WebSocketMessage<*>>> {
                    val socket=mock(WebSocketSession::class.java);`when`(socket.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to runner,"sableUdp" to true));`when`(socket.isOpen).thenReturn(true)
                    val frames=Collections.synchronizedList(mutableListOf<WebSocketMessage<*>>());doAnswer {frames.add(it.arguments[0] as WebSocketMessage<*>);null}.`when`(socket).sendMessage(any());gateway.afterConnectionEstablished(socket);return socket to frames
                }
                val (ua,framesA)=dataSocket("mac");val (ub,framesB)=dataSocket("windows")
                val authA=java.util.UUID.randomUUID();val authB=java.util.UUID.randomUUID()
                gateway.handleMessage(a,TextMessage(mapper.writeValueAsString(RunnerMessage("sable-register","alpha",streamId=sa,token=authA.toString()))))
                gateway.handleMessage(b,TextMessage(mapper.writeValueAsString(RunnerMessage("sable-register","beta",streamId=sb,token=authB.toString()))))
                java.net.DatagramSocket().use {udpA->java.net.DatagramSocket().use {udpB->
                    fun authenticate(udp:java.net.DatagramSocket,token:java.util.UUID){val bytes=byteArrayOf(3,36)+token.toString().toByteArray();udp.send(java.net.DatagramPacket(bytes,bytes.size,java.net.InetAddress.getByName("127.0.0.1"),port))}
                    authenticate(udpA,authA);authenticate(udpB,authB)
                    await {synchronized(framesA){framesA.filterIsInstance<BinaryMessage>().firstOrNull()}}
                    await {synchronized(framesB){framesB.filterIsInstance<BinaryMessage>().firstOrNull()}}
                    assertEquals(sa,(framesA.first() as BinaryMessage).payload.asReadOnlyBuffer().int)
                    assertEquals(sb,(framesB.first() as BinaryMessage).payload.asReadOnlyBuffer().int)
                    val reply=ByteBuffer.allocate(5).putInt(sa).put(4).array()
                    gateway.handleMessage(ub,BinaryMessage(reply));udpA.soTimeout=80
                    assertFailsWith<java.net.SocketTimeoutException>{udpA.receive(java.net.DatagramPacket(ByteArray(32),32))}
                    gateway.handleMessage(ua,BinaryMessage(reply));udpA.soTimeout=2000
                    val packet=java.net.DatagramPacket(ByteArray(32),32);udpA.receive(packet);assertEquals(4,packet.data[0].toInt())
                }}

                downstream(b,sa,"leak");pa.soTimeout=100
                assertFailsWith<java.net.SocketTimeoutException>{pa.getInputStream().read()};pa.soTimeout=3000
                downstream(a,sa,"a");downstream(b,sb,"b")
                assertEquals('a'.code,pa.getInputStream().read());assertEquals('b'.code,pb.getInputStream().read())
                gateway.completed(alpha);downstream(b,sb,"c");assertEquals('c'.code,pb.getInputStream().read())
                Socket("127.0.0.1",port).use {unknown->unknown.soTimeout=3000;unknown.getOutputStream().write(GameNetworkTest().handshake("unknown.server.example.com"));assertEquals(-1,unknown.getInputStream().read())}
            } }
        } finally {gateway.completed(alpha);gateway.completed(beta)}
    }
    @Test fun `public game socket relays bytes to and from authorized runner`() {
        val port=ServerSocket(0).use { it.localPort }
        val manager=mock(ServerManager::class.java)
        val registry=mock(RunnerRegistry::class.java)
        val runner=RunnerDevice("runner","test","MAC","user","key","now",allowedInstanceIds=setOf("instance"))
        `when`(registry.active("runner")).thenReturn(runner)
        `when`(registry.online("runner")).thenReturn(true)
        val session=mock(WebSocketSession::class.java)
        `when`(session.attributes).thenReturn(mutableMapOf<String,Any>("runnerId" to "runner"))
        `when`(session.isOpen).thenReturn(true)
        val sent=Collections.synchronizedList(mutableListOf<WebSocketMessage<*>>())
        doAnswer { sent.add(it.arguments[0] as WebSocketMessage<*>);null }.`when`(session).sendMessage(any())
        val gateway=RunnerGateway(registry,manager)
        gateway.afterConnectionEstablished(session)
        val instance=Instance("instance","test",loaderVersion="21.1.252",port=port,memoryMb=512,javaPath="java",runnerId="runner")
        gateway.start(instance)
        try {
            Socket("127.0.0.1",port).use { player ->
                player.soTimeout=5000
                val mapper=jacksonObjectMapper()
                val deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos()
                var open:RunnerMessage?=null
                while(System.nanoTime()<deadline && open==null) {
                    open=sent.filterIsInstance<TextMessage>().map { mapper.readValue<RunnerMessage>(it.payload) }.firstOrNull { it.type=="open" }
                    if(open==null)Thread.sleep(10)
                }
                val stream=open?.streamId ?: error("게임 스트림이 열리지 않았습니다")
                gateway.handleMessage(session,TextMessage(mapper.writeValueAsString(RunnerMessage("opened",streamId=stream))))
                player.getOutputStream().write("ping".toByteArray())
                var upstream:BinaryMessage?=null
                while(System.nanoTime()<deadline && upstream==null) {
                    upstream=sent.filterIsInstance<BinaryMessage>().firstOrNull { it.payload.asReadOnlyBuffer().int==stream }
                    if(upstream==null)Thread.sleep(10)
                }
                val bytes=upstream?.payload?.asReadOnlyBuffer() ?: error("게임 업스트림 데이터가 없습니다")
                assertEquals(stream,bytes.int)
                assertEquals("ping",ByteArray(bytes.remaining()).also { bytes.get(it) }.toString(Charsets.UTF_8))
                gateway.handleMessage(session,BinaryMessage(ByteBuffer.allocate(8).putInt(stream).put("pong".toByteArray()).flip()))
                assertEquals("pong",ByteArray(4).also { player.getInputStream().readNBytes(it,0,4) }.toString(Charsets.UTF_8))
            }
        } finally { gateway.completed(instance) }
    }
}
