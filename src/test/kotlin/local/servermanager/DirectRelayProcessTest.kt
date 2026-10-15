package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

/**
 * Real OS processes: a manager JVM, a relay-runner JVM and a game-runner JVM (with a fake Minecraft inside).
 * They share only a JSON-lines control socket to the manager, whose byte counters prove game data never crosses it.
 * This is a single-machine multi-process test, not a multi-computer or internet test.
 */
class DirectRelayProcessTest {
    private val procs=mutableListOf<Proc>()
    @AfterEach fun cleanup(){procs.forEach {it.process.destroyForcibly()}}
    private class Proc(val name:String,val process:Process) {
        val lines=LinkedBlockingQueue<String>()
        val writer=PrintWriter(process.outputStream,true)
        init {
            Thread {BufferedReader(InputStreamReader(process.inputStream)).forEachLine {lines.add(it)}}.also {it.isDaemon=true}.start()
            Thread {BufferedReader(InputStreamReader(process.errorStream)).forEachLine {System.err.println("[$name] $it")}}.also {it.isDaemon=true}.start()
        }
        fun expect(prefix:String,seconds:Long=30):String {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds)
            while(System.nanoTime()<end){val l=lines.poll(200,TimeUnit.MILLISECONDS) ?: continue;if(l.startsWith(prefix))return l}
            fail("$name: no '$prefix' output in ${seconds}s")
        }
        fun ask(command:String,reply:String)=run {lines.clear();writer.println(command);expect(reply)}
    }
    private fun spawn(name:String,vararg args:String):Proc {
        val argFile=Files.createTempFile("drp-cp",".args")
        argFile.writeText("-cp\n\""+System.getProperty("java.class.path").replace("\\","\\\\")+"\"\n")
        val java=Path.of(System.getProperty("java.home"),"bin","java").toString()
        val p=ProcessBuilder(listOf(java,"@$argFile","local.servermanager.DirectRelayProcessMain")+args).start()
        return Proc(name,p).also {procs.add(it)}
    }
    private fun freePort()=ServerSocket(0).use {it.localPort}
    private val mapper=jacksonObjectMapper()
    private fun varint(out:ByteArrayOutputStream,value:Int){var v=value;while(true){if(v and 0x7f.inv()==0){out.write(v);return};out.write(v and 0x7f or 0x80);v=v ushr 7}}
    private fun handshake(host:String):ByteArray {
        val body=ByteArrayOutputStream();varint(body,0);varint(body,767);val n=host.toByteArray();varint(body,n.size);body.write(n);body.write(0x63);body.write(0xdd);varint(body,2)
        val p=ByteArrayOutputStream();varint(p,body.size());body.writeTo(p);return p.toByteArray()
    }
    private fun player(port:Int,host:String):Socket {
        val s=Socket();s.connect(InetSocketAddress("127.0.0.1",port),3000);s.soTimeout=8000
        val h=handshake(host);s.getOutputStream().write(h);s.getOutputStream().flush();s.getInputStream().readNBytes(h.size);return s
    }
    private fun roundTrip(s:Socket,bytes:ByteArray):Boolean {
        s.getOutputStream().write(bytes);s.getOutputStream().flush()
        val got=ByteArray(bytes.size);var n=0;while(n<got.size){val r=s.getInputStream().read(got,n,got.size-n);if(r<0)return false;n+=r};return got.contentEquals(bytes)
    }
    private fun status(m:Proc)=mapper.readTree(m.ask("STATUS","STATUS ").removePrefix("STATUS "))

    @Test fun `separate manager relay and game runner processes keep game data off the manager and survive manager restart and local revocation`() {
        val host="play.example.com";val relayId=UUID.randomUUID().toString();val agentId=UUID.randomUUID().toString()
        val control=freePort();val gamePort=freePort();val securePort=freePort()
        val root=Files.createTempDirectory("drp-proc");val relayHome=root.resolve("relay").createDirectories();val agentHome=root.resolve("agent").createDirectories()
        val store=root.resolve("manager-store").toString();val echoFile=root.resolve("echo-port")
        RelayConsentStore(relayHome){null}.set(true,"test") // what the installer checkbox would write
        var manager=spawn("manager","manager",control.toString(),gamePort.toString(),securePort.toString(),store,relayId,agentId,"inst-1",host);manager.expect("READY")
        val relay=spawn("relay","relay",relayHome.toString(),control.toString(),relayId);relay.expect("READY")
        val agent=spawn("agent","agent",agentHome.toString(),control.toString(),agentId,echoFile.toString());agent.expect("READY")
        manager.ask("ENABLE","ENABLED")
        val end=System.currentTimeMillis()+30_000
        while(!status(manager).path("ready").asBoolean()){assertTrue(System.currentTimeMillis()<end,"relay never became ready: ${status(manager)}");Thread.sleep(300)}
        assertEquals("ACTIVE",status(manager).path("phase").asText())

        val before=status(manager);val controlBefore=before.path("in").asLong()+before.path("out").asLong()
        val live=player(gamePort,host)
        val chunk=ByteArray(64*1024) {(it*31).toByte()}
        repeat(200) {assertTrue(roundTrip(live,chunk),"chunk $it corrupted or dropped")} // 12.8 MB each way, only through relay -> agent
        val after=status(manager);val carried=after.path("in").asLong()+after.path("out").asLong()-controlBefore
        println("PROCESS-PROOF: 25.6 MB of game traffic; manager control bytes during transfer=$carried")
        assertTrue(carried<20_000,"manager control channel carried $carried bytes during a 25.6 MB transfer")

        // Manager process dies (restart window): approved traffic continues, nothing new can be authorised.
        manager.process.destroyForcibly();manager.process.waitFor()
        assertTrue(roundTrip(live,chunk),"existing connection must survive a manager outage")
        manager=spawn("manager2","manager",control.toString(),gamePort.toString(),securePort.toString(),store,relayId,agentId,"inst-1",host);manager.expect("READY")
        manager.ask("ENABLE","ENABLED") // settings already persisted; re-applying the same config after recovery is allowed
        val end2=System.currentTimeMillis()+30_000
        while(!status(manager).path("ready").asBoolean()){assertTrue(System.currentTimeMillis()<end2,"no recovery after manager restart: ${status(manager)}");Thread.sleep(300)}
        assertTrue(roundTrip(live,chunk),"existing connection still alive after recovery")
        player(gamePort,host).use {assertTrue(roundTrip(it,chunk),"new connection after manager recovery")}

        // Local owner withdraws consent: effective without the manager's cooperation, within one tick.
        RelayConsentStore(relayHome){null}.set(false,"local-revoke")
        val end3=System.currentTimeMillis()+10_000
        while(runCatching {Socket("127.0.0.1",gamePort).use {true}}.getOrDefault(false)){assertTrue(System.currentTimeMillis()<end3,"relay still listening after local revoke");Thread.sleep(200)}
        assertTrue(runCatching {live.getOutputStream().write(1);live.getInputStream().read()}.getOrDefault(-1)<0,"live stream must end after local revocation")
        live.close()
    }
}
