package local.servermanager

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel

/** Markdown fences are closed/reopened across Discord's 2000 UTF-16 code-unit limit. */
internal object DiscordAgentChunks {
    fun split(text:String):List<String> {
        if(text.isEmpty())return listOf("…")
        val result=mutableListOf<String>();var remaining=text;var fence:String?=null;var marker="```"
        while(remaining.isNotEmpty()){
            val prefix=fence?.let {"$marker$it\n"}.orEmpty()
            var cut=minOf(1850-prefix.length,remaining.length)
            if(cut<remaining.length){val line=remaining.lastIndexOf('\n',cut-1);if(line>cut/2)cut=line+1;if(cut>0 && Character.isHighSurrogate(remaining[cut-1]))cut--}
            val body=remaining.take(cut)
            Regex("(?m)^(`{3,})([^\\r\\n`]*)").findAll(body).forEach {match->if(fence==null){marker=match.groupValues[1];fence=match.groupValues[2].take(40)}else if(match.groupValues[1].length>=marker.length && match.groupValues[2].isBlank())fence=null}
            result.add(prefix+body+(if(fence!=null)"\n$marker" else ""));remaining=remaining.drop(cut)
        }
        return result
    }
}

/** Only the latest snapshot waits; REST completion drives ordering without blocking inference. */
internal class DiscordAgentText(private val channel:MessageChannel,intervalMillis:Long=750,private val onMessage:()->Unit={}) {
    private val messages=mutableListOf<Message>()
    private val lock=Any();private var discarded=false
    private fun remove(message:Message){message.delete().queue({}, {})}
    private val edits=CoalescedMessageEdits<String>({text,complete->
        val chunks=DiscordAgentChunks.split(text)
        fun next(index:Int){
            val target=synchronized(lock){if(discarded || index>=chunks.size){complete();return}else messages.getOrNull(index)}
            if(target!=null)target.editMessage(chunks[index]).setAllowedMentions(emptyList()).queue({next(index+1)},{complete()})
            else channel.sendMessage(chunks[index]).setAllowedMentions(emptyList()).queue({message->
                val keep=synchronized(lock){if(discarded)false else {messages.add(message);true}}
                if(keep){runCatching {onMessage()};next(index+1)}else {remove(message);complete()}
            },{complete()})
        }
        next(0)
    },intervalMillis=intervalMillis)
    fun offer(text:String,finished:Boolean=false){if(synchronized(lock){!discarded})edits.offer(text,finished)}
    /** Permanently discard temporary text, including an in-flight send completing later. */
    fun discard(){
        val sent=synchronized(lock){discarded=true;messages.toList().also {messages.clear()}}
        edits.cancel();sent.forEach(::remove)
    }
}
