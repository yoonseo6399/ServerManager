package local.servermanager

import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.utils.FileUpload
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** All files, diffs and approvals stay in private interaction responses. */
class DiscordAssistant(private val harness:AssistantHarness,private val identity:AssistantIdentity,
    private val config:()->DiscordConfig,private val current:()->JDA?,private val google:AssistantGoogle?=null):AutoCloseable {
    private val origins=ConcurrentHashMap<String,String>()
    private val polling=Executors.newSingleThreadScheduledExecutor()
    private val background=Executors.newSingleThreadExecutor()
    fun register(jda:JDA,guildId:String) {
        val guild=jda.getGuildById(guildId) ?: return
        guild.upsertCommand(Commands.slash("assistant-link","관리 웹 소유자 계정 연결 (비공개)").addOption(OptionType.STRING,"code","관리 웹 AI 비서에서 발급한 5분 연결 코드",true)).queue({}, {})
        guild.upsertCommand(Commands.slash("assistant-google","내 Google 조회 결과 일부를 Qwen으로 비공개 요약")
            .addOption(OptionType.STRING,"source","gmail 또는 calendar",true).addOption(OptionType.BOOLEAN,"consent","조회 일부를 Qwen 컴퓨터로 보내는 데 동의",true)
            .addOption(OptionType.STRING,"query","Gmail 검색 조건",false).addOption(OptionType.STRING,"from","일정 시작 RFC3339",false).addOption(OptionType.STRING,"until","일정 종료 RFC3339",false)).queue({}, {})
    }
    private fun authorized(jda:JDA,guild:String,roles:Set<String>):Boolean {
        val c=config();return jda===current() && c.enabled && c.superAdmins.any {it.guildId==guild && it.roleId in roles}
    }
    fun handle(event:SlashCommandInteractionEvent) {
        val guild=event.guild ?: return
        event.deferReply(true).queue({hook->guild.retrieveMemberById(event.user.id).useCache(false).queue({member->
            try {
                check(authorized(event.jda,guild.id,member.roles.map {it.id}.toSet())) {"현재 Discord 최고 관리자 역할이 필요합니다."}
                if(event.name=="assistant-link") {
                    identity.link(event.getOption("code")!!.asString,event.user.id,guild.id)
                    hook.editOriginal("관리 웹 소유자 계정을 연결했습니다. 연결은 권한 철회·앱 재시작 때 만료됩니다.").setAllowedMentions(emptyList()).queue({}, {})
                } else if(event.name=="assistant-google") {
                    val actor=identity.resolveDiscord(event.user.id,guild.id) ?: error("소유자 계정 연결이 필요합니다.")
                    val query=AssistantGoogleRequest(event.getOption("source")!!.asString,event.getOption("query")?.asString.orEmpty(),event.getOption("from")?.asString.orEmpty(),event.getOption("until")?.asString.orEmpty(),event.getOption("consent")!!.asBoolean)
                    check(google!=null) {"Google 연결 기능을 사용할 수 없습니다."}
                    background.submit {try {
                        check(identity.resolveDiscord(event.user.id,guild.id)==actor) {"연결이 철회되었습니다."}
                        val result=google.summarize(actor,query)
                        check(identity.resolveDiscord(event.user.id,guild.id)==actor) {"연결이 철회되었습니다."}
                        hook.editOriginal(result.notice.take(1500)).setAllowedMentions(emptyList()).setFiles(FileUpload.fromData(result.answer.toByteArray(Charsets.UTF_8),"qwen-private-summary.txt")).queue({}, {})
                    } catch(e:Exception) {failure(hook,e)}}
                } else {
                    val actor=identity.resolveDiscord(event.user.id,guild.id) ?: error("관리 웹 AI 비서에서 연결 코드를 발급받아 /assistant-link로 먼저 연결하세요.")
                    val task=harness.create(actor,event.getOption("task")!!.asString,event.getOption("workspace")!!.asString,event.getOption("files")!!.asString.split(',').map {it.trim()})
                    origins[task.id]="${guild.id}:${event.user.id}";render(hook,task)
                }
            } catch(e:Exception) {failure(hook,e)}
        },{hook.editOriginal("현재 Discord 권한을 확인하지 못했습니다.").queue({}, {})})},{})
    }
    fun button(event:ButtonInteractionEvent) {
        val guild=event.guild ?: return
        val parts=event.componentId.split(':');val action=parts.getOrNull(1);val id=parts.getOrNull(2) ?: return
        event.deferEdit().queue({hook->guild.retrieveMemberById(event.user.id).useCache(false).queue({member->
            try {
                check(authorized(event.jda,guild.id,member.roles.map {it.id}.toSet())) {"현재 Discord 최고 관리자 역할이 필요합니다."}
                val actor=identity.resolveDiscord(event.user.id,guild.id) ?: error("소유자 계정 연결이 만료되었습니다.")
                check(origins[id]=="${guild.id}:${event.user.id}") {"본인이 요청한 작업만 승인할 수 있습니다."}
                val view=when(action) {
                    "a"->{val before=harness.engine.view(actor,id);harness.engine.approve(actor,id,parts.getOrNull(3).orEmpty(),before.state)}
                    "c"->harness.engine.cancel(actor,id)
                    "r"->harness.engine.view(actor,id)
                    else->error("알 수 없는 승인 요청입니다.")
                }
                render(hook,view)
                if(view.state in setOf("PREPARING","APPLYING"))watch(hook,actor,id,0)
            } catch(e:Exception) {failure(hook,e)}
        },{hook.editOriginal("현재 Discord 권한을 확인하지 못했습니다.").queue({}, {})})},{})
    }
    private fun watch(hook:InteractionHook,actor:String,id:String,count:Int) {
        if(count>=300)return
        polling.schedule({runCatching {val view=harness.engine.view(actor,id);render(hook,view);if(view.state in setOf("PREPARING","APPLYING"))watch(hook,actor,id,count+1)}},2,TimeUnit.SECONDS)
    }
    private fun failure(hook:InteractionHook,e:Exception) {hook.editOriginal((e.message ?: "비서 요청 실패").take(1500)).setEmbeds(emptyList()).setComponents(emptyList()).setAllowedMentions(emptyList()).queue({}, {})}
    private fun render(hook:InteractionHook,t:AssistantTaskView) {
        val embed=EmbedBuilder().setTitle("Qwen 비서 · ${t.state}").setDescription(t.purpose.take(1500))
            .addField("컴퓨터·폴더", "${t.computer}\n${t.workspace}".take(1000),false)
            .addField("모델",t.model,true).addField("파일",t.files.joinToString("\n") {it.path}.take(1000),false)
            .addField("외부 전송",t.cloudDisclosure.take(1000),false)
            .addField("검토·상태",(t.review?.let {"${it.risk}: ${it.reason}\n"}.orEmpty()+t.message).take(1000),false)
            .setFooter("작업 ${t.id} · 승인 만료 ${t.expiresAt}")
        if(t.command!=null)embed.addField("실행 명령",("프로그램: ${t.command.program}\n인자(JSON): ${com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(t.command.args)}\n작업 폴더: ${t.stageDirectory}").take(1000),false)
            .addField("호스트 권한", "OS 샌드박스가 아닙니다. 명령은 이 사용자 권한으로 파일·네트워크에 접근할 수 있습니다. 환경 변수를 전달하지 않아도 디스크의 비밀 값에 접근할 수 있습니다.",false)
        val buttons=mutableListOf(Button.secondary("as:r:${t.id}","상태 새로고침"))
        if(t.state in setOf("CONSENT","REVIEWED"))buttons.add(Button.primary("as:a:${t.id}:${t.approval}",if(t.state=="CONSENT")"내용 전송·초안 작성 승인" else "변경·명령 최종 승인"))
        if(t.state !in setOf("APPLIED","CANCELLED","FAILED"))buttons.add(Button.danger("as:c:${t.id}","거절·취소"))
        val text=buildString {
            append("요청: ${t.purpose}\n${t.cloudDisclosure}\n상태: ${t.state}\n")
            t.files.forEach {file->append("\n원본 ${file.path} SHA256 ${file.digest}\n${file.content}\n")}
            t.changes.forEach {change->append("\n변경 후 ${change.path}\n${change.after}\n")}
            t.command?.let {append("\n정확한 명령(JSON) ${com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(it)}\n실행 폴더 ${t.stageDirectory}\n")}
            append("\n${t.logs.joinToString("\n")}\n복구 파일: ${t.backups.joinToString("\n")}")
        }
        hook.editOriginalEmbeds(embed.build()).setComponents(ActionRow.of(buttons)).setAllowedMentions(emptyList())
            .setFiles(FileUpload.fromData(text.toByteArray(Charsets.UTF_8),"assistant-review.txt")).queue({}, {})
    }
    override fun close() {polling.shutdownNow();background.shutdownNow()}
}
