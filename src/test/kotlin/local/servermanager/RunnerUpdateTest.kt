package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.io.InputStream
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.security.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.*
import kotlin.test.*

class RunnerUpdateTest {
    @TempDir lateinit var temp:Path
    private fun updater(corrupt:Boolean=false,badSignature:Boolean=false):Pair<RunnerUpdater,List<RunnerUpdateStatus>> {
        val key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();val bytes="signed disposable fixture jar".toByteArray()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val manifest="protocol=1\nversion=0.4.5\nfile=server-manager-0.4.5.jar\nsize=${bytes.size}\nsha256=$hash\n".toByteArray()
        val signature=Signature.getInstance("Ed25519").apply {initSign(key.private);update(manifest)}.sign().also {if(badSignature)it[0]=(it[0].toInt() xor 1).toByte()}
        val client=mock(HttpClient::class.java)
        doAnswer {call->
            val path=call.getArgument<HttpRequest>(0).uri().path
            val body=when {path.endsWith("/releases")->"""[{"tag_name":"v0.4.5","draft":false,"prerelease":false}]""".toByteArray();path.endsWith("update.properties")->manifest;path.endsWith("update.sig")->signature;else->bytes.copyOf().also {if(corrupt)it[0]=0}}
            @Suppress("UNCHECKED_CAST") val response=mock(HttpResponse::class.java) as HttpResponse<InputStream>
            `when`(response.statusCode()).thenReturn(200);`when`(response.body()).thenReturn(body.inputStream());response
        }.`when`(client).send(any(HttpRequest::class.java) ?: HttpRequest.newBuilder(URI("https://github.com")).build(),any<HttpResponse.BodyHandler<InputStream>>() ?: HttpResponse.BodyHandlers.ofInputStream())
        val states=CopyOnWriteArrayList<RunnerUpdateStatus>()
        return RunnerUpdater(temp,"0.4.4",client,key.public.encoded){states.add(it)} to states
    }
    @Test fun `download verifies pinned signature size and hash before marking a pending update`() {
        temp.resolve("identity.json").writeText("preserve fixture identity")
        val (service,states)=updater();service.check()
        assertTrue(service.pending());assertEquals("0.4.5",temp.resolve("updates/pending.txt").readText())
        assertTrue(states.any {it.phase=="DOWNLOADING"});assertEquals("READY",service.status().phase)
        assertEquals("preserve fixture identity",temp.resolve("identity.json").readText())
    }
    @Test fun `corrupt artifact and bad signature never become an applicable update`() {
        val (bad,_)=updater(corrupt=true);assertFails {bad.check()};assertFalse(bad.pending());assertEquals("FAILED",bad.status().phase)
        assertFalse(temp.resolve("updates/0.4.5/server-manager-0.4.5.jar").exists())
        val (signature,_)=updater(badSignature=true);assertFails {signature.check()};assertFalse(signature.pending())
        assertTrue(temp.resolve("updates/0.4.5").listDirectoryEntries("*.part").isEmpty())
    }
    @Test fun `a version that failed supervisor readiness is not automatically retried`() {
        temp.resolve("failed-update-version.txt").writeText("0.4.5")
        val (service,_)=updater();service.check();assertFalse(service.pending());assertEquals("FAILED",service.status().phase)
    }
    @Test fun `pause update preserves dirty upload tickets but waits for a live orphan Minecraft process`() {
        val service=mock(RunnerUpdater::class.java);`when`(service.pending()).thenReturn(true);`when`(service.status()).thenReturn(RunnerUpdateStatus("READY","0.4.5"))
        val id=java.util.UUID.randomUUID().toString();val folder=temp.resolve("instances/$id").createDirectories()
        val process=ProcessHandle.current();val dirty="${process.pid()}|${process.info().startInstant().orElseThrow().toEpochMilli()}"
        folder.resolve("dirty").writeText(dirty);folder.resolve("folder-import.pending").writeText("fixture pending");folder.resolve("received").writeText("already received")
        val agent=RunnerAgent(temp,RunnerIdentity("https://localhost","fixture","fixture","unused","MAC"),service)
        try {
            `when`(service.busy()).thenReturn(true);assertFalse(agent.restartForUpdate());`when`(service.busy()).thenReturn(false)
            assertFalse(agent.restartForUpdate())
            agent.requestUpdate(true);verify(service,timeout(3000)).phase("PAUSING")
            assertFalse(agent.restartForUpdate());assertTrue(process.isAlive)
            folder.resolve("dirty").writeText("999999999|0")
            assertTrue(agent.restartForUpdate());verify(service).phase("RESTARTING")
            assertTrue(folder.resolve("dirty").exists());assertEquals("fixture pending",folder.resolve("folder-import.pending").readText());assertEquals("already received",folder.resolve("received").readText())
        } finally {agent.shutdownGracefully()}
    }
    @Test fun `wire telemetry rejects unsupported phases oversized counters and arbitrary latest URLs`() {
        assertFails {RunnerUpdateStatus("execute").validated()};assertFails {RunnerUpdateStatus("READY","https://evil.invalid/jar").validated()}
        assertFails {RunnerUpdateStatus("DOWNLOADING",downloadedBytes=2,totalBytes=1).validated()}
        assertFalse(RunnerUpdateSupport.supported("0.4.3"));assertTrue(RunnerUpdateSupport.supported("0.4.4"))
    }
    @Test fun `incoming selected update command dispatches exact model independent version and consent`() {
        val service=mock(RunnerUpdater::class.java);`when`(service.status()).thenReturn(RunnerUpdateStatus("CURRENT"))
        val agent=RunnerAgent(temp,RunnerIdentity("https://localhost","fixture","fixture","unused","MAC"),service)
        val socket=mock(java.net.http.WebSocket::class.java);agent.onOpen(socket)
        try {
            val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            agent.onText(socket,mapper.writeValueAsString(RunnerMessage("update-request",detail="WHEN_IDLE",command="0.7.0-beta.2",state="BETA_OK")),true)
            verify(service,timeout(3000)).install("0.7.0-beta.2",true,false)
            agent.onText(socket,mapper.writeValueAsString(RunnerMessage("update-request",detail="WHEN_IDLE",command="0.6.2",state="STABLE_ROLLBACK")),true)
            verify(service,timeout(3000)).install("0.6.2",false,true)
        }finally{agent.shutdownGracefully()}
    }
}
