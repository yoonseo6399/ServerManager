package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.net.InetSocketAddress
import java.net.http.WebSocket
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*
import kotlin.test.*

class RunnerReceiptRecoveryTest {
    @TempDir lateinit var home:Path
    private fun field(agent:RunnerAgent,name:String)=RunnerAgent::class.java.getDeclaredField(name).also {it.isAccessible=true}
    @Test fun `real HTTP 503 keeps dirty and receipt then verified retry clears them without reupload`() {
        val id="11111111-1111-1111-1111-111111111111";val transfer="22222222-2222-2222-2222-222222222222"
        val folder=home.resolve("instances/$id").createDirectories();val base=folder.resolve("server").createDirectory()
        base.resolve("world.dat").writeBytes(ByteArray(2*1024*1024){(it%251).toByte()})
        folder.resolve("dirty").writeText("${ProcessHandle.current().pid()}|0");folder.resolve("upload.pending").writeText(transfer)
        val manifest=FileInventory.collect(base).let {(f,d)->RunnerFileManifest(f,d)}
        val requests=java.util.concurrent.CopyOnWriteArrayList<String>();var pending=true;var verifying=1
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") {req->
            requests.add(req.requestMethod+" "+req.requestURI.path)
            assertEquals("device",req.requestHeaders.getFirst("X-SM-Runner-Id"));assertEquals("fixture-session",req.requestHeaders.getFirst("X-SM-Session"))
            val status=if(req.requestURI.path.endsWith("/manifest")) {if(pending)503 else if(verifying-->0)202 else 200} else 200
            val body=when {req.requestURI.path.endsWith("/manifest")->jacksonObjectMapper().writeValueAsBytes(manifest);req.requestURI.path.endsWith("/transfer-state")->"{\"state\":\"COMPLETE\"}".toByteArray();else->"{}".toByteArray()}
            if(status==503)req.responseHeaders.add("Retry-After","60")
            if(status==202)req.responseHeaders.add("Retry-After","1")
            req.sendResponseHeaders(status,body.size.toLong());req.responseBody.use {it.write(body)}
        }
        server.start()
        try {
            val agent=RunnerAgent(home,RunnerIdentity("http://127.0.0.1:${server.address.port}","device","fixture","unused","MAC"))
            field(agent,"sessionToken").set(agent,"fixture-session")
            val socket=mock(WebSocket::class.java);val messages=java.util.concurrent.CopyOnWriteArrayList<String>()
            doAnswer {call->messages.add(call.arguments[0].toString());CompletableFuture.completedFuture(socket)}.`when`(socket).sendText(any(CharSequence::class.java),anyBoolean())
            @Suppress("UNCHECKED_CAST") val connection=field(agent,"connection").get(agent) as AtomicReference<WebSocket?>;connection.set(socket)
            val upload=RunnerAgent::class.java.getDeclaredMethod("uploadStopped",String::class.java).also {it.isAccessible=true}
            upload.invoke(agent,id)
            assertTrue(folder.resolve("dirty").exists());assertEquals(transfer,folder.resolve("upload.pending").readText())
            assertTrue(messages.any {it.contains("HTTP 503") && it.contains("RETRYING")})
            pending=false;upload.invoke(agent,id)
            assertFalse(folder.resolve("dirty").exists());assertFalse(folder.resolve("upload.pending").exists())
            assertEquals(3,requests.count {it.endsWith("/manifest")});assertFalse(requests.any {it.endsWith("/begin")})
            assertTrue(messages.any {it.contains("STOPPED")&&it.contains("이미 완료된 파일 인계")})
            assertEquals(manifest.files.getValue("world.dat").sha256,FileInventory.digest(base.resolve("world.dat")))
        } finally {server.stop(0)}
    }
    @Test fun `stopped upload shares the start operation lock and retains dirty until verification completes`() {
        val id="33333333-3333-3333-3333-333333333333";val receipt="44444444-4444-4444-4444-444444444444"
        val folder=home.resolve("instances/$id").createDirectories();folder.resolve("server").createDirectory()
        folder.resolve("dirty").writeText("${ProcessHandle.current().pid()}|0");folder.resolve("upload.pending").writeText(receipt)
        val agent=RunnerAgent(home,RunnerIdentity("http://127.0.0.1:1","device","fixture","unused","MAC"))
        field(agent,"sessionToken").set(agent,"fixture-session")
        @Suppress("UNCHECKED_CAST") val locks=field(agent,"operationLocks").get(agent) as java.util.concurrent.ConcurrentHashMap<String,Any>
        @Suppress("UNCHECKED_CAST") val uploading=field(agent,"uploading").get(agent) as Set<String>
        val method=RunnerAgent::class.java.getDeclaredMethod("uploadStopped",String::class.java).also {it.isAccessible=true}
        val finished=java.util.concurrent.CountDownLatch(1);val failure=AtomicReference<Throwable?>()
        val thread=Thread {try{method.invoke(agent,id)}catch(e:Throwable){failure.set(e)}finally{finished.countDown()}}
        synchronized(locks.computeIfAbsent(id){Any()}) {
            thread.start()
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while(id !in uploading && System.nanoTime()<deadline)Thread.sleep(5)
            assertTrue(id in uploading)
            assertFalse(finished.await(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            assertTrue(folder.resolve("dirty").exists());assertEquals(receipt,folder.resolve("upload.pending").readText())
        }
        assertTrue(finished.await(5,java.util.concurrent.TimeUnit.SECONDS));assertNull(failure.get())
        assertFalse(id in uploading);assertTrue(folder.resolve("dirty").exists())
    }
    @Test fun `idle reason is negotiated with old and new central versions while local report remains intact`() {
        for(protocol in listOf(0,1)) {
            val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);var received:String?=null
            server.createContext("/") {req->
                val body=if(req.requestURI.path.endsWith("/capabilities"))"{\"exitReasonProtocol\":$protocol}" else {received=req.requestBody.readBytes().toString(Charsets.UTF_8);"{}"}
                val bytes=body.toByteArray();req.sendResponseHeaders(200,bytes.size.toLong());req.responseBody.use {it.write(bytes)}
            };server.start()
            try {
                val agent=RunnerAgent(home,RunnerIdentity("http://127.0.0.1:${server.address.port}","device","fixture","unused","MAC"))
                field(agent,"sessionToken").set(agent,"fixture-session")
                val send=RunnerAgent::class.java.getDeclaredMethod("sendExitReport",String::class.java,String::class.java).also {it.isAccessible=true}
                val original=jacksonObjectMapper().writeValueAsString(RunnerExitReport(0,reason="IDLE"))
                send.invoke(agent,"fixture",original)
                val report=jacksonObjectMapper().readTree(received)
                assertEquals(if(protocol==1)"IDLE" else "",report.path("reason").asText())
                assertContains(original,"IDLE")
            }finally{server.stop(0)}
        }
    }

}
