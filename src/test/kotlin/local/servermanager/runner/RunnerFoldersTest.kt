package local.servermanager

import org.junit.jupiter.api.Test
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import java.util.Properties
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

class RunnerFoldersTest {
    private fun fixture(test:(java.nio.file.Path,java.nio.file.Path,String,RunnerFolders)->Unit) {
        val temp=Files.createTempDirectory("runner-folder-").toRealPath()
        try {
            val home=temp.resolve("runner").also {it.createDirectories()};val server=temp.resolve("existing server").also {it.createDirectories()};val id=UUID.randomUUID().toString()
            server.resolve("server.properties").writeText("level-name=world\n")
            server.resolve("world").createDirectories();server.resolve("world/level.dat").writeText("original world")
            server.resolve("paper-custom.jar").writeText("fixture JAR")
            val config=home.resolve("instances/$id/server-folder.properties");config.parent.createDirectories()
            Properties().apply {setProperty("path",server.toString());setProperty("initial","true");setProperty("jar","paper-custom.jar");setProperty("requiredJava","21")}.also {p->config.outputStream().use {p.store(it,"")}}
            test(home,server,id,RunnerFolders(home))
        } finally {temp.toFile().deleteRecursively()}
    }
    @Test fun `existing source stays in place and custom jar remains portable after handoff`()=fixture {home,server,id,folders->
        assertEquals(server,folders.directory(id));assertTrue(folders.initial(id));folders.checkStopped(id)
        val instance=Instance(id,"existing","Paper","1.21.1","132",25565,512,"java")
        folders.prepareRuntime(id,instance)
        val args=folders.arguments(id,instance,"java",false)
        assertTrue(args.contains(server.resolve("paper-custom.jar").toString()))
        assertEquals(args,Launchers.arguments(instance,server,"java",false))
        assertEquals(21,Launchers.requiredJava(instance,server))
        assertEquals("original world",server.resolve("world/level.dat").readText())
        val stage=folders.stage(id);assertEquals(server.parent,stage.parent);assertNotEquals(home,stage.parent)
        assertEquals(server.parent,folders.backup(id).parent)
        val inventory=FileInventory.collect(server).let {(f,d)->RunnerFileManifest(f,d)}
        folders.published(id,inventory);assertFalse(folders.initial(id));folders.checkStopped(id)
        server.resolve("mods").createDirectories();server.resolve("mods/new.jar").writeText("external edit")
        assertFailsWith<IllegalStateException>{folders.checkStopped(id)}
        assertEquals("external edit",server.resolve("mods/new.jar").readText())
    }
    @Test fun `active world and symlinks are refused without replacing source`()=fixture {_,server,id,folders->
        val lock=server.resolve("world/session.lock").also {it.writeText("lock")}
        FileChannel.open(lock,WRITE).use {channel->channel.lock().use {assertFailsWith<IllegalStateException>{folders.checkStopped(id)}}}
        if(!System.getProperty("os.name").startsWith("Windows")) {
            Files.createSymbolicLink(server.resolve("link"),server.parent)
            assertFailsWith<IllegalArgumentException>{folders.checkStopped(id)}
            server.resolve("link").deleteExisting()
        }
        assertEquals("original world",server.resolve("world/level.dat").readText())
    }
    @Test fun `missing or mismatched launcher and escaping jar metadata never starts a different server`()=fixture {_,server,id,folders->
        val instance=Instance(id,"existing","NeoForge","1.21.1","21.1.219",25565,512,"java")
        assertFailsWith<IllegalArgumentException>{folders.arguments(id,instance,"java",false)}
        val paper=instance.copy(loader="Paper",loaderVersion="132")
        server.resolve("server-manager-runtime.json").writeText("""{"loader":"Paper","minecraft":"1.20.1","loaderVersion":"132","requiredJava":17}""")
        assertFailsWith<IllegalArgumentException>{folders.requiredJava(id,paper)}
        server.resolve("server-manager-runtime.json").writeText("""{"entryJar":"../outside.jar"}""")
        assertFailsWith<IllegalArgumentException>{Launchers.arguments(paper,server,"java",false)}
    }
}
