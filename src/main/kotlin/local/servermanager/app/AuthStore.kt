package local.servermanager

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.data.ByteArray as WebBytes
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.UUID
import kotlin.io.path.*

enum class AccountRole { MEMBER, ADMIN, OWNER }
private const val GLOBAL_ADMIN = "*"

data class Account(val id:String, val displayName:String, val handle:String, val owner:Boolean=false, var enabled:Boolean=true, var epoch:Int=0, var permissions:Map<String,String> = emptyMap()) {
    // Persist the global grant in the existing permission map so older releases can still read accounts.json.
    @get:JsonIgnore val role:AccountRole get()=if(owner) AccountRole.OWNER else if(permissions[GLOBAL_ADMIN]=="ADMIN") AccountRole.ADMIN else AccountRole.MEMBER
    fun managesAllServers()=role!=AccountRole.MEMBER
    fun instancePermissions()=permissions.filterKeys { it!=GLOBAL_ADMIN }
}
data class Passkey(val id:String,val userId:String,val publicKey:String,var count:Long,val label:String,val createdAt:String)
data class Invitation(val hash:String,val userId:String,val expiresAt:String,val recovery:Boolean=false)
data class AuthData(val accounts:MutableList<Account> = mutableListOf(),val credentials:MutableList<Passkey> = mutableListOf(),val invitations:MutableList<Invitation> = mutableListOf())

class AuthStore(val directory:Path):CredentialRepository,AutoCloseable {
    private val mapper=jacksonObjectMapper()
    private val random=SecureRandom()
    private val channel:FileChannel
    private val lock:java.nio.channels.FileLock
    private val file=directory.resolve("accounts.json")
    private var data:AuthData
    init {
        directory.createDirectories();Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"))
        channel=FileChannel.open(directory.resolve("store.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)
        lock=channel.tryLock() ?: error("인증 저장소를 사용 중입니다. 다른 관리 앱을 먼저 종료하세요")
        data=if(file.exists()) mapper.readValue(file.readText()) else AuthData()
        if(data.accounts.none { it.owner }) {
            val owner=Account(UUID.randomUUID().toString(),"소유자",token(),owner=true)
            data.accounts.add(owner);issueSetup(owner)
        }
    }
    private fun token():String=ByteArray(32).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun persist() {
        val tmp=Files.createTempFile(directory,"auth-",".tmp")
        try { Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"));tmp.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) } finally { tmp.deleteIfExists() }
    }
    private fun issueSetup(owner:Account) {
        val value=token()
        data.invitations.removeIf { it.userId==owner.id }
        data.invitations.add(Invitation(hash(value),owner.id,Instant.now().plusSeconds(1800).toString(),true))
        persist()
        val path=directory.resolve("setup-code.txt")
        Files.writeString(path,value+"\n",StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)
        Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"))
    }
    @Synchronized fun recoverOwner(ownerId:String?=null) {
        val owners=data.accounts.filter { it.owner }
        val owner=if(ownerId==null) {
            require(owners.size==1) { "소유자 계정이 여러 개입니다. --recover-owner-id=<계정 ID>를 지정하세요" }
            owners.single()
        } else owners.singleOrNull { it.id==ownerId } ?: error("선택한 소유자 계정이 없습니다")
        owner.enabled=true;owner.epoch++
        data.credentials.removeIf { it.userId==owner.id };issueSetup(owner)
    }
    @Synchronized fun account(id:String):Account?=data.accounts.find { it.id==id }?.let { it.copy(permissions=it.permissions.toMap()) }
    @Synchronized fun accounts():List<Account> = data.accounts.map { it.copy(permissions=it.permissions.toMap()) }
    @Synchronized fun credentialList(userId:String)=data.credentials.filter { it.userId==userId }.map { mapOf("id" to it.id,"label" to it.label,"createdAt" to it.createdAt) }
    @Synchronized fun hasOwnerCredential()=data.accounts.filter { it.owner }.any { a->data.credentials.any { it.userId==a.id } }
    @Synchronized fun invitation(value:String):Invitation? = data.invitations.find { it.hash==hash(value) && Instant.now().isBefore(Instant.parse(it.expiresAt)) && account(it.userId)?.enabled==true }
    @Synchronized fun invite(name:String,permissions:Map<String,String>,role:AccountRole=AccountRole.MEMBER):String {
        require(name.isNotBlank() && name.length<=80)
        require(permissions.values.all { it in setOf("ADMIN","VIEWER") }) { "잘못된 권한" }
        require(GLOBAL_ADMIN !in permissions) { "전체 관리자 권한은 계정 역할로 지정하세요" }
        require(role!=AccountRole.OWNER) { "소유자는 등록된 계정의 역할 변경으로 지정하세요" }
        val user=Account(UUID.randomUUID().toString(),name,token(),permissions=permissions + if(role==AccountRole.ADMIN) mapOf(GLOBAL_ADMIN to "ADMIN") else emptyMap())
        val value=token();data.accounts.add(user)
        data.invitations.add(Invitation(hash(value),user.id,Instant.now().plusSeconds(86400).toString()))
        persist();return value
    }
    @Synchronized fun register(userId:String,inviteHash:String?,key:Passkey) {
        val user=data.accounts.find { it.id==userId && it.enabled } ?: error("계정이 비활성화되었습니다")
        require(key.userId==user.id && data.credentials.none { it.id==key.id }) { "이미 등록된 패스키입니다" }
        if(inviteHash!=null) {
            val invitation=data.invitations.find { it.hash==inviteHash && it.userId==userId && Instant.now().isBefore(Instant.parse(it.expiresAt)) } ?: error("초대가 만료되었거나 이미 사용되었습니다")
            data.invitations.remove(invitation)
        }
        data.credentials.add(key);persist()
        if(user.owner)directory.resolve("setup-code.txt").deleteIfExists()
    }
    @Synchronized fun updateCounter(id:String,count:Long) { val key=data.credentials.find { it.id==id } ?: error("삭제된 패스키");key.count=count;persist() }
    @Synchronized fun updateUser(id:String,enabled:Boolean,permissions:Map<String,String>,role:AccountRole?=null) {
        require(permissions.values.all { it in setOf("ADMIN","VIEWER") } && GLOBAL_ADMIN !in permissions) { "잘못된 서버 권한" }
        val index=data.accounts.indexOfFirst { it.id==id };require(index>=0) { "없는 계정" }
        val account=data.accounts[index];val nextRole=role ?: account.role
        require(nextRole!=AccountRole.OWNER || enabled) { "소유자 계정은 비활성화할 수 없습니다" }
        if(account.owner && (!enabled || nextRole!=AccountRole.OWNER)) {
            require(data.accounts.any { other -> other.id!=id && other.owner && other.enabled && data.credentials.any { it.userId==other.id } }) { "패스키가 등록된 마지막 소유자는 해제할 수 없습니다" }
        }
        require(nextRole!=AccountRole.OWNER || account.owner || data.credentials.any { it.userId==id }) { "패스키를 등록한 계정만 소유자로 지정할 수 있습니다" }
        data.accounts[index]=account.copy(owner=nextRole==AccountRole.OWNER,enabled=enabled,permissions=permissions + if(nextRole==AccountRole.ADMIN) mapOf(GLOBAL_ADMIN to "ADMIN") else emptyMap(),epoch=account.epoch+1)
        persist()
    }
    @Synchronized fun revokeSessions(id:String) { data.accounts.find { it.id==id }?.let { it.epoch++;persist() } }
    @Synchronized fun removeInstancePermissions(id:String) {
        data.accounts.filter { id in it.permissions }.forEach { it.permissions=it.permissions-id;it.epoch++ }
        persist()
    }
    @Synchronized fun removeKey(userId:String,id:String) {
        require(data.credentials.count { it.userId==userId }>1) { "마지막 패스키는 삭제할 수 없습니다. 다른 패스키를 먼저 등록하세요" }
        require(data.credentials.removeIf { it.userId==userId && it.id==id }) { "패스키를 찾을 수 없습니다" }
        data.accounts.find { it.id==userId }?.let { it.epoch++ };persist()
    }
    fun allowed(userId:String,id:String,write:Boolean):Boolean {
        val user=account(userId) ?: return false
        return user.enabled && (user.managesAllServers() || user.permissions[id]=="ADMIN" || (!write && user.permissions[id]=="VIEWER"))
    }
    @Synchronized override fun getCredentialIdsForUsername(username:String):Set<PublicKeyCredentialDescriptor> = data.credentials.filter { it.userId==username }.map { PublicKeyCredentialDescriptor.builder().id(WebBytes.fromBase64Url(it.id)).build() }.toSet()
    @Synchronized override fun getUserHandleForUsername(username:String):Optional<WebBytes> = Optional.ofNullable(account(username)?.takeIf { it.enabled }?.let { WebBytes.fromBase64Url(it.handle) })
    @Synchronized override fun getUsernameForUserHandle(userHandle:WebBytes):Optional<String> = Optional.ofNullable(data.accounts.find { it.handle==userHandle.base64Url && it.enabled }?.id)
    private fun registered(key:Passkey):RegisteredCredential {
        val user=data.accounts.first { it.id==key.userId }
        return RegisteredCredential.builder().credentialId(WebBytes.fromBase64Url(key.id)).userHandle(WebBytes.fromBase64Url(user.handle)).publicKeyCose(WebBytes.fromBase64Url(key.publicKey)).signatureCount(key.count).build()
    }
    @Synchronized override fun lookup(credentialId:WebBytes,userHandle:WebBytes):Optional<RegisteredCredential> = Optional.ofNullable(data.credentials.find { it.id==credentialId.base64Url && data.accounts.any { a->a.id==it.userId && a.handle==userHandle.base64Url && a.enabled } }?.let { registered(it) })
    @Synchronized override fun lookupAll(credentialId:WebBytes):Set<RegisteredCredential> = data.credentials.filter { it.id==credentialId.base64Url && account(it.userId)?.enabled==true }.map { registered(it) }.toSet()
    override fun close() { lock.release();channel.close() }
}
