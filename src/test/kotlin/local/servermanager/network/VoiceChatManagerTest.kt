package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*

class VoiceChatManagerTest {
    @TempDir lateinit var dir:Path
    private class World:RelayWorld {
        val relay=UUID.randomUUID().toString();val game=UUID.randomUUID().toString();val a="server-a";val b="server-b"
        val sent=mutableListOf<Pair<String,RunnerMessage>>()
        override fun runner(id:String)=if(id in setOf(relay,game))RunnerDevice(id,id,"MAC","owner","key","now",allowedInstanceIds=if(id==game)setOf(a,b) else emptySet()) else null
        override fun connected(id:String)=true
        override fun runnerName(id:String)=id
        override fun routes()=listOf(Triple(a,game,listOf("a.example.com")),Triple(b,game,listOf("b.example.com")))
        override fun send(runnerId:String,message:RunnerMessage):Boolean{sent.add(runnerId to message);return true}
        override fun resolveHost(host:String)=listOf("203.0.113.2")
        override fun gamePort()=6700
        override fun instancePort(id:String)=25565
    }
    private fun setup(voice:Boolean=true):Pair<World,DirectRelayManager> {
        val w=World();val m=DirectRelayManager(DirectRelayStore(dir.resolve("store")),w)
        for(id in listOf(w.relay,w.game))m.onRunnerMessage(id,RunnerMessage("relay-consent",version=if(voice)"DRP1_VOICE1" else "DRP1_UDP1",detail=jacksonObjectMapper().writeValueAsString(RelayConsentState(true,"test"))))
        return w to m
    }
    private fun config(w:World,vararg routes:VoiceChatRelayConfig)=DirectRelayConfig(true,w.relay,securePort=6400,publicHost="voice.example.com",voicechat=routes.toList())
    @Test fun `voice settings persist outside old schema and expose port forwarding and scoped targets`() {
        val (w,m)=setup();m.save(DirectRelaySave(config(w,VoiceChatRelayConfig(w.a)),0))
        assertTrue(m.view().relays.first {it.runnerId==w.relay}.router.any {it.protocol=="UDP" && it.externalPort==24454 && it.label.startsWith("음성")})
        val mapper=jacksonObjectMapper();val agent=w.sent.last {it.first==w.game && it.second.type=="drp-agent-config"}.second
        val cfg=mapper.readValue(agent.detail,AgentRelayConfig::class.java);assertEquals(listOf(VoiceChatTarget(w.a,24454,"voice.example.com:24454")),cfg.voicechat)
        val old=mapper.readTree(dir.resolve("store/settings.json").readText());assertFalse(old.path("config").has("voicechat"));assertEquals(1,DirectRelayStore(dir.resolve("store")).config().voicechat.size)
        val changed=(old as com.fasterxml.jackson.databind.node.ObjectNode).put("revision",5);dir.resolve("store/settings.json").writeText(changed.toString());assertTrue(DirectRelayStore(dir.resolve("store")).config().voicechat.isEmpty())
    }
    @Test fun `unupdated hosts cannot enable voice but can keep existing TCP mode`() {
        val (w,m)=setup(false);assertFailsWith<IllegalArgumentException>{m.save(DirectRelaySave(config(w,VoiceChatRelayConfig(w.a)),0))}
        m.save(DirectRelaySave(config(w),0));assertTrue(m.store.config().enabled)
    }
    @Test fun `public duplicate private duplicate game conflict and unknown instances are rejected`() {
        val (w,m)=setup()
        val invalid=listOf(
            listOf(VoiceChatRelayConfig(w.a),VoiceChatRelayConfig(w.b,24454,24455)),
            listOf(VoiceChatRelayConfig(w.a),VoiceChatRelayConfig(w.b,24455,24454)),
            listOf(VoiceChatRelayConfig(w.a,6700,24454)),listOf(VoiceChatRelayConfig(w.a,24454,25565)),
            listOf(VoiceChatRelayConfig("unapproved",24454,24454)))
        invalid.forEach {assertFailsWith<IllegalArgumentException>{m.save(DirectRelaySave(config(w,*it.toTypedArray()),0))}}
        m.save(DirectRelaySave(config(w,VoiceChatRelayConfig(w.a),VoiceChatRelayConfig(w.b,24455,24455)),0))
    }
    @Test fun `candidate readiness requires voice port listener and disconnection clears capability`() {
        val (w,m)=setup();m.save(DirectRelaySave(config(w,VoiceChatRelayConfig(w.a)),0))
        fun report(voices:Map<String,VoiceChatStatus>)=m.onReport(w.relay,RunnerRelayReport(RelayConsentState(true,"test"),RelayHubStatus(true,6700,true,6400,1,listOf(RelayAgentStatus(w.game,1,1,1)),emptyList(),emptyMap(),emptyMap(),null,voicechat=voices),emptyList(),true,true))
        report(emptyMap());assertFalse(m.isReady(w.relay));report(mapOf(w.a to VoiceChatStatus("ERROR")));assertFalse(m.isReady(w.relay));report(mapOf(w.a to VoiceChatStatus("LISTENING")));assertTrue(m.isReady(w.relay))
        m.onDisconnect(w.game);assertFalse(m.view().voicechatServers.first {it.instanceId==w.a}.capable)
    }
}
