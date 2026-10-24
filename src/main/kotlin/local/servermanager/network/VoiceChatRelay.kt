package local.servermanager

import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** One public UDP port per Minecraft instance: voice packets contain no DNS routing name. */
data class VoiceChatRelayConfig(val instanceId:String,val publicPort:Int=24454,val localPort:Int=24454)
data class VoiceChatTarget(val instanceId:String,val localPort:Int,val publicAddress:String)
data class VoiceChatStatus(val state:String,val connections:Int=0,val dropped:Long=0,val message:String="")

internal object VoiceDatagram {
    const val MAX=8192
    fun encode(instance:String,bytes:ByteArray):ByteArray {
        require(instance.matches(Regex("[A-Za-z0-9-]{1,64}")) && bytes.size in 1..MAX)
        val id=instance.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(1+id.size+bytes.size).put(id.size.toByte()).put(id).put(bytes).array()
    }
    fun decode(bytes:ByteArray):Pair<String,ByteArray>? {
        if(bytes.size<3)return null
        val n=bytes[0].toInt() and 255
        if(n !in 1..64 || bytes.size-n-1 !in 1..MAX)return null
        val id=String(bytes,1,n,Charsets.US_ASCII)
        if(!id.matches(Regex("[A-Za-z0-9-]{1,64}")))return null
        return id to bytes.copyOfRange(1+n,bytes.size)
    }
}

/** Bounded UDP peers, admitted only while a real TCP player of that IP is on this instance. */
class VoiceChatPublicRelay(private val authorize:(String,InetAddress)->String?,private val forward:(String,Int,ByteArray)->Boolean):AutoCloseable {
    private data class Binding(val id:Int,val instance:String,val runner:String,val peer:InetSocketAddress,val socket:DatagramSocket,
        @Volatile var seen:Long=System.nanoTime(),var window:Long=0,var packets:Int=0,@Volatile var returned:Boolean=false)
    private val sockets=ConcurrentHashMap<String,DatagramSocket>()
    private val bindings=ConcurrentHashMap<Int,Binding>()
    private val peers=ConcurrentHashMap<Pair<String,InetSocketAddress>,Binding>()
    private val errors=ConcurrentHashMap<String,String>()
    private val drops=AtomicLong();private val ids=AtomicInteger()
    @Synchronized fun configure(config:List<VoiceChatRelayConfig>,address:String) {
        require(config.size<=64 && config.map {it.publicPort}.distinct().size==config.size && config.all {it.publicPort in 1024..65535 && it.instanceId.matches(Regex("[A-Za-z0-9-]{1,64}"))})
        val desired=config.associateBy {it.instanceId}
        sockets.entries.filter {desired[it.key]?.publicPort!=it.value.localPort}.forEach {(id,socket)->socket.close();sockets.remove(id,socket);clear(id)}
        errors.keys.removeIf {it !in desired}
        config.forEach {c->
            if(sockets[c.instanceId]?.isClosed==false)return@forEach
            try {
                val socket=DatagramSocket(null)
                try {socket.bind(InetSocketAddress(address,c.publicPort));socket.soTimeout=1000}catch(e:Exception){socket.close();throw e}
                sockets[c.instanceId]=socket;errors.remove(c.instanceId)
                thread(isDaemon=true,name="voicechat-public-udp") {
                    val buffer=ByteArray(VoiceDatagram.MAX+1)
                    while(!socket.isClosed)try {
                        val packet=DatagramPacket(buffer,buffer.size);socket.receive(packet)
                        receive(c.instanceId,socket,packet.socketAddress as InetSocketAddress,buffer.copyOf(packet.length))
                    }catch(_:SocketTimeoutException){expire()}catch(_:Exception){if(!socket.isClosed)drops.incrementAndGet()}
                }
            }catch(_:Exception){errors[c.instanceId]="음성 UDP ${c.publicPort} 포트를 열지 못했습니다"}
        }
    }
    @Synchronized private fun receive(instance:String,socket:DatagramSocket,peer:InetSocketAddress,bytes:ByteArray) {
        fun reject(){drops.incrementAndGet()}
        if(bytes.size !in 1..VoiceDatagram.MAX)return reject()
        val runner=authorize(instance,peer.address) ?: return reject()
        expire()
        val key=instance to peer
        var binding=peers[key]
        if(binding!=null && binding.runner!=runner){remove(binding);binding=null}
        if(binding==null){
            if(bindings.size>=512)return reject()
            binding=Binding(ids.incrementAndGet().also {check(it>0)},instance,runner,peer,socket)
            bindings[binding.id]=binding;peers[key]=binding
        }
        val now=System.nanoTime()
        if(now-binding.window>=1_000_000_000){binding.window=now;binding.packets=0}
        if(++binding.packets>200)return reject()
        binding.seen=now
        if(!forward(runner,binding.id,VoiceDatagram.encode(instance,bytes)))reject()
    }
    fun downstream(runner:String,session:Int,bytes:ByteArray):Boolean {
        val b=bindings[session] ?: return false
        if(b.runner!=runner || bytes.size !in 1..VoiceDatagram.MAX || authorize(b.instance,b.peer.address)!=runner)return false
        return runCatching {b.socket.send(DatagramPacket(bytes,bytes.size,b.peer));b.returned=true;true}.getOrDefault(false)
    }
    fun statuses():Map<String,VoiceChatStatus> = (sockets.keys+errors.keys).associateWith {id->
        VoiceChatStatus(if(errors[id]!=null)"ERROR" else if(bindings.values.any {it.instance==id && it.returned})"CONNECTED" else "LISTENING",
            bindings.values.count {it.instance==id && it.returned},drops.get(),errors[id] ?: "UDP 수신 대기. 외부 포워딩과 실제 음성 연결은 별도 확인이 필요합니다")
    }
    fun removeRunner(runner:String){bindings.values.filter {it.runner==runner}.forEach(::remove)}
    private fun clear(instance:String){bindings.values.filter {it.instance==instance}.forEach(::remove)}
    private fun remove(b:Binding){bindings.remove(b.id,b);peers.remove(b.instance to b.peer,b)}
    private fun expire(){val now=System.nanoTime();bindings.values.filter {now-it.seen>30_000_000_000 || authorize(it.instance,it.peer.address)!=it.runner}.forEach(::remove)}
    override fun close(){sockets.values.forEach {it.close()};sockets.clear();bindings.clear();peers.clear();errors.clear()}
}

/** Per-UDP-peer connected loopback socket; destination comes exclusively from the approved instance settings. */
class VoiceChatAgentRelay(private val targets:()->List<VoiceChatTarget>,private val ready:(String)->Boolean,private val forward:(Int,ByteArray)->Boolean):AutoCloseable {
    private data class Peer(val instance:String,val port:Int,val socket:DatagramSocket,@Volatile var seen:Long=System.nanoTime())
    private val peers=ConcurrentHashMap<Int,Peer>()
    fun receive(session:Int,payload:ByteArray) {
        val (instance,bytes)=VoiceDatagram.decode(payload) ?: return
        val target=targets().singleOrNull {it.instanceId==instance} ?: return
        if(!ready(instance) || target.localPort !in 1024..65535 || session<=0)return
        peers.entries.filter {it.value.instance !in targets().map {t->t.instanceId} || System.nanoTime()-it.value.seen>30_000_000_000}.forEach {(id,p)->if(peers.remove(id,p))p.socket.close()}
        val existing=peers[session]
        if(existing!=null && (existing.instance!=instance || existing.port!=target.localPort))return
        if(existing==null && peers.size>=512)return
        val peer=peers.computeIfAbsent(session) {
            val socket=DatagramSocket(InetSocketAddress("127.0.0.1",0));socket.connect(InetAddress.getByName("127.0.0.1"),target.localPort);socket.soTimeout=1000
            Peer(instance,target.localPort,socket).also {p->
                thread(isDaemon=true,name="voicechat-loopback-udp") {
                    val buffer=ByteArray(VoiceDatagram.MAX+1)
                    try {while(!socket.isClosed){
                        if(System.nanoTime()-p.seen>30_000_000_000 || !ready(instance) || targets().none {t->t.instanceId==instance && t.localPort==p.port})break
                        try {val packet=DatagramPacket(buffer,buffer.size);socket.receive(packet);if(packet.length in 1..VoiceDatagram.MAX)forward(session,buffer.copyOf(packet.length))}catch(_:SocketTimeoutException){}
                    }}catch(_:Exception){}finally {peers.remove(session,p);socket.close()}
                }
            }
        }
        peer.seen=System.nanoTime()
        runCatching {peer.socket.send(DatagramPacket(bytes,bytes.size))}
    }
    fun reconcile(){peers.entries.filter {(_,p)->targets().none {it.instanceId==p.instance && it.localPort==p.port}}.forEach {(id,p)->if(peers.remove(id,p))p.socket.close()}}
    override fun close(){peers.values.forEach {it.socket.close()};peers.clear()}
}
