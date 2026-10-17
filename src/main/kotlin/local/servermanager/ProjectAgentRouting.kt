package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*

data class AgentConversationMessage(val id:String,val author:String,val name:String,val bot:Boolean,val text:String,val at:Long,val replyTo:String?=null,val agentDirected:Boolean=false,val replyAuthor:String?=null)
data class AgentAudience(val messages:List<AgentConversationMessage>,val participantIds:Set<String>)
enum class AgentAddressee { AGENT,HUMAN,UNCERTAIN }

/** Recent private-channel participants, separate from model memory; identities never grant execution permission. */
class ProjectAgentRouting(private val directory:Path,private val clock:()->Long={Instant.now().toEpochMilli()}) {
    private val mapper=jacksonObjectMapper();private val file=directory.resolve("project-agent-audience.json")
    private var channels:Map<String,List<AgentConversationMessage>> = if(file.exists())mapper.readValue(file.readText())else emptyMap()
    @Synchronized fun audience(channel:String):AgentAudience {
        val recent=channels[channel].orEmpty().filter {clock()-it.at in 0..900_000}
        return AgentAudience(recent,recent.filter {!it.bot}.map {it.author}.toSet())
    }
    @Synchronized fun record(channel:String,value:AgentConversationMessage){
        if(value.bot && value.text.isBlank())return // status-only embeds are not conversational turns
        val recent=(audience(channel).messages.filter {it.id!=value.id}+value.copy(name=value.name.take(100),text=value.text.take(300),at=value.at.coerceAtMost(clock()))).sortedBy {it.at}.filter {clock()-it.at in 0..900_000}.takeLast(64)
        channels=(channels.filterValues {rows->rows.any {clock()-it.at in 0..900_000}}+(channel to recent)).entries.toList().takeLast(200).associate {it.toPair()};persist()
    }
    @Synchronized fun clear(channel:String){channels=channels-channel;persist()}
    private fun persist(){GeminiKeyPoolStore(directory).writePrivate(file,mapper.writeValueAsString(channels))}
    fun decide(channel:String,message:AgentConversationMessage,botId:String):AgentAddressee {
        val text=message.text.trim()
        val mentioned=Regex("<@!?([0-9]+)>").findAll(text).map {it.groupValues[1]}.toSet()
        if(botId in mentioned || Regex("^(?:루오노타르|루오)(?:야|[\\s,:!?~]|$)").containsMatchIn(text))return AgentAddressee.AGENT
        if(mentioned.any {it!=botId})return AgentAddressee.HUMAN
        val recent=audience(channel)
        if(message.replyAuthor!=null)return if(message.replyAuthor==botId)AgentAddressee.AGENT else AgentAddressee.HUMAN
        val target=message.replyTo?.let {id->recent.messages.find {it.id==id}}
        if(target!=null)return if(target.bot && target.author==botId)AgentAddressee.AGENT else AgentAddressee.HUMAN
        if(message.replyTo!=null)return AgentAddressee.UNCERTAIN
        if((recent.participantIds+message.author).size==1)return AgentAddressee.AGENT
        val previous=recent.messages.filter {it.id!=message.id}
        val lastBot=previous.lastOrNull {it.bot && it.author==botId}
        if(lastBot!=null && clock()-lastBot.at<120_000 && previous.drop(previous.indexOfLast {it.id==lastBot.id}+1).none {!it.bot && it.author!=message.author})return AgentAddressee.AGENT
        val lastUser=previous.lastOrNull {!it.bot}
        if(lastUser?.author==message.author && lastUser.agentDirected && clock()-lastUser.at<120_000)return AgentAddressee.AGENT
        return AgentAddressee.UNCERTAIN
    }
}
