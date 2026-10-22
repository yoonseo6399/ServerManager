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
    private class Screen {
        data class Row(val id:Int,var card:MessageEmbed?=null,var text:String?=null,var buttons:Boolean=false,var deleted:Boolean=false)
        val rows=CopyOnWriteArrayList<Row>();val calls=CopyOnWriteArrayList<Pair<Int,String>>()
        private fun embed(a:Any?):MessageEmbed=when(a){is Array<*>->a.first() as MessageEmbed;is Collection<*>->a.first() as MessageEmbed;else->a as MessageEmbed}
        fun message(row:Row):Message {
            lateinit var msg:Message
            fun edit(card:MessageEmbed?=null,text:String?=null):MessageEditAction {
                var buttons:Boolean?=null
                fun apply(){if(card!=null){row.card=card;calls.add(row.id to card.title.orEmpty())};if(text!=null)row.text=text;buttons?.let {row.buttons=it}}
                return mock(MessageEditAction::class.java){c->when(c.method.name){
                    "setComponents"->{buttons=when(val a=c.arguments[0]){is Collection<*>->a.isNotEmpty();is Array<*>->a.isNotEmpty();else->a!=null};c.mock}
                    "queue"->{apply();@Suppress("UNCHECKED_CAST") (c.arguments[0] as Consumer<Message>).accept(msg);null}
                    "complete"->{apply();msg}
                    else->RETURNS_SELF.answer(c)
                }}
            }
            msg=mock(Message::class.java){c->when(c.method.name){
                "editMessageEmbeds"->edit(card=embed(c.arguments[0]))
                "editMessage"->edit(text=c.arguments[0].toString())
                "editMessageComponents"->edit().also {it.setComponents(emptyList())}
                "delete"->mock(net.dv8tion.jda.api.requests.restaction.AuditableRestAction::class.java){q->if(q.method.name=="queue"){row.deleted=true;null}else RETURNS_SELF.answer(q)}
                else->RETURNS_DEEP_STUBS.answer(c)
            }}
            return msg
        }
        val top=Row(0).also {rows.add(it)};val main=message(top)
        val channel=mock(TextChannel::class.java){c->when(c.method.name){
            "getId"->"status-fixture"
            "sendMessageEmbeds","sendMessage"->{
                val row=Row(rows.size,if(c.method.name=="sendMessageEmbeds")embed(c.arguments[0])else null,if(c.method.name=="sendMessage")c.arguments[0].toString()else null)
                val msg=message(row)
                mock(MessageCreateAction::class.java){q->when(q.method.name){
                    "setComponents"->{row.buttons=when(val a=q.arguments[0]){is Collection<*>->a.isNotEmpty();is Array<*>->a.isNotEmpty();else->a!=null};q.mock}
                    "complete"->{rows.add(row);msg}
                    "queue"->{rows.add(row);@Suppress("UNCHECKED_CAST") (q.arguments[0] as Consumer<Message>).accept(msg);null}
                    else->RETURNS_SELF.answer(q)
                }}
            }
            else->RETURNS_DEEP_STUBS.answer(c)
        }}
        fun waitFor(check:()->Boolean){val until=System.nanoTime()+3_000_000_000;while(!check() && System.nanoTime()<until)Thread.sleep(2);assertTrue(check())}
    }
    @Test fun `thinking and markdown answers stay plain and live status follows new answer messages`() {
        val s=Screen();val display=DiscordProjectAgentDisplay(s.channel,s.main,"request","14b","run-id",5)
        display.emit(ProjectAgentEvent("thinking",1));s.waitFor {s.rows.size==2};assertTrue(s.rows.last().buttons)
        display.emit(ProjectAgentEvent("usage",1,usage=QwenUsage(tokensPerSecond=13.5,memory=LlmMemory(totalBytes=24L*1073741824,usedBytes=18L*1073741824))))
        s.waitFor {s.top.card?.footer?.text?.contains("13.5 tok/s")==true};assertContains(s.top.card!!.footer!!.text!!,"18.0/24.0 GiB")
        display.emit(ProjectAgentEvent("stream",1,output="actual thought"));s.waitFor {s.rows.any {it.text=="‘actual thought’"} && s.rows.last().card?.title?.startsWith("상태창")==true}
        assertTrue(s.rows[1].deleted,"Old live placeholder should move below the new plain message")
        display.emit(ProjectAgentEvent("stream",1,text="**answer**"));s.waitFor {s.rows.any {it.text=="**answer**"}}
        display.emit(ProjectAgentEvent("done",1,"**final**"));s.waitFor {s.rows.any {it.text=="**final**"} && !s.rows.last().buttons}
        display.emit(ProjectAgentEvent("stream",1,output="late"));Thread.sleep(30);assertTrue(s.rows.none {it.text?.contains("late")==true})
        assertEquals("request",s.top.card!!.description);assertFalse(s.top.buttons)
    }
    @Test fun `live status becomes the same tool card and next actor turn gets a new bottom status`() {
        val s=Screen();val display=DiscordProjectAgentDisplay(s.channel,s.main,"request","14b","run",5)
        val plan=ProjectAgentPlan(ProjectAgentTool("read",com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().createObjectNode().put("path","a.txt")),"/fixture","read a.txt",true)
        val risk=ProjectAgentRisk("ALLOW","LOW","요청한 조회","INSIDE")
        display.emit(ProjectAgentEvent("thinking",1));display.emit(ProjectAgentEvent("usage",1,usage=QwenUsage(tokensPerSecond=13.5)));val slot=s.rows.last();val count=s.rows.size
        display.emit(ProjectAgentEvent("approval",1,plan=plan,risk=risk,token="t",callId="c"));assertEquals(count,s.rows.size);assertContains(slot.card!!.title!!,"승인 대기")
        display.emit(ProjectAgentEvent("tool_start",1,plan=plan,risk=risk,callId="c"));assertEquals(count,s.rows.size);assertContains(slot.card!!.title!!,"도구 실행 중")
        display.emit(ProjectAgentEvent("tool_end",1,"종료 코드 0",plan,risk,callId="c"));s.waitFor {slot.card!!.title!!.contains("초")}
        val completed=slot.card
        display.emit(ProjectAgentEvent("thinking",2));assertEquals(count+1,s.rows.size);assertTrue(s.rows.last().card!!.title!!.startsWith("상태창 · 턴 2"))
        s.waitFor {s.top.card?.footer?.text?.contains("13.5 tok/s")==true};Thread.sleep(40);assertEquals(completed,slot.card,"Delayed status updates must not overwrite the tool card")
        display.emit(ProjectAgentEvent("done",2,"완료"));s.waitFor {!s.rows.last().buttons}
    }
    @Test fun `question card keeps selected answer and requester including full custom text`() {
        val s=Screen();val display=DiscordProjectAgentDisplay(s.channel,s.main,"request","14b","run",5)
        val plan=ProjectAgentPlan(ProjectAgentTool("ask_user",com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree("""{"question":"어떤 브랜치?","choices":["beta20","beta21"]}""")),"/fixture","question",true)
        display.emit(ProjectAgentEvent("question",1,"어떤 브랜치?",plan,token="q"))
        val row=s.rows.single {it.card?.title=="루오의 질문"}
        display.emit(ProjectAgentEvent("question_answered",1,"beta21",plan,token="q",requesterId="123"))
        s.waitFor {row.card?.title?.contains("답변 완료")==true};assertEquals("beta21",row.card!!.fields.single().value);assertContains(row.card!!.footer!!.text!!,"123");assertFalse(row.buttons)
        val custom="x".repeat(2000);val card=DiscordQuestionAnswer.embed("question",custom,"123")
        assertEquals(custom,card.fields.joinToString(""){it.value.orEmpty()});assertTrue(card.fields.all {it.value!!.length<=1024})
    }
    @Test fun `paging edits the original work card without creating a new embed`() {
        val edits=CopyOnWriteArrayList<MessageEmbed>();lateinit var original:Message
        original=mock(Message::class.java){call->if(call.method.name=="editMessageEmbeds") {
            val a=call.arguments[0];val card=when(a){is Array<*>->a.first() as MessageEmbed;is Collection<*>->a.first() as MessageEmbed;else->a as MessageEmbed}
            mock(MessageEditAction::class.java){send->when(send.method.name) {
                "queue"->{edits.add(card);@Suppress("UNCHECKED_CAST") (send.arguments[0] as Consumer<Message>).accept(original);null}
                else->RETURNS_SELF.answer(send)
            }}
        }else RETURNS_DEFAULTS.answer(call)}
        val channel=mock(TextChannel::class.java);`when`(channel.id).thenReturn("paging-test")
        val main=mock(Message::class.java,RETURNS_DEEP_STUBS)
        val id="out-0123456789abcdef"
        DiscordResultCards.register(channel.id,id,"source",original,net.dv8tion.jda.api.EmbedBuilder().setTitle("원래 Bash 작업").build())
        val display=DiscordProjectAgentDisplay(channel,main,"request","14b","run")
        val plan=ProjectAgentPlan(ProjectAgentTool("read_result",com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().createObjectNode().put("id",id)),"/p","원문 이어읽기",true)
        display.emit(ProjectAgentEvent("tool_start",2,plan=plan,readProgress=ProjectAgentReadProgress(id,1,1,200)))
        display.emit(ProjectAgentEvent("result_read",2,plan=plan,readProgress=ProjectAgentReadProgress(id,1,40,200)))
        display.emit(ProjectAgentEvent("tool_start",3,plan=plan,readProgress=ProjectAgentReadProgress(id,41,41,200)))
        display.emit(ProjectAgentEvent("result_read",3,plan=plan,readProgress=ProjectAgentReadProgress(id,41,80,200)))
        display.emit(ProjectAgentEvent("tool_end",3,plan=plan))
        val until=System.nanoTime()+3_000_000_000;while(edits.lastOrNull()?.fields?.lastOrNull()?.value?.contains("80 / 전체 200")!=true && System.nanoTime()<until)Thread.sleep(10)
        assertEquals("원래 Bash 작업",edits.last().title);assertContains(edits.last().fields.last().name!!,"모델이 결과를 읽는 중");assertContains(edits.last().fields.last().value!!,"80 / 전체 200줄")
        verify(channel,never()).sendMessageEmbeds(any<MessageEmbed>())
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
        `when`(channel.id).thenReturn("display-test")
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
        `when`(channel.id).thenReturn("display-test")
        val display=DiscordProjectAgentDisplay(channel,msg,"request","14b","run",filePreview={"original-token"})
        val plan=ProjectAgentPlan(ProjectAgentTool("write",com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().createObjectNode()),"/p","파일 쓰기",false)
        display.emit(ProjectAgentEvent("tool_end",1,"완료",plan,changes=listOf(ProjectAgentFileChange("/p/a.txt","a\n","b\n","--- a.txt\n+++ a.txt\n-a\n+b\n"))))
        val until=System.nanoTime()+3_000_000_000;while(texts.isEmpty() && System.nanoTime()<until)Thread.sleep(2)
        assertTrue(buttons.contains("qa:file:original-token"));assertTrue(texts.any {it.startsWith("```diff\n") && it.contains("+b")})
    }

}
