package local.servermanager

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.ServerSocket
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="SM_LIVE_LAUNCHERS",matches="1")
class LauncherLiveTest {
    @TempDir lateinit var temp:Path
    @ParameterizedTest @ValueSource(strings=["Vanilla","Fabric","Paper","Forge","NeoForge"])
    fun `official launcher installs starts and stops in an isolated flat world`(loader:String) {
        val server=temp.resolve("server").also {it.createDirectories()}
        val javaExecutable=Path.of(System.getProperty("java.home"),"bin/java").toString()
        val catalog=LauncherCatalog(temp.resolve("catalog"))
        val version=if(loader=="NeoForge")"21.1.252" else catalog.versions(loader,"1.21.1").versions.first()
        val instance=Instance("test",loader,loader=loader,loaderVersion=version,port=ServerSocket(0).use {it.localPort},memoryMb=1024,javaPath=javaExecutable)
        val installer=LauncherInstaller(catalog)
        installer.install(instance,server,temp.resolve("installer.jar"),{args ->
            val log=temp.resolve("installer.log")
            val p=ProcessBuilder(args).directory(server.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if(!p.waitFor(300,TimeUnit.SECONDS)){p.destroy();error("Installer timeout: $loader")}
            if(p.exitValue()!=0)println(log.readText().takeLast(3000))
            p.exitValue()
        }) {println(it)}
        val settings=Properties().apply {
            setProperty("server-ip","127.0.0.1");setProperty("server-port",instance.port.toString())
            QueryEndpoint.configure(this,instance.port)
            setProperty("online-mode","true");setProperty("level-type","minecraft:flat");setProperty("generate-structures","false")
            setProperty("view-distance","2");setProperty("simulation-distance","2");setProperty("max-players","2")
        }
        server.resolve("server.properties").outputStream().use {settings.store(it,"isolated test")}
        server.resolve("eula.txt").writeText("eula=true\n")
        val ready=CountDownLatch(1);val lines=java.util.Collections.synchronizedList(mutableListOf<String>())
        val process=ProcessBuilder(Launchers.arguments(instance,server)).directory(server.toFile()).redirectErrorStream(true).start()
        thread(isDaemon=true) {process.inputStream.bufferedReader().useLines {output->output.forEach {line->lines.add(line);if(line.contains("Done (")&&line.contains("For help"))ready.countDown()}}}
        try {
            assertTrue(ready.await(180,TimeUnit.SECONDS),"$loader did not reach RUNNING: "+synchronized(lines){lines.takeLast(20).joinToString("\n")})
            assertTrue(process.isAlive)
            // Vanilla/Fabric log Done before their Query listener is bound.
            val query=MinecraftQuery();val queryDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            var count=query.currentCount(settings.getProperty("query.port").toInt())
            while(count==null && System.nanoTime()<queryDeadline){Thread.sleep(50);count=query.currentCount(settings.getProperty("query.port").toInt())}
            assertEquals(0,count,"Current basic count must work with the actual $loader server")
            assertEquals(0,MinecraftQuery().players(settings.getProperty("query.port").toInt())?.count,"Full stat must work with the actual $loader server")
            process.outputStream.write("list\nstop\n".toByteArray());process.outputStream.flush()
            assertTrue(process.waitFor(60,TimeUnit.SECONDS),"Normal stop was delayed; not force-killing")
            assertEquals(0,process.exitValue(),synchronized(lines){lines.takeLast(20).joinToString("\n")})
            assertTrue(server.resolve("world/level.dat").isRegularFile())
        } finally {
            if(process.isAlive){runCatching {process.outputStream.write("stop\n".toByteArray());process.outputStream.flush()};check(process.waitFor(60,TimeUnit.SECONDS)){"Test process remains active; refusing force stop. PID ${process.pid()}"}}
        }
        println("PASS $loader $version: official hashes, RUNNING, list, normal save/stop")
    }
}
