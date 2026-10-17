package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.utils.NamedAttachmentProxy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentFilesTest {
    @TempDir lateinit var home:Path
    private val mapper=jacksonObjectMapper()
    @Test fun `attachment read uses refreshed message in fixed channel and saves only a new UTF8 file`() {
        val attachment=mock(Message.Attachment::class.java);val proxy=mock(NamedAttachmentProxy::class.java)
        `when`(attachment.id).thenReturn("2");`when`(attachment.size).thenReturn(20);`when`(attachment.fileExtension).thenReturn("kt");`when`(attachment.proxy).thenReturn(proxy)
        `when`(proxy.download()).thenAnswer {CompletableFuture.completedFuture(ByteArrayInputStream("val 이름 = 1\nline two".toByteArray()))}
        val message=mock(Message::class.java);`when`(message.attachments).thenReturn(listOf(attachment))
        @Suppress("UNCHECKED_CAST") val action=mock(RestAction::class.java) as RestAction<Message>
        `when`(action.complete()).thenReturn(message)
        val channel=mock(TextChannel::class.java);`when`(channel.retrieveMessageById("1")).thenReturn(action)
        var checks=0;val files=DiscordProjectAgentFiles({channel},{checks++})
        val args=mapper.readTree("""{"message_id":"1","attachment_id":"2","lines":1}""")
        assertContains(files.execute("read_attachment",args,home){},"1: val 이름 = 1");assertTrue(checks>=2)
        files.execute("save_attachment",args,home.resolve("file.kt")){};assertContains(home.resolve("file.kt").readText(),"line two")
        assertFails {files.execute("save_attachment",args,home.resolve("file.kt")){}}
        assertFails {files.execute("read_attachment",mapper.readTree("""{"message_id":"1","attachment_id":"99"}"""),home){}}
        verify(channel,atLeast(3)).retrieveMessageById("1")
        `when`(attachment.fileExtension).thenReturn("pdf");assertFails {files.execute("read_attachment",args,home){}}
    }
    @Test fun `file tool unavailable outside Discord and outside upload still requires approval`() {
        val tools=ProjectAgentTools(home.resolve("logs"));val read=ProjectAgentTool("read_attachment",mapper.readTree("""{"message_id":"1","attachment_id":"2"}"""))
        assertFails {tools.execute(home,tools.plan(home,read)){}}
        val outside=home.parent.resolve("outside-${home.fileName}.txt");outside.writeText("outside")
        try {val plan=tools.plan(home,ProjectAgentTool("upload_file",mapper.valueToTree(mapOf("path" to outside.toString()))));assertEquals("OUTSIDE",plan.scope);assertFalse(plan.readOnly)}finally{outside.deleteIfExists()}
    }
}
