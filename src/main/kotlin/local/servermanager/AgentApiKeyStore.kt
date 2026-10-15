package local.servermanager

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.*

/** Local encryption, matching the Discord store's protection model. Never serialized into API responses. */
class AgentApiKeyStore(private val directory:Path,private val provider:String="Gemini") {
    private val key=directory.resolve("key.bin")
    private val secret=directory.resolve("gemini.enc")
    private fun validate() {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(key) && !Files.isSymbolicLink(secret)) { "에이전트 키 저장소에 심볼릭 링크를 사용할 수 없습니다." }
    }
    fun read():String? {
        validate()
        if(!secret.exists())return null
        try {
            val bytes=secret.readBytes()
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key.readBytes(),"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            return cipher.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
        } catch(_:Exception) {throw IllegalStateException("저장된 $provider API 키를 읽을 수 없습니다. 키를 다시 설정하세요.")}
    }
    fun save(value:String) {
        require(value.length in 20..512 && value.none {it.isWhitespace()}) { "API 키 형식을 확인하세요." }
        validate();directory.createDirectories()
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        if(!key.exists())write(key,ByteArray(32).also {SecureRandom().nextBytes(it)})
        val iv=ByteArray(12).also {SecureRandom().nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key.readBytes(),"AES"),GCMParameterSpec(128,iv))
        write(secret,iv+cipher.doFinal(value.toByteArray()))
    }
    private fun write(path:Path,bytes:ByteArray) {
        val temp=Files.createTempFile(directory,".agent-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {temp.writeBytes(bytes);Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING)} finally {temp.deleteIfExists()}
    }
}
