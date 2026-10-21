package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*
import kotlin.io.path.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class LocalModelsTest {
    @TempDir lateinit var temp:Path
    @Test fun `seven catalog entries persist without changing legacy defaults and reject unknown IDs`() {
        assertEquals(listOf("14b","30b","qwen3-coder-30b","gemma4-12b","gemma4-31b","qwen38-27b","qwen38-flash-next"),LocalLlm.SIZES)
        assertEquals("14b",QwenSettings(temp).config().model)
        for(m in LocalModels.all){
            assertEquals(m.tag,LocalLlm.tag(m.id))
            QwenSettings(temp).save(QwenConfig(model=m.id));assertEquals(m.id,QwenSettings(temp).config().model)
            val store=ProjectAgentStore(temp.resolve("agent"));store.settings("g","u",m.id,true,null)
            assertEquals(m.id,ProjectAgentStore(temp.resolve("agent")).preferences("g","u").model)
        }
        assertFails {LocalLlm.tag("gemma4:arbitrary")}
        assertFalse(QwenSettings(temp).valid(QwenConfig(model="unknown")))
        assertFalse(LocalModels.thinking("qwen3-coder-30b",true));assertTrue(LocalModels.thinking("gemma4-12b",true))
    }
    @Test fun `read tool preserves newline metadata and identifies partial source without editing bytes`() {
        val tools=ProjectAgentTools(temp.resolve("logs"));val file=temp.resolve("note.txt")
        for((source,format,tail) in listOf(Triple("a\nb\n","LF",1),Triple("a\r\nb\r\n\r\n","CRLF",2),Triple("a\nb","LF",0))){
            file.writeText(source)
            val plan=tools.plan(temp,ProjectAgentTool("read_file",jacksonObjectMapper().valueToTree(mapOf("path" to "note.txt"))))
            val result=tools.execute(temp,plan){}
            assertContains(result.full,"line_endings=$format");assertContains(result.full,"trailing_line_breaks=$tail")
            assertContains(result.full,"1: a");assertEquals(source,file.readText())
        }
        file.writeText("a\nb\nc\n")
        val partial=tools.plan(temp,ProjectAgentTool("read_file",jacksonObjectMapper().valueToTree(mapOf("path" to "note.txt","lines" to 1))))
        assertContains(tools.execute(temp,partial){}.full,"PARTIAL SOURCE")
        file.writeText("\n".repeat(20000))
        assertContains(tools.execute(temp,partial){}.full,"trailing_line_breaks=20000")
    }
    @Test fun `star validator executes centered output and rejects imports and wrong alignment`() {
        assertTrue(LocalModelStars.execute("n=5\nfor i in range(n):\n print(' '*(n-i-1)+'*'*(2*i+1))",temp).first)
        assertFalse(LocalModelStars.execute("print('*')",temp).first)
        assertFalse(LocalModelStars.execute("import os\nprint(os.getcwd())",temp).first)
    }
    @Test fun `Discord settings and one-off run expose the entire same catalog`() {
        val command=DiscordProjectAgent.command()
        for((sub,option) in listOf("setting" to "default","run" to "model")){
            val choices=command.subcommands.single {it.name==sub}.options.single {it.name==option}.choices
            assertEquals(LocalModels.all.map {it.id},choices.map {it.asString})
            assertEquals(LocalModels.all.map {it.label},choices.map {it.name})
        }
    }
}
