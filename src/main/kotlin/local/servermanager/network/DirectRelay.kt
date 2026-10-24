package local.servermanager

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.interfaces.XECPublicKey

/**
 * Direct TCP relay (DRP1): player -> relay runner (public game port) -> encrypted tunnel opened by the game runner -> game.
 * The manager only issues tickets and policy over its authenticated WSS channel; it never carries game bytes.
 *
 * A ticket is `body` (public) plus `key = HMAC(relaySecret, body)`. Only the manager (which gave `relaySecret` to the relay)
 * can mint it, and the key itself never crosses the tunnel socket, so both ends prove possession inside the handshake.
 */
data class RelayTicketBody(val relayId:String,val agentId:String,val epoch:Long,val expiresAt:Long,val nonce:String) {
    fun encode()="drp1|$relayId|$agentId|$epoch|$expiresAt|$nonce"
    companion object {
        private val uuid=Regex("[a-f0-9-]{36}")
        fun parse(value:String):RelayTicketBody? {
            val p=value.split('|');if(p.size!=6 || p[0]!="drp1" || value.length>300)return null
            if(!uuid.matches(p[1]) || !uuid.matches(p[2]) || !Regex("[A-Za-z0-9_-]{16,64}").matches(p[5]))return null
            return RelayTicketBody(p[1],p[2],p[3].toLongOrNull() ?: return null,p[4].toLongOrNull() ?: return null,p[5])
        }
    }
}
class RelayTicket(val body:String,val key:ByteArray)

object DirectRelayCrypto {
    private val random=SecureRandom()
    fun hmac(key:ByteArray,vararg parts:ByteArray):ByteArray=Mac.getInstance("HmacSHA256").run {init(SecretKeySpec(key,"HmacSHA256"));parts.forEach(::update);doFinal()}
    fun ticketKey(relaySecret:ByteArray,body:String)=hmac(relaySecret,"drp1-ticket".toByteArray(),body.toByteArray())
    fun newSecret()=ByteArray(32).also(random::nextBytes)
    fun issue(relaySecret:ByteArray,relayId:String,agentId:String,epoch:Long,ttlMillis:Long,now:Long=System.currentTimeMillis()):RelayTicket {
        val nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also(random::nextBytes))
        val body=RelayTicketBody(relayId,agentId,epoch,now+ttlMillis,nonce).encode()
        return RelayTicket(body,ticketKey(relaySecret,body))
    }
    fun ephemeral():Pair<java.security.PrivateKey,ByteArray> {
        val pair=KeyPairGenerator.getInstance("X25519").generateKeyPair()
        return pair.private to raw(pair.public as XECPublicKey)
    }
    private fun raw(key:XECPublicKey):ByteArray {
        val little=key.u.toByteArray().reversed().toByteArray();return little.copyOf(32)
    }
    fun agree(private:java.security.PrivateKey,peer:ByteArray):ByteArray {
        require(peer.size==32)
        val u=BigInteger(1,peer.reversedArray())
        val key=KeyFactory.getInstance("XDH").generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519,u))
        val shared=KeyAgreement.getInstance("X25519").run {init(private);doPhase(key,true);generateSecret()}
        require(shared.any {it.toInt()!=0}) {"잘못된 키 교환"}
        return shared
    }
    fun equal(a:ByteArray,b:ByteArray)=MessageDigest.isEqual(a,b)
}

/** AES-256-GCM framed duplex channel; each direction has its own key and a strictly increasing counter nonce. */
class SecureChannel(private val socket:Socket,private val sendKey:ByteArray,private val receiveKey:ByteArray):AutoCloseable {
    class Frame(val type:Int,val stream:Int,val payload:ByteArray)
    private val input=DataInputStream(socket.getInputStream().buffered())
    private val output=DataOutputStream(socket.getOutputStream().buffered())
    private var sent=0L;private var received=0L
    private fun cipher(mode:Int,key:ByteArray,counter:Long)=Cipher.getInstance("AES/GCM/NoPadding").apply {
        val iv=ByteArray(12);java.nio.ByteBuffer.wrap(iv).putInt(0).putLong(counter)
        init(mode,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv))
    }
    fun send(type:Int,stream:Int,payload:ByteArray=ByteArray(0),offset:Int=0,length:Int=payload.size) {
        require(length<=MAX_PAYLOAD)
        val plain=java.nio.ByteBuffer.allocate(5+length).put(type.toByte()).putInt(stream).put(payload,offset,length).array()
        synchronized(this) {
            val encrypted=cipher(Cipher.ENCRYPT_MODE,sendKey,sent++).doFinal(plain)
            output.writeInt(encrypted.size);output.write(encrypted);output.flush()
        }
    }
    /** Single reader only. Returns null at a clean end of stream. */
    fun receive():Frame? {
        val size=try {input.readInt()} catch(_:EOFException){return null}
        require(size in 21..MAX_PAYLOAD+5+16) {"잘못된 프레임 크기"}
        val encrypted=ByteArray(size);input.readFully(encrypted)
        val plain=cipher(Cipher.DECRYPT_MODE,receiveKey,received++).doFinal(encrypted)
        val buffer=java.nio.ByteBuffer.wrap(plain)
        return Frame(buffer.get().toInt() and 0xff,buffer.int,ByteArray(buffer.remaining()).also(buffer::get))
    }
    override fun close() {runCatching {socket.close()}}
    companion object {
        const val OPEN=1;const val OPENED=2;const val OPEN_FAIL=3;const val DATA=4;const val CLOSE=5;const val PING=6;const val PONG=7
        const val GOAWAY=8;const val READY_QUERY=9;const val READY=10;const val NOT_READY=11
        const val UDP_REGISTER=12;const val UDP_REGISTERED=13;const val UDP_DENIED=14;const val UDP_UP=15;const val UDP_DOWN=16
        const val VOICE_UP=17;const val VOICE_DOWN=18
        const val MAX_PAYLOAD=64*1024
    }
}

class DirectRelayRejected(val reason:String):java.io.IOException(reason)

object DirectRelayHandshake {
    private const val MAGIC=0x44525031 // "DRP1"
    private fun transcriptOf(body:ByteArray,clientPub:ByteArray,clientNonce:ByteArray,serverPub:ByteArray,serverNonce:ByteArray)=
        java.nio.ByteBuffer.allocate(4+body.size+32+16+32+16).putInt(MAGIC).put(body).put(clientPub).put(clientNonce).put(serverPub).put(serverNonce).array()
    private fun keys(ticketKey:ByteArray,shared:ByteArray,transcript:ByteArray):Pair<ByteArray,ByteArray> {
        val prk=DirectRelayCrypto.hmac(ticketKey,shared,MessageDigest.getInstance("SHA-256").digest(transcript))
        return DirectRelayCrypto.hmac(prk,"c2s".toByteArray()) to DirectRelayCrypto.hmac(prk,"s2c".toByteArray())
    }
    fun client(socket:Socket,ticket:RelayTicket):SecureChannel {
        val out=DataOutputStream(socket.getOutputStream().buffered());val input=DataInputStream(socket.getInputStream())
        val body=ticket.body.toByteArray();val (priv,pub)=DirectRelayCrypto.ephemeral()
        val nonce=ByteArray(16).also(SecureRandom()::nextBytes)
        val hello=java.nio.ByteBuffer.allocate(body.size+48).put(body).put(pub).put(nonce).array()
        out.writeInt(MAGIC);out.writeShort(body.size);out.write(body);out.write(pub);out.write(nonce)
        out.write(DirectRelayCrypto.hmac(ticket.key,"cli".toByteArray(),hello));out.flush()
        val status=input.readUnsignedByte()
        if(status!=0)throw DirectRelayRejected(ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII))
        val serverPub=ByteArray(32).also(input::readFully);val serverNonce=ByteArray(16).also(input::readFully);val proof=ByteArray(32).also(input::readFully)
        val transcript=transcriptOf(body,pub,nonce,serverPub,serverNonce)
        if(!DirectRelayCrypto.equal(proof,DirectRelayCrypto.hmac(ticket.key,"srv".toByteArray(),transcript)))throw DirectRelayRejected("RELAY_NOT_AUTHENTIC")
        val (c2s,s2c)=keys(ticket.key,DirectRelayCrypto.agree(priv,serverPub),transcript)
        return SecureChannel(socket,c2s,s2c)
    }
    /** [check] returns a reject reason for an authenticated but unacceptable ticket (expired, stale epoch, replayed, ...). */
    fun server(socket:Socket,relaySecret:ByteArray,check:(RelayTicketBody)->String?):Pair<SecureChannel,RelayTicketBody> {
        val input=DataInputStream(socket.getInputStream());val out=DataOutputStream(socket.getOutputStream().buffered())
        if(input.readInt()!=MAGIC)throw DirectRelayRejected("BAD_MAGIC")
        val length=input.readUnsignedShort();if(length !in 20..300)throw DirectRelayRejected("AUTH_FAILED")
        val body=ByteArray(length).also(input::readFully);val clientPub=ByteArray(32).also(input::readFully);val clientNonce=ByteArray(16).also(input::readFully)
        val proof=ByteArray(32).also(input::readFully)
        fun refuse(reason:String):Nothing {val bytes=reason.toByteArray(Charsets.US_ASCII);out.writeByte(1);out.writeByte(bytes.size);out.write(bytes);out.flush();throw DirectRelayRejected(reason)}
        val parsed=RelayTicketBody.parse(body.toString(Charsets.UTF_8)) ?: refuse("AUTH_FAILED")
        val ticketKey=DirectRelayCrypto.ticketKey(relaySecret,parsed.encode())
        val hello=java.nio.ByteBuffer.allocate(body.size+48).put(body).put(clientPub).put(clientNonce).array()
        // Authenticate first so unauthenticated peers learn nothing about relay state.
        if(!DirectRelayCrypto.equal(proof,DirectRelayCrypto.hmac(ticketKey,"cli".toByteArray(),hello)))refuse("AUTH_FAILED")
        check(parsed)?.let {reason->refuse(reason)}
        val (priv,pub)=DirectRelayCrypto.ephemeral();val serverNonce=ByteArray(16).also(SecureRandom()::nextBytes)
        val transcript=transcriptOf(body,clientPub,clientNonce,pub,serverNonce)
        out.writeByte(0);out.write(pub);out.write(serverNonce);out.write(DirectRelayCrypto.hmac(ticketKey,"srv".toByteArray(),transcript));out.flush()
        val (c2s,s2c)=keys(ticketKey,DirectRelayCrypto.agree(priv,clientPub),transcript)
        return SecureChannel(socket,s2c,c2s) to parsed
    }
}

data class RelayRoute(val hostname:String,val agentId:String,val instanceId:String)
/** [agents] maps an approved game-runner ID to its current epoch; a missing agent or a different epoch is a revocation. */
data class RelayPolicy(val generation:Long,val agents:Map<String,Long>,val routes:List<RelayRoute>)
data class RelayAgentStatus(val agentId:String,val epoch:Long,val connectedAt:Long,val streams:Int)
data class RelayHubStatus(val gameListening:Boolean,val gamePort:Int?,val secureListening:Boolean,val securePort:Int?,val generation:Long,
    val agents:List<RelayAgentStatus>,val routes:List<RelayRoute>,val streamsByRoute:Map<String,Int>,val rejects:Map<String,Long>,val lastBindError:String?,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) val sableUdp:SableRelayStatus?=null,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) val sableInstances:Map<String,SableRelayStatus>?=null,
    @get:com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) val voicechat:Map<String,VoiceChatStatus> =emptyMap())

class DirectRelayHub(private val relayId:String,secret:ByteArray,private val clock:()->Long={System.currentTimeMillis()},private val playerReadTimeoutMillis:Int=60000):AutoCloseable {
    private inner class Tunnel(val agentId:String,val epoch:Long,val channel:SecureChannel,val connectedAt:Long) {
        val players=ConcurrentHashMap<Int,Socket>();val instances=ConcurrentHashMap<Int,String>();val opens=ConcurrentHashMap<Int,CountDownLatch>();val failed=ConcurrentHashMap<Int,String>()
        val readyReplies=ConcurrentHashMap<String,java.util.concurrent.CompletableFuture<Boolean>>()
        val datagrams=DatagramOutbox {frame->runCatching {
            val b=java.nio.ByteBuffer.wrap(frame);val stream=b.int
            channel.send(SecureChannel.UDP_UP,stream,ByteArray(b.remaining()).also(b::get));true
        }.getOrDefault(false)}
        val voiceDatagrams=DatagramOutbox {frame->runCatching {
            val b=java.nio.ByteBuffer.wrap(frame);val stream=b.int
            channel.send(SecureChannel.VOICE_UP,stream,ByteArray(b.remaining()).also(b::get));true
        }.getOrDefault(false)}
        val ids=AtomicInteger(1)
        fun end(stream:Int,notify:Boolean) {
            instances.remove(stream);sable.removeStream(agentId,stream)
            players.remove(stream)?.let {runCatching {it.close()};if(notify)runCatching {channel.send(SecureChannel.CLOSE,stream)}}
        }
        fun close() {voiceDatagrams.close();voicechat.removeRunner(agentId);datagrams.close();sable.removeRunner(agentId);channel.close();players.keys.toTypedArray().forEach {end(it,false)};opens.values.forEach {it.countDown()}}
    }
    private val io=Executors.newVirtualThreadPerTaskExecutor()
    private val tunnels=ConcurrentHashMap<String,Tunnel>()
    private val sable=SableRelay({agent,stream->
        val t=tunnels[agent];val socket=t?.players?.get(stream);val instance=t?.instances?.get(stream)
        if(!udpEnabled || t==null || socket==null || instance==null || policy.agents[agent]!=t.epoch || policy.routes.none {it.agentId==agent && it.instanceId==instance})null
        else SableRoute(instance,socket.inetAddress)
    },{agent,frame->tunnels[agent]?.datagrams?.offer(frame)==true})
    private val voicechat=VoiceChatPublicRelay({instance,ip->
        val candidates=tunnels.values.filter {t->policy.agents[t.agentId]==t.epoch && policy.routes.any {it.agentId==t.agentId && it.instanceId==instance} && t.instances.any {(stream,id)->id==instance && t.players[stream]?.let {!it.isClosed && it.inetAddress==ip}==true}}
        candidates.singleOrNull()?.agentId
    },{agent,session,bytes->tunnels[agent]?.voiceDatagrams?.offer(java.nio.ByteBuffer.allocate(4+bytes.size).putInt(session).put(bytes).array())==true})
    fun voiceChat(config:List<VoiceChatRelayConfig>,address:String="0.0.0.0")=voicechat.configure(config,address)
    @Volatile private var udpEnabled=false
    @Volatile private var udpRequested=false
    @Synchronized fun sableUdp(enabled:Boolean,port:Int,address:String="0.0.0.0") {
        udpRequested=enabled
        if(!enabled){udpEnabled=false;sable.close();return}
        if(sable.localPort!=port){sable.close();sable.listen(port,address)}
        udpEnabled=sable.localPort==port
    }
    @Volatile private var secret=secret.copyOf()
    /** Established tunnels keep working; only new handshakes need tickets minted from the new secret. */
    fun rotateSecret(next:ByteArray){secret=next.copyOf()}
    private val usedNonces=ConcurrentHashMap<String,Long>()
    private val rejects=ConcurrentHashMap<String,AtomicLong>()
    private val handshakeSlots=Semaphore(64)
    @Volatile private var policy=RelayPolicy(0,emptyMap(),emptyList())
    @Volatile private var game:ServerSocket?=null
    @Volatile private var secure:ServerSocket?=null
    @Volatile private var closed=false
    @Volatile private var bindError:String?=null
    private fun reject(reason:String){rejects.computeIfAbsent(reason){AtomicLong()}.incrementAndGet()}
    private fun bind(port:Int,address:String):ServerSocket? {
        val server=ServerSocket();server.reuseAddress=true
        return try {server.bind(InetSocketAddress(address,port),50);server}
        catch(e:Exception){server.close();bindError="포트 $port 를 열 수 없습니다: ${e.message}";reject("PORT_BIND_FAILED");null}
    }
    /** Game and secure ports are different sockets; binding the same number twice is refused as a port conflict. */
    @Synchronized fun listen(gamePort:Int?,securePort:Int?,address:String="0.0.0.0"):Boolean {
        check(!closed);bindError=null
        require(gamePort==null || securePort==null || gamePort==0 || gamePort!=securePort) {"게임 포트와 실행기 연결 포트는 달라야 합니다"}
        if(game?.localPort!=gamePort || game?.isClosed==true){game?.close();game=null;if(gamePort!=null)game=bind(gamePort,address)?.also {s->io.submit {acceptGame(s)}}}
        if(secure?.localPort!=securePort || secure?.isClosed==true){secure?.close();secure=null;if(securePort!=null)secure=bind(securePort,address)?.also {s->io.submit {acceptSecure(s)}}}
        return (gamePort==null || game!=null) && (securePort==null || secure!=null)
    }
    val gamePort:Int? get()=game?.takeIf {!it.isClosed}?.localPort
    val securePort:Int? get()=secure?.takeIf {!it.isClosed}?.localPort
    /** Applies [next] only when newer. Removed routes refuse new players but let live streams finish; removed agents are cut immediately. */
    @Synchronized fun applyPolicy(next:RelayPolicy):Boolean {
        if(next.generation<=policy.generation){reject("STALE_POLICY");return false}
        policy=next
        tunnels.values.filter {next.agents[it.agentId]!=it.epoch}.forEach {revoked->
            runCatching {revoked.channel.send(SecureChannel.GOAWAY,0,"REVOKED".toByteArray())}
            if(tunnels.remove(revoked.agentId,revoked))revoked.close()
        }
        return true
    }
    /** Closes only the player-facing listener; established players and agent tunnels are untouched. [listen] reopens it. */
    @Synchronized fun pauseGame(){runCatching {game?.close()};game=null}
    fun streamCount()=tunnels.values.sumOf {it.players.size}
    /** Stop accepting new players now; established streams and tunnels finish naturally, then everything closes. */
    fun drainAndClose(deadlineMillis:Long) {
        runCatching {game?.close()}
        Thread.ofVirtual().start {
            val end=clock()+deadlineMillis
            while(!closed && streamCount()>0 && clock()<end){try {Thread.sleep(200)} catch(_:InterruptedException){break}}
            close()
        }
    }
    fun status():RelayHubStatus {
        val p=policy
        return RelayHubStatus(gamePort!=null,gamePort,securePort!=null,securePort,p.generation,
            tunnels.values.map {RelayAgentStatus(it.agentId,it.epoch,it.connectedAt,it.players.size)}.sortedBy {it.agentId},p.routes,
            p.routes.associate {it.hostname to (tunnels[it.agentId]?.players?.size ?: 0)},rejects.mapValues {it.value.get()},bindError,if(udpRequested)sable.status() else null,if(udpRequested)p.routes.map {it.instanceId}.distinct().take(1000).associateWith {sable.status(it)} else null,voicechat.statuses())
    }
    /** Asks the agent whether the instance can serve players, without opening a game connection. */
    fun probe(agentId:String,instanceId:String,timeoutMillis:Long=2000):Boolean {
        val tunnel=tunnels[agentId] ?: return false
        val future=java.util.concurrent.CompletableFuture<Boolean>();tunnel.readyReplies[instanceId]=future
        return try {tunnel.channel.send(SecureChannel.READY_QUERY,0,instanceId.toByteArray());future.get(timeoutMillis,TimeUnit.MILLISECONDS)}
        catch(_:Exception){false} finally {tunnel.readyReplies.remove(instanceId,future)}
    }
    private fun acceptSecure(server:ServerSocket) {
        while(!closed && !server.isClosed) {
            val socket=runCatching {server.accept()}.getOrNull() ?: break
            if(!handshakeSlots.tryAcquire()){socket.close();reject("TOO_MANY_HANDSHAKES");continue}
            io.submit {
                var tunnel:Tunnel?=null
                try {
                    socket.soTimeout=5000
                    val (channel,ticket)=DirectRelayHandshake.server(socket,secret) {body->check(body)}
                    socket.soTimeout=0;handshakeSlots.release()
                    tunnel=Tunnel(ticket.agentId,ticket.epoch,channel,clock())
                    tunnels.put(ticket.agentId,tunnel)?.close()
                    // Policy may have changed between ticket check and registration.
                    if(policy.agents[ticket.agentId]!=ticket.epoch){reject("STALE_EPOCH");return@submit}
                    serve(tunnel)
                } catch(e:DirectRelayRejected){reject(e.reason)}
                catch(e:Exception){reject("PROTOCOL_ERROR")}
                finally {
                    if(tunnel==null)handshakeSlots.release() else {tunnels.remove(tunnel.agentId,tunnel);tunnel.close()}
                    runCatching {socket.close()}
                }
            }
        }
    }
    private fun check(body:RelayTicketBody):String? {
        val now=clock()
        if(body.relayId!=relayId)return "WRONG_RELAY"
        if(body.expiresAt<now)return "EXPIRED"
        val epoch=policy.agents[body.agentId] ?: return "NOT_APPROVED"
        if(epoch!=body.epoch)return "STALE_EPOCH"
        usedNonces.entries.removeIf {it.value<now}
        if(usedNonces.putIfAbsent(body.nonce,body.expiresAt+60_000)!=null)return "REPLAYED"
        return null
    }
    private fun serve(tunnel:Tunnel) {
        while(true) {
            val frame=tunnel.channel.receive() ?: break
            when(frame.type) {
                SecureChannel.OPENED -> tunnel.opens[frame.stream]?.countDown()
                SecureChannel.OPEN_FAIL -> {tunnel.failed[frame.stream]=frame.payload.toString(Charsets.US_ASCII).take(40);tunnel.opens[frame.stream]?.countDown()}
                SecureChannel.DATA -> tunnel.players[frame.stream]?.let {s->runCatching {s.getOutputStream().write(frame.payload);s.getOutputStream().flush()}.onFailure {tunnel.end(frame.stream,true)}}
                SecureChannel.CLOSE -> tunnel.end(frame.stream,false)
                SecureChannel.UDP_REGISTER -> {
                    val token=runCatching {java.util.UUID.fromString(frame.payload.toString(Charsets.US_ASCII)).takeIf {it.toString()==frame.payload.toString(Charsets.US_ASCII)}}.getOrNull()
                    val instance=tunnel.instances[frame.stream]
                    val ok=udpEnabled && token!=null && instance!=null && sable.register(tunnel.agentId,frame.stream,instance,token)
                    tunnel.channel.send(if(ok)SecureChannel.UDP_REGISTERED else SecureChannel.UDP_DENIED,frame.stream,frame.payload.take(36).toByteArray())
                }
                SecureChannel.UDP_DOWN -> if(udpEnabled && frame.payload.isNotEmpty() && frame.payload.size<=65507)
                    sable.downstream(tunnel.agentId,java.nio.ByteBuffer.allocate(4+frame.payload.size).putInt(frame.stream).put(frame.payload).array())
                SecureChannel.VOICE_DOWN -> voicechat.downstream(tunnel.agentId,frame.stream,frame.payload)
                SecureChannel.PING -> tunnel.channel.send(SecureChannel.PONG,0)
                SecureChannel.READY,SecureChannel.NOT_READY -> tunnel.readyReplies[frame.payload.toString(Charsets.UTF_8)]?.complete(frame.type==SecureChannel.READY)
            }
        }
    }
    private fun acceptGame(server:ServerSocket) {
        while(!closed && !server.isClosed) {
            val player=runCatching {server.accept()}.getOrNull() ?: break
            if(!handshakeSlots.tryAcquire()){player.close();reject("TOO_MANY_HANDSHAKES");continue}
            io.submit {
                var released=false
                fun release(){if(!released){released=true;handshakeSlots.release()}}
                var handed=false
                var cleanup:(()->Unit)?=null
                try {
                    player.soTimeout=5000
                    val handshake=MinecraftHandshake.read(player.getInputStream())
                    val route=policy.routes.firstOrNull {it.hostname==handshake.host} ?: run {reject("UNKNOWN_ROUTE");return@submit}
                    val tunnel=tunnels[route.agentId] ?: run {reject("AGENT_OFFLINE");return@submit}
                    if(policy.agents[route.agentId]!=tunnel.epoch){reject("STALE_EPOCH");return@submit}
                    if(tunnel.players.size>=200){reject("TOO_MANY_STREAMS");return@submit}
                    val stream=tunnel.ids.getAndIncrement();val latch=CountDownLatch(1)
                    tunnel.opens[stream]=latch;tunnel.players[stream]=player;tunnel.instances[stream]=route.instanceId
                    cleanup={tunnel.end(stream,true)}
                    try {
                        tunnel.channel.send(SecureChannel.OPEN,stream,route.instanceId.toByteArray())
                        if(!latch.await(3,TimeUnit.SECONDS)){reject("OPEN_TIMEOUT");tunnel.end(stream,true);return@submit}
                        tunnel.failed.remove(stream)?.let {reject("AGENT_$it");tunnel.players.remove(stream);return@submit}
                    } finally {tunnel.opens.remove(stream)}
                    handed=true;release()
                    player.soTimeout=playerReadTimeoutMillis
                    tunnel.channel.send(SecureChannel.DATA,stream,handshake.bytes)
                    val input=player.getInputStream();val buffer=ByteArray(32*1024)
                    while(true) {val count=input.read(buffer);if(count<0)break;tunnel.channel.send(SecureChannel.DATA,stream,buffer,0,count)}
                    tunnel.end(stream,true)
                } catch(e:DirectRelayRejected){reject(e.reason)}
                catch(e:Exception){if(!handed)reject("HANDSHAKE_REJECTED")}
                finally {release();runCatching {cleanup?.invoke()};runCatching {player.close()}}
            }
        }
    }
    override fun close() {
        closed=true;voicechat.close();sable.close();runCatching {game?.close()};runCatching {secure?.close()}
        tunnels.values.forEach {it.close()};tunnels.clear();io.shutdownNow()
    }
}

enum class RelayAgentState {IDLE,CONNECTING,CONNECTED,REJECTED,MANAGER_UNAVAILABLE,UNREACHABLE}
/** What the game runner needs for one connection attempt; null means the manager could not supply a ticket now. */
data class RelayEndpoint(val host:String,val port:Int,val ticket:RelayTicket)

/** Game runner side: dials out to the relay, then serves OPEN requests only for instances it owns. */
class DirectRelayAgent(private val endpoint:()->RelayEndpoint?,private val gamePort:(String)->Int?,private val ready:(String)->Boolean={true},
        private val backoffMillis:LongArray=longArrayOf(500,1000,2000,5000,10000),private val sableAllowed:()->Boolean={false},private val voiceTargets:()->List<VoiceChatTarget> ={emptyList()}):AutoCloseable {
    private val io=Executors.newVirtualThreadPerTaskExecutor()
    private val streams=ConcurrentHashMap<Int,Socket>()
    private val streamInstances=ConcurrentHashMap<Int,String>()
    private val udp=ConcurrentHashMap<Int,java.net.DatagramSocket>()
    private val udpTokens=ConcurrentHashMap<Int,String>()
    private val registrations=ConcurrentHashMap<String,java.util.concurrent.CompletableFuture<Boolean>>()
    @Volatile private var datagrams:DatagramOutbox?=null
    @Volatile private var voiceOutbox:DatagramOutbox?=null
    private val voicechat=VoiceChatAgentRelay(voiceTargets,ready,{session,bytes->voiceOutbox?.offer(java.nio.ByteBuffer.allocate(4+bytes.size).putInt(session).put(bytes).array())==true})
    fun voiceTargetsChanged()=voicechat.reconcile()
    fun streamAtPort(port:Int):Pair<String,Int>?=streams.entries.singleOrNull {it.value.localPort==port && !it.value.isClosed}?.let {e->streamInstances[e.key]?.let {it to e.key}}
    fun registerSable(stream:Int,instance:String,token:String):Boolean {
        val tunnel=channel ?: return false
        if(!sableAllowed() || streamInstances[stream]!=instance || streams[stream]?.isClosed!=false || registrations.size>=200)return false
        val uuid=runCatching {java.util.UUID.fromString(token).takeIf {it.toString()==token}}.getOrNull() ?: return false
        val reply=java.util.concurrent.CompletableFuture<Boolean>()
        if(registrations.putIfAbsent(token,reply)!=null)return false
        return try {tunnel.send(SecureChannel.UDP_REGISTER,stream,uuid.toString().toByteArray());reply.get(2,TimeUnit.SECONDS).also {if(it)udpTokens[stream]=token}}
        catch(_:Exception){false} finally {registrations.remove(token,reply)}
    }
    fun disableSable(){udpTokens.clear();udp.values.forEach {it.close()};udp.clear();registrations.values.forEach {it.complete(false)};registrations.clear()}
    private fun receiveDatagram(tunnel:SecureChannel,frame:SecureChannel.Frame) {
        val instance=streamInstances[frame.stream] ?: return
        val token=udpTokens[frame.stream] ?: return
        val payload=frame.payload
        val valid=(payload.size==38 && payload[0].toInt()==3 && payload[1].toInt()==36 && String(payload,2,36,Charsets.US_ASCII)==token) || (payload.size==1 && payload[0].toInt()==5)
        if(!valid)return
        val port=gamePort(instance) ?: return
        if(!sableAllowed() || !ready(instance) || streams[frame.stream]?.isClosed!=false || frame.payload.size !in 1..2048)return
        val channel=udp.computeIfAbsent(frame.stream) {stream->
            java.net.DatagramSocket(java.net.InetSocketAddress("127.0.0.1",0)).also {socket->
                socket.connect(java.net.InetAddress.getByName("127.0.0.1"),port);socket.soTimeout=1000
                io.submit {
                    val bytes=ByteArray(65508)
                    try {while(!socket.isClosed && streams[stream]?.isClosed==false && sableAllowed()) {
                        try {
                            val packet=java.net.DatagramPacket(bytes,bytes.size);socket.receive(packet)
                            if(packet.length in 1..65507 && (packet.data[0].toInt() and 255) in setOf(0,1,2,4))datagrams?.offer(java.nio.ByteBuffer.allocate(4+packet.length).putInt(stream).put(bytes,0,packet.length).array())
                        } catch(_:java.net.SocketTimeoutException){}
                    }} catch(_:Exception){} finally {udp.remove(stream,socket);socket.close()}
                }
            }
        }
        runCatching {channel.send(java.net.DatagramPacket(frame.payload,frame.payload.size))}
    }
    @Volatile var state=RelayAgentState.IDLE;private set
    @Volatile var reason:String?=null;private set
    @Volatile var connectedAt:Long?=null;private set
    @Volatile private var stopped=false
    @Volatile private var channel:SecureChannel?=null
    private var loop:Thread?=null
    @Volatile private var draining=false
    @Synchronized fun start() {if(loop!=null)return;loop=Thread.ofVirtual().start {run()}}
    fun streamCount()=streams.size
    /** Stop reconnecting; live streams finish naturally and the tunnel is closed once idle or after [deadlineMillis]. */
    fun drain(deadlineMillis:Long) {
        draining=true
        Thread.ofVirtual().start {
            val end=System.currentTimeMillis()+deadlineMillis
            while(!stopped && channel!=null && streams.isNotEmpty() && System.currentTimeMillis()<end){try {Thread.sleep(200)} catch(_:InterruptedException){return@start}}
            close()
        }
    }
    private fun run() {
        var failures=0
        while(!stopped && !draining) {
            state=RelayAgentState.CONNECTING
            val lookup=runCatching {endpoint()}
            val denied=lookup.exceptionOrNull() as? DirectRelayRejected
            val target=lookup.getOrNull()
            if(denied!=null){state=RelayAgentState.REJECTED;reason=denied.reason}
            else if(target==null){state=RelayAgentState.MANAGER_UNAVAILABLE;reason="관리 서버에서 중계 티켓을 받지 못했습니다. 새 연결은 복구 후 처리됩니다"}
            else try {
                val socket=Socket();socket.connect(InetSocketAddress(target.host,target.port),5000);socket.soTimeout=10000;socket.tcpNoDelay=true
                val tunnel=try {DirectRelayHandshake.client(socket,target.ticket)} catch(e:Exception){runCatching {socket.close()};throw e}
                socket.soTimeout=0;channel=tunnel;connectedAt=System.currentTimeMillis();state=RelayAgentState.CONNECTED;reason=null;failures=0
                datagrams=DatagramOutbox {bytes->runCatching {val b=java.nio.ByteBuffer.wrap(bytes);val stream=b.int;tunnel.send(SecureChannel.UDP_DOWN,stream,ByteArray(b.remaining()).also(b::get));true}.getOrDefault(false)}
                voiceOutbox=DatagramOutbox {bytes->runCatching {val b=java.nio.ByteBuffer.wrap(bytes);val session=b.int;tunnel.send(SecureChannel.VOICE_DOWN,session,ByteArray(b.remaining()).also(b::get));true}.getOrDefault(false)}
                try {serve(tunnel)} finally {voiceOutbox?.close();voiceOutbox=null;voicechat.close();datagrams?.close();datagrams=null;disableSable();channel=null;connectedAt=null;streams.keys.toTypedArray().forEach {end(it)};tunnel.close()}
                if(!stopped && state==RelayAgentState.CONNECTED){state=RelayAgentState.UNREACHABLE;reason="중계 실행기 연결이 끊겼습니다"}
            } catch(e:DirectRelayRejected){state=RelayAgentState.REJECTED;reason=e.reason}
            catch(e:Exception){state=RelayAgentState.UNREACHABLE;reason="중계 실행기에 연결하지 못했습니다: ${e.javaClass.simpleName}"}
            if(stopped)break
            try {Thread.sleep(backoffMillis[minOf(failures++,backoffMillis.size-1)])} catch(_:InterruptedException){break}
        }
    }
    private fun serve(tunnel:SecureChannel) {
        while(!stopped) {
            val frame=tunnel.receive() ?: return
            when(frame.type) {
                SecureChannel.OPEN -> open(tunnel,frame.stream,frame.payload.toString(Charsets.UTF_8))
                SecureChannel.DATA -> streams[frame.stream]?.let {s->runCatching {s.getOutputStream().write(frame.payload);s.getOutputStream().flush()}.onFailure {end(frame.stream);runCatching {tunnel.send(SecureChannel.CLOSE,frame.stream)}}}
                SecureChannel.CLOSE -> end(frame.stream)
                SecureChannel.VOICE_UP -> voicechat.receive(frame.stream,frame.payload)
                SecureChannel.UDP_UP -> receiveDatagram(tunnel,frame)
                SecureChannel.UDP_REGISTERED,SecureChannel.UDP_DENIED -> registrations[frame.payload.toString(Charsets.US_ASCII)]?.complete(frame.type==SecureChannel.UDP_REGISTERED)
                SecureChannel.PING -> tunnel.send(SecureChannel.PONG,0)
                SecureChannel.READY_QUERY -> frame.payload.let {tunnel.send(if(ready(it.toString(Charsets.UTF_8)))SecureChannel.READY else SecureChannel.NOT_READY,0,it)}
                SecureChannel.GOAWAY -> {state=RelayAgentState.REJECTED;reason=frame.payload.toString(Charsets.US_ASCII).take(40);return}
            }
        }
    }
    private fun open(tunnel:SecureChannel,stream:Int,instanceId:String) {
        val port=gamePort(instanceId)
        if(port==null || !ready(instanceId) || streams.size>=200 || streams.containsKey(stream)){tunnel.send(SecureChannel.OPEN_FAIL,stream,(if(port==null)"NOT_ALLOWED" else "NOT_READY").toByteArray());return}
        io.submit {
            val socket=Socket()
            try {
                socket.connect(InetSocketAddress("127.0.0.1",port),3000);socket.soTimeout=60000;socket.tcpNoDelay=true
                streams[stream]=socket;streamInstances[stream]=instanceId;tunnel.send(SecureChannel.OPENED,stream)
                val input=socket.getInputStream();val buffer=ByteArray(32*1024)
                while(true) {val count=input.read(buffer);if(count<0)break;tunnel.send(SecureChannel.DATA,stream,buffer,0,count)}
            } catch(e:Exception){if(!streams.containsKey(stream))runCatching {tunnel.send(SecureChannel.OPEN_FAIL,stream,"CONNECT_FAILED".toByteArray())}}
            finally {streamInstances.remove(stream);udpTokens.remove(stream);udp.remove(stream)?.close();if(streams.remove(stream,socket))runCatching {tunnel.send(SecureChannel.CLOSE,stream)};runCatching {socket.close()}}
        }
    }
    private fun end(stream:Int){streamInstances.remove(stream);udpTokens.remove(stream);udp.remove(stream)?.close();streams.remove(stream)?.let {runCatching {it.close()}}}
    override fun close() {stopped=true;voicechat.close();voiceOutbox?.close();disableSable();datagrams?.close();channel?.close();loop?.interrupt();streams.keys.toTypedArray().forEach {end(it)};io.shutdownNow();state=RelayAgentState.IDLE}
}
