package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class DiscordModuleSettingsTest {
    @TempDir lateinit var root:Path
    @Test fun `separate preferences persist reject stale updates and leave legacy token settings alone`() {
        val legacy=root.resolve("settings.json").also {it.writeText("legacy sentinel")}
        val store=DiscordModuleSettings(root);assertTrue(store.enabled("music"))
        store.save(DiscordModuleSave(0,setOf("music","agent")))
        assertFalse(store.enabled("music"));assertTrue(store.enabled("minecraft"))
        assertFalse(DiscordModuleSettings(root).enabled("agent"))
        assertEquals("legacy sentinel",legacy.readText())
        assertFailsWith<IllegalStateException> {store.save(DiscordModuleSave(0,emptySet()))}
        assertFailsWith<IllegalArgumentException> {store.save(DiscordModuleSave(1,setOf("unknown")))}
        store.save(DiscordModuleSave(1,emptySet()));assertTrue(store.enabled("agent"))
    }
    @Test fun `corrupt preferences disable modules and can be recovered through a valid save`() {
        root.resolve("module-settings.json").writeText("broken")
        val store=DiscordModuleSettings(root);assertFalse(store.enabled("music"));assertTrue(store.warning.isNotBlank())
        store.save(DiscordModuleSave(0,emptySet()));assertTrue(store.enabled("music"));assertEquals("",store.warning)
    }
}
