package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class LaunchersTest {
    @TempDir lateinit var temp:Path
    @Test fun `all server kinds have distinct executable arguments and Windows uses Windows classpath`() {
        for((loader,version) in mapOf("NeoForge" to "21.1.252","Forge" to "52.1.0","Fabric" to "0.16.10","Paper" to "133","Vanilla" to "1.21.1")) {
            val server=temp.resolve(loader).also {it.createDirectories()}
            if(loader in listOf("NeoForge","Forge")) {
                val base=if(loader=="NeoForge")"net/neoforged/neoforge/$version" else "net/minecraftforge/forge/1.21.1-$version"
                server.resolve("libraries/$base").createDirectories();for(name in listOf("unix_args.txt","win_args.txt"))server.resolve("libraries/$base/$name").writeText("args")
            } else server.resolve(if(loader=="Fabric")"fabric-server-launch.jar" else "server.jar").writeText("jar")
            val i=Instance("id","test",loader=loader,loaderVersion=version,port=25565,memoryMb=1024,javaPath="/path with spaces/java")
            val unix=Launchers.arguments(i,server,windows=false);val windows=Launchers.arguments(i,server,windows=true)
            assertEquals(i.javaPath,unix[0]);assertTrue("-Xmx1024M" in unix)
            if(loader in listOf("NeoForge","Forge")){assertTrue(unix[3].endsWith("unix_args.txt"));assertTrue(windows[3].endsWith("win_args.txt"))}else{assertEquals("-jar",unix[3]);assertEquals("nogui",unix.last())}
        }
    }
    @Test fun `invalid combinations and argument injection are rejected`() {
        assertFails {Launchers.validate("NeoForge","1.21.2","21.1.252")}
        assertFails {Launchers.validate("Vanilla","1.21.1","1.21.2")}
        assertFails {Launchers.validate("Paper","1.21.1","../../world")}
        assertFails {Launchers.validate("unknown","1.21.1","1.0.0")}
        assertEquals(21,Launchers.javaMajor("openjdk version \"21.0.6\""));assertEquals(8,Launchers.javaMajor("java version \"1.8.0_123\""))
    }
    @Test fun `catalog filters versions by loader and Minecraft and falls back to verified cache`() {
        var offline=false
        val catalog=LauncherCatalog(temp) {url ->
            if(offline)error("offline")
            when {
                url==LauncherCatalog.NEO -> "<version>21.1.1</version><version>21.1.252</version><version>21.2.1</version>"
                url==LauncherCatalog.FORGE -> "<version>1.21.1-52.1.0</version><version>1.21.2-53.1.0</version>"
                url.contains("fabricmc") -> "[{\"loader\":{\"stable\":true,\"version\":\"0.16.10\"}},{\"loader\":{\"stable\":false,\"version\":\"0.17.0\"}}]"
                else -> "[{\"channel\":\"STABLE\",\"id\":133},{\"channel\":\"EXPERIMENTAL\",\"id\":134}]"
            }
        }
        assertEquals(listOf("21.1.252","21.1.1"),catalog.versions("NeoForge","1.21.1").versions)
        assertEquals(listOf("52.1.0"),catalog.versions("Forge","1.21.1").versions)
        assertEquals(listOf("0.16.10"),catalog.versions("Fabric","1.21.1").versions)
        assertEquals(listOf("133"),catalog.versions("Paper","1.21.1").versions)
        offline=true;assertTrue(catalog.versions("Paper","1.21.1",true).stale)
        assertEquals(listOf("133"),LauncherCatalog(temp){error("offline")}.versions("Paper","1.21.1").versions)
    }
    @Test fun `download URLs stay on official HTTPS sources`() {
        assertFails {LauncherDownloads.allowed("http://piston-data.mojang.com/server.jar")}
        assertFails {LauncherDownloads.allowed("https://127.0.0.1/server.jar")}
        assertFails {LauncherDownloads.allowed("https://user@meta.fabricmc.net/x")}
    }
}
