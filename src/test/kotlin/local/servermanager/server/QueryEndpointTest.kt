package local.servermanager

import org.junit.jupiter.api.Test
import java.net.*
import java.util.Properties
import kotlin.test.*

class QueryEndpointTest {
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
