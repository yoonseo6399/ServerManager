package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.*

data class DiscordGrant(val guildId:String, val roleId:String, val instanceId:String, val canStart:Boolean = false, val canStop:Boolean = false, val canRestart:Boolean = false)
data class DiscordAlertRoute(val guildId:String, val channelId:String, val instanceId:String, val errors:Boolean = true, val stops:Boolean = true, val panel:Boolean = false, val starts:Boolean = true) {
    fun accepts(signal:ServerSignal)=if(signal.kind=="stop" && signal.exit?.requested==false)errors else accepts(signal.kind)
    fun accepts(kind:String)=when(kind) {"error"->errors;"stop"->stops;"start"->starts;else->false}
}
data class DiscordSuperAdmin(val guildId:String, val roleId:String)
data class DiscordConfig(val enabled:Boolean = false, val grants:List<DiscordGrant> = emptyList(), val alerts:List<DiscordAlertRoute> = emptyList(), val superAdmins:List<DiscordSuperAdmin> = emptyList())
// Secret-bearing objects deliberately have no generated toString and are never API responses.
class DiscordSave(val config:DiscordConfig, val revision:Long, val token:String? = null, val clearToken:Boolean = false)
private class DiscordDisk(val config:DiscordConfig = DiscordConfig(), val revision:Long = 0, val encryptedToken:String? = null)
data class DiscordSettingsView(val config:DiscordConfig, val revision:Long, val tokenConfigured:Boolean)

class DiscordSettingsStore(private val directory:Path) {
    private val mapper=jacksonObjectMapper()
    private val file=directory.resolve("settings.json")
    private val keyFile=directory.resolve("token.key")
    private var disk:DiscordDisk
    init {
        require(!Files.isSymbolicLink(directory)) { "Discord 설정 폴더에 심볼릭 링크를 사용할 수 없습니다" }
        directory.createDirectories(); Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        require(!Files.isSymbolicLink(file) && !Files.isSymbolicLink(keyFile)) { "Discord 설정에 심볼릭 링크를 사용할 수 없습니다" }
        disk=if(file.exists()) mapper.readValue(file.readText()) else DiscordDisk()
    }
    @Synchronized fun view()=DiscordSettingsView(disk.config,disk.revision,disk.encryptedToken!=null)
    @Synchronized fun token():String?=disk.encryptedToken?.let { encoded ->
        try {
            val bytes=Base64.getDecoder().decode(encoded)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(keyFile.readBytes(),"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
        } catch (_:Exception) { throw IllegalStateException("저장된 Discord 토큰을 읽을 수 없습니다. 관리자 페이지에서 다시 입력하세요.") }
    }
    @Synchronized fun save(request:DiscordSave, instanceExists:(String)->Boolean):DiscordSettingsView {
        check(request.revision==disk.revision) { "다른 화면에서 Discord 설정이 변경되었습니다. 새로고침 후 다시 저장하세요." }
        val config=request.config
        require(config.grants.size<=200 && config.alerts.size<=200 && config.superAdmins.size<=100) { "Discord 규칙 개수를 줄여주세요" }
        fun snowflake(id:String)=id.matches(Regex("[1-9][0-9]{16,19}")) && id.toULongOrNull()!=null
        config.grants.forEach {
            require(snowflake(it.guildId) && snowflake(it.roleId) && it.guildId!=it.roleId) { "Discord 서버·역할 ID를 확인하세요. @everyone 역할은 허용하지 않습니다." }
            require(instanceExists(it.instanceId)) { "존재하는 Minecraft 서버를 선택하세요" }
        }
        config.alerts.forEach {
            require(snowflake(it.guildId) && snowflake(it.channelId)) { "Discord 서버·채널 ID를 확인하세요" }
            require(instanceExists(it.instanceId)) { "존재하는 Minecraft 서버를 선택하세요" }
        }
        config.superAdmins.forEach {
            require(snowflake(it.guildId) && snowflake(it.roleId) && it.guildId!=it.roleId) { "최고관리자 서버·역할 ID를 확인하세요" }
        }
        require(config.grants.distinctBy { Triple(it.guildId,it.roleId,it.instanceId) }.size==config.grants.size) { "중복 역할 규칙입니다" }
        require(config.alerts.distinctBy { Triple(it.guildId,it.channelId,it.instanceId) }.size==config.alerts.size) { "중복 알림 규칙입니다" }
        require(config.superAdmins.distinct().size==config.superAdmins.size) { "중복 최고관리자 역할입니다" }
        config.alerts.filter { it.panel }.forEach { panel ->
            require(config.alerts.count { it.guildId==panel.guildId && it.channelId==panel.channelId }==1) { "제어 패널 채널은 Minecraft 서버마다 별도로 지정하세요" }
        }
        require(!(request.clearToken && !request.token.isNullOrBlank())) { "토큰 입력과 삭제를 동시에 요청할 수 없습니다" }
        var encrypted=if(request.clearToken) null else disk.encryptedToken
        request.token?.takeIf { it.isNotBlank() }?.trim()?.let { token ->
            require(token.length in 30..512 && token.matches(Regex("[A-Za-z0-9_.-]+"))) { "봇 토큰 형식을 확인하세요" }
            if(!keyFile.exists(NOFOLLOW_LINKS)) writePrivate(keyFile,ByteArray(32).also { SecureRandom().nextBytes(it) })
            val iv=ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(keyFile.readBytes(),"AES"),GCMParameterSpec(128,iv))
            encrypted=Base64.getEncoder().encodeToString(iv+cipher.doFinal(token.toByteArray()))
        }
        require(!config.enabled || encrypted!=null) { "봇 토큰을 먼저 입력하세요" }
        val next=DiscordDisk(config,disk.revision+1,encrypted)
        writePrivate(file,mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(next));disk=next
        return view()
    }
    private fun writePrivate(path:Path,bytes:ByteArray) {
        val temp=Files.createTempFile(directory,".discord-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try { temp.writeBytes(bytes); Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING) } finally { temp.deleteIfExists() }
    }
}

object DiscordAccess {
    fun allowed(config:DiscordConfig,guild:String?,roles:Set<String>,instance:String,start:Boolean=false):Boolean =
        allowed(config,guild,roles,instance,if(start) "start" else "status")
    fun allowed(config:DiscordConfig,guild:String?,roles:Set<String>,instance:String,action:String):Boolean {
        if(!config.enabled || guild==null || action !in setOf("status","start","stop","restart")) return false
        if(config.superAdmins.any { it.guildId==guild && it.roleId in roles }) return true
        return config.grants.any { it.guildId==guild && it.roleId in roles && it.instanceId==instance && when(action) {
            "status" -> true; "start" -> it.canStart; "stop" -> it.canStop; else -> it.canRestart
        } }
    }
}
