package local.servermanager

import org.junit.jupiter.api.Test
import java.net.*
import java.util.Properties
import kotlin.test.*

class QueryEndpointTest {
    @Test fun `cloned query ports and planned voice or Sable ports cannot be reused before startup`() {
        val a=Properties();val first=QueryEndpoint.configure(a,25565)
        val clone=Properties().apply {setProperty("query.port",first.toString())}
        val second=QueryEndpoint.configure(clone,25566,setOf(first,24454,24455,25565,25566))
        assertNotEquals(first,second)
        val third=QueryEndpoint.configure(a,25565,setOf(first,second))
        assertTrue(third !in setOf(first,second,25565))
    }

    @Test fun `Query is independent of Sable game UDP and preserves a free dedicated port`() {
        DatagramSocket(InetSocketAddress("127.0.0.1",0)).use {sable->
            val props=Properties().apply {setProperty("query.port",sable.localPort.toString())}
            val query=QueryEndpoint.configure(props,sable.localPort)
            assertNotEquals(sable.localPort,query);assertEquals("true",props.getProperty("enable-query"))
            assertEquals(query,QueryEndpoint.configure(props,sable.localPort))
            DatagramSocket(InetSocketAddress("127.0.0.1",query)).use {
                val replacement=QueryEndpoint.configure(props,sable.localPort)
                assertNotEquals(query,replacement);assertNotEquals(sable.localPort,replacement)
            }
            sable.soTimeout=80
            assertNull(MinecraftQuery().currentCount(query))
            assertFailsWith<SocketTimeoutException>{sable.receive(DatagramPacket(ByteArray(128),128))}
        }
    }
}
