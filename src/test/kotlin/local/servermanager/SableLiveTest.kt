package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.net.*
import java.net.http.*
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.*
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="SM_LIVE_SABLE",matches="1")
class SableLiveTest {
    @TempDir lateinit var temp:Path
    @Test fun `actual Sable and bridge boot with separated Query and normal save stop`() {
        val source=Path.of(System.getenv("SM_LIVE_SABLE_JAR") ?: error("Supply a legally obtained Sable2.0.6 JAR; it is never redistributed"))
        assertEquals("2.0.6",SableIdentity.version(source))
        val server=temp.resolve("server").also {it.createDirectories()}
        val port=java.net.ServerSocket(0).use {it.localPort}
        val instance=Instance("fixture","Sable",loaderVersion="21.1.249",port=port,memoryMb=1024,javaPath=Path.of(System.getProperty("java.home"),"bin/java").toString())
        val installer=LauncherInstaller(LauncherCatalog(temp.resolve("catalog")))
        installer.install(instance,server,temp.resolve("installer.jar"),{args->
            val output=temp.resolve("installer.log");val process=ProcessBuilder(args).directory(server.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            check(process.waitFor(300,TimeUnit.SECONDS)){"Installer still running; not force-killing"};assertEquals(0,process.exitValue(),output.readText().takeLast(3000));process.exitValue()
        }){}
        server.resolve("mods").createDirectories();source.copyTo(server.resolve("mods/sable.jar"))
        Path.of("sable-bridge/build/libs/isolated-sable-probe.jar").copyTo(server.resolve("mods/probe.jar"))
        val settings=Properties().apply {
            setProperty("server-ip","127.0.0.1");setProperty("server-port",port.toString());QueryEndpoint.configure(this,port)
            setProperty("level-type","minecraft:flat");setProperty("generate-structures","false");setProperty("view-distance","2");setProperty("simulation-distance","2")
        }
        server.resolve("server.properties").outputStream().use {settings.store(it,"isolated Sable integration")};server.resolve("eula.txt").writeText("eula=true\n")
        val listener=java.util.concurrent.atomic.AtomicReference<WebSocket.Listener>();val tokens=LinkedBlockingQueue<String>();val packets=LinkedBlockingQueue<ByteArray>()
        val client=mock(HttpClient::class.java);val ws=mock(WebSocket::class.java);val wsBuilder=mock(WebSocket.Builder::class.java)
        `when`(client.newWebSocketBuilder()).thenReturn(wsBuilder);`when`(wsBuilder.connectTimeout(any())).thenReturn(wsBuilder);`when`(wsBuilder.header(anyString(),anyString())).thenReturn(wsBuilder);doAnswer {call->listener.set(call.arguments[1] as WebSocket.Listener);CompletableFuture.completedFuture(ws)}.`when`(wsBuilder).buildAsync(any(),any())
        doAnswer {call->val b=(call.arguments[0] as java.nio.ByteBuffer).slice();packets.add(ByteArray(b.remaining()).also(b::get));CompletableFuture.completedFuture(ws)}.`when`(ws).sendBinary(any(),eq(true))
        SableRunner(client,"https://localhost",{emptyMap()},{if(it==12345) "fixture" to 7 else null},{if(it==7)port else null},{_,_,token->tokens.add(token);true}).use {runner->
            runner.enabled=true
            val builder=ProcessBuilder(Launchers.arguments(instance,server)).directory(server.toFile()).redirectErrorStream(true)
            assertEquals("READY",runner.prepare(instance,server,builder))
            val lines=java.util.Collections.synchronizedList(mutableListOf<String>());val ready=CountDownLatch(1)
            val process=builder.start();val reader=thread(isDaemon=true){process.inputStream.bufferedReader().useLines {it.forEach {line->lines.add(line);if(line.contains("Done (") && line.contains("For help"))ready.countDown()}}}
            try {
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);while(ready.count>0 && process.isAlive && System.nanoTime()<deadline)Thread.sleep(100)
                assertTrue(ready.count==0L,synchronized(lines){lines.takeLast(35).joinToString("\n")})
                val token=tokens.poll(5,TimeUnit.SECONDS);assertNotNull(token,"Real Sable authentication must register through the actual Mixin and local bridge")
                val activated=System.nanoTime()+3_000_000_000;while(synchronized(lines){lines.none {it.contains("SM_SABLE_PROBE_ACTIVATED")}} && System.nanoTime()<activated)Thread.sleep(10)
                assertTrue(synchronized(lines){lines.any {it.contains("SM_SABLE_PROBE_ACTIVATED")}},"Activation must be delivered only after central registration acknowledgement")
                val auth=java.nio.ByteBuffer.allocate(42).putInt(7).put(3).put(36).put(token.toByteArray()).flip()
                listener.get().onBinary(ws,auth,true)
                val ping=packets.poll(8,TimeUnit.SECONDS);assertNotNull(ping,"Actual Sable server must respond through the runner UDP socket")
                val pingFrame=java.nio.ByteBuffer.wrap(ping);assertEquals(7,pingFrame.int);assertEquals(4,pingFrame.get().toInt())
                listener.get().onBinary(ws,java.nio.ByteBuffer.allocate(5).putInt(7).put(5).flip(),true)
                val query=MinecraftQuery();val queryPort=settings.getProperty("query.port").toInt()
                repeat(5){assertEquals(0,query.currentCount(queryPort));assertEquals(0,query.players(queryPort)?.count)}
                process.outputStream.write("say isolated-sable-check\n".toByteArray());process.outputStream.flush();Thread.sleep(500)
                assertFalse(synchronized(lines){lines.any {it.contains("invalid packet ID: 254") || it.contains("InjectionError") || it.contains("Critical injection failure")}})
                assertTrue(synchronized(lines){lines.any {it.contains("Server UDP channel active")}})
                println("PASS: actual NeoForge21.1.249/Sable2.0.6/server-only bridge reached ready, 10 Query requests used isolated UDP, no packet254 or Mixin injection error")
            } finally {
                if(process.isAlive){process.outputStream.write("stop\n".toByteArray());process.outputStream.flush();check(process.waitFor(60,TimeUnit.SECONDS)){"Normal stop delayed; not force-killing"}}
                reader.join(2000)
            }
            assertEquals(0,process.exitValue());assertTrue(server.resolve("world/level.dat").exists())
        }
    }
}
