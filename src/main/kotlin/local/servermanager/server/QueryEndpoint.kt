package local.servermanager

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Properties

/** Query and mods such as Sable both use UDP. Never put Query on the game port. */
object QueryEndpoint {
    fun configure(properties:Properties,gamePort:Int):Int {
        val loopback=InetAddress.getByName("127.0.0.1")
        val previous=properties.getProperty("query.port")?.toIntOrNull()
        val port=if(previous!=null && previous in 1024..65535 && previous!=gamePort &&
            runCatching {DatagramSocket(null).use {it.bind(InetSocketAddress(loopback,previous))}}.isSuccess) previous
        else generateSequence {DatagramSocket(InetSocketAddress(loopback,0)).use {it.localPort}}.first {it!=gamePort}
        properties.setProperty("enable-query","true")
        properties.setProperty("query.port",port.toString())
        return port
    }
}
