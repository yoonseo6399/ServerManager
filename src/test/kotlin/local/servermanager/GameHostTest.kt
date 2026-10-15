package local.servermanager

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

/** Stands in for Minecraft: reports ready, echoes stdin, exits cleanly on "stop", and records every launch in launches.txt. */
object FakeGameMain {
    @JvmStatic fun main(args:Array<String>) {
        val dir=Path.of(args[0])
        java.nio.file.Files.writeString(dir.resolve("launches.txt"),ProcessHandle.current().pid().toString()+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND)
        println("Done (1.0s)! For help, type \"help\"")
        val chatter=args.getOrNull(1)=="chatter"
        if(chatter)Thread {var n=0;while(true){Thread.sleep(150);println("tick ${n++}")}}.also {it.isDaemon=true}.start()
        val input=System.`in`.bufferedReader()
        while(true) {
            val line=input.readLine() ?: break
            if(line=="stop"){println("Stopping server");break}
            println("echo:$line")
        }
    }
}

class GameHostTest {
    private val closeables=mutableListOf<AutoCloseable>()
    private val processes=mutableListOf<Process>()
    @AfterEach fun cleanup() {closeables.reversed().forEach {runCatching {it.close()}};processes.forEach {runCatching {it.destroyForcibly()}}}
    private fun <T:AutoCloseable> T.track():T{closeables.add(this);return this}
    private fun javaBin()=Path.of(System.getProperty("java.home"),"bin","java").toString()
    private fun gameCommand(dir:Path,mode:String="")=listOf(javaBin(),"-cp",System.getProperty("java.class.path"),"local.servermanager.FakeGameMain",dir.toString())+(if(mode.isEmpty())emptyList() else listOf(mode))
    private fun host(home:Path=Files.createTempDirectory("gh-home"),version:String="0.5.2",protocol:Int=1,minCompatible:Int=1,leaseMillis:Long=15000,ping:Long=5000):Pair<GameHostService,Path> {
        val s=GameHostService(home,version,pingMillis=ping,leaseMillis=leaseMillis,protocol=protocol,minCompatible=minCompatible).track();assertTrue(s.start());return s to home
    }
    private fun await(message:String,timeout:Long=10000,condition:()->Boolean){val end=System.currentTimeMillis()+timeout;while(!condition()){assertTrue(System.currentTimeMillis()<end,message);Thread.sleep(25)}}
    private fun launches(dir:Path)=if((dir/"launches.txt").exists())(dir/"launches.txt").readLines().filter {it.isNotBlank()} else emptyList()
    private class Console {val lines=CopyOnWriteArrayList<String>();val seqs=CopyOnWriteArrayList<Long>()
        fun hook(client:GameHostClient){client.onConsole={_,seq,line->seqs.add(seq);lines.add(line)}}}

    @Test fun `host owns the game and the controller can start talk and stop it`() {
        val (_,home)=host();val game=Files.createTempDirectory("gh-game");val c=GameHostClient.connect(home,"controller-1").track();val console=Console().also {it.hook(c)}
        val started=c.start("srv",gameCommand(game),game,System.getenv())
        assertFalse(started.duplicate);assertTrue(started.process.pid()>0 && started.process.isAlive)
        await("ready line"){console.lines.any {it.startsWith("Done (")}}
        c.stdin("srv",started.process.pid(),"say hi");await("echo"){console.lines.contains("echo:say hi")}
        started.process.outputStream.write("list\n".toByteArray());started.process.outputStream.flush();await("echo via Process stream"){console.lines.contains("echo:list")}
        c.stop("srv",started.process.pid());assertTrue(started.process.waitFor(10,TimeUnit.SECONDS));assertEquals(0,started.process.exitValue())
    }

    @Test fun `controller replacement keeps the game pid and console without loss or duplication`() {
        val (service,home)=host();val game=Files.createTempDirectory("gh-game")
        val a=GameHostClient.connect(home,"controller-old");val consoleA=Console().also {it.hook(a)}
        val pid=a.start("srv",gameCommand(game,"chatter"),game,System.getenv()).process.pid()
        await("some output"){consoleA.seqs.size>=4}
        val lastSeenByOld=consoleA.seqs.last()
        a.close() // controller layer is being updated
        await("host notices detach"){!service.controllerAttached()}
        Thread.sleep(700) // the game keeps talking while nobody is attached
        val b=GameHostClient.connect(home,"controller-new").track();val consoleB=Console().also {it.hook(b)}
        assertEquals(listOf("srv"),b.initial.filter {it.alive && it.managed}.map {it.id})
        val adopted=b.adopt("srv",lastSeenByOld) ?: fail("running game must be adoptable")
        assertEquals(pid,adopted.pid(),"same game process")
        await("replay and live output"){consoleB.seqs.size>=6}
        assertEquals(consoleB.seqs.sorted(),consoleB.seqs.toList(),"console order preserved")
        assertEquals(consoleB.seqs.size,consoleB.seqs.toSet().size,"no duplicated sequence numbers")
        assertEquals(lastSeenByOld+1,consoleB.seqs.first(),"nothing missed between old and new controller")
        assertEquals(1,launches(game).size,"the game must not have been started a second time")
        b.stdin("srv",pid,"after-update");await("command after update"){consoleB.lines.contains("echo:after-update")}
        b.stop("srv",pid);assertTrue(adopted.waitFor(10,TimeUnit.SECONDS))
    }

    @Test fun `controller crash in a separate process leaves the game alive and a new controller adopts it`() {
        val (_,home)=host();val game=Files.createTempDirectory("gh-game")
        val main=ProcessBuilder(javaBin(),"-cp",System.getProperty("java.class.path"),"local.servermanager.GameHostControllerMain",home.toString(),game.toString())
            .redirectErrorStream(true).start().also {processes.add(it)}
        val out=BufferedReader(InputStreamReader(main.inputStream));var pid=0L
        val end=System.currentTimeMillis()+30000
        while(pid==0L){assertTrue(System.currentTimeMillis()<end,"controller JVM never reported the game pid");val l=out.readLine() ?: fail("controller exited");if(l.startsWith("PID "))pid=l.removePrefix("PID ").trim().toLong()}
        main.destroyForcibly().waitFor() // crash, not a clean detach
        assertTrue(ProcessHandle.of(pid).map {it.isAlive}.orElse(false),"game must survive its controller")
        val b=GameHostClient.connect(home,"controller-2",retryMillis=20000).track()
        val adopted=b.adopt("srv",0) ?: fail("adopt after crash");assertEquals(pid,adopted.pid())
        val console=Console().also {it.hook(b)};b.stdin("srv",pid,"hello-after-crash");await("echo"){console.lines.contains("echo:hello-after-crash")}
        assertEquals(1,launches(game).size)
        b.stop("srv",pid)
    }

    @Test fun `duplicate start does not launch a second process`() {
        val (_,home)=host();val game=Files.createTempDirectory("gh-game");val c=GameHostClient.connect(home,"c").track()
        val first=c.start("srv",gameCommand(game),game,System.getenv());val second=c.start("srv",gameCommand(game),game,System.getenv())
        assertTrue(second.duplicate);assertEquals(first.process.pid(),second.process.pid())
        Thread.sleep(500);assertEquals(1,launches(game).size)
        c.stop("srv",first.process.pid())
    }

    @Test fun `repeated command ids are executed once and commands for another pid are refused`() {
        val (_,home)=host();val game=Files.createTempDirectory("gh-game");val c=GameHostClient.connect(home,"c").track();val console=Console().also {it.hook(c)}
        val p=c.start("srv",gameCommand(game),game,System.getenv()).process
        c.stdinWithId("srv",p.pid(),"cmd-1","once");c.stdinWithId("srv",p.pid(),"cmd-1","once")
        await("one echo"){console.lines.contains("echo:once")};Thread.sleep(400)
        assertEquals(1,console.lines.count {it=="echo:once"})
        val wrong=assertFailsWith<GameHostRejected> {c.stdin("srv",p.pid()+1,"nope")};assertEquals("WRONG_PROCESS",wrong.reason)
        assertEquals("WRONG_PROCESS",assertFailsWith<GameHostRejected> {c.kill("srv",p.pid()+1).getOrThrow()}.reason)
        assertEquals("BAD_COMMAND",assertFailsWith<GameHostRejected> {c.stdin("srv",p.pid(),"a\nb")}.reason)
        c.stop("srv",p.pid())
    }

    @Test fun `only one controller at a time and a hung controller loses its lease`() {
        val (_,home)=host(leaseMillis=1200,ping=300)
        val first=GameHostClient.connect(home,"one").track()
        assertEquals("CONTROLLER_ACTIVE",assertFailsWith<GameHostRejected> {GameHostClient.connect(home,"two")}.reason)
        first.status() // active controller stays valid
        // A controller that stops answering pings (hung) is evicted; build one by hand that never reads.
        first.close();await("lease released"){runCatching {GameHostClient.connect(home,"three").also {it.close()}}.isSuccess}
        val hung=Socket("127.0.0.1",GameHostClient.readEndpoint(home)!!.port);val reader=BufferedReader(InputStreamReader(hung.getInputStream()))
        val challenge=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(reader.readLine())
        val proof=java.util.Base64.getEncoder().encodeToString(GameHostProtocol.hmac(java.util.Base64.getUrlDecoder().decode(GameHostClient.readEndpoint(home)!!.secret),"gh-hello".toByteArray(),java.util.Base64.getDecoder().decode(challenge.path("nonce").asText())))
        PrintWriter(hung.getOutputStream(),true).println("""{"t":"hello","protocol":1,"minHost":1,"controller":"hung","proof":"$proof"}""")
        assertEquals("welcome",com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(reader.readLine()).path("t").asText())
        await("hung controller replaced",timeout=8000){runCatching {GameHostClient.connect(home,"four").also {it.close()}}.isSuccess}
        hung.close()
    }

    @Test fun `wrong secret and incompatible protocol are refused before any command`() {
        val (_,home)=host(protocol=2,minCompatible=2)
        assertEquals("PROTOCOL_INCOMPATIBLE",assertFailsWith<GameHostRejected> {GameHostClient.connect(home,"old-controller",protocol=1,minHost=1)}.reason)
        // A host that is newer than the controller can support is also refused: controller minHost=3 > host 2.
        assertEquals("PROTOCOL_INCOMPATIBLE",assertFailsWith<GameHostRejected> {GameHostClient.connect(home,"too-new",protocol=2,minHost=3)}.reason)
        GameHostClient.connect(home,"ok",protocol=2,minHost=1).close()
        val endpoint=GameHostClient.readEndpoint(home)!!
        val bad=Socket("127.0.0.1",endpoint.port);val reader=BufferedReader(InputStreamReader(bad.getInputStream()));reader.readLine()
        PrintWriter(bad.getOutputStream(),true).println("""{"t":"hello","protocol":2,"minHost":1,"controller":"x","proof":"AAAA"}""")
        val reply=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(reader.readLine());assertEquals("AUTH_FAILED",reply.path("reason").asText());bad.close()
        val other=Files.createTempDirectory("gh-other");assertNull(GameHostClient.readEndpoint(other))
    }

    @Test fun `a second host process on the same home is refused`() {
        val (_,home)=host()
        val second=GameHostService(home,"0.5.2");assertFalse(second.start());second.close()
    }

    @Test fun `exit must be acknowledged before the same server can start again`() {
        val (_,home)=host();val game=Files.createTempDirectory("gh-game");val c=GameHostClient.connect(home,"c").track()
        val p=c.start("srv",gameCommand(game),game,System.getenv()).process;val pid=p.pid()
        c.stop("srv",pid);assertTrue(p.waitFor(10,TimeUnit.SECONDS))
        assertEquals("EXIT_UNACKED",assertFailsWith<GameHostRejected> {c.start("srv",gameCommand(game),game,System.getenv())}.reason)
        assertEquals(1,launches(game).size)
        c.ackExit("srv",pid)
        val again=c.start("srv",gameCommand(game),game,System.getenv());assertFalse(again.duplicate);assertNotEquals(pid,again.process.pid())
        c.stop("srv",again.process.pid())
    }

    @Test fun `host restart reports a surviving game as not controllable and never duplicates it`() {
        val home=Files.createTempDirectory("gh-home");val game=Files.createTempDirectory("gh-game")
        val first=GameHostService(home,"0.5.2").also {assertTrue(it.start())}
        val c=GameHostClient.connect(home,"c");val pid=c.start("srv",gameCommand(game,"chatter"),game,System.getenv()).process.pid();c.close();first.close()
        await("game launched"){launches(game).size==1}
        val second=GameHostService(home,"0.5.2").track();assertTrue(second.start())
        val status=second.statuses().single();assertEquals(pid,status.pid);assertTrue(status.alive);assertFalse(status.managed);assertEquals("ORPHAN_ALIVE_NOT_CONTROLLABLE",status.reason)
        val c2=GameHostClient.connect(home,"c2").track()
        val dup=c2.start("srv",gameCommand(game),game,System.getenv());assertTrue(dup.duplicate)
        assertEquals("NOT_CONTROLLABLE",assertFailsWith<GameHostRejected> {c2.stdin("srv",pid,"x")}.reason)
        assertEquals(1,launches(game).size)
        ProcessHandle.of(pid).ifPresent {it.destroyForcibly()}
    }
}
