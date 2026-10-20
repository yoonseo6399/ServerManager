package local.servermanager

import java.net.http.WebSocket
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Real RunnerAgent controller in its own JVM; only its manager WSS transport is replaced by stdin/stdout. */
object RunnerHostControllerFixture {
    @JvmStatic fun main(args:Array<String>) {
        val home=Path.of(args[0]);val mapper=jacksonObjectMapper()
        val updater=RunnerUpdater(home,RunnerAgent.version())
        val agent=RunnerAgent(home,RunnerIdentity("http://127.0.0.1:1",args[1],"isolated","unused","MAC"),updater)
        agent.initializeController {println("HOST_INITIALIZED")}
        lateinit var socket:WebSocket
        socket=Proxy.newProxyInstance(WebSocket::class.java.classLoader,arrayOf(WebSocket::class.java)) {_,method,values ->
            when(method.name) {
                "sendText" -> {println("MSG ${values!![0]}");CompletableFuture.completedFuture(socket)}
                "sendBinary","sendPing","sendPong","sendClose" -> CompletableFuture.completedFuture(socket)
                "isInputClosed","isOutputClosed" -> false
                "getSubprotocol" -> ""
                "toString" -> "isolated manager transport"
                else -> null
            }
        } as WebSocket
        agent.onOpen(socket)
        println("CONTROLLER_READY");System.out.flush()
        System.`in`.bufferedReader().forEachLine {line ->
            when(line) {
                "UPDATE" -> {val apply=agent.restartForUpdate();println("UPDATE $apply");if(apply){agent.shutdownGracefully();kotlin.system.exitProcess(0)}}
                "HOST_FAILURE" -> {
                    val coordinator=RunnerAgent::class.java.getDeclaredField("coordinator").also {it.isAccessible=true}.get(agent) as UpdateCoordinator
                    coordinator.update(UpdateComponents.GAME_HOST,"previous",RunnerAgent.version(),"FAILED",error="fixture startup timeout")
                    println("HOST_FAILURE_ADDED")
                }
                "HOST_CHECK" -> {agent.tryReplaceHost();println("HOST_CHECKED")}
                "EXIT" -> {RunnerAgent::class.java.getDeclaredField("updatePauseRequested").also {it.isAccessible=true}.set(agent,true);agent.shutdownGracefully();kotlin.system.exitProcess(0)}
                else -> agent.onText(socket,line,true)
            }
        }
    }
}
