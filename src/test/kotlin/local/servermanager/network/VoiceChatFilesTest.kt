package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.*

class VoiceChatFilesTest {
    @TempDir lateinit var home:Path
    private val target=VoiceChatTarget("a",24454,"voice.example.com:24455")
    private fun file():Path=home.resolve("server/config/voicechat/voicechat-server.properties").also {it.parent.createDirectories()}
    private fun files()=VoiceChatFiles(home.resolve("backups"))
    @Test fun `prepare preserves CRLF comments and other keys and disable restores originals`() {
        val p=file();val original="# 마이크\r\nport=24453\r\nbind_address=*\r\nvoice_host=old.example.com:24453\r\ncodec=VOIP\r\n";p.writeText(original)
        val f=files();assertEquals("CONFIGURED",f.prepare("a",home.resolve("server"),target).state)
        val edited=p.readText();assertTrue(edited.contains("# 마이크\r\n"));assertTrue(edited.contains("codec=VOIP\r\n"));assertTrue(edited.contains("bind_address=127.0.0.1\r\n"));assertTrue(edited.contains("voice_host=voice.example.com:24455\r\n"))
        f.prepare("a",home.resolve("server"),target);assertEquals(edited,p.readText());assertEquals(original,home.resolve("backups/a/original.properties").readText())
        assertEquals("OFF",f.prepare("a",home.resolve("server"),null).state);assertEquals(original,p.readText())
    }
    @Test fun `restore preserves later manual edits while restoring other managed keys`() {
        val p=file();p.writeText("port=24453\nvoice_host=old.example.com\ncodec=AUDIO\n")
        val f=files();f.prepare("a",home.resolve("server"),target);p.writeText(p.readText().replace("port=24454","port=24456").replace("codec=AUDIO","codec=VOIP"))
        f.prepare("a",home.resolve("server"),null);assertTrue(p.readText().contains("port=24456"));assertTrue(p.readText().contains("voice_host=old.example.com"));assertTrue(p.readText().contains("codec=VOIP"));assertFalse(p.readText().contains("bind_address="))
    }
    @Test fun `new plugin config is prepared without installing mod and different address keeps original backup`() {
        val root=home.resolve("paper");root.resolve("plugins").createDirectories();val f=files()
        f.prepare("a",root,target);val p=root.resolve("plugins/voicechat/voicechat-server.properties");assertTrue(p.readText().contains("port=24454"))
        f.prepare("a",root,target.copy(publicAddress="new.example.com:24456"));assertTrue(p.readText().contains("new.example.com:24456"));f.prepare("a",root,null);assertFalse(p.readText().contains("voice_host="))
    }
    @Test fun `external config directory link is refused without changing external file`() {
        val outside=home.resolve("external").createDirectories();val original=outside.resolve("voicechat-server.properties").also {it.writeText("port=24453\n")}
        val root=home.resolve("server");root.resolve("config").createDirectories();Files.createSymbolicLink(root.resolve("config/voicechat"),outside)
        assertFailsWith<IllegalArgumentException>{files().prepare("a",root,target)};assertEquals("port=24453\n",original.readText())
    }
    @Test fun `query collision and malformed managed state fail before changing config`() {
        val p=file();p.writeText("port=24453\n");home.resolve("server/server.properties").writeText("server-port=25565\nenable-query=true\nquery.port=24454\n")
        assertFailsWith<IllegalArgumentException>{files().prepare("a",home.resolve("server"),target)};assertEquals("port=24453\n",p.readText())
        home.resolve("server/server.properties").deleteExisting();home.resolve("backups/a").createDirectories();home.resolve("backups/a/managed.json").writeText("not json")
        assertFails {files().prepare("a",home.resolve("server"),target)};assertEquals("port=24453\n",p.readText())
    }
}
