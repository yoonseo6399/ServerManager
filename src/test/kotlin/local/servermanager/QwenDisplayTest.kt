package local.servermanager

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import kotlin.test.*

class QwenDisplayTest {
    @Test fun `only actual thinking is quoted and same message streams normal answer with stop then clears button`() {
        val cards=CopyOnWriteArrayList<Pair<MessageEmbed,Boolean>>();val channel=mock(TextChannel::class.java)
        lateinit var main:Message
        main=mock(Message::class.java) {call->
            if(call.method.name=="editMessageEmbeds"){
                val embeds=call.arguments[0];val card=when(embeds){is Array<*>->embeds[0] as MessageEmbed;is Collection<*>->embeds.first() as MessageEmbed;else->embeds as MessageEmbed}
                var buttons=false
                mock(MessageEditAction::class.java) {edit->when(edit.method.name){
                    "setComponents"->{buttons=(edit.arguments[0] as Collection<*>).isNotEmpty();edit.mock}
                    "setAllowedMentions"->edit.mock
                    "queue"->{cards.add(card to buttons);@Suppress("UNCHECKED_CAST") val complete=edit.arguments[0] as Consumer<Message>;complete.accept(main);null}
                    else->RETURNS_DEFAULTS.answer(edit)
                }}
            }else RETURNS_DEFAULTS.answer(call)
        }
        val display=DiscordProjectAgentDisplay(channel,main,"request","14b","run-id",5)
        fun waitFor(check:()->Boolean){val until=System.nanoTime()+2_000_000_000;while(!check() && System.nanoTime()<until)Thread.sleep(2);assertTrue(check())}
        display.emit(ProjectAgentEvent("stream",1,output="actual model thinking"));waitFor {cards.isNotEmpty()}
        assertEquals("‘actual model thinking’",cards.last().first.description);assertTrue(cards.last().second)
        display.emit(ProjectAgentEvent("stream",1,text="partial answer",output="actual model thinking"));waitFor {cards.last().first.description=="partial answer"};assertTrue(cards.last().second)
        display.emit(ProjectAgentEvent("done",1,"final answer"));waitFor {cards.last().first.description=="final answer"};assertFalse(cards.last().second)
        display.emit(ProjectAgentEvent("stream",1,output="late thinking"));Thread.sleep(30);assertEquals("final answer",cards.last().first.description)
        verifyNoInteractions(channel)
    }
}
