package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ConsoleAndStartupTest {
    @TempDir lateinit var temp:Path
    @Test fun `event pages start at the tail and preserve cursor boundaries`() {
        val manager=ServerManager()
        val id=(manager.create(CreateRequest("paged logs","21.1.252",31008,512))["config"] as Instance).id
        try {
            repeat(7000) { manager.runtime(id).event("stdout","[$it] sample") }
            @Suppress("UNCHECKED_CAST") val tail=manager.eventPage(id,null,null,300)["events"] as List<Event>
            assertEquals(300,tail.size);assertTrue(tail.first().seq>6000)
            @Suppress("UNCHECKED_CAST") val prior=manager.eventPage(id,tail.first().seq,null,300)["events"] as List<Event>
            assertEquals(prior.last().seq+1,tail.first().seq)
            manager.runtime(id).event("input","list")
            @Suppress("UNCHECKED_CAST") val next=manager.eventPage(id,null,tail.last().seq,300)["events"] as List<Event>
            assertEquals(listOf("list"),next.map { it.text })
            assertFailsWith<IllegalArgumentException> { manager.eventPage(id,null,null,100000) }
        } finally { manager.shutdown();manager.root.resolve(id).toFile().deleteRecursively() }
    }
    @Test fun `startup toggle writes only a user agent and escapes paths`() {
        val app=temp.resolve("Apps & tools/Server Manager.app")
        val binary=app.resolve("Contents/MacOS/ServerManager");binary.parent.createDirectories();binary.writeText("#!/bin/sh\nexit 0\n");binary.toFile().setExecutable(true)
        val data=temp.resolve("Data & worlds")
        val settings=StartupSettings(temp,app,data,true)
        assertEquals(false,settings.status()["enabled"])
        settings.setEnabled(true)
        val file=temp.resolve("Library/LaunchAgents/local.servermanager.plist")
        assertContains(file.readText(),"Apps &amp; tools");assertContains(file.readText(),"SERVER_MANAGER_HOME")
        assertContains(file.readText(),"--service");assertContains(file.readText(),"<key>RunAtLoad</key><true/>")
        assertEquals(true,StartupSettings(temp,app,data,true).status()["enabled"])
        settings.setEnabled(false);assertFalse(file.exists())
    }
    @Test fun `source build does not create a broken login item`() {
        val settings=StartupSettings(temp,null,temp.resolve("data"),true)
        assertEquals(false,settings.status()["supported"])
        assertFailsWith<IllegalStateException> { settings.setEnabled(true) }
        assertFalse(temp.resolve("Library/LaunchAgents/local.servermanager.plist").exists())
    }
    @Test fun `invited users can receive permissions before registering`() {
        AuthStore(temp).use { store ->
            val code=store.invite("Pending member",emptyMap());val id=store.invitation(code)!!.userId
            store.updateUser(id,true,mapOf("server" to "ADMIN"))
            assertTrue(store.allowed(id,"server",true))
            assertEquals(id,store.invitation(code)?.userId)
            assertTrue(store.credentialList(id).isEmpty())
        }
    }
}
