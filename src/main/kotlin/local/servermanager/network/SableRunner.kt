package local.servermanager

import com.sun.net.httpserver.HttpServer
import java.net.*
import java.net.http.*
import java.nio.ByteBuffer
import java.nio.file.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import kotlin.concurrent.thread
import kotlin.io.path.*

/** One authenticated data-only WSS alongside the control connection. No public listener on the runner. */
class SableRunner(private val client:HttpClient,private val url:String,private val headers:()->Map<String,String>,
    private val streamAtPort:(Int)->Pair<String,Int>?,private val portForStream:(Int)->Int?,
    private val register:(String,Int,String)->Boolean,private val directMode:Boolean=false):AutoCloseable {
    @Volatile var enabled=false
    private var web:WebSocket?=null
    private var outbox:DatagramOutbox?=null
    private val udp=ConcurrentHashMap<Int,DatagramSocket>()
    private val keys=ConcurrentHashMap<String,String>()
    private val httpPool=ThreadPoolExecutor(2,2,0L,TimeUnit.SECONDS,ArrayBlockingQueue(32),{r->Thread(r,"sable-local-bridge").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    private var bridge:HttpServer?=null
    @Synchronized fun connect() {
        if(directMode || web!=null)return
        val listener=object:WebSocket.Listener {
            private val frame=java.io.ByteArrayOutputStream()
            override fun onOpen(socket:WebSocket){socket.request(1)}
            override fun onBinary(socket:WebSocket,data:ByteBuffer,last:Boolean):CompletionStage<*> {
                if(frame.size()+data.remaining()>65511){socket.abort();return CompletableFuture.completedFuture(null)}
                frame.write(ByteArray(data.remaining()).also(data::get))
                if(last){val bytes=frame.toByteArray();frame.reset();receive(bytes)}
                socket.request(1);return CompletableFuture.completedFuture(null)
            }
            override fun onText(socket:WebSocket,data:CharSequence,last:Boolean):CompletionStage<*> {socket.request(1);return CompletableFuture.completedFuture(null)}
            override fun onError(socket:WebSocket,error:Throwable){disconnectData(socket)}
            override fun onClose(socket:WebSocket,statusCode:Int,reason:String):CompletionStage<*> {disconnectData(socket);return CompletableFuture.completedFuture(null)}
        }
        val builder=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        headers().forEach { (name,value)->builder.header(name,value) };builder.header("X-SM-Channel","sable-udp")
        val socket=builder.buildAsync(URI(url.replaceFirst("https://","wss://")+"/api/runners/connect"),listener).get(6,TimeUnit.SECONDS)
        web=socket;outbox=DatagramOutbox {bytes->runCatching {socket.sendBinary(ByteBuffer.wrap(bytes),true).get(1,TimeUnit.SECONDS);true}.getOrElse {socket.abort();false}}
    }
    private fun disconnectData(socket:WebSocket){synchronized(this){if(web===socket){web=null;outbox?.close();outbox=null;udp.values.forEach {it.close()};udp.clear()}}}
    private fun receive(bytes:ByteArray) {
        if(bytes.size !in 5..2052)return
        val frame=ByteBuffer.wrap(bytes);val stream=frame.int
        val port=portForStream(stream) ?: return
        if(!enabled)return
        val packet=ByteArray(frame.remaining()).also(frame::get)
        val socket=udp.computeIfAbsent(stream) {
            DatagramSocket(InetSocketAddress("127.0.0.1",0)).also {channel->
                channel.connect(InetAddress.getByName("127.0.0.1"),port);channel.soTimeout=1000
                thread(isDaemon=true,name="sable-loopback-$stream") {
                    val buffer=ByteArray(65508)
                    while(!channel.isClosed && portForStream(stream)!=null)try {
                        val response=DatagramPacket(buffer,buffer.size);channel.receive(response)
                        if(response.length<=65507)outbox?.offer(ByteBuffer.allocate(4+response.length).putInt(stream).put(buffer,0,response.length).array())
                    }catch(_:SocketTimeoutException){}catch(_:Exception){break}
                    udp.remove(stream,channel);channel.close()
                }
            }
        }
        runCatching {socket.send(DatagramPacket(packet,packet.size))}
    }
    @Synchronized private fun localBridge():HttpServer {
        bridge?.let {return it}
        val service=HttpServer.create(InetSocketAddress("127.0.0.1",0),8)
        service.executor=httpPool
        service.createContext("/register") {exchange->
            try {
                var status=403
                if(exchange.requestMethod=="POST" && exchange.requestURI.toString()=="/register" && enabled && (directMode || web!=null)) {
                    val body=exchange.requestBody.readNBytes(129)
                    val parts=String(body,Charsets.US_ASCII).split(' ')
                    val pair=parts.firstOrNull()?.toIntOrNull()?.let(streamAtPort)
                    val key=pair?.first?.let(keys::get)
                    val provided=exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
                    if(body.size<=128 && parts.size==2 && pair!=null && key!=null && MessageDigest.isEqual(key.toByteArray(),provided.toByteArray())) {
                        val token=runCatching {UUID.fromString(parts[1]).takeIf {it.toString()==parts[1]}}.getOrNull()
                        if(token!=null && register(pair.first,pair.second,token.toString()))status=204
                    }
                }
                exchange.sendResponseHeaders(status,-1)
            }catch(_:Exception){runCatching {exchange.sendResponseHeaders(503,-1)}}finally{exchange.close()}
        }
        service.start();bridge=service;return service
    }
    fun prepare(instance:Instance,directory:Path,builder:ProcessBuilder):String {
        val target=directory.resolve("mods/servermanager-sable-bridge-1.0.0.jar")
        val bytes=javaClass.getResourceAsStream("/sable/servermanager-sable-bridge.jar")?.use {it.readNBytes(1024*1024)}
        fun ours()=bytes!=null && target.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target) && target.fileSize()==bytes.size.toLong() && MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(target.readBytes()),MessageDigest.getInstance("SHA-256").digest(bytes))
        val supported=instance.loader=="NeoForge" && instance.minecraft=="1.21.1" && directory.resolve("mods").takeIf {it.isDirectory()}?.let {mods->
            mods.listDirectoryEntries("*.jar").take(2001).takeIf {it.size<=2000}?.mapNotNull {SableIdentity.version(it)}?.let {it.size==1 && it.single()=="2.0.6"}
        }==true
        if(!enabled || !supported) {
            if(ours()) {
                val archive=directory.resolve(".servermanager-bridge-history");archive.createDirectories()
                Files.move(target,archive.resolve("${System.currentTimeMillis()}-"+target.fileName),StandardCopyOption.ATOMIC_MOVE)
            }
            return if(enabled)"지원 대상은 NeoForge 1.21.1 / Sable 2.0.6입니다. TCP 대체 통신을 사용합니다." else "UDP 중계 OFF"
        }
        if(bytes==null)return "보조 모드가 포함되지 않은 빌드입니다. TCP 대체 통신을 사용합니다."
        check(!Files.isSymbolicLink(target.parent) && !Files.isSymbolicLink(target)) {"보조 모드 경로에 심볼릭 링크를 사용할 수 없습니다"}
        if(target.exists() && !ours())return "다른 내용의 보조 모드 파일이 있습니다. 원본을 보존하고 TCP 대체 통신을 사용합니다."
        if(!target.exists()) {
            val temp=Files.createTempFile(target.parent,".sable-bridge-",".tmp")
            try {Files.write(temp,bytes);Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE)}finally{Files.deleteIfExists(temp)}
        }
        connect()
        val key=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also {SecureRandom().nextBytes(it)})
        keys[instance.id]=key
        builder.environment()["SM_SABLE_BRIDGE_PORT"]=localBridge().address.port.toString()
        builder.environment()["SM_SABLE_BRIDGE_KEY"]=key
        return "READY"
    }
    fun heartbeat(){if(!directMode && enabled && web==null && keys.isNotEmpty())runCatching {connect()};web?.let {socket->runCatching {socket.sendPing(ByteBuffer.wrap(byteArrayOf(1))).orTimeout(2,TimeUnit.SECONDS)} }}
    fun closeStream(stream:Int){udp.remove(stream)?.close()}
    fun stopped(instance:String){keys.remove(instance)}
    @Synchronized fun disconnect(){web?.abort();web=null;outbox?.close();outbox=null;udp.values.forEach {it.close()};udp.clear()}
    override fun close(){disconnect();keys.clear();bridge?.stop(0);bridge=null;httpPool.shutdownNow()}
}

/** Only the supported Sable identity is read for bridge ABI safety. This never inspects dependencies. */
internal object SableIdentity {
    fun version(path:Path):String?=runCatching {
        require(path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        java.util.zip.ZipFile(path.toFile()).use {zip->
            val entry=zip.getEntry("META-INF/neoforge.mods.toml") ?: zip.getEntry("META-INF/mods.toml") ?: return@use null
            require(entry.size in 1..1048576)
            val text=zip.getInputStream(entry).use {it.readNBytes(1048577)};require(text.size<=1048576)
            val toml=org.tomlj.Toml.parse(String(text,Charsets.UTF_8));require(!toml.hasErrors())
            val mods=toml.getArray("mods") ?: return@use null
            (0 until mods.size()).map {mods.getTable(it)}.singleOrNull {it.getString("modId")=="sable"}?.getString("version")?.let {version->
                if(version=="${'$'}{file.jarVersion}")zip.getEntry("META-INF/MANIFEST.MF")?.let {manifest->
                    require(manifest.size in 1..1048576)
                    zip.getInputStream(manifest).use {input->val data=input.readNBytes(1048577);require(data.size<=1048576);java.util.jar.Manifest(data.inputStream()).mainAttributes.getValue("Implementation-Version")}
                } else version
            }
        }
    }.getOrNull()
}
