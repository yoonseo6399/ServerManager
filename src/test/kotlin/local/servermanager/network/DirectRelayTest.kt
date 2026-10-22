package local.servermanager

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DirectRelayTest {
    private val closeables=mutableListOf<AutoCloseable>()
    private fun <T:AutoCloseable> T.track():T{closeables.add(this);return this}
    @AfterEach fun cleanup(){closeables.reversed().forEach {runCatching {it.close()}}}

    private val relayId=UUID.randomUUID().toString()
    private val agentId=UUID.randomUUID().toString()
    private val secret=DirectRelayCrypto.newSecret()
    private val host="play.example.com"

    private fun varint(out:ByteArrayOutputStream,value:Int){var v=value;while(true){if(v and 0x7f.inv()==0){out.write(v);return};out.write(v and 0x7f or 0x80);v=v ushr 7}}
    private fun handshake(host:String,tail:ByteArray=ByteArray(0)):ByteArray {
        val body=ByteArrayOutputStream();varint(body,0);varint(body,767);val name=host.toByteArray();varint(body,name.size);body.write(name);body.write(0x63);body.write(0xdd);varint(body,2)
        val packet=ByteArrayOutputStream();varint(packet,body.size());body.writeTo(packet);packet.write(tail);return packet.toByteArray()
    }
    /** Fake Minecraft: one prefix per connection, then byte-for-byte echo regardless of TCP read boundaries. */
    private class EchoGame:AutoCloseable {
        val server=ServerSocket(0);val accepted=AtomicInteger();val received=java.util.concurrent.CopyOnWriteArrayList<String>()
        init {Thread.ofVirtual().start {while(!server.isClosed){val s=runCatching {server.accept()}.getOrNull() ?: break;accepted.incrementAndGet()
            Thread.ofVirtual().start {runCatching {val b=ByteArray(4096);val i=s.getInputStream();val o=s.getOutputStream()
                var first=true;while(true){val n=i.read(b);if(n<0)break;received.add(String(b,0,n));if(first){o.write("echo:".toByteArray());first=false};o.write(b,0,n);o.flush()}}}}}}
        override fun close(){server.close()}
    }
    private class Setup(val hub:DirectRelayHub,val game:EchoGame,val agent:DirectRelayAgent,val ticketCount:AtomicInteger)

    private fun hub()=DirectRelayHub(relayId,secret).track().also {check(it.listen(0,0,"127.0.0.1"))}
    private fun agentFor(hub:DirectRelayHub,game:EchoGame,epoch:Long=1,instance:String="inst",tickets:()->RelayTicket={DirectRelayCrypto.issue(secret,relayId,agentId,epoch,60_000)}):DirectRelayAgent =
        DirectRelayAgent({RelayEndpoint("127.0.0.1",hub.securePort!!,tickets())},{if(it==instance)game.server.localPort else null},backoffMillis=longArrayOf(50,100)).track().also {it.start()}
    private fun policy(generation:Long=1,epoch:Long=1,instance:String="inst",host:String=this.host)=RelayPolicy(generation,mapOf(agentId to epoch),listOf(RelayRoute(host,agentId,instance)))
    private fun await(message:String,timeout:Long=5000,condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(!condition()){assertTrue(System.currentTimeMillis()<end,message);Thread.sleep(20)}}
    private fun connectedAgent(hub:DirectRelayHub,agent:DirectRelayAgent)=await("agent connect"){agent.state==RelayAgentState.CONNECTED && hub.status().agents.isNotEmpty()}
    private fun player(hub:DirectRelayHub,host:String=this.host,payload:String="hello"):Socket {
        val s=Socket();s.connect(InetSocketAddress("127.0.0.1",hub.gamePort!!),3000);s.soTimeout=3000
        s.getOutputStream().write(handshake(host,payload.toByteArray()));s.getOutputStream().flush();return s
    }
    private fun readUntil(s:Socket,marker:String):String {
        val out=ByteArrayOutputStream();val b=ByteArray(4096)
        while(!out.toString(Charsets.UTF_8).contains(marker)){
            val n=s.getInputStream().read(b);assertTrue(n>=0,"EOF before marker $marker");out.write(b,0,n);assertTrue(out.size()<65536,"missing marker $marker")
        }
        return out.toString(Charsets.UTF_8)
    }
    private fun readAll(s:Socket,min:Int):String {val b=ByteArray(4096);var total=ByteArray(0);while(total.size<min){val n=s.getInputStream().read(b);if(n<0)break;total+=b.copyOf(n)};return String(total)}

    @Test fun `game bytes flow player to relay to game runner to Minecraft over the encrypted tunnel`() {
        val game=EchoGame().track();val hub=hub();hub.applyPolicy(policy());val agent=agentFor(hub,game);connectedAgent(hub,agent)
        player(hub).use {assertTrue(readUntil(it,"hello").startsWith("echo:"));assertTrue(game.received.joinToString("").contains("hello"))}
        assertTrue(hub.status().agents.single().agentId==agentId)
    }

    @Test fun `game and secure ports must differ and a port conflict is reported not swallowed`() {
        val hub=DirectRelayHub(relayId,secret).track()
        assertFailsWith<IllegalArgumentException> {hub.listen(40001,40001)}
        val taken=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1")).track()
        assertFalse(hub.listen(taken.localPort,0,"127.0.0.1"))
        assertNotNull(hub.status().lastBindError);assertTrue((hub.status().rejects["PORT_BIND_FAILED"] ?: 0)>=1)
        assertFalse(hub.status().gameListening)
    }

    @Test fun `unregistered device and forged tickets are refused without revealing state`() {
        val game=EchoGame().track();val hub=hub();hub.applyPolicy(policy())
        val forged=RelayTicket(DirectRelayCrypto.issue(secret,relayId,agentId,1,60_000).body,DirectRelayCrypto.newSecret())
        val reason=agentReason(hub,game){forged};assertEquals("AUTH_FAILED",reason)
        val strangerSecret=DirectRelayCrypto.newSecret()
        assertEquals("AUTH_FAILED",agentReason(hub,game){DirectRelayCrypto.issue(strangerSecret,relayId,agentId,1,60_000)})
        val unknown=UUID.randomUUID().toString()
        assertEquals("NOT_APPROVED",agentReason(hub,game){DirectRelayCrypto.issue(secret,relayId,unknown,1,60_000)})
        assertTrue(hub.status().agents.isEmpty())
    }
    private fun agentReason(hub:DirectRelayHub,game:EchoGame,ticket:()->RelayTicket):String? {
        val agent=agentFor(hub,game,tickets=ticket);await("rejection"){agent.state==RelayAgentState.REJECTED};val r=agent.reason;agent.close();return r
    }

    @Test fun `stale epoch expired wrong relay and replayed tickets are rejected`() {
        val game=EchoGame().track();val hub=hub();hub.applyPolicy(policy(epoch=2))
        assertEquals("STALE_EPOCH",agentReason(hub,game){DirectRelayCrypto.issue(secret,relayId,agentId,1,60_000)})
        assertEquals("EXPIRED",agentReason(hub,game){DirectRelayCrypto.issue(secret,relayId,agentId,2,-1000)})
        assertEquals("WRONG_RELAY",agentReason(hub,game){DirectRelayCrypto.issue(secret,UUID.randomUUID().toString(),agentId,2,60_000)})
        val ticket=DirectRelayCrypto.issue(secret,relayId,agentId,2,60_000)
        Socket("127.0.0.1",hub.securePort!!).use {DirectRelayHandshake.client(it,ticket).close()}
        val replay=assertFailsWith<DirectRelayRejected> {Socket("127.0.0.1",hub.securePort!!).use {DirectRelayHandshake.client(it,ticket)}}
        assertEquals("REPLAYED",replay.reason)
    }

    @Test fun `a relay that does not know the secret cannot impersonate the relay to a game runner`() {
        val impostor=DirectRelayHub(relayId,DirectRelayCrypto.newSecret()).track();impostor.listen(0,0,"127.0.0.1")
        impostor.applyPolicy(policy())
        val ticket=DirectRelayCrypto.issue(secret,relayId,agentId,1,60_000)
        val failure=assertFailsWith<DirectRelayRejected> {Socket("127.0.0.1",impostor.securePort!!).use {DirectRelayHandshake.client(it,ticket)}}
        assertTrue(failure.reason in setOf("AUTH_FAILED","RELAY_NOT_AUTHENTIC"))
    }

    @Test fun `relay never connects to arbitrary destinations and the agent refuses instances it does not own`() {
        val game=EchoGame().track();val hub=hub()
        hub.applyPolicy(RelayPolicy(1,mapOf(agentId to 1),listOf(RelayRoute(host,agentId,"inst"),RelayRoute("other.example.com",agentId,"someone-elses-server"))))
        val agent=agentFor(hub,game);connectedAgent(hub,agent)
        // Hostname absent from the approved route table.
        player(hub,"evil.example.com").use {assertTrue(runCatching {it.getInputStream().read()}.getOrDefault(-1)<0)}
        // Approved hostname, but the assigned instance is not served by this agent.
        player(hub,"other.example.com").use {assertTrue(runCatching {it.getInputStream().read()}.getOrDefault(-1)<0)}
        await("rejects recorded"){hub.status().rejects.containsKey("UNKNOWN_ROUTE") && hub.status().rejects.containsKey("AGENT_NOT_ALLOWED")}
        assertEquals(0,game.accepted.get(),"No local connection may be made for rejected routes")
    }

    @Test fun `route switch keeps live streams while new connections use the new agent route`() {
        val oldGame=EchoGame().track();val newGame=EchoGame().track();val hub=hub()
        hub.applyPolicy(policy(1,instance="old"));val agent=agentFor(hub,oldGame,instance="old");connectedAgent(hub,agent)
        val live=player(hub,payload="before");assertTrue(readUntil(live,"before").startsWith("echo:"))
        // Same hostname now maps to a different instance served by a second agent.
        val second=UUID.randomUUID().toString();val agent2=DirectRelayAgent({RelayEndpoint("127.0.0.1",hub.securePort!!,DirectRelayCrypto.issue(secret,relayId,second,1,60_000))},{if(it=="new")newGame.server.localPort else null},{it=="new"},backoffMillis=longArrayOf(50)).track();agent2.start()
        hub.applyPolicy(RelayPolicy(2,mapOf(agentId to 1,second to 1),listOf(RelayRoute(host,agentId,"old"))))
        await("agent2 connect"){hub.status().agents.size==2}
        assertTrue(hub.probe(second,"new"));assertFalse(hub.probe(second,"old"),"probe must report not-ready for an instance the agent does not serve")
        hub.applyPolicy(RelayPolicy(3,mapOf(agentId to 1,second to 1),listOf(RelayRoute(host,second,"new"))))
        live.getOutputStream().write("still-alive".toByteArray());live.getOutputStream().flush()
        assertTrue(readUntil(live,"still-alive").contains("still-alive"),"existing connection survives the route change")
        player(hub,payload="after").use {assertTrue(readUntil(it,"after").startsWith("echo:"))}
        assertTrue(newGame.received.joinToString("").contains("after"));assertFalse(oldGame.received.joinToString("").contains("after"))
        live.close()
        assertFalse(hub.applyPolicy(RelayPolicy(2,emptyMap(),emptyList())),"older policy generation must be ignored")
        assertTrue(hub.status().rejects.containsKey("STALE_POLICY"))
    }

    @Test fun `revocation cuts the tunnel immediately and the agent reconnect is refused`() {
        val game=EchoGame().track();val hub=hub();hub.applyPolicy(policy());val agent=agentFor(hub,game);connectedAgent(hub,agent)
        val live=player(hub);assertTrue(readAll(live,6).startsWith("echo:"))
        hub.applyPolicy(RelayPolicy(2,emptyMap(),emptyList()))
        await("agent notices revocation"){agent.state==RelayAgentState.REJECTED}
        assertTrue(hub.status().agents.isEmpty())
        val marker="AFTER_REVOCATION_${UUID.randomUUID()}"
        runCatching {live.getOutputStream().write(marker.toByteArray());live.getOutputStream().flush()}
        live.soTimeout=3000
        val buffered=ByteArrayOutputStream()
        try {while(true){val n=live.getInputStream().read();if(n<0)break;buffered.write(n);assertTrue(buffered.size()<65536)}}
        catch(e:java.net.SocketException) { /* Reset is also a closed tunnel; timeout still fails. */ }
        assertFalse(buffered.toString(Charsets.UTF_8).contains(marker),"post-revocation data reached the game")
        live.close()
    }

    @Test fun `manager outage keeps the established tunnel and reports an explicit reason for new attempts`() {
        // Closing the client and hub concurrently used to race the Kotlin single-item
        // Collection.toList optimization on ConcurrentHashMap.keys (NoSuchElementException).
        repeat(20) {
            val game=EchoGame().track();val hub=hub();hub.applyPolicy(policy());val managerUp=java.util.concurrent.atomic.AtomicBoolean(true)
            val agent=DirectRelayAgent({if(managerUp.get())RelayEndpoint("127.0.0.1",hub.securePort!!,DirectRelayCrypto.issue(secret,relayId,agentId,1,60_000))else null},{game.server.localPort},backoffMillis=longArrayOf(50)).track()
            try {
                agent.start();connectedAgent(hub,agent);managerUp.set(false)
                player(hub).use {assertTrue(readAll(it,6).startsWith("echo:"),"approved traffic continues while the manager is away")}
                assertEquals(RelayAgentState.CONNECTED,agent.state)
                hub.close()
                await("manager unavailable"){agent.state==RelayAgentState.MANAGER_UNAVAILABLE}
                assertTrue(agent.reason!!.contains("관리 서버"));assertEquals(0,hub.streamCount())
            }finally {agent.close();hub.close();game.close()}
        }
    }

    @Test fun `coalesced handshake response and first encrypted frame loses no bytes`() {
        val server=ServerSocket(0).track();val ticket=DirectRelayCrypto.issue(secret,relayId,agentId,1,60000)
        val sent=java.util.concurrent.CompletableFuture<Unit>()
        Thread.ofVirtual().start {
            try {server.accept().use {peer ->
                val captured=ByteArrayOutputStream()
                val bufferedPeer=object:Socket(){override fun getInputStream()=peer.getInputStream();override fun getOutputStream():java.io.OutputStream=captured}
                val channel=DirectRelayHandshake.server(bufferedPeer,secret){null}.first
                channel.send(SecureChannel.DATA,7,"first-frame".toByteArray())
                // Exactly one network write contains both the handshake and first application record.
                peer.getOutputStream().write(captured.toByteArray());peer.getOutputStream().flush();sent.complete(Unit)
                Thread.sleep(200)
            }}catch(e:Exception){sent.completeExceptionally(e)}
        }
        Socket("127.0.0.1",server.localPort).use {peer ->
            peer.soTimeout=2000
            val channel=DirectRelayHandshake.client(peer,ticket)
            assertEquals("first-frame",String(channel.receive()!!.payload))
        }
        sent.get(3,java.util.concurrent.TimeUnit.SECONDS)
    }
    @Test fun `idle read exception closes handed off player and releases stream`() {
        val game=EchoGame().track();val hub=DirectRelayHub(relayId,secret,playerReadTimeoutMillis=500).track().also {it.listen(0,0,"127.0.0.1");it.applyPolicy(policy())}
        val agent=agentFor(hub,game);connectedAgent(hub,agent)
        player(hub).use {p->
            // TCP can split handshake and payload into separate echo responses. Drain all pending bytes
            // until the remote EOF; a leaked handed-off socket fails with the bounded socket timeout.
            val received=readUntil(p,"hello")+p.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(received.startsWith("echo:"));assertTrue(received.contains("hello"))
            await("timeout cleans stream"){hub.streamCount()==0 && agent.streamCount()==0}
        }
    }
    @Test fun `tampered ciphertext frames are rejected by the secure channel`() {
        val server=ServerSocket(0).track();val a=Socket("127.0.0.1",server.localPort).track();val b=server.accept().track()
        val k1=DirectRelayCrypto.newSecret();val k2=DirectRelayCrypto.newSecret()
        val sender=SecureChannel(a,k1,k2);val receiver=SecureChannel(b,k2,k1);b.soTimeout=2000
        sender.send(SecureChannel.DATA,7,"ok".toByteArray())
        assertEquals("ok",String(receiver.receive()!!.payload))
        a.getOutputStream().write(java.nio.ByteBuffer.allocate(44).putInt(40).put(ByteArray(40) {1}).array());a.getOutputStream().flush()
        assertFailsWith<javax.crypto.AEADBadTagException> {receiver.receive()}
    }

    @Test fun `a duplicated frame cannot be replayed because nonces are counters`() {
        val k1=DirectRelayCrypto.newSecret();val k2=DirectRelayCrypto.newSecret()
        val capture=ServerSocket(0).track();val a=Socket("127.0.0.1",capture.localPort).track();val tap=capture.accept().track()
        SecureChannel(a,k1,k2).send(SecureChannel.DATA,1,"once".toByteArray())
        val wire=tap.getInputStream().readNBytes(4+5+4+16)
        val sink=ServerSocket(0).track();val feeder=Socket("127.0.0.1",sink.localPort).track();val inbound=sink.accept().track();inbound.soTimeout=1000
        feeder.getOutputStream().write(wire+wire);feeder.getOutputStream().flush()
        val receiver=SecureChannel(inbound,k2,k1)
        assertEquals("once",String(receiver.receive()!!.payload))
        assertFailsWith<javax.crypto.AEADBadTagException> {receiver.receive()}
    }
}
