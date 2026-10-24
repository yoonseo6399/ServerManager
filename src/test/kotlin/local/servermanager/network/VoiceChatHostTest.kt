package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*

class VoiceChatHostTest {
    @TempDir lateinit var root:Path
    @Test fun `resident applies voice settings before game start and restores originals on next start after disable`() {
        val home=root.resolve("host");val server=root.resolve("game").createDirectories();val file=server.resolve("config/voicechat/voicechat-server.properties");file.parent.createDirectories()
        val original="# keep\nport=24453\ncodec=VOIP\n";file.writeText(original)
        GameHostService(home,"fixture").use {host->assertTrue(host.start())
            GameHostClient.connect(home,"voice-test").use {client->
                val mapper=jacksonObjectMapper();val target=VoiceChatTarget("srv",24454,"voice.example.com:24455")
                assertTrue(client.relayForward(mapper.writeValueAsString(RunnerMessage("drp-agent-config",detail=mapper.writeValueAsString(AgentRelayConfig(emptyList(),setOf("srv"),voicechat=listOf(target)))))))
                val command=listOf(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"local.servermanager.FakeGameMain",server.toString())
                val p=client.start("srv",command,server,emptyMap()).process
                try {assertTrue(file.readText().contains("voice_host=voice.example.com:24455"));assertTrue(file.readText().contains("# keep"));client.stop("srv",p.pid());assertTrue(p.waitFor(10,TimeUnit.SECONDS));assertEquals(0,p.exitValue());client.ackExit("srv",p.pid())}finally {if(p.isAlive)p.destroyForcibly()}
                client.relayForward(mapper.writeValueAsString(RunnerMessage("drp-agent-config",detail=mapper.writeValueAsString(AgentRelayConfig(emptyList(),setOf("srv"))))))
                val next=client.start("srv",command,server,emptyMap()).process
                try {assertEquals(original,file.readText());client.stop("srv",next.pid());assertTrue(next.waitFor(10,TimeUnit.SECONDS))}finally {if(next.isAlive)next.destroyForcibly()}
            }
        }
    }
}
