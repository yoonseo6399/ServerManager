package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Test-only entry point used by DirectRelayProcessTest so manager, relay runner and game runner are three separate JVMs
 * that only share a JSON-lines control socket. Roles:
 *   manager <controlPort> <gamePort> <securePort> <storeDir> <relayId> <agentId> <instance> <host>
 *   relay   <home> <controlPort> <runnerId>
 *   agent   <home> <controlPort> <runnerId> <echoPortFile>
 */
object DirectRelayProcessMain {
    private val mapper=jacksonObjectMapper()
    @JvmStatic fun main(args:Array<String>) {
        when(args[0]) {"manager"->manager(args);"relay"->runner(args,false);"agent"->runner(args,true)}
    }

    private fun manager(a:Array<String>) {
        val (controlPort,gamePort,securePort)=Triple(a[1].toInt(),a[2].toInt(),a[3].toInt())
        val store=DirectRelayStore(Path.of(a[4]));val relayId=a[5];val agentId=a[6];val instance=a[7];val host=a[8]
        val inBytes=AtomicLong();val outBytes=AtomicLong()
        val writers=ConcurrentHashMap<String,PrintWriter>()
        val world=object:RelayWorld {
            override fun runner(id:String)=RunnerDevice(id,id.take(4),"MAC","owner","key","now",allowedInstanceIds=setOf(instance))
            override fun connected(id:String)=writers.containsKey(id)
            override fun runnerName(id:String)=id.take(4)
            override fun routes()=listOf(Triple(instance,agentId,listOf(host)))
            override fun send(runnerId:String,message:RunnerMessage):Boolean {
                val w=writers[runnerId] ?: return false;val line=mapper.writeValueAsString(message)
                outBytes.addAndGet(line.length+1L);synchronized(w){w.println(line);return !w.checkError()}
            }
            override fun resolveHost(host:String)=listOf("203.0.113.9")
            override fun gamePort()=gamePort
        }
        val core=DirectRelayManager(store,world)
        val server=ServerSocket(controlPort)
        Thread {while(true){val s=server.accept();Thread {
            var id:String?=null
            try {
                val reader=BufferedReader(InputStreamReader(s.getInputStream()));val writer=PrintWriter(s.getOutputStream(),true)
                id=reader.readLine().removePrefix("HELLO ");writers[id]=writer
                while(true){val line=reader.readLine() ?: break;inBytes.addAndGet(line.length+1L);core.onRunnerMessage(id,mapper.readValue<RunnerMessage>(line))}
            } catch(_:Exception){} finally {id?.let {core.onDisconnect(it);writers.remove(it)};runCatching {s.close()}}
        }.start()}}.also {it.isDaemon=true}.start()
        Thread {while(true){runCatching {core.reconcile()};Thread.sleep(300)}}.also {it.isDaemon=true}.start()
        println("READY");System.out.flush()
        val input=BufferedReader(InputStreamReader(System.`in`))
        while(true) {
            when(input.readLine() ?: return) {
                "ENABLE" -> {val limit=System.currentTimeMillis()+20000;while((core.consent(relayId)?.allowed!=true || !world.connected(relayId)) && System.currentTimeMillis()<limit)Thread.sleep(100);core.save(DirectRelaySave(DirectRelayConfig(true,relayId,null,securePort,"127.0.0.1","relay.example.com"),store.revision()));println("ENABLED")}
                "STATUS" -> println("STATUS "+mapper.writeValueAsString(mapOf("in" to inBytes.get(),"out" to outBytes.get(),"ready" to core.isReady(relayId),"phase" to core.view().phase,
                    "gameListenerInManager" to runCatching {Socket("127.0.0.1",gamePort).use {true}}.getOrDefault(false))))
            }
            System.out.flush()
        }
    }

    private fun runner(a:Array<String>,agent:Boolean) {
        val home=Path.of(a[1]);val controlPort=a[2].toInt();val runnerId=a[3]
        var echo:ServerSocket?=null
        if(agent) {
            echo=ServerSocket(0);Files.writeString(Path.of(a[4]),echo.localPort.toString())
            Thread {while(true){val s=echo.accept();Thread {runCatching {val b=ByteArray(65536);val i=s.getInputStream();val o=s.getOutputStream();while(true){val n=i.read(b);if(n<0)break;o.write(b,0,n);o.flush()}}}.start()}}.also {it.isDaemon=true}.start()
        }
        val out=java.util.concurrent.atomic.AtomicReference<PrintWriter?>()
        val role=DirectRelayRunnerRole(home,{m->val w=out.get();if(w==null)false else synchronized(w){w.println(mapper.writeValueAsString(m));!w.checkError()}},
            {if(agent)echo!!.localPort else null},{agent},RelayConsentStore(home),longArrayOf(100,200,500),"127.0.0.1")
        println("READY");System.out.flush()
        Thread {while(true){Thread.sleep(500);runCatching {role.tick()}}}.also {it.isDaemon=true}.start()
        while(true) {
            try {
                val s=Socket("127.0.0.1",controlPort);val w=PrintWriter(s.getOutputStream(),true);w.println("HELLO $runnerId");out.set(w)
                role.onSession()
                val reader=BufferedReader(InputStreamReader(s.getInputStream()))
                while(true){val line=reader.readLine() ?: break;role.onMessage(mapper.readValue<RunnerMessage>(line))}
            } catch(_:Exception){}
            out.set(null);Thread.sleep(300)
        }
    }
}
