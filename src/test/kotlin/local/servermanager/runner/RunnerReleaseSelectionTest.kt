package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.io.InputStream
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.security.*
import kotlin.io.path.*
import kotlin.test.*

class RunnerReleaseSelectionTest {
    @TempDir lateinit var home:Path
    private fun fixture(current:String,target:String,rollback:Boolean=false,bad:Boolean=false):Pair<RunnerUpdater,MutableList<URI>> {
        val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();val jar="disposable signed $target".toByteArray()
        val hash=MessageDigest.getInstance("SHA-256").digest(jar).joinToString(""){"%02x".format(it)}
        val manifest="protocol=1\nversion=$target\nfile=server-manager-$target.jar\nsize=${jar.size}\nsha256=$hash\n".toByteArray()
        val signature=Signature.getInstance("Ed25519").apply {initSign(keys.private);update(manifest)}.sign().also {if(bad)it[0]=(it[0].toInt() xor 1).toByte()}
        val catalog=jacksonObjectMapper().writeValueAsBytes(listOf(mapOf("tag_name" to "v$target","prerelease" to target.contains("-beta."),"draft" to false,"assets" to listOf("update.properties","update.sig","server-manager-$target.jar").map {mapOf("name" to it)})))
        val client=mock(HttpClient::class.java);val calls=mutableListOf<URI>()
        doAnswer {call->
            val uri=call.getArgument<HttpRequest>(0).uri();calls.add(uri)
            val bytes=when{uri.path.endsWith("/releases")->catalog;uri.path.endsWith("update.properties")->manifest;uri.path.endsWith("update.sig")->signature;else->jar}
            @Suppress("UNCHECKED_CAST") val response=mock(HttpResponse::class.java) as HttpResponse<InputStream>
            `when`(response.statusCode()).thenReturn(200);`when`(response.body()).thenReturn(bytes.inputStream());response
        }.`when`(client).send(any(HttpRequest::class.java) ?: HttpRequest.newBuilder(URI("https://github.com")).build(),any<HttpResponse.BodyHandler<InputStream>>() ?: HttpResponse.BodyHandlers.ofInputStream())
        return RunnerUpdater(home,current,client,keys.public.encoded,rollback) to calls
    }
    @Test fun `stable runner ignores automatic beta but manual consent downloads exact beta and preserves it`() {
        val (updater,calls)=fixture("0.6.2","0.7.0-beta.2")
        updater.check();assertFalse(updater.pending());assertEquals(1,calls.size)
        assertFails {updater.install("0.7.0-beta.2",false)};assertFalse(updater.pending())
        updater.install("0.7.0-beta.2",true);assertEquals("0.7.0-beta.2",home.resolve("updates/pending.txt").readText());assertTrue(updater.pending())
        val count=calls.size;updater.check();assertEquals(count,calls.size);assertEquals("0.7.0-beta.2",updater.status().latest)
    }
    @Test fun `rollback requires supported bootstrap stable target and explicit consent`() {
        val (unsupported,_)=fixture("0.7.0-beta.2","0.6.2");assertFails {unsupported.install("0.6.2",false,true)};assertFalse(unsupported.pending())
        val (supported,calls)=fixture("0.7.0-beta.2","0.6.2",true)
        assertFails {supported.install("0.6.2",false)};assertFalse(supported.pending())
        supported.install("0.6.2",false,true);assertTrue(supported.pending());assertEquals("0.6.2",home.resolve("updates/rollback-approved.txt").readText())
        val count=calls.size;supported.check();assertEquals(count,calls.size);assertEquals("0.6.2",supported.status().latest)
    }
    @Test fun `rollback still rejects bad signature and older beta`() {
        val (bad,_)=fixture("0.7.0-beta.2","0.6.2",true,true);assertFails {bad.install("0.6.2",false,true)};assertFalse(bad.pending());assertFalse(home.resolve("updates/rollback-approved.txt").exists())
        val (beta,_)=fixture("0.7.0-beta.2","0.7.0-beta.1",true);assertFails {beta.install("0.7.0-beta.1",true,true)};assertFalse(beta.pending())
    }
    @Test fun `manual approval markers never authorize another target or beta downgrade`() {
        home.resolve("updates").createDirectories();UpdateRollback.stageConsent(home,"0.6.2","0.7.0-beta.2",true,true)
        assertTrue(UpdateRollback.approved(home,"0.6.2","0.7.0-beta.2",true));assertFalse(UpdateRollback.approved(home,"0.6.1","0.7.0-beta.2",true));assertFalse(UpdateRollback.approved(home,"0.6.2","0.7.0-beta.2",false))
        assertFails {UpdateRollback.stageConsent(home,"0.7.0-beta.1","0.7.0-beta.2",true,true)}
        assertFails {UpdateRollback.stageConsent(home,"0.5.1","0.7.0-beta.2",true,true)}
    }
}
