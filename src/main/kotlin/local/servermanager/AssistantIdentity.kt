package local.servermanager

import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom
import java.util.Base64

/** A Discord role never implies ownership of a web account. Links expire on revocation/account epoch change/restart. */
@Service
class AssistantIdentity(private val store:AuthStore) {
    private data class Pending(val user:String,val epoch:Int,val expires:Instant)
    private data class Link(val user:String,val epoch:Int)
    private val pending=ConcurrentHashMap<String,Pending>();private val links=ConcurrentHashMap<String,Link>()
    fun code(user:String):String {
        val account=store.account(user);check(account?.owner==true && account.enabled)
        pending.entries.removeIf {it.value.user==user || it.value.expires.isBefore(Instant.now())}
        val bytes=ByteArray(24);SecureRandom().nextBytes(bytes);val code=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        pending[AssistantWorkspace.digest(code.toByteArray())]=Pending(user,account.epoch,Instant.now().plusSeconds(300));return code
    }
    fun link(code:String,discordUser:String,guild:String):String {
        val item=pending.remove(AssistantWorkspace.digest(code.toByteArray())) ?: error("연결 코드가 없거나 이미 사용되었습니다.")
        check(item.expires.isAfter(Instant.now())) {"연결 코드가 만료되었습니다."}
        val account=store.account(item.user);check(account?.owner==true && account.enabled && account.epoch==item.epoch)
        links["$guild:$discordUser"]=Link(item.user,item.epoch);return item.user
    }
    fun resolveDiscord(discordUser:String,guild:String):String? {
        val link=links["$guild:$discordUser"] ?: return null
        return store.account(link.user)?.takeIf {it.owner && it.enabled && it.epoch==link.epoch}?.id
    }
    fun revoke(user:String) {links.entries.removeIf {it.value.user==user};pending.entries.removeIf {it.value.user==user}}
}
