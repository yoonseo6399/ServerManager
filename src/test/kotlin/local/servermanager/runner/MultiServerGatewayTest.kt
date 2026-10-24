package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import kotlin.test.*

class MultiServerGatewayTest {
    @TempDir lateinit var root:Path
    @Test fun `ports reserved by colocated relay and voice follow the selected computer and moves reject conflicts`() {
        val manager=mock(ServerManager::class.java);val network=mock(GameNetwork::class.java)
        val registry=mock(RunnerRegistry::class.java);val service=mock(DirectRelayService::class.java)
        val store=DirectRelayStore(root);val core=DirectRelayManager(store,mock(RelayWorld::class.java))
        `when`(service.core).thenReturn(core)
        `when`(network.config()).thenReturn(GameNetworkConfig(true,port=6700))
        val a=Instance("a","a",loaderVersion="21",port=25565,memoryMb=512,javaPath="java",runnerId="one")
        val b=a.copy(id="b",runnerId="two");`when`(manager.instance("a")).thenReturn(a);`when`(manager.instance("b")).thenReturn(b)
        `when`(manager.configuredGamePorts("one","b")).thenReturn(setOf(25565))
        `when`(manager.configuredGamePorts("relay","b")).thenReturn(emptySet())
        store.save(DirectRelaySave(DirectRelayConfig(true,"relay",securePort=6400,voicechat=listOf(VoiceChatRelayConfig("a",24454,34454),VoiceChatRelayConfig("b",24455,34454))),0))
        val gateway=RunnerGateway(registry,manager,network,service)
        try {
            assertEquals(setOf(6700,6400),gateway.reservedGamePorts("relay"));assertEquals(setOf(34454),gateway.reservedGamePorts("one"));assertTrue(gateway.reservedGamePorts("other").isEmpty())
            assertFailsWith<IllegalArgumentException> {gateway.validateRunnerAssignment(b,"one")}
            gateway.validateRunnerAssignment(b,"relay")
            store.save(DirectRelaySave(store.config().copy(voicechat=listOf(VoiceChatRelayConfig("a",24454,34454),VoiceChatRelayConfig("b",24455,24454))),1))
            assertFailsWith<IllegalArgumentException> {gateway.validateRunnerAssignment(b,"relay")}
        } finally {gateway.closeDatagrams()}
    }
}
