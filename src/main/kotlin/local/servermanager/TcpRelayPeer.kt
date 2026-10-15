package local.servermanager

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One authenticated WSS session. Negative IDs keep relay traffic separate from game streams. */
class TcpRelayPeer(private val text:(RunnerMessage)->Boolean,private val binary:(Int,ByteArray,Int)->Boolean):AutoCloseable {
    private data class Stream(val socket:Socket,val ready:CountDownLatch=CountDownLatch(1))
    private val streams=ConcurrentHashMap<Int,Stream>()
    private val slots=Semaphore(32)
    private val ids=AtomicInteger(-1)
    fun busy()=streams.isNotEmpty()
    private val workers=Executors.newCachedThreadPool()
    @Volatile private var listener:ServerSocket?=null
    @Volatile private var closed=false
    @Synchronized fun listen(port:Int?) {
        check(!closed)
        require(port==null || port in 1024..65535)
        if(listener?.localPort==port && listener?.isClosed==false)return
        listener?.close();listener=null
        streams.keys.toList().forEach {end(it,true)}
        if(port==null)return
        val server=ServerSocket()
        try {server.bind(InetSocketAddress("0.0.0.0",port),32)} catch(e:Exception) {server.close();throw e}
        listener=server
        workers.submit {
            while(!closed && listener===server) {
                val socket=runCatching {server.accept()}.getOrNull() ?: break
                synchronized(this) {
                    if(closed || listener!==server || !slots.tryAcquire())socket.close()
                    else {
                        val id=ids.getAndDecrement()
                        if(id>=0){slots.release();socket.close()}
                        else {
                            val stream=Stream(socket);streams[id]=stream
                            workers.submit {pump(id,stream,true)}
                        }
                    }
                }
            }
        }
    }
    @Synchronized fun open(id:Int,port:Int) {
        if(closed || id>=0 || streams.containsKey(id) || !slots.tryAcquire()) {text(RunnerMessage("relay-closed",streamId=id));return}
        val stream=Stream(Socket());streams[id]=stream
        workers.submit {
            try {
                stream.socket.connect(InetSocketAddress("127.0.0.1",port),3000)
                stream.ready.countDown()
                if(!text(RunnerMessage("relay-opened",streamId=id)))return@submit
                pump(id,stream,false)
            } catch(_:Exception) {} finally {end(id,true)}
        }
    }
    fun ready(id:Int) {streams[id]?.ready?.countDown()}
    fun receive(id:Int,bytes:ByteArray) {
        val stream=streams[id] ?: return
        runCatching {stream.socket.getOutputStream().write(bytes)}.onFailure {end(id,true)}
    }
    private fun pump(id:Int,stream:Stream,request:Boolean) {
        try {
            if(request && (!text(RunnerMessage("relay-open",streamId=id)) || !stream.ready.await(5,TimeUnit.SECONDS)))return
            stream.socket.soTimeout=60000
            val buffer=ByteArray(64*1024)
            val input=stream.socket.getInputStream()
            while(!closed) {val count=input.read(buffer);if(count<0 || !binary(id,buffer,count))break}
        } catch(_:Exception) {} finally {end(id,true)}
    }
    fun end(id:Int,notify:Boolean=false) {
        val stream=streams.remove(id) ?: return
        stream.ready.countDown();runCatching {stream.socket.close()};slots.release()
        if(notify)text(RunnerMessage("relay-closed",streamId=id))
    }
    @Synchronized override fun close() {
        if(closed)return
        closed=true;runCatching {listener?.close()};listener=null
        streams.keys.toList().forEach {end(it)};workers.shutdownNow()
    }
}
