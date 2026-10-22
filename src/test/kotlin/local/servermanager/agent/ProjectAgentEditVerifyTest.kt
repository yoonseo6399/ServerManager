package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentEditVerifyTest {
    @TempDir lateinit var temp:Path

    @Test fun `Pi read edit reread chain preserves original bytes and supplies final file content`() {
        PiTestFixture(temp).use {f->val file=f.home.resolve("chain.txt");file.writeText("original\r\nTAIL\r\n");var step=0
            f.run(f.model {q->when(step++){
                0->PiTestFixture.call("read",mapOf("path" to "chain.txt"))
                1->PiTestFixture.call("edit",mapOf("path" to "chain.txt","edits" to listOf(mapOf("oldText" to "original","newText" to "original\r\nadded"))))
                2->PiTestFixture.call("read",mapOf("path" to "chain.txt"))
                else->{assertContains(q.toString(),"added");PiTestFixture.answer("재조회로 검증했습니다.")}
            }},"chain.txt에 added 추가 후 다시 읽기")
            assertContentEquals("original\r\nadded\r\nTAIL\r\n".toByteArray(),file.readBytes())
            assertEquals(listOf("read","edit","read"),f.events.filter {it.kind=="tool_end"}.map {it.plan!!.tool.name});assertEquals("재조회로 검증했습니다.",f.events.last().text)
        }
    }
}
