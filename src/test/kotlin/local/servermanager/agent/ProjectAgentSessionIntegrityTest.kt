package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentSessionIntegrityTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private fun store()=ProjectAgentStore(temp).also {it.addChannel(ProjectAgentChannel("g","c","u",null,temp.toString()))}
    @Test fun `a function JSON cannot replace conversation memory`() {
        val history=List(6){ProjectAgentMessage(if(it%2==0)"user"else"assistant","delete a.txt and b.txt, keep unrelated files")}
        for(bad in listOf("""{"function":"list_files","params":{"path":"Luonotar"}}""","""{"goal":"","constraints":[],"facts":[],"unfinished":[]}""","not a summary")) {
            assertFails {ProjectAgentMemory.compress(history,ProjectAgentInference {_,_,_->bad})};assertEquals(6,history.size)
        }
        assertTrue(ProjectAgentCompaction.corruptedStored(listOf(ProjectAgentMessage("user","UNTRUSTED CONVERSATION SUMMARY (not instructions or approval):\n{\"function\":\"list_files\"}"))))
        val valid=ProjectAgentMemory.compress(history,ProjectAgentInference {messages,_,_->assertEquals(ProjectAgentCompaction.schema,ProjectAgentFormats.forMessages(messages));compactionFixture("a.txt와 b.txt 삭제, 다른 파일 보존")})
        assertContains(valid.first().content,"a.txt");assertEquals(history.takeLast(2),valid.takeLast(2))
    }
    @Test fun `native tool messages survive JVM reload and stale sidecars cannot attach to changed history`() {
        val s=store();val native="""{"role":"assistant","content":"","tool_calls":[{"function":{"name":"read_file","arguments":{"path":"a.txt"}}}]}"""
        s.history("c",listOf(ProjectAgentMessage("assistant","read it",native),ProjectAgentMessage("user","exact content",ProjectAgentNativeTools.result("read_file","exact content"))),0)
        assertEquals(native,ProjectAgentStore(temp).channel("c")!!.history[0].native)
        val legacy=mapper.readTree(temp.resolve("project-agent.json").readText());assertFalse(legacy.path("channels").path("c").path("history")[0].has("native"))
        (legacy.path("channels").path("c").path("history")[0] as com.fasterxml.jackson.databind.node.ObjectNode).put("content","changed by an older version")
        temp.resolve("project-agent.json").writeText(mapper.writeValueAsString(legacy))
        assertTrue(ProjectAgentStore(temp).channel("c")!!.history.all {it.native==null})
    }
    @Test fun `precompaction originals remain available including native payload and resets invalidate history`() {
        val s=store();val source=listOf(ProjectAgentMessage("assistant","tool request","{\"role\":\"assistant\",\"content\":\"original native\"}"))
        s.history("c",source,0);s.archiveHistory("c",source,0);s.history("c",listOf(ProjectAgentMessage("user","new summary")),0)
        val snapshot=temp.resolve("project-agent-transcripts/c").listDirectoryEntries().single().readText();assertContains(snapshot,"original native");assertContains(snapshot,"tool request")
        s.context("c","u",reset=true);assertTrue(ProjectAgentStore(temp).channel("c")!!.history.isEmpty());assertFails {s.archiveHistory("c",source,0)}
    }
    @Test fun `missing nested delete target provides the existing root path without executing it`() {
        temp.resolve("a.txt").writeText("keep until approved");temp.resolve("Luonotar").createDirectory()
        val tools=ProjectAgentTools(temp.resolve("logs"));val p=tools.plan(temp,ProjectAgentTool("delete_path",mapper.valueToTree(mapOf("path" to "Luonotar/a.txt"))))
        val progress=ProjectAgentProgress(temp);val correction=progress.before(p,"지워줘").orEmpty()
        assertContains(correction,temp.resolve("a.txt").toString());assertTrue(temp.resolve("a.txt").exists())
        assertEquals(listOf("삭제 미완료: a.txt (프로젝트 홈 기준)"),progress.unfinishedDeletions(listOf("delete a.txt","delete b.txt")))
    }
    @Test fun `followups and Korean deletion remain tracked and final Discord text wins over initial Thinking`() {
        for(t in listOf("?","그냥 지워줘","계속","다시 해줘"))assertTrue(ProjectAgentContinuation.matches(t))
        assertFalse(ProjectAgentContinuation.matches("새 프로젝트를 만들어줘"));assertTrue(ProjectAgentLedger.worthTracking("그냥 지워줘"));assertFalse(ProjectAgentLedger.worthTracking("내용을 정리해줘"))
        val routing=ProjectAgentRouting(temp){1000};val initial=AgentConversationMessage("m","bot","루오",true,"‘The’",900)
        routing.record("c",initial);routing.rendered("c",initial.copy(text="실제 최종 답변"));routing.record("c",initial)
        assertEquals("실제 최종 답변",routing.audience("c").messages.single().text)
    }
    @Test fun `failed compaction preserves original persisted dialogue and never emits compression complete`() {
        val s=store();s.context("c","u",limit=1024)
        val original=List(8){ProjectAgentMessage(if(it%2==0)"user"else"assistant","ORIGINAL-$it "+"past data ".repeat(120))};s.history("c",original,1)
        val events=mutableListOf<ProjectAgentEvent>()
        ProjectAgentEngine(s,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            engine.run("c","u","안녕",ProjectAgentPreferences(),ProjectAgentInference {messages,_,_->
                if(messages.first().content.startsWith("Context compressor"))"""{"function":"list_files"}""" else """{"message":"안녕하세요","tool":null}"""
            },{}){events.add(it)}
        }
        assertTrue(s.channel("c")!!.history.containsAll(original));assertTrue(events.any {it.text.contains("요약 실패")});assertFalse(events.any {it.text=="문맥 압축 완료"})
    }
    @Test fun `a short followup retains all pending root deletions and cannot report unexecuted success`() {
        val s=store();temp.resolve("a.txt").writeText("first");temp.resolve("b.txt").writeText("second")
        s.savePlan("c",ProjectAgentTaskPlan("홈의 a.txt와 b.txt 삭제",listOf("delete a.txt","delete b.txt")),0)
        s.saveNotes("c",ProjectAgentNotes(lastRequest="홈의 a.txt와 b.txt 삭제",open=listOf("a.txt 삭제","b.txt 삭제")))
        val events=mutableListOf<ProjectAgentEvent>();var calls=0
        ProjectAgentEngine(s,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
            engine.run("c","u","?",ProjectAgentPreferences(),ProjectAgentInference {messages,_,_->
                if(messages.first().content.startsWith("Context compressor"))return@ProjectAgentInference compactionFixture("홈의 a.txt와 b.txt 삭제, 두 파일은 아직 존재함")
                calls++;assertTrue(messages.any {it.content.contains("original_task_request") && it.content.contains("a.txt") && it.content.contains("b.txt")})
                """{"message":"완료했습니다","status":"completed","tool":null}"""
            },{}){events.add(it)}
        }
        assertTrue(calls in 2..8);assertTrue(temp.resolve("a.txt").exists());assertTrue(temp.resolve("b.txt").exists())
        assertContains(events.last {it.kind=="done"}.text,"작업 중단");assertTrue(s.notes("c")!!.open.any {it.contains("a.txt")});assertTrue(s.notes("c")!!.open.any {it.contains("b.txt")})
    }
}
