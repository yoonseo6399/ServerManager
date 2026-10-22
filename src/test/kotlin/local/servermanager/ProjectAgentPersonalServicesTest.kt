package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class ProjectAgentPersonalServicesTest {
    @TempDir lateinit var temp:Path
    @Test fun `provider source ACL isolates consumer server channel and user and forum revokes immediately`() {
        val grants=ProjectAgentHistoryGrants(temp)
        grants.register("provider","11","100","FORUM")
        assertFails {grants.requireAllowed("consumer","200","22","100")}
        grants.bind("provider","11","100","consumer","200",setOf("22"),true)
        assertEquals("provider",ProjectAgentHistoryGrants(temp).requireAllowed("consumer","200","22","101","100").guild)
        assertFails {grants.requireAllowed("other","200","22","100")}
        assertFails {grants.requireAllowed("consumer","201","22","100")}
        assertFails {grants.requireAllowed("consumer","200","23","100")}
        assertFails {grants.bind("provider","other","100","consumer","200",setOf("22"),true)}
        grants.revokeTarget("provider","11","100","200")
        assertFails {grants.requireAllowed("consumer","200","22","101","100")}
        grants.bind("provider","11","100","consumer","200",setOf("22"),true)
        grants.remove("provider","11","100");assertFails {grants.requireAllowed("consumer","200","22","100")}
    }
    @Test fun `cross server read uses provider authority and never requires recipient source membership`() {
        val grants=ProjectAgentHistoryGrants(temp);grants.register("provider","11","100","FORUM");grants.bind("provider","11","100","consumer","200",setOf("22"),true)
        val jda=org.mockito.Mockito.mock(net.dv8tion.jda.api.JDA::class.java)
        val guild=org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Guild::class.java,org.mockito.Mockito.RETURNS_DEEP_STUBS)
        val member=org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Member::class.java){call->if(call.method.name=="hasPermission")true else org.mockito.Mockito.RETURNS_DEFAULTS.answer(call)}
        val forum=org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.channel.concrete.ForumChannel::class.java)
        org.mockito.Mockito.`when`(guild.id).thenReturn("provider");org.mockito.Mockito.`when`(guild.selfMember).thenReturn(org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.SelfMember::class.java){call->if(call.method.name=="hasPermission")true else org.mockito.Mockito.RETURNS_DEFAULTS.answer(call)})
        org.mockito.Mockito.`when`(guild.retrieveMemberById("11").useCache(false).timeout(5,java.util.concurrent.TimeUnit.SECONDS).complete()).thenReturn(member)
        org.mockito.Mockito.`when`(forum.id).thenReturn("100");org.mockito.Mockito.`when`(forum.guild).thenReturn(guild)
        org.mockito.Mockito.`when`(jda.getGuildChannelById("100")).thenReturn(forum)
        var authority="";val history=DiscordProjectAgentHistory({jda},grants){authority=it.guild+":"+it.owner}
        val session=ProjectAgentChannel("consumer","200","22",null,temp.toString())
        assertSame(forum,history.check(session,"100"));assertEquals("provider:11",authority)
        org.mockito.Mockito.verify(guild,org.mockito.Mockito.never()).retrieveMemberById("22")
        grants.revokeTarget("provider","11","100","200");assertFails {history.check(session,"100")}
    }
    @Test fun `one shot schedules persist claim once isolate owners and never replay after restart`() {
        var now=Instant.parse("2026-10-22T00:00:00Z");val store=ProjectAgentSchedules(temp){now}
        val session=ProjectAgentChannel("g","c","u",null,temp.toString(),contextEpoch=7)
        val task=store.create(session,8,"2026-10-22T09:01:00+09:00","test","파일 조사")
        assertTrue(store.due().isEmpty());assertTrue(store.list("g","other").isEmpty());assertFails {store.cancel("g","other",task.id)}
        now=now.plusSeconds(60);assertEquals(task.id,store.due().single().id)
        assertNotNull(store.claim(task.id));assertNull(store.claim(task.id))
        val restarted=ProjectAgentSchedules(temp){now}
        assertEquals("INTERRUPTED",restarted.list("g","u").single().state);assertTrue(restarted.due().isEmpty())
        val next=restarted.create(session,8,"2026-10-22T09:02:00+09:00","cancel","noop")
        assertEquals("CANCELLED",restarted.cancel("g","u",next.id).state)
        assertFails {restarted.create(session,8,"2026-10-22T09:05:00","no offset","noop")}
        assertFails {restarted.create(session,8,"2026-10-22T00:00:00Z","past","noop")}
    }
    @Test fun `dispatcher waits offline or busy then executes once and records completion or permission failure`() {
        var now=Instant.parse("2026-10-22T00:00:00Z");val schedules=ProjectAgentSchedules(temp){now};val session=ProjectAgentChannel("g","c","u",null,temp.toString())
        val task=schedules.create(session,0,"2026-10-22T00:01:00Z","once","inspect")
        var connected=false;var busy=false;var count=0;var reject=false
        val dispatcher=ProjectAgentScheduleDispatcher(schedules,{connected},{busy},{it()},{count++;check(!reject){"권한 철회"};true})
        now=now.plusSeconds(61);dispatcher.tick();assertEquals(0,count)
        connected=true;busy=true;dispatcher.tick();assertEquals(0,count)
        busy=false;dispatcher.tick();dispatcher.tick();assertEquals(1,count);assertEquals("DONE",schedules.list("g","u").first {it.id==task.id}.state)
        val denied=schedules.create(session,0,"2026-10-22T00:02:00Z","denied","inspect");now=now.plusSeconds(60);reject=true
        dispatcher.tick();dispatcher.tick();assertEquals(2,count);assertEquals("FAILED",schedules.list("g","u").first {it.id==denied.id}.state)
    }
    @Test fun `late schedules are marked missed and independent ASK for mutation requires approval`() {
        var now=Instant.parse("2026-10-22T00:00:00Z");val schedules=ProjectAgentSchedules(temp){now}
        val session=ProjectAgentChannel("g","c","u",null,temp.toString());val task=schedules.create(session,0,"2026-10-22T00:01:00Z","late","noop")
        now=now.plusSeconds(1000);assertNull(schedules.claim(task.id));assertEquals("MISSED",schedules.list("g","u").single().state)
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val tools=ProjectAgentTools(temp.resolve("logs"),services=ProjectAgentPersonalServices(session,schedules,DiscordProjectAgentHistory({null},ProjectAgentHistoryGrants(temp)),{ProjectAgentPreferences()},{ }))
        val policy=PiToolPolicy(temp,temp,tools,AgentAccess.OWNER)
        val checked=policy.check("schedule_task",mapper.readTree("""{"action":"cancel","id":"${task.id}"}"""))
        val risk=PiApprovalDecision.effective(checked,ProjectAgentRisk("ASK","MEDIUM","예약 변경 동의 필요","INSIDE"))
        assertEquals("ASK",risk.action);assertTrue(PiApprovalDecision.human(false,checked.display,risk,false))
        assertTrue(PiToolPolicy.extras(AgentAccess.READ).none {it["name"]=="schedule_task"});assertTrue(PiToolPolicy.extras(AgentAccess.READ).map {it["name"]}.containsAll(ProjectAgentServicePort.historyNames))
    }
}
