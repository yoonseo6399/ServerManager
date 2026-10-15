package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.*

class RunnerRegistryTest {
    @TempDir lateinit var temp:Path
    @Test fun `registrant owner and global administrator grant servers while instance admin cannot`() {
        AuthStore(temp.resolve("security")).use { auth ->
            auth.invite("viewer",mapOf("a" to "VIEWER"))
            auth.invite("admin",mapOf("a" to "ADMIN"))
            val viewer=auth.accounts().first { it.displayName=="viewer" }
            val admin=auth.accounts().first { it.displayName=="admin" }
            val manager=mock(ServerManager::class.java)
            `when`(manager.root).thenReturn(temp)
            `when`(manager.list()).thenReturn(emptyList())
            `when`(manager.instance("a")).thenReturn(Instance("a","server",loaderVersion="21.1.252",port=25565,memoryMb=512,javaPath="java"))
            val registry=RunnerRegistry(manager,auth)
            val code=registry.enroll(viewer.id,"친구의 Mac")
            val key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val publicKey=Base64.getUrlEncoder().withoutPadding().encodeToString(key.public.encoded)
            val runner=registry.pair(RunnerPairRequest(code,publicKey,"MAC","0.4.0-beta.3"))
            assertFailsWith<IllegalArgumentException> { registry.pair(RunnerPairRequest(code,publicKey,"MAC","0.4.0-beta.3")) }
            assertFalse(registry.canAssign(admin.id,"a",runner.id))
            assertFailsWith<IllegalArgumentException> { registry.allow(admin.id,runner.id,"a",true) }
            `when`(manager.instance("private")).thenReturn(Instance("private","private",loaderVersion="21.1.252",port=25566,memoryMb=512,javaPath="java"))
            assertFailsWith<IllegalArgumentException> { registry.allow(viewer.id,runner.id,"private",true) }
            val globalId=auth.invitation(auth.invite("global",emptyMap(),AccountRole.ADMIN))!!.userId
            registry.allow(globalId,runner.id,"a",true);assertTrue(registry.canAssign(globalId,"a",runner.id));assertTrue(registry.views(globalId).single().canGrant)
            registry.allow(auth.accounts().first {it.owner}.id,runner.id,"a",false)
            registry.allow(viewer.id,runner.id,"a",true)
            assertTrue(registry.canAssign(admin.id,"a",runner.id))
            assertFalse(registry.canAssign(viewer.id,"a",runner.id))
            val bytes="connect:nonce".toByteArray()
            val signature=Signature.getInstance("Ed25519").apply { initSign(key.private);update(bytes) }.sign()
            assertTrue(registry.verify(runner.id,bytes,Base64.getUrlEncoder().withoutPadding().encodeToString(signature)))
            val timestamp=System.currentTimeMillis();val nonce="abcdefghijklmnopqrstuvwx"
            fun signed(purpose:String,n:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(Signature.getInstance("Ed25519").apply {initSign(key.private);update("$purpose\n${runner.id}\n$timestamp\n$n\n".toByteArray())}.sign())
            assertFalse(registry.acceptUdpConnection(runner.id,timestamp,nonce,signed("sm-runner-v1",nonce)))
            assertTrue(registry.acceptUdpConnection(runner.id,timestamp,nonce,signed("sm-runner-udp-v1",nonce)))
            assertFalse(registry.acceptUdpConnection(runner.id,timestamp,nonce,signed("sm-runner-udp-v1",nonce)))
            assertFalse(registry.acceptConnection(runner.id,timestamp,nonce,signed("sm-runner-v1",nonce)))
            registry.seen(runner.id,"0.4.4");registry.updateStatus(runner.id,RunnerUpdateStatus())
            assertFails {registry.requestUpdate(admin.id,runner.id,false)}
            registry.requestUpdate(viewer.id,runner.id,false)
            assertEquals("REQUESTED",registry.views(viewer.id).single().update?.phase)
            registry.tcpRelayCapability(runner.id,true);assertTrue(registry.tcpRelaySupported(runner.id))
            assertTrue(registry.views(viewer.id).single().tcpRelaySupported)
            registry.disconnected(runner.id);assertFalse(registry.tcpRelaySupported(runner.id));assertFails {registry.requestUpdate(viewer.id,runner.id,false)}
            registry.seen(runner.id,"0.4.0-beta.8");assertFails {registry.requestUpdate(viewer.id,runner.id,false)}
            registry.allow(viewer.id,runner.id,"a",false)
            assertFalse(registry.canAssign(admin.id,"a",runner.id))
            registry.revoke(viewer.id,runner.id)
            assertNull(registry.active(runner.id))
        }
    }
}
