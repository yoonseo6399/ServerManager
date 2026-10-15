package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.*
import java.net.*
import java.util.*
import java.util.concurrent.*
import java.util.jar.*
import kotlin.io.path.*
import kotlin.test.*

class RunnerHostIntegrationTest {
    private val mapper=jacksonObjectMapper()
    private fun await(label:String,condition:()->Boolean){val end=System.currentTimeMillis()+20000;while(!condition()){assertTrue(System.currentTimeMillis()<end,label);Thread.sleep(50)}}
    @Test fun `packaged runner controller update preserves host fake game console and direct relay socket`() {
        val jar=Path.of("build/libs/server-manager-${Path.of("VERSION").readText().trim()}.jar").toAbsolutePath()
        assertTrue(jar.isRegularFile(),"test task must build bootJar first")
        val home=Files.createTempDirectory("runner-host-flow");val game=home.resolve("game").createDirectories()
        val javaBin=Path.of(System.getProperty("java.home"),"bin","java").toString()
        // Flatten only the packaged app classes for a normal JVM test driver. The resident service runs the real bootJar.
        val flat=home.resolve("app-classes.jar")
        JarOutputStream(flat.outputStream()).use {out->JarFile(jar.toFile()).use {input->input.entries().asSequence().filter {it.name.startsWith("BOOT-INF/classes/") && !it.isDirectory}.forEach {entry->out.putNextEntry(JarEntry(entry.name.removePrefix("BOOT-INF/classes/")));input.getInputStream(entry).use {it.copyTo(out)};out.closeEntry()}}}
        val agentId=UUID.randomUUID().toString();val relayId=UUID.randomUUID().toString();val id=UUID.randomUUID().toString()
        val folder=home.resolve("instances/$id").createDirectories();folder.resolve("server").createDirectories()
        val echo=ServerSocket(0);val peers=CopyOnWriteArrayList<Socket>()
        Thread.ofVirtual().start {while(!echo.isClosed){val s=runCatching {echo.accept()}.getOrNull() ?: break;peers.add(s);Thread.ofVirtual().start {runCatching {s.getInputStream().copyTo(s.getOutputStream())}}}}
        val instance=Instance(id,"Fake",loader="Fabric",loaderVersion="test",port=echo.localPort,memoryMb=512,javaPath=javaBin,runnerId=agentId,fileSyncEnabled=false)
        folder.resolve("instance.json").writeText(mapper.writeValueAsString(instance))
        assertTrue(GameHostMain.spawnDetached(home,javaBin,jar.toString(),false))
        val seed=GameHostClient.connect(home,"seed",retryMillis=10000)
        val process=seed.start(id,listOf(javaBin,"-cp",System.getProperty("java.class.path"),"local.servermanager.FakeGameMain",game.toString(),"chatter"),game,System.getenv(),echo.localPort).process
        val pid=process.pid();folder.resolve("dirty").writeText("$pid|0");seed.close();Thread.sleep(300)
        val secret=DirectRelayCrypto.newSecret();val hub=DirectRelayHub(relayId,secret);hub.listen(0,0,"127.0.0.1");hub.applyPolicy(RelayPolicy(1,mapOf(agentId to 1L),listOf(RelayRoute("play.example.com",agentId,id))))
        val children=CopyOnWriteArrayList<Process>();val messages=CopyOnWriteArrayList<RunnerMessage>();val lines=CopyOnWriteArrayList<String>()
        fun controller():Pair<Process,java.io.PrintWriter> {
            val p=ProcessBuilder(javaBin,"-cp",flat.toString()+java.io.File.pathSeparator+System.getProperty("java.class.path"),"local.servermanager.RunnerHostControllerFixture",home.toString(),agentId).redirectErrorStream(true).start();children.add(p)
            val writer=p.outputStream.writer().let {java.io.PrintWriter(it,true)}
            Thread.ofVirtual().start {p.inputStream.bufferedReader().forEachLine {line->lines.add(line);if(line.startsWith("MSG "))runCatching {mapper.readValue<RunnerMessage>(line.removePrefix("MSG "))}.onSuccess {m->
                messages.add(m)
                if(m.type=="drp-ticket-request") {val t=DirectRelayCrypto.issue(secret,relayId,agentId,1,120000);writer.println(mapper.writeValueAsString(RunnerMessage("drp-ticket",token=m.token,state="OK",detail=mapper.writeValueAsString(mapOf("body" to t.body,"key" to Base64.getUrlEncoder().withoutPadding().encodeToString(t.key))))))}
            }}}
            await("controller ready: ${lines.takeLast(5)}"){lines.any {it=="CONTROLLER_READY"} || !p.isAlive};check(p.isAlive){lines.joinToString("\n")}
            writer.println(mapper.writeValueAsString(RunnerMessage("session",token="isolated")))
            return p to writer
        }
        try {
            val (first,input)=controller()
            await("host mode SERVICE"){messages.any {it.type=="update-detail" && it.detail?.contains("SERVICE")==true}}
            input.println(mapper.writeValueAsString(RunnerMessage("drp-agent-config",detail=mapper.writeValueAsString(AgentRelayConfig(listOf(AgentRelayTarget(relayId,"127.0.0.1",hub.securePort!!)),setOf(id))))))
            await("agent connected"){hub.status().agents.isNotEmpty()}
            Socket("127.0.0.1",hub.gamePort!!).use {player->
                player.soTimeout=5000
                val handshake=GameNetworkTest().handshake("play.example.com");player.getOutputStream().write(handshake);assertEquals(handshake.size,player.getInputStream().readNBytes(handshake.size).size)
                fun echo(text:String){player.getOutputStream().write(text.toByteArray());assertEquals(text,String(player.getInputStream().readNBytes(text.length)))}
                echo("before")
                home.resolve("updates").createDirectories();home.resolve("updates/pending.txt").writeText("99.0.0")
                home.resolve("updates/99.0.0").createDirectories();home.resolve("updates/99.0.0/update.properties").writeText("version=99.0.0\ncontroller.minHost=1\ncontroller.maxHost=1\n")
                input.println("UPDATE");assertTrue(first.waitFor(15,TimeUnit.SECONDS),lines.takeLast(20).joinToString("\n"));assertEquals(0,first.exitValue());assertTrue(lines.contains("UPDATE true"))
                val consoleBefore=ConsolePosition.load(folder.resolve("console.seq"));assertTrue(consoleBefore>0)
                echo("controller-exited");assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive)
                lines.clear();messages.clear();val (second,next)=controller()
                await("running reattached"){messages.any {it.type=="state" && it.state=="RUNNING" && it.pid==pid}}
                await("console replay"){messages.any {it.type=="log"}}
                assertTrue(messages.any {it.type=="update-detail" && it.detail?.contains("FAILED")==true},"same-version fixture controller reports staged-version rollback, not successful binary update")
                echo("replacement");assertEquals(1,(game/"launches.txt").readLines().size)
                next.println("EXIT");assertTrue(second.waitFor(10,TimeUnit.SECONDS));assertTrue(folder.resolve("dirty").exists());assertTrue(ConsolePosition.load(folder.resolve("console.seq"))>=consoleBefore)
                println("RUNNER-HOST-PROOF fakeGamePid=$pid hostPid=${GameHostClient.readEndpoint(home)!!.pid} controllers=${children.map {it.pid()}} relayPreserved=true consoleReattached=true")
            }
        } finally {
            children.forEach {if(it.isAlive){it.outputStream.writer().let {w->w.write("EXIT\n");w.flush()};if(!it.waitFor(5,TimeUnit.SECONDS))it.destroyForcibly()}}
            hub.close();echo.close();peers.forEach {it.close()}
            runCatching {GameHostClient.connect(home,"cleanup",retryMillis=3000).use {it.stop(id,pid)}}
            process.waitFor(10,TimeUnit.SECONDS);HostLifecycle.replaceIfIdle(home)
            home.resolve("host-launch-label.txt").takeIf {it.exists()}?.readText()?.let {ProcessBuilder("/bin/launchctl","remove",it).start().waitFor()}
        }
    }
    @Test fun `incompatible live relay host fails runner initialization before health callback`() {
        val jar=Path.of("build/libs/server-manager-${Path.of("VERSION").readText().trim()}.jar").toAbsolutePath()
        assertTrue(jar.isRegularFile(),"test task must build bootJar first")
        val home=Files.createTempDirectory("runner-health-failure");val flat=home.resolve("app-classes.jar")
        JarOutputStream(flat.outputStream()).use {out->JarFile(jar.toFile()).use {input->input.entries().asSequence().filter {it.name.startsWith("BOOT-INF/classes/") && !it.isDirectory}.forEach {entry->out.putNextEntry(JarEntry(entry.name.removePrefix("BOOT-INF/classes/")));input.getInputStream(entry).use {it.copyTo(out)};out.closeEntry()}}}
        GameHostService(home,"future",protocol=2,minCompatible=2).use {service->
            assertTrue(service.start())
            val javaBin=Path.of(System.getProperty("java.home"),"bin","java").toString()
            val p=ProcessBuilder(javaBin,"-cp",flat.toString()+java.io.File.pathSeparator+System.getProperty("java.class.path"),"local.servermanager.RunnerHostControllerFixture",home.toString(),UUID.randomUUID().toString()).redirectErrorStream(true).start()
            assertTrue(p.waitFor(15,TimeUnit.SECONDS));val output=p.inputStream.bufferedReader().readText()
            assertNotEquals(0,p.exitValue());assertFalse(output.contains("HOST_INITIALIZED"));assertFalse(output.contains("CONTROLLER_READY"))
            GameHostClient.connect(home,"still-running",protocol=2,minHost=2).close()
        }
    }

}
