package local.servermanager

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class SystemLogEntry(val seq:Long,val time:String,val level:String,val component:String,val message:String)
data class SystemLogPage(val events:List<SystemLogEntry>,val nextCursor:Long,val oldestCursor:Long,val reset:Boolean,val sessionId:String)

/** Current-process diagnostic buffer. Never opens a caller-supplied path or returns raw log files. */
class SystemLogBuffer(private val capacity:Int=2000) {
    init {require(capacity in 1..10000)}
    val sessionId=UUID.randomUUID().toString()
    private val entries=ArrayDeque<SystemLogEntry>()
    private var sequence=0L
    @Synchronized fun append(time:Long,level:String,component:String,message:String) {
        if(level !in setOf("INFO","WARN","ERROR"))return
        // Agent/provider and authentication diagnostics can include user content, credentials or mail.
        val lower=component.lowercase()
        if(listOf("agent","oauth","google","gmail","ollama","qwen","localllm","auth","http.wire","http.headers").any {it in lower})return
        entries.addLast(SystemLogEntry(++sequence,Instant.ofEpochMilli(time).toString(),level,
            component.take(160),redact(message)))
        while(entries.size>capacity)entries.removeFirst()
    }
    @Synchronized fun page(after:Long=0,limit:Int=200,level:String="ALL",component:String="",since:String?=null,query:String=""):SystemLogPage {
        require(after>=0 && limit in 1..500) {"로그 조회 범위를 확인하세요"}
        require(level in setOf("ALL","INFO","WARN","ERROR")) {"로그 수준을 확인하세요"}
        require(component.length<=160 && query.length<=200) {"검색어가 너무 깁니다"}
        val start=since?.takeIf {it.isNotBlank()}?.let {Instant.parse(it)}
        val oldest=entries.firstOrNull()?.seq ?: sequence+1
        val reset=after>sequence || (after!=0L && after<oldest-1)
        val cursor=if(reset)0 else after
        val result=mutableListOf<SystemLogEntry>()
        var scanned=cursor
        for(entry in entries) {
            if(entry.seq<=cursor)continue
            scanned=entry.seq
            if((level=="ALL" || entry.level==level) && entry.component.contains(component,true) &&
                (start==null || !Instant.parse(entry.time).isBefore(start)) && entry.message.contains(query,true)) {
                result.add(entry)
                if(result.size==limit)break
            }
        }
        return SystemLogPage(result,if(scanned==cursor)sequence else scanned,oldest,reset,sessionId)
    }
    companion object {
        fun redact(value:String):String {
            // Bound work before regex processing; suppress whole suspicious rows instead of partial secrets.
            val line=value.take(8192)
            if(Regex("(?i)(authorization|cookie|password|passwd|secret|token|api[-_ ]?key|private[-_ ]?key|client_secret|BEGIN .*PRIVATE KEY)").containsMatchIn(line))
                return "[민감한 인증 정보가 포함될 수 있어 메시지를 숨겼습니다]"
            if(Regex("(?i)(prompt|메일 본문|message.content|request.body|response.body)").containsMatchIn(line))
                return "[사용자 요청 또는 응답 내용이 포함될 수 있어 메시지를 숨겼습니다]"
            return line.replace(Regex("https?://[^\\s<>\"]+")) { match ->
                runCatching {val uri=java.net.URI(match.value); "${uri.scheme}://${uri.host ?: "[host]"}${if(uri.port>=0)":"+uri.port else ""}/[경로 숨김]"}.getOrDefault("[주소 숨김]")
            }.replace(Regex("[A-Za-z0-9_-]{40,}"),"[긴 식별자 숨김]")
                .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"),"").take(4000)
        }
    }
}

@Service
class SystemLogs {
    val buffer=SystemLogBuffer()
    private var root:Logger?=null
    private val appender=object:AppenderBase<ILoggingEvent>() {
        override fun append(event:ILoggingEvent) {
            // Throwable objects and MDC are deliberately excluded.
            buffer.append(event.timeStamp,event.level.levelStr,event.loggerName,event.formattedMessage ?: "")
        }
    }
    @PostConstruct fun start() {
        val context=LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        appender.context=context;appender.name="OWNER_SYSTEM_LOG_VIEW";appender.start()
        root=context.getLogger(Logger.ROOT_LOGGER_NAME).also {it.addAppender(appender)}
    }
    @PreDestroy fun stop() {root?.detachAppender(appender);appender.stop()}
}

/** /api/admin/** is owner-only in Security; no raw log download endpoint is exposed. */
@RestController
@RequestMapping("/api/admin/system-logs")
class SystemLogsApi(private val logs:SystemLogs) {
    @GetMapping fun page(@RequestParam(defaultValue="0") after:Long,
        @RequestParam(defaultValue="200") limit:Int,@RequestParam(defaultValue="ALL") level:String,
        @RequestParam(defaultValue="") component:String,@RequestParam(required=false) since:String?,
        @RequestParam(defaultValue="") query:String):ResponseEntity<SystemLogPage> =
        ResponseEntity.ok().header("Cache-Control","no-store").body(logs.buffer.page(after,limit,level,component,since,query))
}
