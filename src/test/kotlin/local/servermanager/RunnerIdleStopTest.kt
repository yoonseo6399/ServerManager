package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.http.WebSocket
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class RunnerIdleStopTest {
    @TempDir lateinit var temp:Path
    @Suppress("UNCHECKED_CAST") private fun <T> field(agent:RunnerAgent,name:String):T=RunnerAgent::class.java.getDeclaredField(name).also {it.isAccessible=true}.get(agent) as T
    @Test fun `worker confirms actual zero and writes only normal stop while occupied unknown joining and unready refuse`() {
        for(mode in listOf("empty","occupied","unknown","joining","unready")) {
            DatagramSocket(0,InetAddress.getByName("127.0.0.1")).use {server->
                val agent=RunnerAgent(temp.resolve(mode),RunnerIdentity("https://localhost:1","device","fixture","not-used","MAC"))
                val process=mock(Process::class.java);val output=ByteArrayOutputStream();`when`(process.isAlive).thenReturn(true);`when`(process.outputStream).thenReturn(output)
                field<ConcurrentHashMap<String,Process>>(agent,"processes")["11111111-1111-1111-1111-111111111111"]=process
                field<ConcurrentHashMap<String,Instance>>(agent,"configs")["11111111-1111-1111-1111-111111111111"]=Instance("11111111-1111-1111-1111-111111111111","fixture",loaderVersion="21.1.252",port=server.localPort,memoryMb=512,javaPath="java",runnerId="device")
                if(mode!="unready")field<MutableSet<String>>(agent,"ready").add("11111111-1111-1111-1111-111111111111")
                field<ConcurrentHashMap<String,Int>>(agent,"queryPorts")["11111111-1111-1111-1111-111111111111"]=server.localPort
                val counter=AtomicLong();field<ConcurrentHashMap<String,AtomicLong>>(agent,"activity")["11111111-1111-1111-1111-111111111111"]=counter
                val socket=mock(WebSocket::class.java);val messages=mutableListOf<String>()
                doAnswer {call->messages.add(call.arguments[0].toString());CompletableFuture.completedFuture(socket)}.`when`(socket).sendText(any(CharSequence::class.java),anyBoolean())
                field<AtomicReference<WebSocket?>>(agent,"connection").set(socket)
                server.soTimeout=3000
                val responder=if(mode in setOf("empty","occupied","joining"))thread {
                    repeat(2){round->
                        val request=DatagramPacket(ByteArray(128),128);server.receive(request)
                        assertEquals(if(round==0)7 else 11,request.length)
                        val session=request.data.copyOfRange(3,7)
                        val body=if(round==0)"12345\u0000".toByteArray() else {
                            if(mode=="joining")counter.incrementAndGet()
                            val count=if(mode=="occupied")1 else 0
                            "fixture\u0000SMP\u0000world\u0000$count\u000020\u0000".toByteArray()+byteArrayOf((server.localPort and 255).toByte(),(server.localPort ushr 8).toByte())+"127.0.0.1\u0000".toByteArray()
                        }
                        val response=byteArrayOf(if(round==0)9 else 0)+session+body
                        server.send(DatagramPacket(response,response.size,request.address,request.port))
                    }
                } else null
                val stop=RunnerAgent::class.java.getDeclaredMethod("stopIfEmpty",String::class.java,String::class.java).also {it.isAccessible=true}
                stop.invoke(agent,"11111111-1111-1111-1111-111111111111","request-ticket");responder?.join(3000)
                assertEquals(if(mode=="empty")"stop\n" else "",output.toString(),mode)
                assertEquals(mode=="empty","11111111-1111-1111-1111-111111111111" in field<Set<String>>(agent,"idleStops"),mode)
                verify(process,never()).destroyForcibly()
                val result=messages.single {it.contains("idle-result")}
                assertContains(result,if(mode=="empty")"ACCEPTED" else "DENIED",message=mode);assertContains(result,"request-ticket")
                assertFalse(responder?.isAlive==true)
                field<Map<String,StopMonitor>>(agent,"stopMonitors").values.forEach {it.close()}
            }
        }
    }
}
