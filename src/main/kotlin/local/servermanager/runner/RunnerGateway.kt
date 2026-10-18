package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.AbstractWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class RunnerMessage(val type:String,val instanceId:String?=null,val state:String?=null,val version:String?=null,val detail:String?=null,
    val token:String?=null,val streamId:Int?=null,val command:String?=null,val cpuPercent:Double?=null,val rssMb:Double?=null,
    val uptimeSeconds:Long?=null,val playerCount:Int?=null,val players:List<String>?=null,val pid:Long?=null)

@Service
class RunnerGateway(private val registry:RunnerRegistry,private val manager:ServerManager,private val network:GameNetwork?=null,private val directRelay:DirectRelayService?=null):AbstractWebSocketHandler(),RemoteController {
    private val logger=org.slf4j.LoggerFactory.getLogger(RunnerGateway::class.java)
    private val mapper=jacksonObjectMapper()
    private val sessions=ConcurrentHashMap<String,WebSocketSession>()
    private val tokens=ConcurrentHashMap<String,String>()
    private val tcpRelays=ConcurrentHashMap<String,TcpRelayPeer>()
    private fun syncTcpRelay(id:String) {
        val config=network?.config() ?: return
        if(!registry.tcpRelaySupported(id)) {
            tcpRelays.remove(id)?.close()
            if(registry.active(id)==null) {send(id,RunnerMessage("relay-config",state="OFF"));runCatching {sessions[id]?.close(CloseStatus.POLICY_VIOLATION)}}
            if(config.relayRunnerId==id)network.relayMessage="선택한 실행기가 오프라인이거나 TCP 중계 수신을 허용하지 않았습니다"
            return
        }
        val enabled=config.enabled && config.relayRunnerId==id && activeRoutes.isNotEmpty()
        if(enabled)tcpRelays.computeIfAbsent(id) {TcpRelayPeer({send(id,it)},{stream,bytes,count->sendBinary(id,stream,bytes,count)})}
        else tcpRelays.remove(id)?.close()
        send(id,RunnerMessage("relay-config",state=if(enabled)"ON" else "OFF",detail=config.port.toString()))
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=5000) fun syncTcpRelays() {
        sessions.keys.forEach {syncTcpRelay(it)}
        if(network?.config()?.relayRunnerId==null)network?.relayMessage="중앙 Mac 중계"
        network?.config()?.relayRunnerId?.let {if(!connected(it))network.relayMessage="중계 실행기 오프라인"}
    }
    private data class PendingWorkspace(val runner:String,val instance:String,val future:java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode>)
    private val workspaceRequests=ConcurrentHashMap<String,PendingWorkspace>()
    private val workspaceSlots=java.util.concurrent.Semaphore(32)
    private data class PendingLlm(val runner:String,val future:java.util.concurrent.CompletableFuture<com.fasterxml.jackson.databind.JsonNode>,val progress:(QwenProgress)->Unit={})
    private val llmRequests=ConcurrentHashMap<String,PendingLlm>()
    private val llmRunners=ConcurrentHashMap.newKeySet<String>()
    private val restartRunners=ConcurrentHashMap.newKeySet<String>()
    private val restartRequests=ConcurrentHashMap<String,java.util.concurrent.CompletableFuture<String?>>()
    fun restartSupported(id:String)=id in restartRunners
    /** Returns null when the runner accepted the restart, otherwise its refusal reason. */
    fun requestRestart(id:String):String? {
        check(connected(id) && registry.active(id)!=null) {"실행기를 온라인으로 연결하세요"}
        check(restartSupported(id)) {"이 실행기는 원격 재시작을 지원하지 않습니다. 먼저 새 버전으로 업데이트하세요"}
        val future=java.util.concurrent.CompletableFuture<String?>()
        check(restartRequests.putIfAbsent(id,future)==null) {"이미 재시작을 요청했습니다"}
        try {
            check(send(id,RunnerMessage("restart-request"))) {"실행기 연결이 끊겼습니다"}
            return future.get(10,TimeUnit.SECONDS)
        } catch(e:java.util.concurrent.TimeoutException){throw IllegalStateException("실행기가 응답하지 않았습니다")}
        finally {restartRequests.remove(id,future)}
    }
    private val agentLlmRunners=ConcurrentHashMap.newKeySet<String>()
    private val budgetLlmRunners=ConcurrentHashMap.newKeySet<String>()
    private val streamingLlmRunners=ConcurrentHashMap.newKeySet<String>()
    private val llmAnswered=ConcurrentHashMap.newKeySet<String>()
    /** ALLOWED: the runner owner enabled Qwen; DENIED: connected but not enabled; UNKNOWN: offline or older than 0.5.0. */
    fun llmCapability(id:String):String = when {
        !connected(id) -> "UNKNOWN"
        id in llmRunners -> "ALLOWED"
        id in llmAnswered -> "DENIED"
        else -> "UNKNOWN"
    }
    fun llmReady(id:String)=connected(id) && registry.active(id)!=null && id in llmRunners
    private fun llmCall(id:String,state:String,prompt:String,think:Boolean,seconds:Long,progress:(QwenProgress)->Unit={}):com.fasterxml.jackson.databind.JsonNode {
        check(llmReady(id)) {"지정한 실행기가 오프라인이거나 Qwen 실행을 허용하지 않았습니다. 실행기의 llm-enabled 설정을 확인하세요."}
        val key=java.util.UUID.randomUUID().toString();val pending=PendingLlm(id,java.util.concurrent.CompletableFuture(),progress);llmRequests[key]=pending
        try {
            check(send(id,RunnerMessage("llm-request",token=key,state=state,command=prompt,detail=if(think)"THINK" else "NO_THINK"))) {"실행기 연결을 확인하세요"}
            return pending.future.get(seconds,TimeUnit.SECONDS)
        }
        catch(e:java.util.concurrent.TimeoutException){send(id,RunnerMessage("llm-cancel",token=key));throw IllegalStateException("실행기의 Qwen 응답이 시간 제한을 초과했습니다")}
        catch(e:InterruptedException){send(id,RunnerMessage("llm-cancel",token=key));Thread.currentThread().interrupt();throw IllegalStateException("실행기의 Qwen 생성을 중단했습니다.",e)}
        catch(e:java.util.concurrent.ExecutionException){throw IllegalStateException(e.cause?.message ?: "실행기의 Qwen 요청 실패")}
        finally {llmRequests.remove(key)}
    }
    /** op is "probe" (read Ollama status) or "unload" (free every Qwen model, then report status). */
    fun llmAdmin(id:String,op:String):com.fasterxml.jackson.databind.JsonNode {
        require(op=="probe" || op=="unload")
        return llmCall(id,op,"",false,if(op=="probe")20 else 60)
    }
    /**
     * Uses [runnerId] when pinned (throws if it cannot serve), otherwise the first connected opted-in runner.
     * Null means no runner is available so the caller may fall back to the manager computer.
     */
    fun llmChat(size:String,prompt:String,think:Boolean,runnerId:String?=null):RemoteLlm.Result? {
        val id=runnerId ?: llmRunners.sorted().firstOrNull {llmReady(it)} ?: return null
        val answer=mapper.treeToValue(llmCall(id,size,prompt,think,190),LocalLlm.Answer::class.java)
        return RemoteLlm.Result(answer,registry.active(id)?.name ?: id)
    }
    fun llmConversation(size:String,messages:List<ProjectAgentMessage>,think:Boolean,runnerId:String?,progress:(QwenProgress)->Unit):RemoteLlm.Result? {
        val id=runnerId ?: streamingLlmRunners.sorted().firstOrNull {llmReady(it)} ?: return null
        check(id in streamingLlmRunners && llmReady(id)) {"선택한 실행기가 실시간 출력·중지를 지원하지 않습니다. 관리 앱과 실행기를 새 Qwen 베타로 업데이트하세요."}
        val answer=mapper.treeToValue(llmCall(id,"agent:$size",mapper.writeValueAsString(messages),think,190,progress),LocalLlm.Answer::class.java)
        return RemoteLlm.Result(answer,registry.active(id)?.name ?: id)
    }

    fun llmBudgetConversation(size:String,messages:List<ProjectAgentMessage>,think:Boolean,runnerId:String?,budget:LocalContextBudget,progress:(QwenProgress)->Unit):RemoteLlm.Result? {
        val id=runnerId ?: budgetLlmRunners.sorted().firstOrNull {llmReady(it)} ?: streamingLlmRunners.sorted().firstOrNull {llmReady(it)} ?: return null
        if(id !in budgetLlmRunners) {
            check(!ProjectAgentNativeTools.active(messages)) {"공식 Ollama 도구 호출에는 추론 실행기도 새 Beta 11로 업데이트해야 합니다."}
            check(budget.window==8192) {"16K 문맥에는 추론 실행기도 Beta 11 이상으로 업데이트해야 합니다."}
            check(budget.output==4096 || messages.firstOrNull()?.content?.startsWith("You are 루오노타르")!=true) {"출력 예약 변경에는 추론 실행기도 Beta 11 이상으로 업데이트해야 합니다."}
            return llmConversation(size,messages,think,id,progress)
        }
        check(llmReady(id)) {"선택한 실행기가 오프라인이거나 LLM 허용이 해제됐습니다."}
        val payload=mapper.writeValueAsString(mapOf("messages" to messages.map {mapOf("role" to it.role,"content" to it.content,"native" to it.native)},"budget" to budget))
        val answer=mapper.treeToValue(llmCall(id,"agent-profile:$size",payload,think,190,progress),LocalLlm.Answer::class.java)
        return RemoteLlm.Result(answer,registry.active(id)?.name ?: id)
    }

    private val sableStates=ConcurrentHashMap<String,String>()
    private val udpSessions=ConcurrentHashMap<String,WebSocketSession>()
    private val udpOutboxes=ConcurrentHashMap<String,DatagramOutbox>()
    private val sable=SableRelay({runner,stream->
        sockets[stream]?.takeIf {it.runnerId==runner && it.socket.isConnected && !it.socket.isClosed && connected(runner) && registry.active(runner)?.allowedInstanceIds?.contains(it.instanceId)==true}
            ?.let {SableRoute(it.instanceId,it.socket.inetAddress)}
    },{runner,frame->udpOutboxes[runner]?.offer(frame)==true})
    private val sockets=ConcurrentHashMap<Int,GameStream>()
    private val listeners=ConcurrentHashMap<String,ServerSocket>()
    fun controllerRelayStreams()=sockets.isNotEmpty() || tcpRelays.values.any {it.busy()}
    private val streams=AtomicInteger(1)
    private val activeRoutes=ConcurrentHashMap<String,String>()
    private val handshakeSlots=java.util.concurrent.Semaphore(64)
    private var sharedListener:ServerSocket?=null
    private val io=Executors.newCachedThreadPool()
    private data class GameStream(val id:Int,val runnerId:String,val instanceId:String,val socket:Socket,val opened:CountDownLatch=CountDownLatch(1))
    @jakarta.annotation.PreDestroy fun closeDatagrams(){tcpRelays.values.forEach {it.close()};tcpRelays.clear();sable.close();udpOutboxes.values.forEach {it.close()};udpOutboxes.clear();udpSessions.values.forEach {runCatching {it.close()}};udpSessions.clear()}
    @PostConstruct fun attach() { manager.attachRemoteController(this);RemoteLlm.provider=::llmChat;RemoteLlm.conversationProvider=::llmConversation;RemoteLlm.budgetConversationProvider=::llmBudgetConversation;RemoteLlm.modelLimitProvider={size,pinned->val id=pinned ?: streamingLlmRunners.sorted().firstOrNull {llmReady(it)};id?.let {llmAdmin(it,"probe").path("modelLimits").path(size).asInt().takeIf {value->value>0}}};directRelay?.sender={id,message->send(id,message)};directRelay?.connectedCheck={connected(it)} }
    override fun afterConnectionEstablished(session:WebSocketSession) {
        val id=session.attributes["runnerId"] as? String ?: run { session.close(CloseStatus.POLICY_VIOLATION);return }
        if(session.attributes["sableUdp"]==true) {
            if(!connected(id)){session.close(CloseStatus.POLICY_VIOLATION);return}
            session.binaryMessageSizeLimit=65511
            val decorated=org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator(session,1000,1024*1024)
            udpSessions.put(id,session)?.let {runCatching {it.close()}}
            udpOutboxes.put(id,DatagramOutbox {frame->runCatching {decorated.sendMessage(BinaryMessage(frame));true}.getOrDefault(false)})?.close()
            return
        }
        tcpRelays.remove(id)?.close()
        restartRunners.remove(id);llmRunners.remove(id);agentLlmRunners.remove(id);streamingLlmRunners.remove(id);budgetLlmRunners.remove(id);llmAnswered.remove(id)
        registry.tcpRelayCapability(id,false)
        registry.connected(id)
        sessions.put(id,session)?.takeIf { it!==session }?.let { previous ->
            sockets.values.filter {it.runnerId==id}.forEach {closeStream(it,false)}
            manager.remoteDisconnected(id)
            previous.close(CloseStatus.NORMAL)
        }
        session.binaryMessageSizeLimit=128*1024
        session.textMessageSizeLimit=256*1024
        val token=ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        tokens[id]=token
        registry.seen(id,registry.device(id)?.version ?: "unknown")
        send(id,RunnerMessage("session",token=token))
    }
    override fun handleTextMessage(session:WebSocketSession,message:TextMessage) {
        val id=session.attributes["runnerId"] as? String ?: return
        if(sessions[id]!==session || registry.active(id)==null) { session.close(CloseStatus.POLICY_VIOLATION);return }
        if(message.payload.length>256*1024) { session.close(CloseStatus.TOO_BIG_TO_PROCESS);return }
        val event=runCatching { mapper.readValue<RunnerMessage>(message.payload) }.getOrNull() ?: return
        if(directRelay?.core?.onRunnerMessage(id,event)==true)return
        when(event.type) {
            "restart-capability" -> if(event.state=="RESTART_V1" && registry.active(id)!=null)restartRunners.add(id) else restartRunners.remove(id)
            "restart-result" -> restartRequests[id]?.complete(if(event.state=="ACCEPTED")null else (event.detail ?: "REFUSED").take(60))
            "relay-capability" -> {
                registry.tcpRelayCapability(id,event.state=="TCP_V1")
                if(event.state=="TCP_V1")tcpRelays.computeIfAbsent(id) {TcpRelayPeer({send(id,it)},{stream,bytes,count->sendBinary(id,stream,bytes,count)})}
                syncTcpRelay(id)
            }
            "llm-capability" -> {if(event.state=="OLLAMA_AGENT_V3" && event.detail=="CONTEXT_TOOLS_V1" && registry.active(id)!=null)budgetLlmRunners.add(id)else budgetLlmRunners.remove(id);llmAnswered.add(id);if(event.state in setOf("OLLAMA_V1","OLLAMA_AGENT_V2","OLLAMA_AGENT_V3") && registry.active(id)!=null)llmRunners.add(id) else llmRunners.remove(id);if(event.state in setOf("OLLAMA_AGENT_V2","OLLAMA_AGENT_V3") && registry.active(id)!=null)agentLlmRunners.add(id) else agentLlmRunners.remove(id);if(event.state=="OLLAMA_AGENT_V3" && registry.active(id)!=null)streamingLlmRunners.add(id) else streamingLlmRunners.remove(id)}
            "llm-progress" -> if(id in streamingLlmRunners)event.token?.let {key->llmRequests[key]?.takeIf {it.runner==id}?.let {pending->event.detail?.takeIf {it.length<=32768}?.let {raw->runCatching {pending.progress(mapper.readValue<QwenProgress>(raw).bounded())}}}}
            "llm-thinking" -> event.token?.let {key->llmRequests[key]?.takeIf {it.runner==id}?.let {runCatching {it.progress(QwenProgress(thinking=event.detail.orEmpty().take(1500)))}}}
            "llm-result" -> event.token?.let {key->llmRequests[key]?.takeIf {it.runner==id}?.let {pending->
                if(event.state=="OK")runCatching {mapper.readTree(event.detail ?: "")}.onSuccess {pending.future.complete(it)}.onFailure {pending.future.completeExceptionally(IllegalStateException("실행기 Qwen 응답을 읽지 못했습니다"))}
                else pending.future.completeExceptionally(IllegalStateException(event.detail?.take(500) ?: "실행기의 Qwen 요청 실패"))
            }}
            "relay-status" -> if(network?.config()?.relayRunnerId==id)network.relayMessage=when(event.state) {"READY"->"실행기 TCP 중계 준비 완료";"OFF"->"실행기 TCP 중계 대기 · 게임 서버 정지";else->"실행기 TCP 포트를 열지 못했습니다. 로컬 수신 허용·포트 충돌을 확인하세요"}
            "relay-open" -> event.streamId?.takeIf {it<0}?.let {stream->
                val config=network?.config()
                if(config?.enabled==true && config.relayRunnerId==id && registry.tcpRelaySupported(id) && activeRoutes.isNotEmpty())tcpRelays[id]?.open(stream,config.port)
                else send(id,RunnerMessage("relay-closed",streamId=stream))
            }
            "relay-closed" -> event.streamId?.takeIf {it<0}?.let {tcpRelays[id]?.end(it)}
            "sable-register" -> {
                val stream=event.streamId;val instance=event.instanceId;val token=event.token
                val accepted=stream!=null && instance!=null && token!=null && network?.config()?.sableUdpEnabled==true &&
                    runCatching {sable.register(id,stream,instance,java.util.UUID.fromString(token))}.getOrDefault(false)
                send(id,RunnerMessage("sable-registered",instanceId=instance,state=if(accepted)"OK" else "DENIED",streamId=stream,token=token))
            }
            "query-status" -> event.instanceId?.let {instance->if(registry.active(id)?.allowedInstanceIds?.contains(instance)==true)manager.remoteQueryStatus(instance,id,event.state=="UNKNOWN")}
            "sable-status" -> event.instanceId?.let {instance->if(manager.instance(instance).runnerId==id && registry.active(id)?.allowedInstanceIds?.contains(instance)==true)sableStates[instance]=event.detail?.take(200) ?: "TCP_FALLBACK"}

            "workspace-result" -> event.token?.let {key->workspaceRequests[key]?.takeIf {it.runner==id && it.instance==event.instanceId}?.let {pending->
                if(event.state=="OK")runCatching {mapper.readTree(event.detail ?: "{}")}.onSuccess {pending.future.complete(it)}.onFailure {pending.future.completeExceptionally(it)}
                else pending.future.completeExceptionally(IllegalStateException(event.detail?.take(500) ?: "실행기 파일 작업 실패"))
            }}
            "install-result" -> if(event.instanceId!=null && event.token!=null)manager.remoteInstallResult(event.instanceId,id,event.token,event.state=="OK",event.detail)

            "heartbeat" -> {
                // Server-to-runner traffic also keeps reverse-proxy read timeouts alive
                // during a long HTTP upload, when no control command is being sent.
                send(id,RunnerMessage("heartbeat-ack"))
                registry.seen(id,event.version?.take(80) ?: "unknown")
                manager.pendingFolderImports(id).forEach { (instance,request)->runCatching {importFolder(instance,request)} }
            }
            "update-capability" -> registry.updateCapability(id,event.state=="RELEASE_SELECTION_V1",event.detail=="ROLLBACK_V1")
            "update-status" -> event.detail?.let {runCatching {registry.updateStatus(id,mapper.readValue<RunnerUpdateStatus>(it))}}
            "update-detail" -> event.detail?.takeIf {it.length<=200_000}?.let {runCatching {registry.updateDetail(id,mapper.readValue<UpdateDetail>(it))}}
            "folder-source" -> event.instanceId?.let { instance ->
                if(registry.active(id)?.allowedInstanceIds?.contains(instance)==true)runCatching {manager.folderSource(instance,id,event.state.orEmpty(),event.detail)}
            }
            "import-progress" -> if(event.instanceId!=null && event.token!=null && event.detail!=null)runCatching {
                manager.runnerFolderProgress(event.instanceId,id,event.token,mapper.readValue<FolderImportProgress>(event.detail))
            }
            "file-progress" -> if(event.instanceId!=null && event.detail!=null)runCatching {manager.runnerFileProgress(event.instanceId,id,mapper.readValue<FolderImportProgress>(event.detail))}
            "import-failed" -> if(event.instanceId!=null && event.token!=null)runCatching {manager.folderImportFailed(event.instanceId,id,event.token,event.detail)}
            "stop-progress" -> if(event.instanceId!=null&&event.pid!=null&&event.detail!=null)runCatching {manager.remoteStopProgress(event.instanceId,id,event.pid,mapper.readValue<StopWatchStatus>(event.detail))}
            "idle-result" -> event.instanceId?.let {manager.remoteIdleResult(it,id,event.detail,event.state=="ACCEPTED")}
            "state" -> {
                registry.seen(id,event.version?.take(80) ?: "unknown")
                val instanceId=event.instanceId ?: return
                if(manager.instance(instanceId).runnerId!=id) return
                if(event.state=="START_FAILED") manager.remoteStartFailed(instanceId,id,event.detail)
                else if(event.state=="STOPPED") manager.remoteWorkerReady(instanceId,id)
                else event.state?.let { manager.remoteState(instanceId,id,it,event.detail) }
                manager.remoteMetrics(instanceId,id,event.pid,event.cpuPercent,event.rssMb,event.uptimeSeconds,event.playerCount,event.players)
                // A manager restart loses in-memory listeners while Minecraft may
                // remain alive on its runner. Restore only from this authenticated
                // session's current, authorized running generation; never send start.
                if(event.state=="RUNNING" && event.pid!=null && event.pid>0 && registry.active(id)?.allowedInstanceIds?.contains(instanceId)==true) {
                    val runtime=manager.runtime(instanceId)
                    synchronized(runtime) {
                        if(runtime.state=="RUNNING" && runtime.remotePid==event.pid && !runtime.recoveringFiles) {
                            try {openRelay(manager.instance(instanceId),requireRunner(manager.instance(instanceId)))}
                            catch(e:Exception){logger.warn("Running server relay recovery failed for {}: {}",instanceId,e.message)}
                        }
                    }
                }
            }
            "log" -> event.instanceId?.let { manager.remoteLog(it,id,event.state ?: "output",event.detail ?: "") }
            "opened" -> event.streamId?.let { stream -> sockets[stream]?.takeIf { it.runnerId==id }?.opened?.countDown() }
            "closed" -> event.streamId?.let { stream -> sockets[stream]?.takeIf { it.runnerId==id }?.let { closeStream(it,false) } }
        }
    }
    override fun handleBinaryMessage(session:WebSocketSession,message:BinaryMessage) {
        val id=session.attributes["runnerId"] as? String ?: return
        if(session.attributes["sableUdp"]==true) {
            if(udpSessions[id]!==session || !connected(id))return
            val buffer=message.payload;if(buffer.remaining() !in 5..65511)return
            sable.downstream(id,ByteArray(buffer.remaining()).also(buffer::get));return
        }
        if(sessions[id]!==session)return
        val buffer=message.payload
        if(buffer.remaining()<4 || buffer.remaining()>65540)return
        val streamId=buffer.int
        if(streamId<0) {
            if(network?.config()?.relayRunnerId==id && registry.tcpRelaySupported(id))tcpRelays[id]?.receive(streamId,ByteArray(buffer.remaining()).also(buffer::get))
            return
        }
        val stream=sockets[streamId]?.takeIf { it.runnerId==id } ?: return
        val bytes=ByteArray(buffer.remaining());buffer.get(bytes)
        runCatching { stream.socket.getOutputStream().write(bytes);stream.socket.getOutputStream().flush() }.onFailure { closeStream(stream,true) }
    }
    override fun afterConnectionClosed(session:WebSocketSession,status:CloseStatus) {
        val id=session.attributes["runnerId"] as? String ?: return
        if(session.attributes["sableUdp"]==true){if(udpSessions.remove(id,session)){udpOutboxes.remove(id)?.close();sable.removeRunner(id)};return}
        if(sessions.remove(id,session)) {
            tcpRelays.remove(id)?.close()
            udpOutboxes.remove(id)?.close();udpSessions.remove(id)?.let {runCatching {it.close()}};sable.removeRunner(id)
            tokens.remove(id);registry.disconnected(id);manager.remoteDisconnected(id);directRelay?.core?.onDisconnect(id)
            restartRunners.remove(id);llmRunners.remove(id);agentLlmRunners.remove(id);streamingLlmRunners.remove(id);budgetLlmRunners.remove(id);llmAnswered.remove(id);llmRequests.values.filter {it.runner==id}.forEach {it.future.completeExceptionally(IllegalStateException("실행기가 연결 해제됐습니다"))}
            workspaceRequests.values.filter {it.runner==id}.forEach {it.future.completeExceptionally(IllegalStateException("실행기가 연결 해제됐습니다. 파일을 다시 확인하세요"))}
            sockets.values.filter { it.runnerId==id }.forEach { closeStream(it,false) }
        }
    }
    fun requestUpdate(id:String,pause:Boolean,version:String?=null,allowPrerelease:Boolean=false,allowRollback:Boolean=false):Boolean {
        check(registry.remoteUpdateSupported(id))
        if(version!=null){SignedUpdates.compare(version,"0.0.0");check(registry.releaseSelectionSupported(id));if(allowRollback)check(registry.rollbackSupported(id))}
        return connected(id) && send(id,RunnerMessage("update-request",detail=if(pause) "PAUSE_TRANSFER" else "WHEN_IDLE",command=version,state=if(allowRollback)"STABLE_ROLLBACK" else if(allowPrerelease)"BETA_OK" else null))
    }
    fun connected(id:String)=sessions[id]?.isOpen==true && registry.online(id)
    fun authorized(id:String,token:String?):Boolean = token!=null && registry.active(id)!=null && connected(id) &&
        MessageDigest.isEqual((tokens[id] ?: "").toByteArray(),token.toByteArray())
    fun send(id:String,message:RunnerMessage):Boolean {
        val session=sessions[id] ?: return false
        return runCatching { synchronized(session) { if(!session.isOpen)return false;session.sendMessage(TextMessage(mapper.writeValueAsString(message))) };true }.getOrDefault(false)
    }
    private fun sendBinary(id:String,streamId:Int,bytes:ByteArray,count:Int):Boolean {
        val session=sessions[id] ?: return false
        return runCatching {
            val frame=ByteBuffer.allocate(4+count).putInt(streamId).put(bytes,0,count).flip()
            synchronized(session) { if(!session.isOpen)return false;session.sendMessage(BinaryMessage(frame)) }
            true
        }.getOrDefault(false)
    }
    private fun relayPlayer(socket:Socket,instanceId:String,runnerId:String,initial:ByteArray=byteArrayOf()) {
        if(sockets.size>=500 || sockets.values.count { it.runnerId==runnerId }>=100 || !connected(runnerId)) {socket.close();return}
        val stream=GameStream(streams.incrementAndGet(),runnerId,instanceId,socket)
        sockets[stream.id]=stream
        if(!send(runnerId,RunnerMessage("open",instanceId=instanceId,streamId=stream.id))) {closeStream(stream,false);return}
        io.submit {
            try {
                if(!stream.opened.await(5,TimeUnit.SECONDS)){logger.debug("Game stream open timed out for instance {}",instanceId);return@submit}
                socket.soTimeout=60000
                if(initial.isNotEmpty() && !sendBinary(runnerId,stream.id,initial,initial.size))return@submit
                val input=socket.getInputStream();val buffer=ByteArray(64*1024)
                while(true) {val count=input.read(buffer);if(count<0)break;if(!sendBinary(runnerId,stream.id,buffer,count))break}
            } catch (e:Exception) {logger.debug("Game stream ended: {}",e.javaClass.simpleName)} finally {closeStream(stream,true)}
        }
    }
    @Synchronized private fun openSharedRelay(instance:Instance,runnerId:String) {
        val service=network ?: error("공유 중계를 설정하세요")
        val config=service.config()
        check(config.enabled)
        check(activeRoutes.none { (id,runner)->runner==runnerId && id!=instance.id && manager.instance(id).port==instance.port }) { "이 실행기의 내부 게임 포트를 다른 서버가 사용 중입니다" }
        if(sharedListener?.isClosed!=false) {
            val listener=ServerSocket();listener.reuseAddress=false
            try {listener.bind(InetSocketAddress(if(config.relayRunnerId==null)"0.0.0.0" else "127.0.0.1",config.port),50)}
            catch(e:Exception){listener.close();throw IllegalStateException("공유 게임 포트 ${config.port}를 열 수 없습니다")}
            sharedListener=listener
            io.submit {
                while(!listener.isClosed) {
                    val player=runCatching {listener.accept()}.getOrNull() ?: break
                    if(!handshakeSlots.tryAcquire()){player.close();continue}
                    io.submit {
                        var handed=false
                        try {
                            player.soTimeout=5000
                            val handshake=MinecraftHandshake.read(player.getInputStream())
                            val id=service.resolve(handshake.host) ?: run {logger.debug("Unknown game handshake host");return@submit}
                            val runner=activeRoutes[id] ?: return@submit
                            if(!connected(runner))return@submit
                            relayPlayer(player,id,runner,handshake.bytes);handed=true
                        } catch (e:Exception) {logger.debug("Game handshake rejected: {}",e.javaClass.simpleName)} finally {handshakeSlots.release();if(!handed)runCatching {player.close()}}
                    }
                }
            }
        }
        if(config.sableUdpEnabled)sable.listen(config.port)
        activeRoutes[instance.id]=runnerId
        config.relayRunnerId?.let {syncTcpRelay(it)}
    }
    private fun openRelay(instance:Instance,runnerId:String) {
        if(network?.config()?.enabled==true){openSharedRelay(instance,runnerId);return}
        if(listeners.containsKey(instance.id))return
        val listener=ServerSocket();listener.reuseAddress=false
        try {listener.bind(InetSocketAddress("0.0.0.0",instance.port),50)}
        catch(e:Exception){listener.close();throw IllegalStateException("게임 중계 포트 ${instance.port}를 열 수 없습니다")}
        listeners[instance.id]=listener
        io.submit {while(!listener.isClosed){val socket=runCatching {listener.accept()}.getOrNull() ?: break;relayPlayer(socket,instance.id,runnerId)}}
    }
    override fun sableStatus(instance:Instance):SableRelayStatus {
        if(network?.config()?.sableUdpEnabled!=true)return SableRelayStatus("OFF")
        val runner=instance.runnerId ?: return SableRelayStatus("UNSUPPORTED",message="원격 실행기를 선택하세요")
        if(!runCatching {SignedUpdates.compare(registry.active(runner)?.version ?: "unknown","0.4.6")>=0}.getOrDefault(false))return SableRelayStatus("UNSUPPORTED",message="실행기 0.4.6 이상이 필요합니다")
        val state=sableStates[instance.id]
        if(state!=null && state!="READY")return SableRelayStatus("TCP_FALLBACK",message=state)
        return sable.status(instance.id)
    }
    override fun sharedGamePort():Int?=network?.config()?.takeIf {it.enabled}?.port
    override fun stopIfEmpty(instance:Instance,requestId:String):Boolean {
        val id=requireRunner(instance)
        val version=registry.active(id)?.version ?: return false
        if(!runCatching {SignedUpdates.compare(version,"0.4.0-beta.6")>=0}.getOrDefault(false))return false
        return send(id,RunnerMessage("idle-stop",instanceId=instance.id,detail=requestId))
    }
    private fun closeStream(stream:GameStream,notify:Boolean) {
        if(sockets.remove(stream.id,stream)) {
            sable.removeStream(stream.runnerId,stream.id)
            runCatching { stream.socket.close() }
            if(notify)send(stream.runnerId,RunnerMessage("close",instanceId=stream.instanceId,streamId=stream.id))
        }
    }
    private fun requireRunner(instance:Instance):String {
        val id=instance.runnerId ?: error("실행기를 선택하세요")
        check(registry.active(id)?.allowedInstanceIds?.contains(instance.id)==true) { "기기 등록자의 서버 사용 허용이 필요합니다" }
        check(connected(id)) { "실행기가 오프라인입니다" }
        return id
    }
    override fun supportsWorkspace(instance:Instance):Boolean {
        val id=instance.runnerId ?: return false
        return connected(id) && runCatching {SignedUpdates.compare(registry.active(id)?.version ?: "unknown","0.4.5")>=0}.getOrDefault(false)
    }
    override fun workspace(instance:Instance,request:WorkspaceRequest):com.fasterxml.jackson.databind.JsonNode {
        val id=requireRunner(instance);check(supportsWorkspace(instance)) {"실행기를 0.4.5 이상으로 업데이트하세요"}
        check(workspaceSlots.tryAcquire()) {"파일 요청이 많습니다. 잠시 후 다시 시도하세요"}
        val key=java.util.UUID.randomUUID().toString();val pending=PendingWorkspace(id,instance.id,java.util.concurrent.CompletableFuture());workspaceRequests[key]=pending
        try {check(send(id,RunnerMessage("workspace",instanceId=instance.id,token=key,detail=mapper.writeValueAsString(request)))) {"실행기 연결을 확인하세요"};return pending.future.get(45,TimeUnit.SECONDS)}
        catch(e:java.util.concurrent.TimeoutException){throw IllegalStateException("실행기 응답이 지연됐습니다. 작업 결과를 확인한 뒤 다시 시도하세요")}
        catch(e:java.util.concurrent.ExecutionException){throw IllegalStateException(e.cause?.message ?: "실행기 파일 작업 실패")}
        finally{workspaceRequests.remove(key);workspaceSlots.release()}
    }
    override fun install(instance:Instance,requestId:String) {
        val id=requireRunner(instance);check(supportsWorkspace(instance));check(send(id,RunnerMessage("install",instanceId=instance.id,token=requestId,detail=mapper.writeValueAsString(instance))))
    }
    override fun start(instance:Instance) {
        val id=requireRunner(instance)
        check(instance.syncEnabled() || supportsWorkspace(instance)) {"파일 동기화 OFF에는 실행기 0.4.5 이상이 필요합니다"}
        if(instance.loader!="NeoForge" || instance.minecraft!="1.21.1") {
            val version=registry.active(id)?.version ?: "unknown"
            check(runCatching { SignedUpdates.compare(version,"0.4.0-beta.5")>=0 }.getOrDefault(false)) { "선택한 실행기를 beta.5 이상으로 업데이트한 뒤 이 런처를 시작하세요" }
        }
        if(instance.diagnosticSession!=null)check(runCatching {SignedUpdates.compare(registry.active(id)?.version ?: "unknown","0.4.3")>=0}.getOrDefault(false)) {"격리 모드 검사를 위해 실행기를 0.4.3 이상으로 업데이트하세요"}
        openRelay(instance,id)
        if(runCatching {SignedUpdates.compare(registry.active(id)?.version ?: "unknown","0.4.6")>=0}.getOrDefault(false))
            send(id,RunnerMessage("sable-config",state=if(network?.config()?.enabled==true && network.config().sableUdpEnabled)"ON" else "OFF"))
        sableStates.remove(instance.id)
        if(!send(id,RunnerMessage("start",instanceId=instance.id,detail=mapper.writeValueAsString(instance)))) {
            completed(instance);error("실행기에 시작 명령을 전달하지 못했습니다")
        }
    }
    override fun importFolder(instance:Instance,requestId:String) {
        val id=requireRunner(instance)
        check(runCatching {SignedUpdates.compare(registry.active(id)!!.version,"0.4.0-beta.8")>=0}.getOrDefault(false)) { "서버 실행기를 beta.8 이상으로 업데이트하세요. 실행기를 다시 시작하면 새 업데이트를 확인합니다" }
        check(send(id,RunnerMessage("import-folder",instanceId=instance.id,detail=mapper.writeValueAsString(instance),token=requestId))) { "실행기에 가져오기 명령을 전달하지 못했습니다" }
    }
    override fun stop(instance:Instance) { check(send(requireRunner(instance),RunnerMessage("stop",instanceId=instance.id))) { "실행기에 종료 명령을 전달하지 못했습니다" } }
    override fun supportsForceStop(instance:Instance):Boolean {
        val id=instance.runnerId ?: return false
        return connected(id) && runCatching {SignedUpdates.compare(registry.active(id)?.version ?: "unknown","0.4.7")>=0}.getOrDefault(false)
    }
    override fun forceStop(instance:Instance,pid:Long) {
        val id=requireRunner(instance)
        check(supportsForceStop(instance)) {"원격 강제 종료에는 실행기 0.4.7 이상이 필요합니다"}
        require(pid>0)
        check(send(id,RunnerMessage("force-stop",instanceId=instance.id,pid=pid))) {"실행기에 강제 종료 명령을 전달하지 못했습니다"}
    }
    override fun restart(instance:Instance) { stop(instance) }
    override fun command(instance:Instance,value:String) { check(send(requireRunner(instance),RunnerMessage("command",instanceId=instance.id,command=value))) { "실행기에 콘솔 명령을 전달하지 못했습니다" } }
    @Synchronized override fun completed(instance:Instance) {
        activeRoutes.remove(instance.id)
        network?.config()?.relayRunnerId?.let {syncTcpRelay(it)}
        if(activeRoutes.isEmpty()){sharedListener?.close();sharedListener=null;sable.close()}
        sableStates.remove(instance.id)
        listeners.remove(instance.id)?.close()
        sockets.values.filter { it.instanceId==instance.id }.forEach { closeStream(it,true) }
    }
}

@Configuration
@EnableWebSocket
class RunnerSocketConfig(private val gateway:RunnerGateway,private val devices:RunnerRegistry):WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry:WebSocketHandlerRegistry) {
        registry.addHandler(gateway,"/api/runners/connect").addInterceptors(object:HandshakeInterceptor {
            override fun beforeHandshake(request:ServerHttpRequest,response:ServerHttpResponse,wsHandler:org.springframework.web.socket.WebSocketHandler,attributes:MutableMap<String,Any>):Boolean {
                val id=request.headers.getFirst("X-SM-Runner-Id")
                val timestamp=request.headers.getFirst("X-SM-Timestamp")?.toLongOrNull()
                val nonce=request.headers.getFirst("X-SM-Nonce")
                val signature=request.headers.getFirst("X-SM-Signature")
                val udp=request.headers.getFirst("X-SM-Channel")=="sable-udp"
                if(id==null || timestamp==null || nonce==null || signature==null || !(if(udp)devices.acceptUdpConnection(id,timestamp,nonce,signature) else devices.acceptConnection(id,timestamp,nonce,signature))) {
                    response.setStatusCode(HttpStatus.FORBIDDEN);return false
                }
                attributes["runnerId"]=id;attributes["sableUdp"]=udp
                return true
            }
            override fun afterHandshake(request:ServerHttpRequest,response:ServerHttpResponse,wsHandler:org.springframework.web.socket.WebSocketHandler,exception:Exception?) {}
        })
    }
}
