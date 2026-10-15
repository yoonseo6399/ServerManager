package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TcpRelayPeerTest {
    @Test @Timeout(15) fun `relay forwards opaque bidirectional bytes and stops accepting when disabled`() {
        ServerSocket(0).use {target->
            val pool=Executors.newSingleThreadExecutor()
            val bytes=ByteArray(128*1024) {(it%251).toByte()}
            val echo=pool.submit {
                target.accept().use {socket->socket.soTimeout=5000;val actual=socket.getInputStream().readNBytes(bytes.size);assertContentEquals(bytes,actual);socket.getOutputStream().write(actual);socket.getOutputStream().flush()}
            }
            lateinit var ingress:TcpRelayPeer;lateinit var central:TcpRelayPeer
            ingress=TcpRelayPeer({message->when(message.type) {"relay-open"->central.open(message.streamId!!,target.localPort);"relay-closed"->central.end(message.streamId!!)};true},{id,data,count->central.receive(id,data.copyOf(count));true})
            central=TcpRelayPeer({message->when(message.type) {"relay-opened"->ingress.ready(message.streamId!!);"relay-closed"->ingress.end(message.streamId!!)};true},{id,data,count->ingress.receive(id,data.copyOf(count));true})
            val port=ServerSocket(0).use {it.localPort}
            try {
                ingress.listen(port);ingress.listen(port)
                Socket("127.0.0.1",port).use {client->client.soTimeout=5000;client.getOutputStream().write(bytes);assertContentEquals(bytes,client.getInputStream().readNBytes(bytes.size))}
                echo.get(5,TimeUnit.SECONDS)
                ingress.listen(null)
                assertFails {Socket("127.0.0.1",port).close()}
            } finally {ingress.close();central.close();pool.shutdownNow()}
        }
    }
    @Test @Timeout(10) fun `unknown streams cannot write and occupied ports fail without closing another listener`() {
        val peer=TcpRelayPeer({true},{_,_,_->true})
        ServerSocket(0).use {occupied->
            try {assertFails {peer.listen(occupied.localPort)};peer.receive(-22,byteArrayOf(1));assertFalse(peer.busy());peer.open(1,occupied.localPort);assertFalse(peer.busy())}
            finally {peer.close()}
            assertFalse(occupied.isClosed)
        }
    }
}
