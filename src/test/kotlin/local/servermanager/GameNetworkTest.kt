package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class GameNetworkTest {
    @TempDir lateinit var temp:Path
    @Test fun `disabled UDP setting keeps legacy persisted config shape`() {
        val mapper=jacksonObjectMapper();val legacy=mapper.readTree(mapper.writeValueAsString(GameNetworkConfig()))
        assertFalse(legacy.has("sableUdpEnabled"))
        assertTrue(mapper.readTree(mapper.writeValueAsString(GameNetworkConfig(enabled=true,sableUdpEnabled=true))).path("sableUdpEnabled").asBoolean())
        assertFails {GameDomains.validate(GameNetworkConfig(sableUdpEnabled=true),emptySet())}
    }
    @Test fun `remote relay requires shared TCP and rejects UDP or malformed runner IDs`() {
        val id="12345678-1234-1234-1234-123456789abc"
        val config=GameNetworkConfig(enabled=true,baseDomain="server.example.com",relayRunnerId=id)
        GameDomains.validate(config,emptySet())
        assertFails {GameDomains.validate(config.copy(enabled=false),emptySet())}
        assertFails {GameDomains.validate(config.copy(sableUdpEnabled=true),emptySet())}
        assertFails {GameDomains.validate(config.copy(relayRunnerId="bad"),emptySet())}
    }
    private fun config()=GameNetworkConfig(enabled=true,baseDomain="server.example.com",targetHost="gateway.example.com",defaultInstanceId="a",defaultHostname="playground.example.com",labels=mapOf("a" to "alpha","b" to "beta"))
    @Test fun `server domains distinguish instances and preserve only explicit legacy alias`() {
        val c=config();GameDomains.validate(c,setOf("a","b"))
        assertEquals("a",GameDomains.resolve(c,setOf("a","b"),"ALPHA.SERVER.EXAMPLE.COM."))
        assertEquals("b",GameDomains.resolve(c,setOf("a","b"),"beta.server.example.com\u0000FML3\u0000"))
        assertEquals("a",GameDomains.resolve(c,setOf("a","b"),"playground.example.com"))
        assertNull(GameDomains.resolve(c,setOf("a","b"),"unknown.server.example.com"))
        assertFails {GameDomains.validate(c.copy(labels=mapOf("a" to "same","b" to "same")),setOf("a","b"))}
        assertFails {GameDomains.validate(c.copy(labels=mapOf("a" to "other.zone.com")),setOf("a","b"))}
        assertFails {GameDomains.validate(c.copy(defaultHostname="beta.server.example.com"),setOf("a","b"))}
    }
    private fun varint(out:ByteArrayOutputStream,value:Int) {var n=value;do {var b=n and 127;n=n ushr 7;if(n!=0)b=b or 128;out.write(b)}while(n!=0)}
    fun handshake(host:String):ByteArray {
        val p=ByteArrayOutputStream();varint(p,0);varint(p,767);val h=host.toByteArray();varint(p,h.size);p.write(h);p.write(24);p.write(255);varint(p,2)
        return ByteArrayOutputStream().also {varint(it,p.size());it.write(p.toByteArray())}.toByteArray()
    }
    @Test fun `handshake retains every raw byte and leaves following payload untouched`() {
        val raw=handshake("alpha.server.example.com\u0000FML3\u0000")
        val input=ByteArrayInputStream(raw+byteArrayOf(1,2,3))
        val parsed=MinecraftHandshake.read(input)
        assertEquals("alpha.server.example.com",parsed.host);assertContentEquals(raw,parsed.bytes);assertContentEquals(byteArrayOf(1,2,3),input.readAllBytes())
        assertFails {MinecraftHandshake.read(ByteArrayInputStream(byteArrayOf(0x81.toByte(),0x20)))}
        assertFails {MinecraftHandshake.read(ByteArrayInputStream(raw.copyOf(raw.size-1)))}
        assertFails {MinecraftHandshake.read(ByteArrayInputStream(handshake("evil/host")))}
    }
    @Test fun `DNS token is private encrypted masked and removable`() {
        val store=GameNetworkStore(temp.resolve("network"));val token="fake_cloudflare_token_for_test_only"
        val c=config().copy(cloudflareEnabled=true,zoneId="a".repeat(32))
        val saved=store.save(GameNetworkSave(c,0,token),setOf("a","b"))
        assertTrue(saved.tokenConfigured);assertEquals(token,store.token())
        assertFalse(temp.resolve("network/settings.json").readText().contains(token));assertFalse(jacksonObjectMapper().writeValueAsString(saved).contains(token))
        assertEquals(token,GameNetworkStore(temp.resolve("network")).token())
        assertFails {store.save(GameNetworkSave(c,0),setOf("a","b"))}
        store.save(GameNetworkSave(c.copy(cloudflareEnabled=false),1,clearToken=true),setOf("a","b"));assertNull(store.token())
    }
    @Test fun `Cloudflare DNS is only A plus correct per-server SRV and foreign records are protected`() {
        val mapper=jacksonObjectMapper();val records=mutableMapOf<String,com.fasterxml.jackson.databind.JsonNode>();var changes=0
        val client=CloudflareDns("fake_token","a".repeat(32)) { method,path,body ->
            if(method=="GET") {
                val name=java.net.URLDecoder.decode(path.substringAfter("name=").substringBefore('&'),Charsets.UTF_8)
                mapper.valueToTree(mapOf("success" to true,"result" to listOfNotNull(records[name])))
            } else {
                changes++;val next=(body!!.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()).put("id","id-${changes}")
                records[next.path("name").asText()]=next;mapper.valueToTree(mapOf("success" to true,"result" to next))
            }
        }
        val name="alpha.server.example.com"
        client.upsert(name,"A","203.0.113.1",tag="owned:a:A")
        client.upsert("_minecraft._tcp.$name","SRV",null,mapOf("priority" to 0,"weight" to 5,"port" to 6399,"target" to name),"owned:a:SRV")
        assertFalse(records[name]!!.path("proxied").asBoolean());assertEquals(name,records["_minecraft._tcp.$name"]!!.path("data").path("target").asText())
        client.upsert(name,"A","203.0.113.1",tag="owned:a:A");assertEquals(2,changes)
        assertFails {client.upsert(name,"A","203.0.113.2",tag="foreign:b:A")};assertEquals(2,changes)
        client.upsert(name,"A","203.0.113.2",tag="owned:a:A");assertEquals(3,changes)
    }
    @Test fun `DNS cleanup preserves manual and other installation records and cancels on edits`() {
        val mapper=jacksonObjectMapper();val tag="ServerManager:installation:"
        val id="12345678-1234-1234-1234-123456789abc";val removed=mutableListOf<String>()
        val records=listOf(
            mapOf("id" to "keep","type" to "A","name" to "keep.server.example.com","comment" to "$tag$id:A"),
            mapOf("id" to "stale","type" to "SRV","name" to "_minecraft._tcp.old.server.example.com","comment" to "$tag$id:SRV"),
            mapOf("id" to "manual","type" to "A","name" to "old.server.example.com","comment" to ""),
            mapOf("id" to "other","type" to "A","name" to "other.example.com","comment" to "ServerManager:other:$id:A"),
            mapOf("id" to "malformed","type" to "TXT","name" to "old.example.com","comment" to "$tag$id:A"))
        val client=CloudflareDns("fake_token","a".repeat(32)) {method,path,_->
            if(method=="DELETE")removed.add(path.substringAfter('/'))
            mapper.valueToTree(mapOf("success" to true,"result" to records,"result_info" to mapOf("total_pages" to 1)))
        }
        client.prune(setOf("keep.server.example.com"),tag);assertEquals(listOf("stale"),removed)
        removed.clear();var checks=0
        assertFails {client.prune(emptySet(),tag) {++checks<2}};assertTrue(removed.isEmpty())
    }
    @Test fun `deleting an instance removes its label and legacy default without resetting DNS credentials`() {
        val store=GameNetworkStore(temp.resolve("network"));val token="fake_cloudflare_token_for_test_only"
        store.save(GameNetworkSave(config().copy(cloudflareEnabled=true,zoneId="a".repeat(32)),0,token),setOf("a","b"))
        store.removeInstance("a",setOf("b"));val view=store.view()
        assertEquals(mapOf("b" to "beta"),view.config.labels);assertNull(view.config.defaultInstanceId)
        assertEquals(token,store.token());assertEquals(2,view.revision)
        store.save(GameNetworkSave(view.config,view.revision),setOf("b"))
    }
}
