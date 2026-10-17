package local.servermanager

import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.test.*

class MinecraftQueryTest {
    @Test fun `basic count validates complete status and rejects truncation and inconsistent full stat zero`() {
        fun basic(count:String)=byteArrayOf(0,1,2,3,4)+"fixture\u0000SMP\u0000world\u0000$count\u000020\u0000".toByteArray()+byteArrayOf(0xdd.toByte(),0x63)+"127.0.0.1\u0000".toByteArray()
        assertEquals(0,MinecraftQuery.parseBasicCount(basic("0")))
        assertEquals(1,MinecraftQuery.parseBasicCount(basic("1")))
        assertNull(MinecraftQuery.parseBasicCount(basic("-1")))
        assertNull(MinecraftQuery.parseBasicCount(basic("0").dropLast(1).toByteArray()))
        val inconsistent=byteArrayOf(0,1,2,3,4)+ByteArray(11)+"hostname\u0000test\u0000numplayers\u00000\u0000".toByteArray()+"\u0000\u0000\u0001player_\u0000\u0000Alex\u0000".toByteArray()
        assertNull(MinecraftQuery.parseFullStat(inconsistent))
    }
    @Test fun `full stat returns count and names only when a complete response is present`() {
        val metadata="hostname\u0000Test\u0000numplayers\u00002\u0000maxplayers\u000020\u0000".toByteArray()
        val marker="\u0000\u0000\u0001player_\u0000\u0000".toByteArray()
        val packet=byteArrayOf(0,1,2,3,4)+ByteArray(11)+metadata+marker+"Alice\u0000Bob\u0000".toByteArray()
        val sample=MinecraftQuery.parseFullStat(packet)
        assertEquals(2,sample?.count)
        assertEquals(listOf("Alice","Bob"),sample?.names)
        assertNull(MinecraftQuery.parseFullStat(packet.copyOfRange(0,packet.size-16)))
        assertNull(MinecraftQuery.parseFullStat(byteArrayOf()))
    }
    @Test fun `query exchanges challenge and full stat over loopback UDP`() {
        DatagramSocket(0,java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout=3000
            val responder=Thread {
                repeat(2) { round ->
                    val request=DatagramPacket(ByteArray(128),128)
                    server.receive(request)
                    val bytes=request.data.copyOf(request.length)
                    assertEquals(0xfe,bytes[0].toInt() and 0xff)
                    assertEquals(if(round==0) 9 else 0,bytes[2].toInt())
                    val session=bytes.copyOfRange(3,7)
                    assertTrue(session.all {it.toInt() in 0..15})
                    val body=if(round==0) "12345\u0000".toByteArray() else {
                        val metadata="hostname\u0000Test\u0000numplayers\u00001\u0000".toByteArray()
                        ByteArray(11)+metadata+"\u0000\u0000\u0001player_\u0000\u0000Alex\u0000".toByteArray()
                    }
                    val response=if(round==0)("\u0009"+session.toString(Charsets.UTF_8)+"12345\u0000").toByteArray(Charsets.UTF_8) else byteArrayOf(0)+session+body
                    server.send(DatagramPacket(response,response.size,request.address,request.port))
                }
            }
            responder.start()
            val result=MinecraftQuery().players(server.localPort)
            responder.join(3000)
            assertFalse(responder.isAlive)
            assertEquals(1,result?.count)
            assertEquals(listOf("Alex"),result?.names)
        }
    }
}
