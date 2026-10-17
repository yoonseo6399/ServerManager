package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*
import kotlin.test.*

class AccountRoleTest {
    @TempDir lateinit var temp:Path
    @Test fun `global administrator manages existing and future servers and persists in backward compatible schema`() {
        var id=""
        AuthStore(temp).use {store->
            id=store.invitation(store.invite("admin",emptyMap(),AccountRole.ADMIN))!!.userId
            assertEquals(AccountRole.ADMIN,store.account(id)!!.role);assertTrue(store.allowed(id,"new-server",true));assertFalse(store.account(id)!!.owner)
            val raw=jacksonObjectMapper().readTree(temp.resolve("accounts.json").readText()).path("accounts").last()
            assertEquals(setOf("id","displayName","handle","owner","enabled","epoch","permissions"),raw.fieldNames().asSequence().toSet())
            store.updateUser(id,true,mapOf("one" to "VIEWER"));assertTrue(store.allowed(id,"another",true))
        }
        AuthStore(temp).use {store->assertEquals(AccountRole.ADMIN,store.account(id)!!.role);store.updateUser(id,true,mapOf("one" to "VIEWER"),AccountRole.MEMBER);assertFalse(store.allowed(id,"another",false));assertFalse(store.allowed(id,"one",true));assertTrue(store.allowed(id,"one",false))}
    }
    @Test fun `owner grants preserve keys revoke old sessions and cannot remove last registered owner`() {
        AuthStore(temp).use {store->
            val owner=store.accounts().single();val bootstrap=store.invitation(temp.resolve("setup-code.txt").readText().trim())!!
            store.register(owner.id,bootstrap.hash,Passkey("b3duZXI",owner.id,"cHVibGlj",0,"owner",Instant.now().toString()))
            val token=store.invite("member",emptyMap());val invite=store.invitation(token)!!;val id=invite.userId
            assertFailsWith<IllegalArgumentException>{store.updateUser(id,true,emptyMap(),AccountRole.OWNER)}
            store.register(id,invite.hash,Passkey("bWVtYmVy",id,"cHVibGlj",0,"member",Instant.now().toString()))
            val keys=store.credentialList(id);store.updateUser(id,true,emptyMap(),AccountRole.OWNER);assertTrue(store.account(id)!!.owner);assertEquals(1,store.account(id)!!.epoch);assertEquals(keys,store.credentialList(id))
            assertFailsWith<IllegalArgumentException>{store.updateUser(id,false,emptyMap(),AccountRole.OWNER)}
            store.updateUser(owner.id,true,emptyMap(),AccountRole.MEMBER)
            assertFailsWith<IllegalArgumentException>{store.updateUser(id,true,emptyMap(),AccountRole.ADMIN)}
            assertFailsWith<IllegalArgumentException>{store.invite("injected",mapOf("*" to "ADMIN"))}
        }
    }
}
