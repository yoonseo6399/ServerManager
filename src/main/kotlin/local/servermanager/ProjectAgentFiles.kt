package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.utils.FileUpload
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

interface ProjectAgentFilePort {
    fun execute(name:String,args:JsonNode,path:Path,active:()->Unit):String
}

/** A fixed private channel, never model-supplied URLs/channel IDs. Downloaded data is never executed. */
internal class DiscordProjectAgentFiles(private val channel:()->TextChannel,private val authorize:()->Unit):ProjectAgentFilePort {
    override fun execute(name:String,args:JsonNode,path:Path,active:()->Unit):String {
        active();authorize();val target=channel()
        if(name=="list_attachments")return target.history.retrievePast(25).complete().flatMap {message->message.attachments.map {"message=${message.id} attachment=${it.id} name=${it.fileName} bytes=${it.size}"}}.joinToString("\n").take(12000).ifBlank {"최근 25개 메시지에 첨부가 없습니다."}
        if(name=="upload_file"){
            require(path.isRegularFile() && !Files.isSymbolicLink(path));require(path.fileSize()<=minOf(10_485_760,target.guild.maxFileSize,target.jda.selfUser.allowedFileSize)) {"이 도구의 업로드 한도는 10MiB입니다."}
            require(!Regex("(?i)(^\\.env|credential|secret|private.*key|id_rsa|id_ed25519|token)").containsMatchIn(path.name)) {"비밀 정보로 보이는 파일은 업로드하지 않습니다."}
            val bytes=path.readBytes()
            require(!Regex("-----BEGIN .*PRIVATE KEY-----|(?i)(api[_-]?key|password|access[_-]?token)\\s*[:=]\\s*[\\\"']?[^\\s\\\"']{8,}").containsMatchIn(bytes.toString(Charsets.UTF_8))) {"비밀 정보로 보이는 내용이 있어 업로드를 차단했습니다."}
            active();authorize()
            val sent=FileUpload.fromData(bytes,path.name).use {upload->target.sendFiles(upload).setAllowedMentions(emptyList()).complete()}
            return "현재 프로젝트 채널에 파일 업로드 완료: ${path.name} · message=${sent.id}"
        }
        val messageId=args.path("message_id").asText();val attachmentId=args.path("attachment_id").asText()
        require(messageId.matches(Regex("[0-9]{1,20}")) && attachmentId.matches(Regex("[0-9]{1,20}")))
        // A fresh fetch renews expired CDN signatures and verifies the message still exists in this channel.
        val attachment=target.retrieveMessageById(messageId).complete().attachments.firstOrNull {it.id==attachmentId} ?: error("이 채널 메시지의 첨부가 아닙니다.")
        require(attachment.size<=1_048_576) {"텍스트 첨부 읽기는 1MiB 이하만 지원합니다."}
        require(attachment.fileExtension?.lowercase() !in setOf("png","jpg","jpeg","gif","webp","pdf","zip","jar","exe","dmg","pkg")) {"이번 버전은 텍스트·코드 파일만 읽습니다."}
        val download=attachment.proxy.download()
        val bytes=try {download.get(30,TimeUnit.SECONDS).use {it.readNBytes(1_048_577)}}catch(e:Exception){download.cancel(true);throw e}
        require(bytes.size<=1_048_576 && bytes.none {it==0.toByte()}) {"텍스트 첨부가 아니거나 크기 제한을 초과했습니다."}
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        active();authorize()
        if(name=="save_attachment"){
            require(!Files.exists(path,LinkOption.NOFOLLOW_LINKS) && path.parent.isDirectory()) {"새 파일 경로를 지정하세요. 기존 파일은 덮어쓰지 않습니다."}
            Files.write(path,bytes,StandardOpenOption.CREATE_NEW);return "첨부 저장 완료: $path (자동 실행하지 않음)"
        }
        val start=args.path("start_line").asInt(1);require(start>=1)
        return text.lineSequence().drop(start-1).take(args.path("lines").asInt(120).coerceIn(1,400)).mapIndexed {index,line->"${start+index}: ${line.take(2000)}"}.joinToString("\n").take(24000)
    }
}
