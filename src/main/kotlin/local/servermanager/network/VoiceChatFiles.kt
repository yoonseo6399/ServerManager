package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.charset.CodingErrorAction
import java.util.Properties
import kotlin.io.path.*

/** Only three managed keys. Preserve other settings/comments; restore original values when disabled. */
class VoiceChatFiles(private val backups:Path) {
    private val mapper=jacksonObjectMapper()
    private val keys=setOf("port","bind_address","voice_host")
    private val names=listOf("config/voicechat/voicechat-server.properties","plugins/voicechat/voicechat-server.properties")
    private fun read(file:Path):String {
        require(Files.isRegularFile(file,NOFOLLOW_LINKS) && file.fileSize()<=256*1024) {"음성 설정 파일 형식·크기를 확인하세요"}
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString()
    }
    private fun values(text:String)=Properties().also {it.load(text.reader())}
    private fun safe(root:Path,relative:String):Path {
        require(relative in names)
        val file=root.resolve(relative)
        var parent=file.parent
        while(!parent.exists(NOFOLLOW_LINKS))parent=parent.parent
        require(parent.toRealPath().startsWith(root.toRealPath()) && !Files.isSymbolicLink(file)) {"음성 설정의 외부 링크 경로는 수정할 수 없습니다"}
        return file
    }
    private fun edit(text:String,updates:Map<String,String?>):String {
        val ending=if(text.contains("\r\n"))"\r\n" else "\n"
        val pattern=Regex("^\\s*(port|bind_address|voice_host)\\s*(?:[:=]|\\s)")
        val seen=mutableSetOf<String>();val rows=mutableListOf<String>()
        text.split(Regex("\\r?\\n")).let {if(it.lastOrNull()=="")it.dropLast(1) else it}.forEach {line->
            val key=pattern.find(line)?.groupValues?.get(1)
            if(key==null || key !in updates)rows.add(line)
            else if(seen.add(key))updates[key]?.let {rows.add("$key=$it")}
        }
        updates.filterKeys {it !in seen}.forEach {(key,value)->if(value!=null)rows.add("$key=$value")}
        val result=rows.joinToString(ending)+ending
        val parsed=values(result)
        updates.forEach {(key,value)->require(parsed.getProperty(key)==value) {"음성 설정의 중복·여러 줄 키를 확인하세요: $key"}}
        return result
    }
    @Synchronized fun prepare(instance:String,root:Path,target:VoiceChatTarget?):VoiceChatStatus {
        require(instance.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val stateDir=backups.resolve(instance);val state=stateDir.resolve("managed.json")
        require(!Files.isSymbolicLink(backups) && !Files.isSymbolicLink(stateDir) && !Files.isSymbolicLink(state))
        val previous=if(state.exists())mapper.readTree(read(state)) else null
        if(target==null && previous==null)return VoiceChatStatus("OFF")
        val relative=previous?.path("path")?.asText() ?: names.firstOrNull {root.resolve(it).exists(NOFOLLOW_LINKS)}
            ?: if(root.resolve("plugins").isDirectory())names[1] else names[0]
        val file=safe(root,relative)
        val text=if(file.exists(NOFOLLOW_LINKS))read(file) else ""
        val props=values(text)
        if(target==null){
            val applied=previous!!.path("applied")
            val restore=keys.filter {props.getProperty(it)==applied.path(it).asText(null)}.associateWith {previous.path("original").path(it).takeUnless {n->n.isNull||n.isMissingNode}?.asText()}
            if(restore.isNotEmpty())atomicText(file,edit(text,restore))
            state.deleteIfExists()
            return VoiceChatStatus("OFF",message="기존 음성 설정을 복원했습니다. 사용자가 변경한 값은 보존했습니다")
        }
        require(target.instanceId==instance && target.localPort in 1024..65535 && target.publicAddress.matches(Regex("[A-Za-z0-9.-]{1,253}:[0-9]{1,5}"))) {"음성 중계 주소·포트를 확인하세요"}
        val query=root.resolve("server.properties")
        if(query.exists()) {
            val game=values(read(query));require(target.localPort!=game.getProperty("server-port")?.toIntOrNull() && (game.getProperty("enable-query")!="true" || target.localPort!=game.getProperty("query.port")?.toIntOrNull())) {"음성 포트가 게임·쿼리 포트와 충돌합니다"}
        }
        val applied=mapOf("port" to target.localPort.toString(),"bind_address" to "127.0.0.1","voice_host" to target.publicAddress)
        val next=edit(text,applied)
        stateDir.createDirectories();file.parent.createDirectories()
        require(!Files.isSymbolicLink(stateDir.resolve("original.properties")))
        if(previous==null && file.exists() && !stateDir.resolve("original.properties").exists())stateDir.resolve("original.properties").writeText(text)
        val original=previous?.path("original") ?: mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(keys.associateWith {props.getProperty(it)})
        atomicText(state,mapper.writeValueAsString(mapOf("path" to relative,"original" to original,"applied" to applied)))
        atomicText(file,next)
        return VoiceChatStatus("CONFIGURED",message="음성 설정 준비: ${target.publicAddress} → 이 실행기 UDP ${target.localPort}. Simple Voice Chat 모드는 별도로 설치되어 있어야 합니다")
    }
}
