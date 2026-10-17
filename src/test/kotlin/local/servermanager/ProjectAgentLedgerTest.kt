package local.servermanager

import kotlin.test.*

class ProjectAgentLedgerTest {
    private fun ledger(vararg texts:String)=ProjectAgentLedger().also {it.load(texts.toList())}

    @Test fun `only long multi part requests are tracked and conversation questions are not`() {
        assertFalse(ProjectAgentLedger.worthTracking("그거 이어서 해줘"))
        assertTrue(ProjectAgentLedger.worthTracking("로그 검색 기능을 추가하고 테스트를 작성한 다음 문서도 갱신해줘. 기존 API는 바꾸지 마."))
        assertFalse(ProjectAgentLedger.worthTracking("지금까지 대화 문맥에서 내가 처음에 뭐라고 했는지 기억나는 대로 도구를 쓰지 말고 알려줘 제발 부탁해요"))
    }

    @Test fun `heuristic split uses bullets then sentences and never exceeds the item cap`() {
        val bullets=ProjectAgentLedger.split("다음을 해줘\n1. 로그 검색 추가\n2) 테스트 작성\n- 문서 갱신")
        assertTrue(bullets.containsAll(listOf("로그 검색 추가","테스트 작성","문서 갱신")),bullets.toString())
        assertEquals(1,ProjectAgentLedger.split("파일 하나만 고쳐줘").size)
        assertTrue(ProjectAgentLedger.split((1..20).joinToString("\n") {"항목 번호 $it 처리"}).size<=ProjectAgentLedger.MAX_ITEMS)
        assertTrue(ProjectAgentLedger.split("x".repeat(500)).single().length<=ProjectAgentLedger.ITEM_CHARS)
    }

    @Test fun `model extraction output is parsed strictly and bad output yields null`() {
        assertEquals(listOf("A 기능 추가","B 문서 갱신"),ProjectAgentLedger.parseItems("""{"items":["A 기능 추가","B 문서 갱신"]}"""))
        assertEquals(listOf("A 기능 추가"),ProjectAgentLedger.parseItems("```json\n{\"items\":[\"A 기능 추가\",1,\"x\"]}\n```"))
        assertNull(ProjectAgentLedger.parseItems("no json"));assertNull(ProjectAgentLedger.parseItems("""{"items":[]}"""));assertNull(ProjectAgentLedger.parseItems("""{"items":"x"}"""))
        val failing=ProjectAgentInference {_,_,_->error("model down")}
        assertEquals(1,ProjectAgentLedger.extract("파일 하나만 고쳐줘",failing).size,"extraction failure must fall back instead of failing the run")
    }

    @Test fun `an item needs concrete evidence and action items additionally need a successful changing tool`() {
        val l=ledger("config 파일을 수정한다","원인을 설명한다")
        assertNotNull(l.update("R1","done","ok",true),"too short evidence")
        assertNotNull(l.update("R1","done","I changed the config file",false),"action item without tool proof")
        assertEquals(2,l.open().size)
        assertNull(l.update("R1","done","edit_file approved and applied to config.yml",true))
        assertNull(l.update("R2","done","explained in the final message above",false),"non-action item may close on evidence alone")
        assertTrue(l.open().isEmpty())
        assertNotNull(ledger("x 수정").update("R9","done","whatever evidence text",true),"unknown id")
        assertNotNull(ledger("x 수정").update("R1","maybe","whatever evidence text",true),"unknown status")
    }

    @Test fun `blocked needs a reason and closed items are not reopened or overwritten`() {
        val l=ledger("서버를 재시작한다","문서를 쓴다")
        assertNotNull(l.update("R1","blocked","",true));assertNull(l.update("R1","blocked","권한 없음 (승인 거절)",false))
        assertNull(l.update("R1","done","a later claim must not change a closed item",true))
        assertEquals("blocked",l.all.first().status)
        assertEquals(listOf("문서를 쓴다"),l.openTexts())
    }

    @Test fun `report lists every item with its state and the pinned view hides evidence`() {
        val l=ledger("A를 추가한다","B를 설명한다","C를 삭제한다")
        l.update("R1","done","write_file applied a.txt successfully",true);l.update("R3","blocked","승인 거절",false)
        val report=l.report()
        assertTrue(report.contains("✅ R1") && report.contains("⬜ R2") && report.contains("⛔ R3") && report.contains("미완료") && report.contains("승인 거절"),report)
        assertTrue(l.pinned().none {it.containsKey("evidence")})
        assertEquals("",ProjectAgentLedger().report())
    }

    @Test fun `duplicates are ignored and the list stays bounded`() {
        val l=ledger("같은 항목")
        assertNull(l.add("같은 항목"))
        repeat(20) {l.add("추가 항목 $it")}
        assertTrue(l.all.size<=ProjectAgentLedger.MAX_ITEMS+4)
    }
}
