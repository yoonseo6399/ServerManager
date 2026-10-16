package local.servermanager

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.time.Instant
import kotlin.io.path.*
import kotlin.test.*

class UpdateCoordinatorTest {
    private fun home()=Files.createTempDirectory("upd")

    // ---- decisions ---------------------------------------------------------------------------------------------
    @Test fun `control layer update proceeds while hosted games run when the host protocol is compatible`() {
        val facts=UpdateFacts(hostedGamesAlive=2,hostProtocol=1,targetMinHost=1,targetMaxHost=1)
        assertEquals(UpdateDecision(true),UpdatePolicy.decide(UpdateComponents.RUNNER_CONTROL,facts))
        assertEquals(UpdateDecision(true),UpdatePolicy.decide(UpdateComponents.MANAGER_APP,facts))
    }
    @Test fun `control layer update waits with a stated reason for every unsafe condition`() {
        fun reason(f:UpdateFacts,c:String=UpdateComponents.RUNNER_CONTROL)=UpdatePolicy.decide(c,f).also {assertFalse(it.apply)}.waitReason
        assertEquals("LEGACY_GAME_RUNNING",reason(UpdateFacts(legacyGamesAlive=1)))
        assertEquals("HOST_PROTOCOL_INCOMPATIBLE",reason(UpdateFacts(hostedGamesAlive=1,hostProtocol=1,targetMinHost=2,targetMaxHost=3)))
        assertEquals("HOST_PROTOCOL_INCOMPATIBLE",reason(UpdateFacts(hostedGamesAlive=1,hostProtocol=4,targetMinHost=1,targetMaxHost=3)))
        assertEquals("HOST_UNREACHABLE",reason(UpdateFacts(hostedGamesAlive=1,hostProtocol=null)))
        assertEquals("RELAY_STREAMS_ACTIVE",reason(UpdateFacts(relayStreamsInController=3)))
        assertEquals("SERVER_PREPARING",reason(UpdateFacts(preparing=true)))
        assertEquals("TRANSFER_NOT_RESUMABLE",reason(UpdateFacts(transferActive=true,transferResumable=false)))
        assertEquals("DIRTY_HANDOFF_PENDING",reason(UpdateFacts(dirtyWithoutProcess=true)),"dirty/receipt protection is never bypassed")
    }
    @Test fun `a transfer in progress only pauses at a checkpoint and never skips dirty protection`() {
        val d=UpdatePolicy.decide(UpdateComponents.RUNNER_CONTROL,UpdateFacts(transferActive=true,transferResumable=true))
        assertTrue(d.apply && d.pauseTransfers)
        // dirty + a live transfer: the update pauses at the checkpoint and the dirty marker/receipt stay untouched for the resume.
        val withDirty=UpdatePolicy.decide(UpdateComponents.RUNNER_CONTROL,UpdateFacts(transferActive=true,dirtyWithoutProcess=true));assertTrue(withDirty.apply && withDirty.pauseTransfers)
        assertEquals("DIRTY_HANDOFF_PENDING",UpdatePolicy.decide(UpdateComponents.RUNNER_CONTROL,UpdateFacts(dirtyWithoutProcess=true)).waitReason)
    }
    @Test fun `the resident host itself updates only when nothing live depends on it`() {
        assertEquals("GAME_RUNNING",UpdatePolicy.decide(UpdateComponents.GAME_HOST,UpdateFacts(hostedGamesAlive=1)).waitReason)
        assertEquals("GAME_RUNNING",UpdatePolicy.decide(UpdateComponents.GAME_HOST,UpdateFacts(legacyGamesAlive=1)).waitReason)
        assertEquals("RELAY_STREAMS_ACTIVE",UpdatePolicy.decide(UpdateComponents.GAME_HOST,UpdateFacts(relayStreamsInHost=1)).waitReason)
        assertTrue(UpdatePolicy.decide(UpdateComponents.GAME_HOST,UpdateFacts()).apply)
    }

    // ---- status API model -----------------------------------------------------------------------------------------
    @Test fun `status carries component versions stage wait reason error time and job id and its log survives a restart`() {
        val home=home();var now=Instant.parse("2026-10-16T01:00:00Z")
        val c=UpdateCoordinator(home,{now})
        c.update(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2","CHECKING")
        c.update(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2","DOWNLOADING")
        c.update(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2","VERIFYING")
        now=now.plusSeconds(5);c.update(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2","WAITING",waitReason="LEGACY_GAME_RUNNING")
        c.update(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2","WAITING",waitReason="LEGACY_GAME_RUNNING") // heartbeat repeat is not re-logged
        c.update(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2","APPLYING");c.update(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2","RECONNECTING")
        val before=c.snapshot().components.single {it.component==UpdateComponents.RUNNER_CONTROL}
        assertEquals("RECONNECTING",before.stage);assertEquals("0.5.2",before.target);assertEquals("0.5.1",before.current);assertNotNull(before.jobId);assertTrue(before.updatedAt.isNotEmpty())
        assertEquals(listOf("CHECKING","DOWNLOADING","VERIFYING","WAITING","APPLYING","RECONNECTING"),c.snapshot().log.map {it.stage})
        // The new controller starts: same files, new object.
        val again=UpdateCoordinator(home,{now})
        again.reconcileStart(UpdateComponents.RUNNER_CONTROL,"0.5.2",null)
        val after=again.snapshot().components.single {it.component==UpdateComponents.RUNNER_CONTROL}
        assertEquals("DONE",after.stage);assertEquals("0.5.2",after.current)
        assertEquals(before.jobId,after.jobId,"same job id across the restart")
        assertEquals("DONE",again.snapshot().log.last().stage);assertTrue(again.snapshot().log.size>=7)
        assertEquals(1,again.logSince(again.snapshot().log.first().seq).size.coerceAtMost(1)) // incremental read after reconnect
        assertEquals(3,UpdateCoordinator(home).snapshot().components.size,"all three components are always present")
    }
    @Test fun `secrets never reach status or log`() {
        val home=home();val c=UpdateCoordinator(home)
        c.update(UpdateComponents.GAME_HOST,"0.5.1","0.5.2","FAILED",error="download failed token=ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD Authorization: Bearer abc.def.ghi https://user:pw@example.com/x")
        val all=Files.readString(home/"updates/progress.log")+Files.readString(home/"updates/components.json")+c.snapshot().toString()
        listOf("ghp_abcdefghijkl","abc.def.ghi","user:pw").forEach {assertFalse(all.contains(it),"leaked $it")}
        assertTrue(c.snapshot().components.single {it.component==UpdateComponents.GAME_HOST}.error!!.contains("[redacted]"))
    }

    // ---- signature, version, start confirmation, rollback ------------------------------------------------------------
    private class Pkg(val manifest:ByteArray,val signature:ByteArray,val jar:Path,val key:ByteArray)
    private fun pkg(version:String,extra:String="",tamperJar:Boolean=false,tamperManifest:Boolean=false,sign:java.security.PrivateKey?=null,keyOverride:ByteArray?=null):Pkg {
        val dir=Files.createTempDirectory("pkg");val pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val jar=dir.resolve("server-manager-$version.jar");jar.writeBytes(ByteArray(2048) {(it*7).toByte()})
        val hash=MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") {"%02x".format(it)}
        var manifest="version=$version\nprotocol=1\nfile=server-manager-$version.jar\nsha256=$hash\nsize=${jar.fileSize()}\n$extra".toByteArray()
        val signature=Signature.getInstance("Ed25519").apply {initSign(sign ?: pair.private);update(manifest)}.sign()
        if(tamperManifest)manifest=String(manifest).replace("size=2048","size=2049").toByteArray()
        if(tamperJar)jar.writeBytes(ByteArray(2048) {1})
        return Pkg(manifest,signature,jar,keyOverride ?: pair.public.encoded)
    }
    @Test fun `a valid signed package passes and carries host protocol bounds`() {
        val p=pkg("0.5.2","controller.minHost=1\ncontroller.maxHost=2\nhost.protocol=2\n")
        val ok=assertIs<UpdatePackageVerifier.Result.Ok>(UpdatePackageVerifier.verify(p.manifest,p.signature,p.jar,p.key,"0.5.2","0.5.1"))
        assertEquals(Triple(1,2,2),Triple(ok.minHost,ok.maxHost,ok.hostProtocol))
    }
    @Test fun `only explicit stable rollback relaxes version ordering and never signature validation`() {
        val p=pkg("0.6.1")
        assertIs<UpdatePackageVerifier.Result.Rejected>(UpdatePackageVerifier.verify(p.manifest,p.signature,p.jar,p.key,"0.6.1","0.7.0-beta.2"))
        assertIs<UpdatePackageVerifier.Result.Ok>(UpdatePackageVerifier.verify(p.manifest,p.signature,p.jar,p.key,"0.6.1","0.7.0-beta.2",true))
        val bad=pkg("0.6.1",tamperJar=true)
        assertIs<UpdatePackageVerifier.Result.Rejected>(UpdatePackageVerifier.verify(bad.manifest,bad.signature,bad.jar,bad.key,"0.6.1","0.7.0-beta.2",true))
        val beta=pkg("0.7.0-beta.1")
        assertIs<UpdatePackageVerifier.Result.Rejected>(UpdatePackageVerifier.verify(beta.manifest,beta.signature,beta.jar,beta.key,"0.7.0-beta.1","0.7.0-beta.2",true))
    }
    @Test fun `tampered manifest tampered package wrong key version mismatch and downgrade are rejected`() {
        fun r(p:Pkg,expected:String="0.5.2",current:String="0.5.1")=assertIs<UpdatePackageVerifier.Result.Rejected>(UpdatePackageVerifier.verify(p.manifest,p.signature,p.jar,p.key,expected,current)).reason
        assertEquals("SIGNATURE_INVALID",r(pkg("0.5.2",tamperManifest=true)))
        assertEquals("PACKAGE_HASH_MISMATCH",r(pkg("0.5.2",tamperJar=true)))
        assertEquals("SIGNATURE_INVALID",r(pkg("0.5.2",keyOverride=KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)))
        assertEquals("VERSION_MISMATCH",r(pkg("0.5.2"),expected="0.5.3"))
        assertEquals("NOT_NEWER",r(pkg("0.5.2"),current="0.5.2"))
        assertEquals("NOT_NEWER",r(pkg("0.5.2"),current="0.6.0"))
        assertEquals("MANIFEST_INVALID",r(pkg("0.5.2","controller.minHost=x\n")))
        assertEquals("MANIFEST_INVALID",r(pkg("0.5.2","controller.minHost=3\ncontroller.maxHost=2\n")))
    }
    @Test fun `a new layer that is confirmed in time keeps the update`() {
        val g=ActivationGuard(home());g.begin(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2",60_000,now=1000)
        assertIs<ActivationGuard.Outcome.Waiting>(g.check("0.5.2",now=2000))
        assertTrue(g.confirm("0.5.2"));assertIs<ActivationGuard.Outcome.Nothing>(g.check("0.5.2",now=3000))
        assertFalse(g.confirm("0.5.2"),"nothing left to confirm")
    }
    @Test fun `start failure version mismatch and missed confirmation roll back to the previous version`() {
        val h=home();val g=ActivationGuard(h)
        g.begin(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2",1000,now=0)
        val late=assertIs<ActivationGuard.Outcome.RolledBack>(g.check("0.5.2",now=5000))
        assertEquals("0.5.2" to "0.5.1",late.failed to late.restored);assertEquals("0.5.2",(h/"failed-update-version.txt").readText().trim());assertEquals("0.5.1",(h/"updates/active.txt").readText().trim())
        assertNull(g.read(),"journal cleared after rollback")
        g.begin(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.3",60_000,now=0)
        val mismatch=assertIs<ActivationGuard.Outcome.RolledBack>(g.check("0.5.1",now=10))
        assertTrue(mismatch.reason.contains("0.5.3"))
        // repeated failed starts
        g.begin(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.4",60_000,now=0);g.begin(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.4",60_000,now=0)
        assertEquals(2,g.read()!!.attempts)
        assertIs<ActivationGuard.Outcome.RolledBack>(g.check("0.5.1",now=10))
    }
    @Test fun `the coordinator reports a rolled back update as failed with the restored version`() {
        val h=home();val c=UpdateCoordinator(h);c.update(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2","RECONNECTING")
        c.reconcileStart(UpdateComponents.RUNNER_CONTROL,"0.5.1","0.5.2")
        val row=c.snapshot().components.single {it.component==UpdateComponents.RUNNER_CONTROL}
        assertEquals("FAILED",row.stage);assertEquals("0.5.1",row.current);assertEquals("0.5.2",row.target);assertNotNull(row.error)
    }
}
