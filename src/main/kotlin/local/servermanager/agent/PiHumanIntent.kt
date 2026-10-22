package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import kotlin.io.path.*

/** Only authenticated human input enters this ledger, never model history or a compaction summary. */
internal class PiHumanIntent(private val directory:Path) {
    private data class Entry(val user:String,val epoch:Long,val revision:Long,val requests:List<String>,val open:Boolean=true)
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("pi-human-intent.json")
    private var entries:Map<String,Entry> = if(file.exists())mapper.readValue(file.readText())else emptyMap()
    private fun persist(next:Map<String,Entry>){GeminiKeyPoolStore(directory).writePrivate(file,mapper.writeValueAsString(next));entries=next}
    private fun matches(e:Entry,c:ProjectAgentChannel,revision:Long)=e.user==c.user && e.epoch==c.contextEpoch && e.revision==revision
    @Synchronized fun begin(c:ProjectAgentChannel,revision:Long,request:String) {
        val prior=entries[c.channel]
        val resume=Regex("(?i)^(재개해?|재개해줘|계속해?|계속해줘|마저 ?진행해?|이어서 ?진행해?|resume|continue)[.!。 ]*$").matches(request.trim())
        val requests=if(resume && prior!=null && prior.open && matches(prior,c,revision))prior.requests+request else listOf(request)
        persist((entries+(c.channel to Entry(c.user,c.contextEpoch,revision,(requests.take(1)+requests.drop(1).takeLast(7))))).entries.toList().takeLast(500).associate {it.toPair()})
    }
    @Synchronized fun append(c:ProjectAgentChannel,revision:Long,request:String) {
        val previous=entries[c.channel] ?: return
        if(previous.open && matches(previous,c,revision))persist(entries+(c.channel to previous.copy(requests=(previous.requests.take(1)+(previous.requests.drop(1)+request).takeLast(7)))))
    }
    @Synchronized fun requests(c:ProjectAgentChannel,revision:Long):List<String> = entries[c.channel]?.takeIf {it.open && matches(it,c,revision)}?.requests.orEmpty()
    @Synchronized fun finish(channel:String){entries[channel]?.let {persist(entries+(channel to it.copy(open=false)))}}
    @Synchronized fun clear(channel:String){if(channel in entries)persist(entries-channel)}
}
