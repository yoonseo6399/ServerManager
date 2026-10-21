package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

@EnabledIfEnvironmentVariable(named="AGENT_SESSION_LIVE",matches="true")
class ProjectAgentSessionLiveTest {
    @TempDir lateinit var temp:Path
    @Test fun `short followup after forced compaction deletes all four root files and preserves unrelated bytes`() {
        val mapper=jacksonObjectMapper();val directory=temp.resolve("settings");val store=ProjectAgentStore(directory);val home=temp.resolve("project").createDirectories()
        val targets=listOf("a.txt","aqp.txt","aqq.txt","qq.txt");targets.forEach {home.resolve(it).writeText("original $it\r\n")};home.resolve("Luonotar").createDirectory();home.resolve("keep.txt").writeText("UNRELATED\r\n")
        store.addChannel(ProjectAgentChannel("g","c","u",null,home.toString()));store.context("c","u",limit=1024)
        val original="프로젝트 홈 바로 아래의 a.txt, aqp.txt, aqq.txt, qq.txt 네 파일을 모두 삭제해. Luonotar 하위가 아니야. keep.txt와 다른 파일은 보존하고 확인해."
        store.savePlan("c",ProjectAgentTaskPlan(original,targets.map {"delete $it"}+"verify all four are gone"),1)
        store.saveNotes("c",ProjectAgentNotes(lastRequest=original,open=targets.map {"$it 파일 삭제"}))
        val history=listOf(ProjectAgentMessage("user",original),ProjectAgentMessage("assistant","홈에 네 파일과 keep.txt, Luonotar/가 있습니다."))+List(6){ProjectAgentMessage(if(it%2==0)"user"else"assistant","이전 작업 대화 "+"파일은 홈 바로 아래에 있고 keep.txt는 보존해야 합니다. ".repeat(12))}
        store.history("c",history,1)
        val llm=LocalLlm();assertTrue(llm.loaded().isEmpty(),"Do not interrupt another inference");val events=mutableListOf<ProjectAgentEvent>();var compactions=0;var approvals=0;val started=System.nanoTime()
        val inference=object:ProjectAgentInference {
            private var native:String?=null
            override fun nativeTools()=true
            override fun nativeMessage()=native
            override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
            if(messages.first().content.startsWith("Context compressor"))compactions++
            val main=messages.first().content.startsWith("You are 루오노타르")
            val budget=LocalContextBudget().let {if(main)it else it.helper()}
            val answer=llm.conversationWithBudget("qwen38-27b",messages,think,budget,progress);check(!answer.limited);native=answer.nativeMessage;return answer.text
        }}
        // Uses the real native function loop and a separate fresh model session for each approval review.
        try {
            ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->engine.run("c","u","테스트 사용자","?",ProjectAgentPreferences(explicitPermission=true),inference,ProjectAgentLimits(18,AgentAccess.OWNER),{check((System.nanoTime()-started)/1e9<600) {"live test time budget"}}) {e->
                events.add(e);if(e.kind=="approval"){assertEquals("delete_path",e.plan!!.tool.name);approvals++;engine.approve("c","u",e.token!!,true){}}
                if(e.kind in setOf("tool_end","done"))println("SESSION LIVE ${e.kind} ${e.plan?.tool?.name} ${e.text.take(250)}")
            }}
        }finally{llm.unloadAll();val proof=mapOf("compactions" to compactions,"approvals" to approvals,"targets_absent" to targets.associateWith {!home.resolve(it).exists()},"keep_preserved" to (home.resolve("keep.txt").readText()=="UNRELATED\r\n"),"seconds" to (System.nanoTime()-started)/1e9,"final" to events.lastOrNull {it.kind=="done"}?.text);Path.of("build/session-live-proof.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(proof))}
        assertTrue(targets.all {!home.resolve(it).exists()});assertContentEquals("UNRELATED\r\n".toByteArray(),home.resolve("keep.txt").readBytes());assertTrue(compactions>0);assertEquals(4,approvals)
        assertTrue(directory.resolve("project-agent-transcripts/c").listDirectoryEntries().isNotEmpty())
    }
}
