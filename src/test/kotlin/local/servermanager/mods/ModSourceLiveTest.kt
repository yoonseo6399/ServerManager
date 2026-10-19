package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*

/** Explicit opt-in, read-only upstream checks. Never install, rename or change a running server's files. */
class ModSourceLiveTest {
    @Test fun `consented installed hashes retain all files alongside real Modrinth unknowns`() {
        val file=System.getenv("SM_MOD_HASH_FIXTURE")
        assumeTrue(!file.isNullOrBlank())
        val rows=Path.of(file!!).readText().lineSequence().last {it.startsWith("{")}
        val hashes=jacksonObjectMapper().readValue<Map<String,String>>(rows)
        val api=ModrinthApi()
        val result=ModSources.lookup(api,hashes.values,ModSourceCache(),true)
        val snapshot=ModInventorySnapshot("fixture",hashes.map {(name,hash)->InstalledMod(name,!name.endsWith(".disabled",true),1,"",hash)},emptyList())
        val attached=ModInventory.attach(snapshot,result)
        assertEquals(hashes.size,attached.mods.size)
        assertTrue(result.cache.sources.isNotEmpty(),result.warnings.joinToString())
        assertEquals(hashes.keys,attached.mods.map {it.filename}.toSet())
        assertTrue(attached.mods.filter {it.source==null}.all {it.warning!=null})
        println("LIVE MOD LOOKUP: files=${hashes.size}, known=${attached.mods.count {it.source!=null}}, unknown=${attached.mods.count {it.source==null}}, lookupWarnings=${result.warnings}")
        val directory=System.getenv("SM_MOD_SCAN_DIR")
        if(!directory.isNullOrBlank()) {
            val scanned=ModInventory.scan(Path.of(directory))
            assertEquals(hashes,scanned.mods.associate {it.filename to it.sha512})
            assertEquals(hashes.size,ModInventory.attach(scanned,result).mods.size)
            println("READ ONLY SCAN: ${scanned.mods.size} real files matched all consented hashes")
        }
    }
}
