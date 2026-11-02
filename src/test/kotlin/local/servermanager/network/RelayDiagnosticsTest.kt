package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class RelayDiagnosticsTest {
    @TempDir lateinit var root:Path
    @Test fun `diagnostic log rotates remains private and retains timestamp event identifiers and cause types`() {
        RelayFileLog(root,maxBytes=250,backups=2).use {log->
            repeat(15){log.emit("stream_closed",mapOf("stream" to it,"errorType" to relayError(java.util.concurrent.CompletionException(java.net.ConnectException("secret-token")))))}
        }
        val files=Files.list(root).use {it.toList()};assertTrue(files.size in 2..3)
        val text=files.joinToString("\n"){Files.readString(it)};assertFalse(text.contains("secret-token"))
        val mapper=jacksonObjectMapper()
        files.forEach {f->
            Files.readAllLines(f).forEach {line->val n=mapper.readTree(line);assertTrue(n.has("at"));assertEquals("stream_closed",n.path("event").asText());assertTrue(n.path("errorType").asText().contains("ConnectException"))}
            if(!System.getProperty("os.name").startsWith("Windows"))assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(f))
        }
    }
}
