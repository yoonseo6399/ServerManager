package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.net.*
import java.net.http.*
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.*
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.test.*

class SableRunnerTest {
    @TempDir lateinit var temp:Path
    @Test fun `server only bridge authenticates locally and data socket forwards exact isolated UDP packets`() {
        val mods=temp.resolve("mods").also {it.createDirectories()}
        ZipOutputStream(mods.resolve("renamed.jar").outputStream()).use {z->z.putNextEntry(ZipEntry("META-INF/neoforge.mods.toml"));z.write("modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"sable\"\nversion=\"2.0.6\"\n".toByteArray());z.closeEntry()}
        val client=mock(HttpClient::class.java);val builder=mock(WebSocket.Builder::class.java);val web=mock(WebSocket::class.java)
        val listener=AtomicReference<WebSocket.Listener>();val sent=LinkedBlockingQueue<ByteArray>()
        `when`(client.newWebSocketBuilder()).thenReturn(builder);`when`(builder.connectTimeout(any())).thenReturn(builder)
        `when`(builder.header(anyString(),anyString())).thenReturn(builder)
        doAnswer {call->listener.set(call.arguments[1] as WebSocket.Listener);CompletableFuture.completedFuture(web)}.`when`(builder).buildAsync(any(),any())
        doAnswer {call->val b=(call.arguments[0] as ByteBuffer).slice();sent.add(ByteArray(b.remaining()).also(b::get));CompletableFuture.completedFuture(web)}.`when`(web).sendBinary(any(),eq(true))
        val tokens=LinkedBlockingQueue<String>()
        DatagramSocket(InetSocketAddress("127.0.0.1",0)).use {server->
            SableRunner(client,"https://localhost",{mapOf("X-SM-Runner-Id" to "fixture")},{if(it==12345)"a" to 7 else null},{if(it==7)server.localPort else null},{id,stream,token->assertEquals("a",id);assertEquals(7,stream);tokens.add(token);true}).use {runner->
                runner.enabled=true
                val instance=Instance("a","fixture",loaderVersion="21.1.249",port=25565,memoryMb=512,javaPath="java")
                val process=ProcessBuilder("java")
                assertEquals("READY",runner.prepare(instance,temp,process));assertTrue(mods.resolve("servermanager-sable-bridge-1.0.0.jar").exists())
                val port=process.environment().getValue("SM_SABLE_BRIDGE_PORT");val secret=process.environment().getValue("SM_SABLE_BRIDGE_KEY")
                val token=UUID.randomUUID().toString()
                fun request(key:String,body:String):Int=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/register")).header("Authorization","Bearer $key").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()
                assertEquals(403,request("bad","12345 $token"));assertTrue(tokens.isEmpty())
                assertEquals(403,request(secret,"12346 $token"));assertEquals(204,request(secret,"12345 $token"));assertEquals(token,tokens.poll())
                runner.disconnect();runner.connect();assertEquals(204,request(secret,"12345 $token"));assertEquals(token,tokens.poll())
                server.soTimeout=2000
                val response=thread {
                    val packet=DatagramPacket(ByteArray(128),128);server.receive(packet)
                    assertContentEquals(byteArrayOf(3,36)+token.toByteArray(),packet.data.copyOf(packet.length))
                    server.send(DatagramPacket(byteArrayOf(4),1,packet.socketAddress))
                }
                val frame=ByteBuffer.allocate(42).putInt(7).put(3).put(36).put(token.toByteArray()).array()
                listener.get().onBinary(web,ByteBuffer.wrap(frame,0,10),false)
                listener.get().onBinary(web,ByteBuffer.wrap(frame,10,32),true)
                assertContentEquals(ByteBuffer.allocate(5).putInt(7).put(4).array(),sent.poll(3,TimeUnit.SECONDS))
                response.join(3000);assertFalse(response.isAlive)
                runner.closeStream(7);runner.stopped("a");assertEquals(403,request(secret,"12345 $token"))
                runner.enabled=false
                assertEquals("UDP 중계 OFF",runner.prepare(instance,temp,ProcessBuilder("java")))
                assertFalse(mods.resolve("servermanager-sable-bridge-1.0.0.jar").exists());assertTrue(mods.resolve("renamed.jar").exists())
            }
        }
    }
}
