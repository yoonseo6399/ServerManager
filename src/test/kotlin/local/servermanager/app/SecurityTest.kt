package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*
import kotlin.test.*

class SecurityTest {
    @TempDir lateinit var temp:Path
    @Test fun `bootstrap code is single use and credential persists`() {
        var ownerId=""
        AuthStore(temp).use { store ->
            val code=temp.resolve("setup-code.txt").readText().trim()
            val invite=store.invitation(code)!!;ownerId=invite.userId
            store.register(ownerId,invite.hash,Passkey("a2V5",ownerId,"cHVibGlj",0,"test",Instant.now().toString()))
            assertTrue(store.hasOwnerCredential());assertNull(store.invitation(code));assertFalse(temp.resolve("setup-code.txt").exists())
            assertTrue(store.allowed(ownerId,"any",true))
        }
        AuthStore(temp).use { assertEquals(1,it.credentialList(ownerId).size);assertTrue(it.hasOwnerCredential()) }
    }
    @Test fun `server permissions and revocation epoch apply immediately`() {
        AuthStore(temp).use { store ->
            val code=store.invite("Viewer",mapOf("a" to "VIEWER","b" to "ADMIN"))
            val id=store.invitation(code)!!.userId
            assertTrue(store.allowed(id,"a",false));assertFalse(store.allowed(id,"a",true))
            assertTrue(store.allowed(id,"b",true));assertFalse(store.allowed(id,"c",false))
            val before=store.account(id)!!.epoch
            store.updateUser(id,false,mapOf("a" to "ADMIN"))
            assertFalse(store.allowed(id,"a",false));assertEquals(before+1,store.account(id)!!.epoch)
            assertNull(store.invitation(code))
        }
    }
    @Test fun `recovery revokes owner keys and issues a new local code`() {
        AuthStore(temp).use { store ->
            val invite=store.invitation(temp.resolve("setup-code.txt").readText().trim())!!
            store.register(invite.userId,invite.hash,Passkey("a2V5",invite.userId,"cHVibGlj",0,"test",Instant.now().toString()))
            store.recoverOwner()
            assertFalse(store.hasOwnerCredential());assertTrue(store.credentialList(invite.userId).isEmpty())
            assertEquals(1,store.account(invite.userId)!!.epoch)
            assertNotNull(store.invitation(temp.resolve("setup-code.txt").readText().trim()))
        }
    }
    @Test fun `external binding requires canonical HTTPS domain`() {
        assertEquals("http://localhost:8787",SecuritySettings("",8787,"127.0.0.1").origin)
        assertFailsWith<IllegalArgumentException> { SecuritySettings("",8787,"0.0.0.0") }
        assertFailsWith<IllegalArgumentException> { SecuritySettings("http://example.com",8787,"0.0.0.0") }
        assertFailsWith<IllegalArgumentException> { SecuritySettings("https://example.com/path",8787,"0.0.0.0") }
        assertTrue(SecuritySettings("https://mc.example.com:8443",8787,"0.0.0.0").secure)
    }
    @Test fun `last key cannot be deleted and invitation does not store plaintext`() {
        AuthStore(temp).use { store ->
            val code=store.invite("Member",emptyMap());assertFalse(temp.resolve("accounts.json").readText().contains(code))
            val invite=store.invitation(code)!!
            store.register(invite.userId,invite.hash,Passkey("a2V5",invite.userId,"cHVibGlj",0,"test",Instant.now().toString()))
            assertFailsWith<IllegalArgumentException> { store.removeKey(invite.userId,"a2V5") }
        }
    }
    @Test fun `non-loopback traffic requires the configured reverse proxy token`() {
        val guard=RequestGuard(SecuritySettings("https://mc.example.com",8787,"0.0.0.0"),"test-proxy-key",AuditLog(temp.resolve("audit.jsonl")))
        val request=org.springframework.mock.web.MockHttpServletRequest("GET","/api/health").apply { remoteAddr="192.168.1.50" }
        val denied=org.springframework.mock.web.MockHttpServletResponse()
        guard.doFilter(request,denied,org.springframework.mock.web.MockFilterChain())
        assertEquals(403,denied.status)
        request.addHeader("X-ServerManager-Proxy","test-proxy-key");request.addHeader("X-Real-IP","203.0.113.10")
        val allowed=org.springframework.mock.web.MockHttpServletResponse();val chain=org.springframework.mock.web.MockFilterChain()
        guard.doFilter(request,allowed,chain)
        assertNotNull(chain.request);assertEquals("203.0.113.10",request.getAttribute("sm.client"))
    }
}
