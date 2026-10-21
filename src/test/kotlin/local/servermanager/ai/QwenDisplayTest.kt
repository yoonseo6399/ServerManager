package local.servermanager

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.MessageEditAction
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import kotlin.test.*

class QwenDisplayTest {
    @Test fun `thinking and markdown answers use plain messages while status stays in embeds`() {
        val cards=CopyOnWriteArrayList<Pair<MessageEmbed,Boolean>>();val texts=CopyOnWriteArrayList<String>();val recorded=CopyOnWriteArrayList<String>()
        lateinit var main:Message
        fun edit(card:MessageEmbed?=null,text:String?=null):MessageEditAction {
            var buttons=false
            return mock(MessageEditAction::class.java){call->when(call.method.name){
                "setComponents"->{buttons=(call.arguments[0] as Collection<*>).isNotEmpty();call.mock}
                "setAllowedMentions"->call.mock
                "queue"->{if(card!=null)cards.add(card to buttons);if(text!=null)texts.add(text);@Suppress("UNCHECKED_CAST") (call.arguments[0] as Consumer<Message>).accept(main);null}
                else->RETURNS_DEFAULTS.answer(call)
            }}
        }
        main=mock(Message::class.java){call->when(call.method.name){
            "editMessageEmbeds"->{val a=call.arguments[0];edit(card=when(a){is Array<*>->a.first() as MessageEmbed;is Collection<*>->a.first() as MessageEmbed;else->a as MessageEmbed})}
            "editMessage"->edit(text=call.arguments[0].toString())
            else->RETURNS_DEFAULTS.answer(call)
        }}
        val channel=mock(TextChannel::class.java){call->if(call.method.name=="sendMessage"){
            val text=call.arguments[0].toString()
            mock(MessageCreateAction::class.java){send->when(send.method.name){
                "setAllowedMentions"->send.mock
                "queue"->{texts.add(text);@Suppress("UNCHECKED_CAST") (send.arguments[0] as Consumer<Message>).accept(main);null}
                else->RETURNS_DEFAULTS.answer(send)
            }}
        }else RETURNS_DEFAULTS.answer(call)}
        val display=DiscordProjectAgentDisplay(channel,main,"request","14b","run-id",5,rendered={_,text->recorded.add(text)})
        fun await(check:()->Boolean){val until=System.nanoTime()+3_000_000_000;while(!check() && System.nanoTime()<until)Thread.sleep(2);assertTrue(check())}
        display.emit(ProjectAgentEvent("thinking",1));await {cards.isNotEmpty()};assertTrue(cards.last().second)
        display.emit(ProjectAgentEvent("usage",1,usage=QwenUsage(tokensPerSecond=13.5,memory=LlmMemory(totalBytes=24L*1073741824,usedBytes=18L*1073741824))))
        await {cards.last().first.footer?.text?.contains("13.5 tok/s")==true};assertContains(cards.last().first.footer!!.text!!,"18.0/24.0 GiB")
        display.emit(ProjectAgentEvent("stream",1,output="actual thought"));await {texts.contains("‘actual thought’")}
        assertTrue(recorded.isEmpty())
        display.emit(ProjectAgentEvent("stream",1,text="**answer**"));await {texts.last()=="**answer**"}
        display.emit(ProjectAgentEvent("done",1,"**final**"));await {texts.last()=="**final**" && !cards.last().second && recorded.lastOrNull()=="**final**"}
        display.emit(ProjectAgentEvent("stream",1,output="late"));Thread.sleep(30);assertEquals("**final**",texts.last())
        assertTrue(cards.all {it.first.description==null})
    }
    @Test fun `long markdown fences and unicode fit Discord message limits`() {
        val chunks=DiscordAgentChunks.split("```kotlin\n"+"val 가 = \"😀\"\n".repeat(600)+"```\n끝")
        assertTrue(chunks.size>2);assertTrue(chunks.all {it.length<=2000});assertTrue(chunks.all {Regex("(?m)^```").findAll(it).count()%2==0})
        assertTrue(chunks.joinToString("").contains("끝"))
    }
}
