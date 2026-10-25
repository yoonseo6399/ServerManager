package local.servermanager

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Properties

/** Query and mods such as Sable both use UDP. Never put Query on the game port. */
object QueryEndpoint {
    fun configure(properties:Properties,gamePort:Int,reserved:Set<Int> = emptySet()):Int {
        val loopback=InetAddress.getByName("127.0.0.1")
        val previous=properties.getProperty("query.port")?.toIntOrNull()
        val port=if(previous!=null && previous in 1024..65535 && previous!=gamePort && previous !in reserved &&
            runCatching {DatagramSocket(null).use {it.bind(InetSocketAddress(loopback,previous))}}.isSuccess) previous
        else (0 until 256).firstNotNullOfOrNull {
            val candidate=DatagramSocket(InetSocketAddress(loopback,0)).use {it.localPort}
            candidate.takeIf {it!=gamePort && it !in reserved}
        } ?: (1024..65535).firstOrNull {it!=gamePort && it !in reserved &&
            runCatching {DatagramSocket(null).use {socket->socket.bind(InetSocketAddress(loopback,it))}}.isSuccess
        } ?: error("사용 가능한 플레이어 조회 UDP 포트가 없습니다")
        properties.setProperty("enable-query","true")
        properties.setProperty("query.port",port.toString())
        return port
    }
}
