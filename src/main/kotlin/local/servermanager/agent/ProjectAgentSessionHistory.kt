package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*

/** Additive private sidecar: preserves provider-native messages while the legacy JSON stays rollback-readable. */
internal class ProjectAgentSessionHistory(private val directory:Path) {
    data class NativeState(val epoch:Long=0,val digest:String="",val native:Map<Int,String> = emptyMap())
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("project-agent-native-history.json")
    private var states:Map<String,NativeState> = runCatching {if(file.exists())mapper.readValue<Map<String,NativeState>>(file.readText())else emptyMap<String,NativeState>()}.getOrDefault(emptyMap())
    private fun digest(history:List<ProjectAgentMessage>)=MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(history.map {mapOf("role" to it.role,"content" to it.content)})).joinToString(""){"%02x".format(it)}
    fun restore(channel:String,epoch:Long,history:List<ProjectAgentMessage>):List<ProjectAgentMessage> {
        val state=states[channel]?.takeIf {it.epoch==epoch && it.digest==digest(history)} ?: return history
        return history.mapIndexed {i,m->m.copy(native=state.native[i])}
    }
    fun save(channels:Map<String,ProjectAgentChannel>) {
        val next=channels.mapValues {(_,c)->NativeState(c.contextEpoch,digest(c.history),c.history.mapIndexedNotNull {i,m->m.native?.let {i to it}}.toMap())}
        GeminiKeyPoolStore(directory).writePrivate(file,mapper.writeValueAsString(next));states=next
    }
    fun archive(channel:String,epoch:Long,history:List<ProjectAgentMessage>) {
        require(channel.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val folder=directory.resolve("project-agent-transcripts/$channel")
        // An immutable pre-compaction snapshot. Include native payload explicitly (legacy DTO ignores that field).
        val value=mapOf("epoch" to epoch,"at" to System.currentTimeMillis(),"messages" to history.map {mapOf("role" to it.role,"content" to it.content,"native" to it.native)})
        GeminiKeyPoolStore(folder).writePrivate(folder.resolve("${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.json"),mapper.writeValueAsString(value))
    }
}
