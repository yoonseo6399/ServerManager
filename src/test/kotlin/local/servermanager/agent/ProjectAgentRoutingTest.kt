package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentRoutingTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    @Test fun `audience persists distinct humans and expires after fifteen minutes`() {
        var now=1000000L;val routing=ProjectAgentRouting(temp){now}
        fun record(id:String,author:String,bot:Boolean=false)=routing.record("c",AgentConversationMessage(id,author,author,bot,"말",now))
        record("1","alice");record("2","alice");record("3","bob");record("4","bot",true)
        assertEquals(setOf("alice","bob"),routing.audience("c").participantIds)
        assertEquals(2,ProjectAgentRouting(temp){now}.audience("c").participantIds.size)
        now+=900001;assertTrue(routing.audience("c").participantIds.isEmpty());routing.clear("c");assertTrue(ProjectAgentRouting(temp){now}.audience("c").messages.isEmpty())
    }
    @Test fun `single human followup is instant while mentions and replies identify the actual addressee`() {
        val routing=ProjectAgentRouting(temp){1000}
        fun message(text:String,reply:String?=null,replyAuthor:String?=null)=AgentConversationMessage("new","alice","Alice",false,text,1000,reply,replyAuthor=replyAuthor)
        routing.record("c",message("이전 질문"))
        assertEquals(AgentAddressee.AGENT,routing.decide("c",message("그거 말고 내가 알고 있는 대화 말이야"),"123"))
        routing.record("c",AgentConversationMessage("bob-msg","bob","Bob",false,"사람끼리 대화",1000))
        assertEquals(AgentAddressee.UNCERTAIN,routing.decide("c",message("그거 해줘"),"123"))
        assertEquals(AgentAddressee.AGENT,routing.decide("c",message("루오, 해줘"),"123"))
        assertEquals(AgentAddressee.AGENT,routing.decide("c",message("<@123> 해줘"),"123"))
        assertEquals(AgentAddressee.HUMAN,routing.decide("c",message("<@456> 해줘"),"123"))
        assertEquals(AgentAddressee.HUMAN,routing.decide("c",message("그거 해줘","bob-msg"),"123"))
        assertEquals(AgentAddressee.AGENT,routing.decide("c",message("그거 해줘","old-bot","123"),"123"))
    }
    @Test fun `another human speaking after the bot makes an unaddressed followup ambiguous even within same millisecond`() {
        val routing=ProjectAgentRouting(temp){1000}
        routing.record("c",AgentConversationMessage("1","alice","Alice",false,"question",1000))
        routing.record("c",AgentConversationMessage("2","bob","Bob",false,"hello",1000))
        routing.record("c",AgentConversationMessage("3","123","Luo",true,"answer",1000))
        val message=AgentConversationMessage("5","alice","Alice",false,"그거 해줘",1000)
        assertEquals(AgentAddressee.AGENT,routing.decide("c",message,"123"))
        routing.record("c",AgentConversationMessage("4","bob","Bob",false,"human chatter",1000))
        assertEquals(AgentAddressee.UNCERTAIN,routing.decide("c",message,"123"))
    }
    @Test fun `promise without tool is repaired and only real read result completes request`() {
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u","demo",temp.toString()))
        temp.resolve("hello.txt").writeText("ORIGINAL_FILE_MARKER\n"+"source-line\n".repeat(80))
        var actor=0;var summaries=0;val events=mutableListOf<ProjectAgentEvent>()
        val model=ProjectAgentInference {messages,_,_->when {
            messages.first().content.startsWith("Context compressor")->"이전 목표와 실제 결과를 보존한 테스트 요약"
            messages.first().content.startsWith("Independent")->"""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"read"}"""
            messages.first().content.startsWith("Tool-result")-> {summaries++;error("Read must not be summarized")}
            else->when(actor++){
                0->"""{"message":"hello.txt 내용을 확인해 볼게요!","tool":null}"""
                1->{assertTrue(messages.any {it.content.contains("No operation from this reply")});"""{"message":"읽을게요","tool":{"name":"read_file","arguments":{"path":"hello.txt"}}}"""}
                else->{assertTrue(messages.any {it.content.contains("ORIGINAL_FILE_MARKER")});"""{"message":"원문을 확인했습니다.","status":"completed","tool":null}"""}
            }
        }}
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {it.run("c","u","hello.txt 파일을 읽어줘",ProjectAgentPreferences(explicitPermission=false),model,{},events::add)}
        assertEquals(0,summaries);assertEquals(1,events.count {it.kind=="tool_end"});assertEquals("원문을 확인했습니다.",events.last().text)
        assertFalse(events.any {it.kind=="message" && it.text=="hello.txt 내용을 확인해 볼게요!"})
    }
    @Test fun `read summarization is invoked only on explicit tool argument`() {
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u",null,temp.toString()))
        temp.resolve("large.txt").writeText("source-line\n".repeat(100));var actor=0;var summaries=0
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {it.run("c","u","파일 확인",ProjectAgentPreferences(explicitPermission=false),ProjectAgentInference {messages,_,_->when {
            messages.first().content.startsWith("Context compressor")->"이전 목표와 실제 결과를 보존한 테스트 요약"
            messages.first().content.startsWith("Independent")->"""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"read"}"""
            messages.first().content.startsWith("Tool-result")-> {summaries++;"SUMMARY_MARKER"}
            actor++==0->"""{"message":"파일을 읽을게요","tool":{"name":"read_file","arguments":{"path":"large.txt","summarize":true}}}"""
            else->{assertTrue(messages.any {it.content.contains("SUMMARY_MARKER")});"""{"message":"확인했습니다.","tool":null}"""}
        }},{}){}}
        assertEquals(1,summaries)
    }
    @Test fun `flattened tool intent is executed through approval instead of being accepted as a final answer`() {
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u",null,temp.toString()));val file=temp.resolve("chain.txt");file.writeText("original\n")
        var actor=0;var approvals=0
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->engine.run("c","u","파일 끝에 added 추가",ProjectAgentPreferences(),ProjectAgentInference {messages,_,_->when {
            messages.first().content.startsWith("Context compressor")->"이전 목표와 실제 결과를 보존한 테스트 요약"
            messages.first().content.startsWith("Independent")->"""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"write"}"""
            actor++==0->"""{"message":"읽기","tool":{"name":"read_file","arguments":{"path":"chain.txt"}}}"""
            actor==2->"""{"message":"Appending added","tool_name":"write_file","arguments":{"path":"chain.txt","content":"original\nadded\n"}}"""
            else->"""{"message":"추가했습니다.","tool":null,"requirements":[{"id":"R1","status":"done","evidence_ids":["T2"],"evidence":"chain.txt의 끝에 원문을 보존하고 added를 저장했습니다"}]}"""
        }},{}){e->if(e.kind=="approval"){approvals++;engine.approve("c","u",e.token!!,true){}}}}
        assertEquals("original\nadded\n",file.readText());assertEquals(1,approvals)
        assertNull(ProjectAgentReply.parse("""{"message":"done","tool":null,"tool_name":"write_file","arguments":{}}"""))
        assertNull(ProjectAgentReply.parse("""{"message":"done","tool_name":"unknown","arguments":{}}"""))
        assertNull(ProjectAgentReply.parse("""{"message":"just a promise"}"""))
    }
    @Test fun `requested reread after saving is required before completion and final bytes preserve original plus insertion`() {
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u",null,temp.toString()));val file=temp.resolve("chain.txt");file.writeText("original\n")
        var actor=0;val events=mutableListOf<ProjectAgentEvent>()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->engine.run("c","u","chain.txt에 added를 추가하고 저장 후 다시 읽어서 검증해",ProjectAgentPreferences(),ProjectAgentInference {messages,_,_->when {
            messages.first().content.startsWith("Context compressor")->"이전 목표와 실제 결과를 보존한 테스트 요약"
            messages.first().content.startsWith("Independent")->"""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"safe"}"""
            else->when(actor++){
                0->"""{"message":"읽기","tool":{"name":"read_file","arguments":{"path":"chain.txt"}}}"""
                1->"""{"message":"저장","tool":{"name":"write_file","arguments":{"path":"chain.txt","content":"original\nadded\n"}}}"""
                2->"""{"message":"검증 완료했습니다.","tool":null}"""
                3->{assertTrue(messages.any {it.content.contains("Human explicitly requested rereading")});"""{"message":"재조회","tool":{"name":"read_file","arguments":{"path":"chain.txt"}}}"""}
                else->"""{"message":"재조회로 검증했습니다.","tool":null,"requirements":[{"id":"R1","status":"done","evidence_ids":["T2"],"evidence":"chain.txt를 저장하고 재조회했습니다"}]}"""
            }
        }},{}){e->events.add(e);if(e.kind=="approval")engine.approve("c","u",e.token!!,true){}}}
        assertContentEquals("original\nadded\n".toByteArray(),file.readBytes());assertEquals(listOf("read_file","write_file","read_file"),events.filter {it.kind=="tool_end"}.map {it.plan!!.tool.name})
        assertTrue(events.any {it.kind=="status" && it.text.contains("재조회 검증 요청")});assertTrue(events.last().text.startsWith("재조회로 검증했습니다."))
    }
    @Test fun `repeated empty promises terminate honestly rather than pretending completion or looping`() {
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u",null,temp.toString()));var calls=0;val events=mutableListOf<ProjectAgentEvent>()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {it.run("c","u","안녕.txt 파일을 읽어줘",ProjectAgentPreferences(),ProjectAgentInference {_,_,_->calls++;"""{"message":"파일을 읽어볼게요","tool":null}"""},{},events::add)}
        assertEquals(3,calls);assertContains(events.last().text,"작업 완료로 처리하지 않았습니다");assertFalse(events.any {it.kind=="tool_start"})
    }
    @Test fun `explanations and conversation memory do not force unnecessary filesystem tools`() {
        assertFalse(ProjectAgentCompletion.needsContinuation("파일 읽는 방법을 설명해줘","read_file 도구를 사용합니다.","completed",0))
        assertFalse(ProjectAgentCompletion.needsContinuation("우리 대화 기억해?","빌드 관련 대화를 했어요.","completed",0))
        assertFalse(ProjectAgentCompletion.needsContinuation("파일 읽어줘","어느 파일의 경로인지 알려 주세요.","clarify",0))
        assertTrue(ProjectAgentCompletion.needsContinuation("폴더 찾아줘","I will find it.","completed",1))
        assertTrue(ProjectAgentCompletion.needsContinuation("README.md 확인해줘","완료했습니다.","completed",0))
        assertFalse(ProjectAgentCompletion.needsContinuation("파일을 다시 읽으라는 말이 아니야. 우리 대화에서 방금 무엇을 요청했는지 알려줘. 파일 도구를 쓰지 말고 대화 기록만 보고 답해줘.","안녕.txt를 읽어달라고 요청하셨어요.","completed",0))
        val text=(1..500).joinToString("\n"){"$it: exact source line"};val bounded=ProjectAgentRawResult.fit(text,100)
        assertTrue(bounded.startsWith("1: exact source line\n2:"));assertContains(bounded,"요약하지 않음");assertContains(bounded,"start_line:")
    }
}
