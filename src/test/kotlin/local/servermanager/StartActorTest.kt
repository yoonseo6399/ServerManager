package local.servermanager

import org.junit.jupiter.api.Test
import java.net.ServerSocket
import net.dv8tion.jda.api.EmbedBuilder
import kotlin.io.path.*
import kotlin.test.*

class StartActorTest {
    @Test fun `start embed shows Discord profile name image and source without allowing other avatar hosts`() {
        val actor=StartActor("DISCORD","친구 이름","123456789012345678","https://cdn.discordapp.com/avatars/123456789012345678/hash.png")
        val builder=EmbedBuilder().setTitle("🟢 서버 시작 완료 · Let's Create").setDescription("이제 접속할 수 있습니다.")
        DiscordStartProfile.apply(builder,actor)
        val embed=builder.build()
        assertEquals("친구 이름",embed.author!!.name);assertEquals(actor.profileUrl(),embed.author!!.url)
        assertEquals(actor.avatarUrl,embed.thumbnail!!.url);assertEquals(actor.avatarUrl,embed.author!!.iconUrl)
        assertTrue(embed.fields.any {it.name=="실행 경로" && it.value=="Discord"})
        listOf("http://cdn.discordapp.com/avatar.png","https://evil.example/avatar.png","https://cdn.discordapp.com.evil.example/avatar.png").forEach {assertNull(actor.copy(avatarUrl=it).safeAvatar())}
        assertNull(actor.copy(discordId="invalid").profileUrl())
        val auto=EmbedBuilder().setTitle("시작");DiscordStartProfile.apply(auto,StartActor("AUTO","자동 재시작"));assertNull(auto.build().thumbnail)
        assertTrue(auto.build().fields.any {it.value=="자동 재시작"})
    }
    @Test fun `accepted run retains its actor until ready and remote restart attributes only the next run`() {
        val manager=ServerManager();val i=manager.create(CreateRequest("start actor","21.1.219",ServerSocket(0).use {it.localPort},512))["config"] as Instance
        manager.attachRemoteController(object:RemoteController {
            override fun start(instance:Instance){}
            override fun stop(instance:Instance){}
            override fun restart(instance:Instance){}
            override fun command(instance:Instance,value:String){}
        });manager.assignRunner(i.id,"runner")
        val events=java.util.concurrent.CopyOnWriteArrayList<ServerSignal>();val sub=manager.onSignal {events.add(it)}
        val first=StartActor("DISCORD","First","123456789012345678");val next=StartActor("DISCORD","Next","223456789012345678")
        try {
            manager.startAs(i.id,first);assertTrue(events.isEmpty());manager.remoteState(i.id,"runner","RUNNING")
            assertEquals(first,events.single {it.kind=="start"}.startActor)
            assertFailsWith<IllegalStateException>{manager.startAs(i.id,next)}
            assertEquals(first,manager.runtime(i.id).startActor)
            manager.restartAs(i.id,next);manager.remoteExited(i.id,"runner",RunnerExitReport(0));manager.remoteCommitted(i.id,"runner")
            manager.remoteCloudReady(i.id);manager.remoteWorkerReady(i.id,"runner")
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while(manager.runtime(i.id).state!="TRANSFERRING" && System.nanoTime()<deadline)Thread.sleep(5)
            assertEquals("TRANSFERRING",manager.runtime(i.id).state)
            manager.remoteState(i.id,"runner","RUNNING");manager.remoteState(i.id,"runner","RUNNING")
            assertEquals(listOf(first,next),events.filter {it.kind=="start"}.map {it.startActor})
            manager.remoteExited(i.id,"runner",RunnerExitReport(0));manager.remoteCommitted(i.id,"runner")
        }finally{sub.close();manager.shutdown();manager.root.resolve(i.id).toFile().deleteRecursively()}
    }
}
