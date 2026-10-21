package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.time.OffsetDateTime

/** Only authenticated administrator owners get these tools; grants are rechecked at plan and execution. */
internal class ProjectAgentPersonalServices(private val session:ProjectAgentChannel,private val schedules:ProjectAgentSchedules,
    private val history:DiscordProjectAgentHistory,private val preferences:()->ProjectAgentPreferences,private val authorize:()->Unit) :ProjectAgentServicePort {
    private val mapper=jacksonObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    override fun plan(home:Path,call:ProjectAgentTool):ProjectAgentPlan {
        authorize();val args=call.arguments
        val readOnly=when(call.name) {
            "list_chat_channels"->true
            "read_chat_history"->{history.check(session,args.path("channel_id").asText());require(args.path("limit").asInt(20) in 1..50);true}
            "list_forum_posts"->{history.check(session,args.path("forum_id").asText());require(args.path("limit").asInt(20) in 1..50);true}
            "schedule_task"->{val action=args.path("action").asText();require(action in setOf("create","list","cancel"));if(action=="create") {
                val at=OffsetDateTime.parse(args.path("at").asText()).toInstant();require(at.isAfter(java.time.Instant.now()) && at.isBefore(java.time.Instant.now().plusSeconds(366L*86400)))
                require(args.path("title").asText().isNotBlank() && args.path("title").asText().length<=100 && args.path("prompt").asText().isNotBlank() && args.path("prompt").asText().length<=6000)
            };if(action=="cancel")require(args.path("id").asText().isNotBlank());action=="list"}
            else->error("지원하지 않는 앱 서비스입니다.")
        }
        val description=when(call.name) {
            "schedule_task"->"예약 ${args.path("action").asText()} · ${args.path("title").asText()} · ${args.path("at").asText()} ${args.path("id").asText()}\n요청: ${args.path("prompt").asText()}"
            "read_chat_history"->"허용한 Discord 기록 조회 · 채널 ${args.path("channel_id").asText()} · 최대 ${args.path("limit").asInt(20)}개 · 이전 ${args.path("before").asText()}"
            "list_forum_posts"->"허용한 포럼 게시글 목록 · 포럼 ${args.path("forum_id").asText()} · 보관됨 ${args.path("archived").asBoolean()} · 최대 ${args.path("limit").asInt(20)}개"
            else->"사용자가 허용한 Discord 채널·포럼 목록"
        }
        return ProjectAgentPlan(call,home.toRealPath().toString(),description,readOnly,scope=if(readOnly)"INSIDE"else "UNKNOWN",sandboxed=readOnly)
    }
    override fun execute(call:ProjectAgentTool,active:()->Unit):ProjectAgentToolResult {
        active();authorize();val a=call.arguments
        val result:Any=when(call.name) {
            "list_chat_channels"->mapOf("channels" to history.channels(session))
            "read_chat_history"->history.read(session,a.path("channel_id").asText(),a.path("limit").asInt(20),a.path("before").asText().takeIf {it.isNotBlank()},active)
            "list_forum_posts"->history.posts(session,a.path("forum_id").asText(),a.path("archived").asBoolean(false),a.path("limit").asInt(20),active)
            "schedule_task"->when(a.path("action").asText()) {
                "create"->schedules.create(session,preferences().revision,a.path("at").asText(),a.path("title").asText(),a.path("prompt").asText())
                "cancel"->schedules.cancel(session.guild,session.user,a.path("id").asText())
                "list"->mapOf("now" to OffsetDateTime.now().toString(),"timezone" to java.time.ZoneId.systemDefault().id,"tasks" to schedules.list(session.guild,session.user),"notice" to "일회성 예약. 관리 앱·Discord 연결이 실행 중이어야 합니다. 15분 초과 지연은 실행하지 않습니다. 미래 도구는 사전 승인되지 않습니다.")
                else->error("예약 action이 올바르지 않습니다.")
            }
            else->error("지원하지 않는 앱 서비스입니다.")
        }
        active();val raw=mapper.writeValueAsString(result);return ProjectAgentToolResult(ProjectAgentContext.summary(raw),raw)
    }
}
