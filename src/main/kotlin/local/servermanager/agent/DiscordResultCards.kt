package local.servermanager

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed

data class ProjectAgentReadProgress(val id:String,val start:Int,val current:Int,val total:Int) {
    companion object {
        fun parse(text:String):ProjectAgentReadProgress? {
            val m=Regex("^결과 (out-[0-9a-f]{16}) 줄 (\\d+)-(\\d+)/(\\d+)").find(text) ?: return null
            return ProjectAgentReadProgress(m.groupValues[1],m.groupValues[2].toInt(),m.groupValues[3].toInt(),m.groupValues[4].toInt())
        }
    }
}

/** A result's later pages edit the original card; no additional turn messages are sent. */
internal object DiscordResultCards {
    private class Card(val call:String,val message:Message,var base:MessageEmbed,val created:Long=System.currentTimeMillis()) {
        var progress:ProjectAgentReadProgress?=null
        val edits=CoalescedMessageEdits<MessageEmbed>({embed,complete->message.editMessageEmbeds(embed).queue({complete()},{complete()})})
    }
    private val cards=linkedMapOf<String,Card>()
    private fun key(channel:String,id:String)="$channel:$id"
    @Synchronized fun register(channel:String,id:String,call:String,message:Message,base:MessageEmbed) {
        cards.entries.removeIf {System.currentTimeMillis()-it.value.created>ProjectAgentResults.TTL_MILLIS}
        while(cards.size>=500)cards.remove(cards.keys.first())
        cards[key(channel,id)]=Card(call,message,base)
    }
    @Synchronized fun completed(channel:String,call:String,base:MessageEmbed){cards.filterKeys {it.startsWith("$channel:")}.values.filter {it.call==call}.forEach {it.base=base}}
    @Synchronized fun finishReading(channel:String,ids:Set<String>){ids.forEach {id->cards[key(channel,id)]?.let {card->card.progress?.let {p->card.edits.offer(EmbedBuilder(card.base).addField("모델 결과 조회 마침","${p.id} · 마지막 조회 ${p.current} / 전체 ${p.total}줄",false).build())}}}}
    @Synchronized fun reading(channel:String,progress:ProjectAgentReadProgress):Boolean {
        val card=cards[key(channel,progress.id)] ?: return false
        if(System.currentTimeMillis()-card.created>ProjectAgentResults.TTL_MILLIS){cards.remove(key(channel,progress.id));return false}
        card.progress=progress
        card.edits.offer(EmbedBuilder(card.base).addField("모델이 결과를 읽는 중…","${progress.id} · 현재 ${progress.current} / 전체 ${progress.total}줄",false).build())
        return true
    }
}
