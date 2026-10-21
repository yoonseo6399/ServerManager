package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentEditVerifyTest {
    @TempDir lateinit var temp:Path

    @Test fun `an edit_file change must be reread before completion when the user asked for verification`() {
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u",null,temp.toString()))
        val file=temp.resolve("chain.txt");file.writeText("original\n")
        var actor=0;val events=mutableListOf<ProjectAgentEvent>()
        ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->engine.run("c","u","chain.txt에 added를 추가하고 저장 후 다시 읽어서 검증해",ProjectAgentPreferences(),ProjectAgentInference {messages,_,_->when {
            messages.first().content.startsWith("Context compressor")->compactionFixture("이전 목표와 실제 결과를 보존한 테스트 요약")
            messages.first().content.startsWith("Independent")->"""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"safe"}"""
            else->when(actor++) {
                0->"""{"message":"읽기","tool":{"name":"read_file","arguments":{"path":"chain.txt"}}}"""
                1->"""{"message":"수정","tool":{"name":"edit_file","arguments":{"path":"chain.txt","find":"original","replace":"original\nadded"}}}"""
                2->"""{"message":"검증 완료했습니다.","tool":null}"""
                3->{assertTrue(messages.any {it.content.contains("Human explicitly requested rereading")});"""{"message":"재조회","tool":{"name":"read_file","arguments":{"path":"chain.txt"}}}"""}
                else->"""{"message":"재조회로 검증했습니다.","tool":null,"requirements":[{"id":"R1","status":"done","evidence_ids":["T2"],"evidence":"chain.txt의 기존 줄과 추가 줄을 재조회했습니다"}]}"""
            }
        }},{}){e->events.add(e);if(e.kind=="approval")engine.approve("c","u",e.token!!,true){}}}
        assertEquals("original\nadded\n",file.readText())
        assertEquals(listOf("read_file","edit_file","read_file"),events.filter {it.kind=="tool_end"}.map {it.plan!!.tool.name})
        assertTrue(events.any {it.kind=="status" && it.text.contains("재조회 검증 요청")})
    }
}
