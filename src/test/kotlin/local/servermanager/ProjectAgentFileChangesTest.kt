package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentFileChangesTest {
    @TempDir lateinit var temp:Path
    private fun plan(name:String,paths:List<Path>)=ProjectAgentPlan(ProjectAgentTool(name,jacksonObjectMapper().createObjectNode()),temp.toString(),name,false,scope="INSIDE",resolvedTargets=paths.map {it.toString()})
    @Test fun `all direct file edit styles capture exact originals creation deletion and CRLF`() {
        val home=temp.resolve("project").createDirectories();val profile=temp.resolve("profile").createDirectories();val file=home.resolve("a.txt");val capture=ProjectAgentFileChanges(home,profile)
        file.writeText("HEAD\r\nvalue=1\r\nTAIL\r\n")
        for(name in listOf("edit","write","edit_file","save_attachment")) {
            val before=capture.before(plan(name,listOf(file)));val old=file.readText();file.writeText(old.replace("value=1","value=2"))
            val change=capture.after(before).single();assertEquals(old,change.before);assertEquals(old.replace("value=1","value=2"),change.after);assertContains(change.diff,"-value=1");assertContains(change.diff,"+value=2")
            file.writeText(old)
        }
        val created=home.resolve("new.txt");val pre=capture.before(plan("write",listOf(created)));created.writeText("new\n")
        assertNull(capture.after(pre).single().before)
        val deleted=capture.before(plan("delete_path",listOf(created)));created.deleteExisting();assertNull(capture.after(deleted).single().after)
    }
    @Test fun `bash reports bounded project changes and skips secrets symlinks binary and private profile`() {
        val home=temp.resolve("project").createDirectories();val profile=home.resolve("private-profile").createDirectories();val capture=ProjectAgentFileChanges(home,profile)
        val normal=home.resolve("a.txt");normal.writeText("a");home.resolve(".env").writeText("secret-before");profile.resolve("auth.json").writeText("private-before")
        val pre=capture.before(plan("bash",emptyList()));normal.writeText("b");home.resolve("new.py").writeText("print(1)\n");home.resolve(".env").writeText("secret-after");profile.resolve("auth.json").writeText("private-after")
        val changes=capture.after(pre);assertEquals(setOf("a.txt","new.py"),changes.map {Path.of(it.path).fileName.toString()}.toSet())
        val previous=capture.before(plan("write",listOf(normal)));normal.writeBytes(byteArrayOf(0,1,2));assertTrue(capture.after(previous).isEmpty(),"unreadable/binary result must not be falsely reported as deletion")
    }
    @Test fun `preview is owner channel epoch bound expires and original bytes remain intact`() {
        var now=100L;val previews=ProjectAgentFilePreviews {now};val change=ProjectAgentFileChange("/p/a.txt","a\r\n","b\r\n","diff")
        val token=previews.put("c","u",1,change);assertEquals("a\r\n",previews.get(token,"c","u",1).change.before)
        assertFails {previews.get(token,"other","u",1)};assertFails {previews.get(token,"c","other",1)};assertFails {previews.get(token,"c","u",2)}
        now+=600001;assertFails {previews.get(token,"c","u",1)}
    }
    @Test fun `long fenced output including nested markdown remains wrapped in every Discord chunk`() {
        val text="```python\nprint(1)\n```\n"+"a long log line 한글 😀\n".repeat(300)
        val chunks=DiscordAgentChunks.split(DiscordAgentCode.wrap(text))
        assertTrue(chunks.size>2);assertTrue(chunks.all {it.length<=2000 && it.startsWith("````text\n") && it.endsWith("````")})
        val diff=ProjectAgentFileChanges.unified("a.txt","old\n","new\n")
        assertContains(diff,"-old");assertContains(diff,"+new")
    }
}
