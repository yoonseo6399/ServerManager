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
        val cards=CopyOnWriteArrayList<Pair<MessageEmbed,Boolean>>();val texts=CopyOnWriteArrayList<String>()
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
        val display=DiscordProjectAgentDisplay(channel,main,"request","14b","run-id",5)
        fun await(check:()->Boolean){val until=System.nanoTime()+3_000_000_000;while(!check() && System.nanoTime()<until)Thread.sleep(2);assertTrue(check())}
        display.emit(ProjectAgentEvent("thinking",1));await {cards.isNotEmpty()};assertTrue(cards.last().second)
        display.emit(ProjectAgentEvent("usage",1,usage=QwenUsage(tokensPerSecond=13.5,memory=LlmMemory(totalBytes=24L*1073741824,usedBytes=18L*1073741824))))
        await {cards.last().first.footer?.text?.contains("13.5 tok/s")==true};assertContains(cards.last().first.footer!!.text!!,"18.0/24.0 GiB")
        display.emit(ProjectAgentEvent("stream",1,output="actual thought"));await {texts.contains("‘actual thought’")}
        display.emit(ProjectAgentEvent("stream",1,text="**answer**"));await {texts.last()=="**answer**"}
        display.emit(ProjectAgentEvent("done",1,"**final**"));await {texts.last()=="**final**" && !cards.last().second}
        display.emit(ProjectAgentEvent("stream",1,output="late"));Thread.sleep(30);assertEquals("**final**",texts.last())
        assertTrue(cards.all {it.first.description==null})
    }
    @Test fun `long markdown fences and unicode fit Discord message limits`() {
        val chunks=DiscordAgentChunks.split("```kotlin\n"+"val 가 = \"😀\"\n".repeat(600)+"```\n끝")
        assertTrue(chunks.size>2);assertTrue(chunks.all {it.length<=2000});assertTrue(chunks.all {Regex("(?m)^```").findAll(it).count()%2==0})
        assertTrue(chunks.joinToString("").contains("끝"))
    }
    @Test fun `completed silent bash keeps command and review and duplicate turn numbers cannot overwrite another call`() {
        val cards=mutableMapOf<Int,MessageEmbed>();var sent=0
        fun embed(value:Any?):MessageEmbed=when(value){is Array<*>->value.first() as MessageEmbed;is Collection<*>->value.first() as MessageEmbed;else->value as MessageEmbed}
        fun message(id:Int):Message {
            val msg=mock(Message::class.java,RETURNS_DEEP_STUBS)
            doAnswer {call->cards[id]=embed(call.arguments[0]);val action=mock(MessageEditAction::class.java,RETURNS_SELF);`when`(action.complete()).thenReturn(msg);action}.`when`(msg).editMessageEmbeds(any<MessageEmbed>())
            return msg
        }
        val channel=mock(TextChannel::class.java){call->if(call.method.name=="sendMessageEmbeds") {
            val id=++sent;cards[id]=embed(call.arguments[0]);val msg=message(id)
            mock(MessageCreateAction::class.java){action->if(action.method.name=="complete")msg else RETURNS_SELF.answer(action)}
        }else RETURNS_DEFAULTS.answer(call)}
        val display=DiscordProjectAgentDisplay(channel,message(0),"request","14b","run",5)
        val risk=ProjectAgentRisk("ALLOW","LOW","Qwen3.8 27B · 선택한 임시 파일만 제거","INSIDE")
        fun plan(file:String)=ProjectAgentPlan(ProjectAgentTool("bash",com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree("""{"command":"rm -- $file"}""")),"/fixture","rm -- $file",false,scope="INSIDE")
        val a=plan("a.txt");val b=plan("b.txt")
        display.emit(ProjectAgentEvent("tool_start",1,plan=a,risk=risk,callId="call-a"));display.emit(ProjectAgentEvent("tool_start",1,plan=b,risk=risk,callId="call-b"))
        display.emit(ProjectAgentEvent("tool_end",1,"종료 코드 0 · 출력 없음",a,risk,callId="call-a",exitCode=0));display.emit(ProjectAgentEvent("tool_end",1,"종료 코드 0 · 출력 없음",b,risk,callId="call-b",exitCode=0))
        assertEquals(2,sent);assertContains(cards[1]!!.description!!,"rm -- a.txt");assertContains(cards[2]!!.description!!,"rm -- b.txt");assertContains(cards[1]!!.description!!,"출력 없음");assertTrue(cards[1]!!.fields.any {it.value.orEmpty().contains("Qwen3.8 27B")})
    }

    @Test fun `file changes render plain diff and a separate original detail button`() {
        val texts=CopyOnWriteArrayList<String>();val buttons=CopyOnWriteArrayList<String>()
        val msg=mock(Message::class.java,RETURNS_DEEP_STUBS)
        val channel=mock(TextChannel::class.java){call->when(call.method.name) {
            "sendMessageEmbeds"->mock(MessageCreateAction::class.java){action->when(action.method.name) {
                "setComponents"->{(action.arguments[0] as Collection<*>).filterIsInstance<net.dv8tion.jda.api.components.actionrow.ActionRow>().forEach {row->row.components.filterIsInstance<net.dv8tion.jda.api.components.buttons.Button>().mapNotNullTo(buttons){it.customId}};action.mock}
                "complete"->msg
                else->RETURNS_SELF.answer(action)
            }}
            "sendMessage"->{val text=call.arguments[0].toString();mock(MessageCreateAction::class.java){action->when(action.method.name) {
                "queue"->{texts.add(text);@Suppress("UNCHECKED_CAST") (action.arguments[0] as Consumer<Message>).accept(msg);null}
                else->RETURNS_SELF.answer(action)
            }}}
            else->RETURNS_DEFAULTS.answer(call)
        }}
        val display=DiscordProjectAgentDisplay(channel,msg,"request","14b","run",filePreview={"original-token"})
        val plan=ProjectAgentPlan(ProjectAgentTool("write",com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().createObjectNode()),"/p","파일 쓰기",false)
        display.emit(ProjectAgentEvent("tool_end",1,"완료",plan,changes=listOf(ProjectAgentFileChange("/p/a.txt","a\n","b\n","--- a.txt\n+++ a.txt\n-a\n+b\n"))))
        val until=System.nanoTime()+3_000_000_000;while(texts.isEmpty() && System.nanoTime()<until)Thread.sleep(2)
        assertTrue(buttons.contains("qa:file:original-token"));assertTrue(texts.any {it.startsWith("```diff\n") && it.contains("+b")})
    }

}
