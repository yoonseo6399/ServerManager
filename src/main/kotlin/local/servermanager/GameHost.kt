package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.*

/**
 * Resident game host. It owns the Minecraft processes (and their stdin/stdout) so the replaceable control layer
 * (runner agent or desktop manager) can restart, update or crash without touching a game.
 *
 * Wire format: newline-delimited JSON over a loopback socket. The first message is a server challenge; the client
 * proves possession of the local secret (HMAC) before any command is processed. Exactly one controller may be attached.
 */
object GameHostProtocol {
    const val VERSION=1
    /** Oldest controller protocol this host still serves. Raising it forces the "wait for idle" path for host updates. */
    const val MIN_COMPATIBLE=1
    const val MAX_LINE=256*1024
    fun hmac(secret:ByteArray,vararg parts:ByteArray):ByteArray=Mac.getInstance("HmacSHA256").run {init(SecretKeySpec(secret,"HmacSHA256"));parts.forEach(::update);doFinal()}
}

data class HostEndpoint(val port:Int,val secret:String,val pid:Long,val version:String,val protocol:Int,val minCompatible:Int,val startMillis:Long=0)
data class HostInstanceStatus(val id:String,val pid:Long,val startMillis:Long,val alive:Boolean,val exitCode:Int?,val exitAcked:Boolean,val lastSeq:Long,val managed:Boolean,val reason:String?=null,val ready:Boolean=false)
private data class PersistedInstance(val id:String,val pid:Long,val startMillis:Long,val exitCode:Int?=null,val exitAcked:Boolean=false)
private data class PersistedHost(val instances:List<PersistedInstance> =emptyList())

class GameHostService(private val home:Path,private val version:String,private val ringSize:Int=5000,private val pingMillis:Long=5000,private val leaseMillis:Long=15000,
        private val protocol:Int=GameHostProtocol.VERSION,private val minCompatible:Int=GameHostProtocol.MIN_COMPATIBLE):AutoCloseable {
    private val mapper=jacksonObjectMapper()
    private val dir=home.resolve("host")
    private val endpointFile=dir.resolve("endpoint.json")
    private val stateFile=dir.resolve("state.json")
    private val random=SecureRandom()
    private val secret=ByteArray(32).also(random::nextBytes)
    private val io=Executors.newCachedThreadPool {r->Thread(r,"game-host-io").apply {isDaemon=true}}
    private lateinit var lockChannel:FileChannel
    private var lock:FileLock?=null
    private lateinit var server:ServerSocket
    @Volatile private var closed=false

    private inner class Game(val id:String,val process:Process?,val pid:Long,val startMillis:Long) {
        val ring=java.util.ArrayDeque<Pair<Long,String>>();val seq=AtomicLong()
        @Volatile var exitCode:Int?=null;@Volatile var exitAcked=false;@Volatile var ready=false
        val seenCommands=object:LinkedHashMap<String,Boolean>(){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,Boolean>)=size>1000}
        val managed get()=process!=null
        @Volatile var stopping=false;@Volatile var port:Int?=null
        fun alive()=if(process!=null)process.isAlive else ProcessHandle.of(pid).map {it.isAlive && it.info().startInstant().map {s->s.toEpochMilli()}.orElse(-1)==startMillis}.orElse(false)
    }
    private val games=ConcurrentHashMap<String,Game>()
    private var controller:Controller?=null
    private val relayInbox=java.util.concurrent.atomic.AtomicReference<((JsonNode)->Unit)?>(null)
    /** Extension point: the host also owns relay sockets. The relay role hands outbound control messages here. */
    @Volatile var relayHandler:((String)->Unit)?=null
    @Volatile var onRelayMessage:((String)->Unit)?=null

    private inner class Controller(val socket:Socket,val id:String) {
        val out=PrintWriter(socket.getOutputStream().bufferedWriter(Charsets.UTF_8),false)
        @Volatile var lastPong=System.currentTimeMillis()
        fun send(node:Map<String,Any?>):Boolean=synchronized(out) {out.println(mapper.writeValueAsString(node));out.flush();!out.checkError()}
        fun close(){runCatching {socket.close()}}
    }

    fun port():Int=server.localPort
    fun endpoint()=HostEndpoint(server.localPort,Base64.getUrlEncoder().withoutPadding().encodeToString(secret),ProcessHandle.current().pid(),version,protocol,minCompatible,ProcessHandle.current().info().startInstant().map {it.toEpochMilli()}.orElse(0))

    /** Returns false when another resident host already owns [home]; never starts a second host. */
    fun start():Boolean {
        dir.createDirectories();runCatching {Files.setPosixFilePermissions(dir,PosixFilePermissions.fromString("rwx------"))}
        lockChannel=FileChannel.open(dir.resolve("service.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
        lock=try {lockChannel.tryLock()} catch(_:java.nio.channels.OverlappingFileLockException){null} // same-JVM duplicate; another process yields null
        if(lock==null){lockChannel.close();return false}
        recoverState()
        server=ServerSocket();server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),8)
        writeEndpoint()
        io.submit {acceptLoop()}
        io.submit {pingLoop()}
        return true
    }
    private fun writeEndpoint() {
        val tmp=Files.createTempFile(dir,".endpoint-",".tmp")
        try {
            tmp.writeBytes(mapper.writeValueAsBytes(endpoint()))
            runCatching {Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"))}
            Files.move(tmp,endpointFile,ATOMIC_MOVE,REPLACE_EXISTING)
        } finally {tmp.deleteIfExists()}
    }
    /** After a host restart the previous Process handles are gone. A still-alive game is reported as unmanaged and never duplicated. */
    private fun recoverState() {
        val persisted=runCatching {mapper.readValue(stateFile.readText(),PersistedHost::class.java)}.getOrNull() ?: return
        persisted.instances.forEach {p->
            val game=Game(p.id,null,p.pid,p.startMillis).also {g->g.exitCode=p.exitCode;g.exitAcked=p.exitAcked}
            if(p.exitCode!=null || game.alive())games[p.id]=game
        }
        persist()
    }
    @Synchronized private fun persist() {
        val body=PersistedHost(games.values.map {PersistedInstance(it.id,it.pid,it.startMillis,it.exitCode,it.exitAcked)})
        val tmp=Files.createTempFile(dir,".state-",".tmp")
        try {tmp.writeBytes(mapper.writeValueAsBytes(body));Files.move(tmp,stateFile,ATOMIC_MOVE,REPLACE_EXISTING)} finally {tmp.deleteIfExists()}
    }
    private fun acceptLoop() {
        while(!closed) {
            val socket=runCatching {server.accept()}.getOrNull() ?: break
            io.submit {serve(socket)}
        }
    }
    @Volatile private var relayRole:DirectRelayRunnerRole?=null
    /**
     * The direct relay (hub and agent tunnels) lives here so established player connections survive control-layer updates.
     * Control messages travel through whichever controller is attached; without one, new tickets cannot be fetched
     * but existing tunnels keep carrying traffic.
     */
    @Synchronized private fun relay():DirectRelayRunnerRole=relayRole ?: DirectRelayRunnerRole(home,
        {message->controller?.send(mapOf("t" to "relay","body" to mapper.writeValueAsString(message))) ?: false},
        {id->games[id]?.takeIf {it.alive() && it.ready && !it.stopping}?.port},
        {id->games[id]?.let {it.alive() && it.ready && !it.stopping}==true}).also {
        relayRole=it
        io.submit {while(!closed){try {Thread.sleep(1000)} catch(_:InterruptedException){break};runCatching {it.tick()}}}
    }
    private fun pingLoop() {
        while(!closed) {
            try {Thread.sleep(pingMillis)} catch(_:InterruptedException){return}
            val c=controller ?: continue
            if(System.currentTimeMillis()-c.lastPong>leaseMillis){c.close();continue}
            c.send(mapOf("t" to "ping"))
        }
    }
    private fun readLine(reader:BufferedReader):String? {
        val sb=StringBuilder()
        while(true) {
            val ch=reader.read();if(ch<0)return if(sb.isEmpty())null else sb.toString()
            if(ch=='\n'.code)return sb.toString()
            if(sb.length>=GameHostProtocol.MAX_LINE)throw java.io.IOException("line too long")
            sb.append(ch.toChar())
        }
    }
    private fun serve(socket:Socket) {
        var me:Controller?=null
        try {
            socket.soTimeout=10000;socket.tcpNoDelay=true
            val reader=BufferedReader(InputStreamReader(socket.getInputStream(),Charsets.UTF_8))
            val writer=PrintWriter(socket.getOutputStream().bufferedWriter(Charsets.UTF_8),false)
            fun raw(map:Map<String,Any?>){synchronized(writer){writer.println(mapper.writeValueAsString(map));writer.flush()}}
            val nonce=ByteArray(24).also(random::nextBytes)
            raw(mapOf("t" to "challenge","nonce" to Base64.getEncoder().encodeToString(nonce),"protocol" to protocol,"minCompatible" to minCompatible,"version" to version,"pid" to ProcessHandle.current().pid()))
            val hello=mapper.readTree(readLine(reader) ?: return)
            val proof=runCatching {Base64.getDecoder().decode(hello.path("proof").asText())}.getOrNull() ?: ByteArray(0)
            // Authenticate before revealing anything else; a wrong proof gets a generic refusal.
            if(hello.path("t").asText()!="hello" || !MessageDigest.isEqual(proof,GameHostProtocol.hmac(secret,"gh-hello".toByteArray(),nonce))){raw(mapOf("t" to "reject","reason" to "AUTH_FAILED"));return}
            val clientProtocol=hello.path("protocol").asInt(0);val clientMin=hello.path("minHost").asInt(1)
            if(clientProtocol<minCompatible || clientMin>protocol){raw(mapOf("t" to "reject","reason" to "PROTOCOL_INCOMPATIBLE","protocol" to protocol,"minCompatible" to minCompatible));return}
            val controllerId=hello.path("controller").asText("").take(64)
            synchronized(this) {
                val existing=controller
                if(existing!=null && !existing.socket.isClosed && System.currentTimeMillis()-existing.lastPong<=leaseMillis){raw(mapOf("t" to "reject","reason" to "CONTROLLER_ACTIVE"));return}
                existing?.close()
                me=Controller(socket,controllerId).also {controller=it}
            }
            val c=me!!;socket.soTimeout=0
            // Replace the raw writer with the controller's serialized one for everything after this point.
            c.send(mapOf("t" to "welcome","protocol" to protocol,"version" to version,"instances" to statuses()))
            while(!closed) {
                val line=readLine(reader) ?: break
                c.lastPong=System.currentTimeMillis()
                val msg=mapper.readTree(line)
                handle(c,msg)
            }
        } catch(_:Exception) {} finally {
            me?.let {m->synchronized(this){if(controller===m)controller=null};m.close()}
            runCatching {socket.close()}
        }
    }
    fun statuses():List<HostInstanceStatus> =games.values.sortedBy {it.id}.map {
        HostInstanceStatus(it.id,it.pid,it.startMillis,it.alive(),it.exitCode,it.exitAcked,it.seq.get(),it.managed,if(!it.managed && it.alive())"ORPHAN_ALIVE_NOT_CONTROLLABLE" else null,it.ready)
    }
    fun anyAlive()=games.values.any {it.alive()}
    fun controllerAttached()=controller?.socket?.isClosed==false

    @Volatile private var replacementPrepared=false
    @Synchronized private fun handle(c:Controller,m:JsonNode) {
        val req=m.path("req").asText("")
        fun ok(extra:Map<String,Any?> =emptyMap()){c.send(mapOf("t" to "ok","req" to req)+extra)}
        fun fail(reason:String,extra:Map<String,Any?> =emptyMap()){c.send(mapOf("t" to "error","req" to req,"reason" to reason)+extra)}
        when(m.path("t").asText()) {
            "pong" -> {}
            "status" -> ok(mapOf("instances" to statuses()))
            "start" -> if(replacementPrepared)fail("HOST_REPLACEMENT_PENDING") else start(c,m,::ok,::fail)
            "attach" -> {
                val id=m.path("id").asText();val after=m.path("afterSeq").asLong(0)
                val game=games[id] ?: return fail("NO_SUCH_INSTANCE")
                val replay:List<Pair<Long,String>>;val first:Long
                synchronized(game.ring){replay=game.ring.filter {it.first>after};first=game.ring.firstOrNull()?.first ?: (game.seq.get()+1)}
                val gap=if(after+1<first && game.seq.get()>after)mapOf("gapFrom" to after+1,"gapTo" to first-1) else emptyMap()
                ok(mapOf("pid" to game.pid,"startMillis" to game.startMillis,"alive" to game.alive(),"exitCode" to game.exitCode,"lastSeq" to game.seq.get(),"ready" to game.ready)+gap)
                replay.forEach {(s,l)->c.send(mapOf("t" to "console","id" to id,"seq" to s,"line" to l,"replay" to true))}
                c.send(mapOf("t" to "attached","id" to id,"lastSeq" to game.seq.get()))
            }
            "stdin" -> {
                val game=games[m.path("id").asText()] ?: return fail("NO_SUCH_INSTANCE")
                val expect=m.path("expectPid").asLong(0)
                if(expect!=0L && expect!=game.pid)return fail("WRONG_PROCESS")
                val cmd=m.path("cmdId").asText("");val line=m.path("line").asText()
                if(line.isBlank() || line.length>4096 || line.contains('\n') || line.contains('\r'))return fail("BAD_COMMAND")
                if(game.process==null || !game.process.isAlive)return fail(if(game.alive())"NOT_CONTROLLABLE" else "NOT_RUNNING")
                if(cmd.isNotEmpty()) synchronized(game.seenCommands){if(game.seenCommands.put(cmd,true)!=null)return ok(mapOf("duplicate" to true))}
                runCatching {synchronized(game.process){game.process.outputStream.write((line+"\n").toByteArray());game.process.outputStream.flush()}}.onFailure {return fail("WRITE_FAILED")}
                ok()
            }
            "stop","kill" -> {
                val game=games[m.path("id").asText()] ?: return fail("NO_SUCH_INSTANCE")
                val expect=m.path("expectPid").asLong(0)
                if(expect<=0 || expect!=game.pid)return fail("WRONG_PROCESS")
                val p=game.process ?: return fail("NOT_CONTROLLABLE")
                if(!p.isAlive)return fail("NOT_RUNNING")
                game.stopping=true
                if(m.path("t").asText()=="stop")runCatching {synchronized(p){p.outputStream.write("stop\n".toByteArray());p.outputStream.flush()}}
                else {p.descendants().use {s->s.forEach {runCatching {it.destroyForcibly()}}};p.destroyForcibly()}
                ok()
            }
            "ack-exit" -> {
                val game=games[m.path("id").asText()] ?: return ok()
                if(game.pid!=m.path("pid").asLong() || game.alive() || game.exitCode==null && game.managed)return fail("NOT_EXITED")
                game.exitAcked=true;games.remove(game.id,game);persist();ok()
            }
            "prepare-replacement" -> {
                if(anyAlive())return fail("GAME_RUNNING")
                if(relayRole!=null)return fail("RELAY_SERVICE_ACTIVE")
                replacementPrepared=true;ok(mapOf("prepared" to true))
            }
            "relay" -> {
                if(replacementPrepared)return fail("HOST_REPLACEMENT_PENDING")
                val message=runCatching {mapper.readValue(m.path("body").asText(),RunnerMessage::class.java)}.getOrNull() ?: return
                relay().onMessage(message)
            }
            "relay-session" -> if(replacementPrepared)fail("HOST_REPLACEMENT_PENDING") else relay().onSession()
            "relay-busy" -> ok(mapOf("busy" to (relayRole?.busy()==true)))
            else -> fail("UNKNOWN_COMMAND")
        }
    }
    private fun start(c:Controller,m:JsonNode,ok:(Map<String,Any?>)->Unit,fail:(String,Map<String,Any?>)->Unit) {
        val id=m.path("id").asText()
        if(!id.matches(Regex("[A-Za-z0-9-]{1,64}")))return fail("BAD_ID",emptyMap())
        val command=m.path("command").map {it.asText()};val directory=Path.of(m.path("dir").asText())
        if(command.isEmpty() || !directory.isDirectory())return fail("BAD_REQUEST",emptyMap())
        val game=synchronized(games) {
            val existing=games[id]
            if(existing!=null) {
                // Never start a second process for the same server.
                if(existing.alive())return ok(mapOf("duplicate" to true,"pid" to existing.pid,"startMillis" to existing.startMillis,"managed" to existing.managed))
                if(!existing.exitAcked)return fail("EXIT_UNACKED",mapOf("pid" to existing.pid,"exitCode" to existing.exitCode))
                games.remove(id)
            }
            val builder=ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
            val env=m.path("env");if(env.isObject)builder.environment().apply {clear();env.fields().forEach {(k,v)->put(k,v.asText())}}
            val process=try {builder.start()} catch(e:Exception){return fail("START_FAILED",mapOf("detail" to (e.message ?: e.javaClass.simpleName).take(300)))}
            val started=process.toHandle().info().startInstant().map {it.toEpochMilli()}.orElse(System.currentTimeMillis())
            Game(id,process,process.pid(),started).also {it.port=m.path("port").takeIf {p->p.isInt}?.asInt();games[id]=it}
        }
        persist()
        ok(mapOf("pid" to game.pid,"startMillis" to game.startMillis,"duplicate" to false))
        io.submit {pump(game)}
    }
    private fun pump(game:Game) {
        val process=game.process!!
        try {
            BufferedReader(InputStreamReader(process.inputStream,Charsets.UTF_8)).useLines {lines->lines.forEach {line->
                val s=game.seq.incrementAndGet()
                if(!game.ready && line.contains("Done (") && line.contains("For help"))game.ready=true
                synchronized(game.ring){game.ring.addLast(s to line);while(game.ring.size>ringSize)game.ring.removeFirst()}
                controller?.send(mapOf("t" to "console","id" to game.id,"seq" to s,"line" to line.take(8192)))
            }}
        } catch(_:Exception) {}
        val code=runCatching {process.waitFor()}.getOrDefault(-1)
        game.exitCode=code;persist()
        controller?.send(mapOf("t" to "exit","id" to game.id,"pid" to game.pid,"code" to code))
    }
    override fun close() {
        closed=true;runCatching {server.close()};controller?.close();runCatching {relayRole?.close()};io.shutdownNow()
        runCatching {lock?.release()};runCatching {lockChannel.close()}
        // Games deliberately keep running: closing the host handle never kills a game.
    }
}

/** Process view of a game owned by the resident host, so existing runner code can treat it like a normal Process. */
class HostedProcess(private val client:GameHostClient,val id:String,private val pid:Long,private val startMillis:Long):Process() {
    private val exit=CountDownLatch(1)
    private val console=QueueInputStream()
    @Volatile private var code:Int?=null
    /** True when the game had already finished starting at the moment this controller attached. */
    @Volatile var readyAtAttach=false
    private val stdin=object:OutputStream() {
        private val buffer=java.io.ByteArrayOutputStream()
        override fun write(b:Int){buffer.write(b)}
        override fun flush(){val text=buffer.toString(Charsets.UTF_8).also {buffer.reset()};text.split('\n').filter {it.isNotBlank()}.forEach {client.stdin(id,pid,it)}}
    }
    private val pendingSeqs=java.util.ArrayDeque<Long>()
    /** Highest console sequence the consumer has fully handled (for example written to its log file). */
    @Volatile var writtenSeq=0L;private set
    internal fun feed(seq:Long,line:String){synchronized(pendingSeqs){pendingSeqs.addLast(seq)};console.put((line+"\n").toByteArray())}
    /** Call once per line read from [getInputStream], after that line has been persisted. */
    fun markWritten(){synchronized(pendingSeqs){pendingSeqs.pollFirst()?.let {writtenSeq=it}}}
    fun savePosition(file:Path){runCatching {file.parent?.createDirectories();file.writeText(writtenSeq.toString())}}
    internal fun finished(exitCode:Int){code=exitCode;console.finish();exit.countDown()}
    override fun getOutputStream():OutputStream=stdin
    override fun getInputStream():InputStream=console
    override fun getErrorStream():InputStream=InputStream.nullInputStream()
    override fun waitFor():Int{exit.await();return code!!}
    override fun waitFor(timeout:Long,unit:TimeUnit):Boolean=exit.await(timeout,unit)
    override fun exitValue():Int=code ?: throw IllegalThreadStateException("process hasn't exited")
    override fun isAlive():Boolean=exit.count>0
    override fun destroy(){client.stop(id,pid)}
    override fun destroyForcibly():Process{client.kill(id,pid);return this}
    override fun pid():Long=pid
    override fun toHandle():ProcessHandle=ProcessHandle.of(pid).orElseThrow()
    override fun info():ProcessHandle.Info=toHandle().info()
}

class GameHostRejected(val reason:String):java.io.IOException(reason)

/** Controller side. A new client attaches after a controller update; it never restarts or duplicates a game. */
class GameHostClient private constructor(private val socket:Socket,private val reader:BufferedReader,private val controllerId:String):AutoCloseable {
    private val mapper=jacksonObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false) // a newer host may add fields
    private val writer=PrintWriter(socket.getOutputStream().bufferedWriter(Charsets.UTF_8),false)
    private val pending=ConcurrentHashMap<String,java.util.concurrent.CompletableFuture<JsonNode>>()
    private val processes=ConcurrentHashMap<String,HostedProcess>()
    private val counter=AtomicLong()
    @Volatile var hostVersion="";private set
    @Volatile var hostProtocol=0;private set
    @Volatile var initial:List<HostInstanceStatus> =emptyList();private set
    @Volatile var disconnected=false;private set
    /** Delivers every console line exactly once in order per instance; replays are de-duplicated by sequence number. */
    var onConsole:(String,Long,String)->Unit={_,_,_->}
    var onRelay:(String)->Unit={}
    var onExit:(String,Long,Int)->Unit={_,_,_->}
    private val lastSeq=ConcurrentHashMap<String,Long>()
    private val attachedLatches=ConcurrentHashMap<String,CountDownLatch>()

    companion object {
        fun readEndpoint(home:Path):HostEndpoint? {
            val file=home.resolve("host/endpoint.json");if(!file.isRegularFile())return null
            return runCatching {jacksonObjectMapper().readValue(file.readText(),HostEndpoint::class.java)}.getOrNull()
        }
        /** [minHost] is the oldest host protocol this controller can talk to. */
        fun connect(home:Path,controllerId:String,protocol:Int=GameHostProtocol.VERSION,minHost:Int=1,retryMillis:Long=0):GameHostClient {
            val end=System.currentTimeMillis()+retryMillis
            while(true) {
                try {return connectOnce(home,controllerId,protocol,minHost)}
                catch(e:GameHostRejected){if(e.reason!="CONTROLLER_ACTIVE" || System.currentTimeMillis()>=end)throw e}
                catch(e:java.io.IOException){if(System.currentTimeMillis()>=end)throw e}
                Thread.sleep(200)
            }
        }
        private fun connectOnce(home:Path,controllerId:String,protocol:Int,minHost:Int):GameHostClient {
            val endpoint=readEndpoint(home) ?: throw java.io.IOException("상주 게임 서비스가 실행 중이 아닙니다")
            val socket=Socket();socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"),endpoint.port),3000);socket.soTimeout=10000;socket.tcpNoDelay=true
            try {
                val reader=BufferedReader(InputStreamReader(socket.getInputStream(),Charsets.UTF_8));val mapper=jacksonObjectMapper()
                val challenge=mapper.readTree(reader.readLine() ?: throw java.io.IOException("서비스 응답 없음"))
                if(challenge.path("t").asText()!="challenge")throw java.io.IOException("서비스 프로토콜 오류")
                val nonce=Base64.getDecoder().decode(challenge.path("nonce").asText())
                val proof=GameHostProtocol.hmac(Base64.getUrlDecoder().decode(endpoint.secret),"gh-hello".toByteArray(),nonce)
                val out=socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                out.write(mapper.writeValueAsString(mapOf("t" to "hello","protocol" to protocol,"minHost" to minHost,"controller" to controllerId,"proof" to Base64.getEncoder().encodeToString(proof))));out.write("\n");out.flush()
                val reply=mapper.readTree(reader.readLine() ?: throw GameHostRejected("CLOSED"))
                if(reply.path("t").asText()=="reject")throw GameHostRejected(reply.path("reason").asText("REJECTED"))
                socket.soTimeout=0
                return GameHostClient(socket,reader,controllerId).also {it.accept(reply)}
            } catch(e:Exception){runCatching {socket.close()};throw e}
        }
    }
    private fun accept(welcome:JsonNode) {
        hostVersion=welcome.path("version").asText();hostProtocol=welcome.path("protocol").asInt()
        initial=welcome.path("instances").map {mapper.treeToValue(it,HostInstanceStatus::class.java)}
        Thread({readLoop()},"game-host-client").apply {isDaemon=true;start()}
    }
    private fun send(map:Map<String,Any?>):Boolean=synchronized(writer) {writer.println(mapper.writeValueAsString(map));writer.flush();!writer.checkError()}
    private fun request(map:Map<String,Any?>,timeoutMillis:Long=10000):JsonNode {
        val req="r"+counter.incrementAndGet();val future=java.util.concurrent.CompletableFuture<JsonNode>();pending[req]=future
        try {
            if(!send(map+("req" to req)))throw java.io.IOException("서비스 연결이 끊겼습니다")
            val reply=future.get(timeoutMillis,TimeUnit.MILLISECONDS)
            if(reply.path("t").asText()=="error")throw GameHostRejected(reply.path("reason").asText("ERROR"))
            return reply
        } catch(e:java.util.concurrent.TimeoutException){throw java.io.IOException("서비스 응답 시간 초과")} finally {pending.remove(req)}
    }
    private fun readLoop() {
        try {
            while(true) {
                val line=reader.readLine() ?: break
                val m=mapper.readTree(line)
                when(m.path("t").asText()) {
                    "ping" -> send(mapOf("t" to "pong"))
                    "ok","error" -> pending[m.path("req").asText()]?.complete(m)
                    "console" -> {
                        val id=m.path("id").asText();val seq=m.path("seq").asLong()
                        val fresh=synchronized(lastSeq){val prev=lastSeq[id] ?: 0;if(seq>prev){lastSeq[id]=seq;true}else false}
                        if(fresh){processes[id]?.feed(seq,m.path("line").asText());onConsole(id,seq,m.path("line").asText())}
                    }
                    "attached" -> attachedLatches[m.path("id").asText()]?.countDown()
                    "exit" -> {val id=m.path("id").asText();processes[id]?.finished(m.path("code").asInt());onExit(id,m.path("pid").asLong(),m.path("code").asInt())}
                    "relay" -> onRelay(m.path("body").asText())
                }
            }
        } catch(_:Exception) {}
        disconnected=true
        pending.values.forEach {it.completeExceptionally(java.io.IOException("서비스 연결이 끊겼습니다"))}
    }
    fun status():List<HostInstanceStatus> =request(mapOf("t" to "status")).path("instances").map {mapper.treeToValue(it,HostInstanceStatus::class.java)}
    class StartResult(val process:HostedProcess,val duplicate:Boolean)
    fun start(id:String,command:List<String>,dir:Path,env:Map<String,String>,port:Int?=null):StartResult {
        val reply=request(mapOf("t" to "start","id" to id,"command" to command,"dir" to dir.toString(),"env" to env)+(if(port!=null)mapOf("port" to port) else emptyMap()),30000)
        val process=HostedProcess(this,id,reply.path("pid").asLong(),reply.path("startMillis").asLong());processes[id]=process
        if(!reply.path("duplicate").asBoolean())attachInternal(id,0,process,adopting=false) else attachInternal(id,lastSeq[id] ?: 0,process)
        return StartResult(process,reply.path("duplicate").asBoolean())
    }
    /** Reconnect to a game that survived a controller update. Missed console output is replayed from [afterSeq]. */
    fun adopt(id:String,afterSeq:Long=0):HostedProcess? {
        val info=status().firstOrNull {it.id==id && it.managed && it.alive} ?: return null
        val process=HostedProcess(this,id,info.pid,info.startMillis);processes[id]=process
        attachInternal(id,afterSeq,process);return process
    }
    private fun attachInternal(id:String,afterSeq:Long,process:HostedProcess,adopting:Boolean=true) {
        val latch=CountDownLatch(1);attachedLatches[id]=latch
        lastSeq.remove(id);if(afterSeq>0)lastSeq[id]=afterSeq
        val reply=request(mapOf("t" to "attach","id" to id,"afterSeq" to afterSeq))
        process.readyAtAttach=reply.path("ready").asBoolean()
        if(!reply.path("alive").asBoolean()){reply.path("exitCode").takeIf {!it.isNull}?.let {process.finished(it.asInt())}}
        else if(!latch.await(10,TimeUnit.SECONDS))throw java.io.IOException("콘솔 재생이 끝나지 않았습니다")
        attachedLatches.remove(id)
    }
    fun stdin(id:String,pid:Long,line:String)=request(mapOf("t" to "stdin","id" to id,"expectPid" to pid,"cmdId" to java.util.UUID.randomUUID().toString(),"line" to line))
    fun stdinWithId(id:String,pid:Long,cmdId:String,line:String)=request(mapOf("t" to "stdin","id" to id,"expectPid" to pid,"cmdId" to cmdId,"line" to line))
    fun stop(id:String,pid:Long)=runCatching {request(mapOf("t" to "stop","id" to id,"expectPid" to pid))}
    fun kill(id:String,pid:Long)=runCatching {request(mapOf("t" to "kill","id" to id,"expectPid" to pid))}
    fun ackExit(id:String,pid:Long)=request(mapOf("t" to "ack-exit","id" to id,"pid" to pid))
    /** Direct-relay control traffic (manager messages for the relay role that lives in the host). */
    fun relayForward(json:String)=send(mapOf("t" to "relay","body" to json))
    fun relaySession()=send(mapOf("t" to "relay-session"))
    fun relayBusy():Boolean=runCatching {request(mapOf("t" to "relay-busy")).path("busy").asBoolean(true)}.getOrDefault(true)
    fun prepareReplacement():Boolean=request(mapOf("t" to "prepare-replacement")).path("prepared").asBoolean(false)
    /** Detach without touching any game. The host immediately accepts the next controller. */
    override fun close(){runCatching {socket.close()}}
}

/** Unbounded in-memory stream: the host connection's reader must never block on a slow console consumer. */
class QueueInputStream:InputStream() {
    private val queue=LinkedBlockingQueue<ByteArray>()
    private val eof=ByteArray(0)
    private var current=ByteArray(0);private var pos=0;private var ended=false
    fun put(bytes:ByteArray){if(bytes.isNotEmpty())queue.put(bytes)}
    fun finish(){queue.put(eof)}
    @Synchronized private fun fill():Boolean {
        while(pos>=current.size) {
            if(ended)return false
            val next=queue.take()
            if(next===eof){ended=true;return false}
            current=next;pos=0
        }
        return true
    }
    @Synchronized override fun read():Int=if(fill())current[pos++].toInt() and 0xff else -1
    @Synchronized override fun read(b:ByteArray,off:Int,len:Int):Int {
        if(len==0)return 0
        if(!fill())return -1
        val n=minOf(len,current.size-pos);System.arraycopy(current,pos,b,off,n);pos+=n;return n
    }
    override fun available():Int=synchronized(this){current.size-pos}
}

object ConsolePosition {
    fun load(file:Path):Long=runCatching {file.readText().trim().toLong()}.getOrDefault(0L)
}
