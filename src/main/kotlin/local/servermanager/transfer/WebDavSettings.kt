package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.*

data class WebDavConfig(val enabled:Boolean=false,val url:String="",val username:String="")
data class WebDavView(val config:WebDavConfig,val revision:Long,val passwordConfigured:Boolean)
class WebDavSave(val config:WebDavConfig,val revision:Long,val password:String?=null,val clearPassword:Boolean=false)
private data class WebDavDisk(val config:WebDavConfig=WebDavConfig(),val revision:Long=0,val encryptedPassword:String?=null)

@Service
class WebDavSettings(manager:ServerManager) {
    private val mapper=jacksonObjectMapper()
    private val directory=manager.root.resolve("security/webdav")
    private val file=directory.resolve("settings.json")
    private val keyFile=directory.resolve("secret.key")
    private var disk:WebDavDisk
    init {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(file) && !Files.isSymbolicLink(keyFile))
        directory.createDirectories();Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        disk=if(file.exists()) mapper.readValue(file.readText()) else WebDavDisk()
    }
    @Synchronized fun view()=WebDavView(disk.config,disk.revision,disk.encryptedPassword!=null)
    @Synchronized fun credentials():Pair<WebDavConfig,String>? {
        if(!disk.config.enabled) return null
        val encrypted=disk.encryptedPassword ?: return null
        val bytes=Base64.getDecoder().decode(encrypted)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(keyFile.readBytes(),"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return disk.config to cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
    }
    @Synchronized fun save(request:WebDavSave):WebDavView {
        check(request.revision==disk.revision) { "WebDAV 설정이 다른 화면에서 변경되었습니다" }
        val config=request.config
        if(config.url.isNotBlank()) {
            val uri=URI(config.url)
            require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.port in -1..65535) { "HTTPS WebDAV 폴더 주소를 입력하세요" }
            require(config.url.length<=2048 && !config.url.contains("..")) { "WebDAV 주소를 확인하세요" }
        }
        require(config.username.length<=200 && !config.username.contains('\n') && !config.username.contains('\r'))
        require(!(request.clearPassword && !request.password.isNullOrBlank()))
        var encrypted=if(request.clearPassword) null else disk.encryptedPassword
        request.password?.takeIf { it.isNotEmpty() }?.let { password ->
            require(password.length<=1024)
            if(!keyFile.exists()) writePrivate(keyFile,ByteArray(32).also { SecureRandom().nextBytes(it) })
            val iv=ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(keyFile.readBytes(),"AES"),GCMParameterSpec(128,iv))
            encrypted=Base64.getEncoder().encodeToString(iv+cipher.doFinal(password.toByteArray()))
        }
        require(!config.enabled || (config.url.isNotBlank() && config.username.isNotBlank() && encrypted!=null)) { "WebDAV 주소·사용자 이름·앱 비밀번호를 입력하세요" }
        val next=WebDavDisk(config,disk.revision+1,encrypted)
        writePrivate(file,mapper.writeValueAsBytes(next));disk=next
        return view()
    }
    private fun writePrivate(path:Path,bytes:ByteArray) {
        val tmp=Files.createTempFile(directory,".webdav-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try { tmp.writeBytes(bytes);Files.move(tmp,path,ATOMIC_MOVE,REPLACE_EXISTING) } finally { tmp.deleteIfExists() }
    }
}

@RestController
@RequestMapping("/api/admin/webdav")
class WebDavSettingsApi(private val settings:WebDavSettings,private val sync:WebDavSync) {
    private fun fresh(req:HttpServletRequest) {
        val verified=req.getSession(false)?.getAttribute("sm.verified") as? Long ?: 0
        if(System.currentTimeMillis()-verified>300000) throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"패스키로 다시 인증한 뒤 진행하세요")
    }
    @GetMapping fun get()=ResponseEntity.ok().header("Cache-Control","no-store").body(settings.view())
    @PutMapping fun save(@RequestBody request:WebDavSave,req:HttpServletRequest):WebDavView { fresh(req);return settings.save(request) }
    @GetMapping("/status/{id}") fun status(@PathVariable id:String)=sync.status(id)
    @PostMapping("/sync/{id}") fun retry(@PathVariable id:String,req:HttpServletRequest):Map<String,Boolean> { fresh(req);sync.schedule(id);return mapOf("scheduled" to true) }
}
