package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.*
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

/** Tiny game JVM: owns game TCP, Query UDP and voice UDP, just as independent Minecraft instances do. */
object MultiServerGameMain {
    @JvmStatic fun main(args:Array<String>) {
        val dir=Path.of(args[0]);val id=args[1]
        fun properties(path:Path)=Properties().apply {path.inputStream().use(::load)}
        val p=properties(dir.resolve("server.properties"));val v=properties(dir.resolve("config/voicechat/voicechat-server.properties"))
        ServerSocket(p.getProperty("server-port").toInt(),16,InetAddress.getLoopbackAddress()).use {tcp->
            DatagramSocket(InetSocketAddress("127.0.0.1",p.getProperty("query.port").toInt())).use {
                DatagramSocket(InetSocketAddress("127.0.0.1",v.getProperty("port").toInt())).use {voice->
                    Thread.ofVirtual().start {while(!tcp.isClosed) {val s=runCatching {tcp.accept()}.getOrNull() ?: break;Thread.ofVirtual().start {s.use {runCatching {s.getInputStream().copyTo(s.getOutputStream())}}}}}
                    Thread.ofVirtual().start {while(!voice.isClosed)try {val packet=DatagramPacket(ByteArray(8192),8192);voice.receive(packet);val bytes="$id:".toByteArray()+packet.data.copyOf(packet.length);voice.send(DatagramPacket(bytes,bytes.size,packet.socketAddress))} catch(_:Exception){break}}
                    println("Done (0.1s)! For help, type help")
                    System.`in`.bufferedReader().useLines {lines->for(line in lines){if(line=="stop")break}}
                }
            }
        }
    }
}

class MultiServerHostTest {
    @TempDir lateinit var root:Path
    private val mapper=jacksonObjectMapper()
    private fun await(check:()->Boolean) {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!check()){assertTrue(System.nanoTime()<end,"startup timed out");Thread.sleep(10)}}
    @Test fun `one resident host runs two game JVMs and isolated voice routes concurrently and stopping one leaves the other alive`() {
        val reserved=mutableSetOf<Int>()
        fun udpPort():Int {while(true){val p=DatagramSocket(0).use {it.localPort};if(reserved.add(p))return p}}
        fun gamePort():Int {while(true){val p=ServerSocket(0).use {it.localPort};if(reserved.add(p))return p}}
        val games=listOf("a","b");val ports=games.associateWith {gamePort()};val voices=games.associateWith {udpPort()};val publics=games.associateWith {udpPort()}
        val queryPorts=mutableSetOf<Int>();var cloned:Int?=null
        val dirs=games.associateWith {id->root.resolve(id).createDirectories().also {dir->
            val p=Properties().apply {setProperty("server-port",ports.getValue(id).toString());cloned?.let {setProperty("query.port",it.toString())}}
            val query=QueryEndpoint.configure(p,ports.getValue(id),reserved+queryPorts);queryPorts.add(query);cloned=query
            dir.resolve("server.properties").outputStream().use {p.store(it,"fixture")}
            dir.resolve("config/voicechat").createDirectories().resolve("voicechat-server.properties").writeText("# cloned mod defaults\nport=24454\n")
        }}
        assertEquals(2,queryPorts.size)
        val home=root.resolve("host");val relay=UUID.randomUUID().toString();val agent=UUID.randomUUID().toString();val secret=DirectRelayCrypto.newSecret()
        DirectRelayHub(relay,secret).use {hub->
            assertTrue(hub.listen(0,0,"127.0.0.1"));hub.applyPolicy(RelayPolicy(1,mapOf(agent to 1L),games.map {RelayRoute("$it.example.com",agent,it)}))
            hub.voiceChat(games.map {VoiceChatRelayConfig(it,publics.getValue(it),voices.getValue(it))},"127.0.0.1")
            GameHostService(home,"multi-test").use {host->assertTrue(host.start())
                GameHostClient.connect(home,"multi-controller").use {client->
                    client.onRelay={body->val message=mapper.readValue<RunnerMessage>(body);if(message.type=="drp-ticket-request") {
                        val ticket=DirectRelayCrypto.issue(secret,relay,agent,1L,120000)
                        client.relayForward(mapper.writeValueAsString(RunnerMessage("drp-ticket",token=message.token,state="OK",detail=mapper.writeValueAsString(mapOf("body" to ticket.body,"key" to Base64.getUrlEncoder().withoutPadding().encodeToString(ticket.key))))))
                    }}
                    val config=AgentRelayConfig(listOf(AgentRelayTarget(relay,"127.0.0.1",hub.securePort!!)),games.toSet(),voicechat=games.map {VoiceChatTarget(it,voices.getValue(it),"voice.example.com:${publics.getValue(it)}")})
                    assertTrue(client.relayForward(mapper.writeValueAsString(RunnerMessage("drp-agent-config",detail=mapper.writeValueAsString(config)))))
                    assertEquals(voices.values.toSet(),client.reservedUdpPorts(),"planned voice reservations are visible before any game binds")
                    val processes=mutableMapOf<String,HostedProcess>()
                    try {
                        games.forEach {id->processes[id]=client.start(id,listOf(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"local.servermanager.MultiServerGameMain",dirs.getValue(id).toString(),id),dirs.getValue(id),emptyMap(),ports.getValue(id)).process}
                        await {client.status().count {it.alive && it.ready}==2 && hub.status().agents.isNotEmpty()}
                        val players=games.associateWith {id->Socket("127.0.0.1",hub.gamePort!!).apply {soTimeout=3000;val hello=GameNetworkTest().handshake("$id.example.com");getOutputStream().write(hello);assertContentEquals(hello,getInputStream().readNBytes(hello.size))}}
                        try {
                            fun voice(id:String,n:Int) {DatagramSocket().use {s->s.soTimeout=3000;val text="packet-$n";val b=text.toByteArray();s.send(DatagramPacket(b,b.size,InetSocketAddress("127.0.0.1",publics.getValue(id))));val reply=DatagramPacket(ByteArray(128),128);s.receive(reply);assertEquals("$id:$text",String(reply.data,0,reply.length))}}
                            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor().use {pool->games.map {id->pool.submit {repeat(20){voice(id,it)}}}.forEach {it.get(15,TimeUnit.SECONDS)}}
                            val survivor=processes.getValue("b").pid()
                            client.stop("a",processes.getValue("a").pid());assertTrue(processes.getValue("a").waitFor(10,TimeUnit.SECONDS));assertEquals(0,processes.getValue("a").exitValue())
                            assertTrue(processes.getValue("b").isAlive);assertEquals(survivor,client.status().first {it.id=="b"}.pid);voice("b",21)
                            val b=players.getValue("b");b.getOutputStream().write("still-live".toByteArray());assertEquals("still-live",String(b.getInputStream().readNBytes(10)))
                            println("MULTI-SERVER-PROOF gamePorts=$ports queryPorts=$queryPorts localVoice=$voices publicVoice=$publics concurrentVoicePackets=40 stoppedA=true survivorPid=$survivor")
                        } finally {players.values.forEach {it.close()}}
                    } finally {processes.forEach {(id,p)->if(p.isAlive){runCatching {client.stop(id,p.pid())};if(!p.waitFor(5,TimeUnit.SECONDS))p.destroyForcibly()}}}
                }
            }
        }
    }
}
