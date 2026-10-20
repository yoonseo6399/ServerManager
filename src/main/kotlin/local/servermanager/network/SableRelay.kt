package local.servermanager

import java.net.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Bounded, lossy datagram queue: network stalls must not become an unbounded latency buffer. */
class DatagramOutbox(private val write:(ByteArray)->Boolean):AutoCloseable {
    private data class Item(val at:Long,val bytes:ByteArray)
    private val queue=ArrayBlockingQueue<Item>(64)
    @Volatile private var open=true
    private val worker=thread(isDaemon=true,name="sable-datagrams") {
        while(open) {
            val item=try {queue.poll(200,java.util.concurrent.TimeUnit.MILLISECONDS)}catch(_:InterruptedException){null} ?: continue
            if(System.nanoTime()-item.at>100_000_000)continue
            if(!write(item.bytes)){close();break}
        }
    }
    fun offer(bytes:ByteArray):Boolean= open && queue.offer(Item(System.nanoTime(),bytes.copyOf()))
    override fun close(){open=false;queue.clear();worker.interrupt()}
}

data class SableRoute(val instance:String,val address:InetAddress)
data class SableRelayStatus(val state:String,val connections:Int=0,val dropped:Long=0,val message:String="")

/** Sable 2.0.6 AUTH is byte 3 + a Minecraft UTF-8 UUID. No UDP hostname or fanout is used. */
class SableRelay(private val authorize:(String,Int)->SableRoute?,private val forward:(String,ByteArray)->Boolean):AutoCloseable {
    private data class Binding(val runner:String,val stream:Int,val instance:String,val ip:InetAddress,val token:UUID,
        val created:Long=System.nanoTime(),var peer:InetSocketAddress?=null,var seen:Long=System.nanoTime(),var window:Long=0,var packets:Int=0,var returned:Boolean=false,var confirmed:Boolean=false)
    private val bindings=ConcurrentHashMap<UUID,Binding>()
    private val peers=ConcurrentHashMap<InetSocketAddress,Binding>()
    private val dropped=AtomicLong()
    @Volatile private var socket:DatagramSocket?=null
    @Volatile private var error:String?=null
    @Synchronized fun listen(port:Int,address:String="0.0.0.0") {
        if(socket?.isClosed==false)return
        try {
            val udp=DatagramSocket(null);try {udp.bind(InetSocketAddress(address,port));udp.soTimeout=1000}catch(e:Exception){udp.close();throw e}
            socket=udp;error=null
            thread(isDaemon=true,name="sable-public-udp") {
                val buffer=ByteArray(65508)
                while(!udp.isClosed)try {
                    val packet=DatagramPacket(buffer,buffer.size);udp.receive(packet)
                    receive(packet.socketAddress as InetSocketAddress,buffer.copyOf(packet.length))
                }catch(_:SocketTimeoutException){expire()}catch(_:Exception){if(!udp.isClosed)dropped.incrementAndGet()}
            }
        } catch(_:Exception){error="UDP 포트를 열 수 없습니다. TCP 대체 통신을 사용합니다."}
    }
    val localPort:Int? get()=socket?.takeIf {!it.isClosed}?.localPort
    fun register(runner:String,stream:Int,instance:String,token:UUID):Boolean {
        expire()
        if(socket?.isClosed!=false || bindings.size>=500)return false
        val route=authorize(runner,stream)?.takeIf {it.instance==instance} ?: return false
        if(bindings.containsKey(token))return false
        removeStream(runner,stream)
        return bindings.putIfAbsent(token,Binding(runner,stream,instance,route.address,token))==null
    }
    @Synchronized fun receive(peer:InetSocketAddress,bytes:ByteArray):Boolean {
        fun reject():Boolean {dropped.incrementAndGet();return false}
        if(bytes.isEmpty() || bytes.size>2048)return reject()
        val type=bytes[0].toInt() and 255
        val binding=if(type==3) {
            if(bytes.size!=38 || bytes[1].toInt()!=36)return reject()
            val text=String(bytes,2,36,Charsets.US_ASCII)
            val uuid=runCatching {UUID.fromString(text).takeIf {it.toString()==text}}.getOrNull() ?: return reject()
            bindings[uuid] ?: return reject()
        } else peers[peer] ?: return reject()
        val route=authorize(binding.runner,binding.stream)
        if(route?.instance!=binding.instance || route.address!=binding.ip || peer.address!=binding.ip)return reject()
        if(type!=3 && (type!=5 || bytes.size!=1))return reject()
        val now=System.nanoTime()
        if(binding.peer!=null && binding.peer!=peer)return reject()
        if(now-binding.window>1_000_000_000){binding.window=now;binding.packets=0}
        if(++binding.packets>64)return reject()
        if(type==3){val existing=peers.putIfAbsent(peer,binding);if(existing!=null && existing!==binding)return reject();binding.peer=peer}
        if(type==5 && binding.returned)binding.confirmed=true
        binding.seen=now
        if(!forward(binding.runner,ByteBuffer.allocate(4+bytes.size).putInt(binding.stream).put(bytes).array()))return reject()
        return true
    }
    fun downstream(runner:String,frame:ByteArray):Boolean {
        if(frame.size !in 5..65511)return false
        val buffer=ByteBuffer.wrap(frame);val stream=buffer.int
        val binding=bindings.values.firstOrNull {it.runner==runner && it.stream==stream} ?: return false
        val route=authorize(runner,stream) ?: return false
        if(route.instance!=binding.instance || route.address!=binding.ip)return false
        val peer=binding.peer ?: return false
        val payload=ByteArray(buffer.remaining()).also(buffer::get)
        if((payload[0].toInt() and 255) !in setOf(0,1,2,4))return false
        return runCatching {socket?.takeIf {!it.isClosed}?.send(DatagramPacket(payload,payload.size,peer)) ?: return false;binding.returned=true;true}.getOrDefault(false)
    }
    fun status(instance:String?=null)=SableRelayStatus(if(error!=null)"ERROR" else if(bindings.values.any {(instance==null || it.instance==instance) && it.confirmed})"CONNECTED" else "WAITING",
        bindings.values.count {(instance==null || it.instance==instance) && it.confirmed},dropped.get(),error ?: if(bindings.values.any {(instance==null || it.instance==instance) && it.confirmed}) "Sable UDP 왕복 통신을 확인했습니다." else "공유 게임 포트의 UDP 포워딩과 클라이언트 접속을 확인하세요. 미연결 시 TCP 대체 통신을 사용합니다.")
    fun removeStream(runner:String,stream:Int){bindings.values.filter {it.runner==runner && it.stream==stream}.forEach(::remove)}
    fun removeRunner(runner:String){bindings.values.filter {it.runner==runner}.forEach(::remove)}
    private fun remove(binding:Binding){bindings.remove(binding.token,binding);binding.peer?.let {peers.remove(it,binding)}}
    private fun expire(){val now=System.nanoTime();bindings.values.filter {now-(if(it.peer==null)it.created else it.seen)>30_000_000_000 || authorize(it.runner,it.stream)==null}.forEach(::remove)}
    override fun close(){socket?.close();socket=null;bindings.clear();peers.clear()}
}
