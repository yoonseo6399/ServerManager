package local.servermanager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.MessageDigest
import kotlin.io.path.*
import kotlin.test.*
class UpdatesTest {
 @TempDir lateinit var temp:Path
 private fun fixture():Triple<ByteArray,ByteArray,ByteArray>{
  val keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();val content="a release".toByteArray();temp.resolve("server-manager-1.2.3.jar").writeBytes(content)
  val hash=MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
  val manifest="protocol=1\nversion=1.2.3\nfile=server-manager-1.2.3.jar\nsize=${content.size}\nsha256=$hash\n".toByteArray()
  val signer=Signature.getInstance("Ed25519");signer.initSign(keys.private);signer.update(manifest);return Triple(manifest,signer.sign(),keys.public.encoded)
 }
 @Test fun `signed update verifies signature and payload independently`() {
  val (bytes,sig,key)=fixture();val p=SignedUpdates.manifest(bytes,sig,key)
  assertTrue(SignedUpdates.validFile(temp.resolve(p.getProperty("file")),p))
  temp.resolve(p.getProperty("file")).writeText("tampered!");assertFalse(SignedUpdates.validFile(temp.resolve(p.getProperty("file")),p))
  assertFails { SignedUpdates.manifest(bytes+"tampered".toByteArray(),sig,key) }
  assertFails { SignedUpdates.manifest(bytes,sig,KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded) }
 }
 @Test fun `semantic version comparison prevents downgrades and malformed paths`() {
  assertTrue(SignedUpdates.compare("0.10.0","0.9.9")>0);assertTrue(SignedUpdates.compare("0.2.0","0.3.0")<0)
  assertEquals(0,SignedUpdates.compare("1.0.0","1.0.0"));assertFails { SignedUpdates.compare("../1.0","0.3.0") }
 }
 @Test fun `beta updates sort numerically below stable and stay out of stable channel`() {
  assertTrue(SignedUpdates.compare("0.4.0-beta.10","0.4.0-beta.2")>0)
  assertTrue(SignedUpdates.compare("0.4.0-beta.10","0.4.0")<0)
  assertTrue(SignedUpdates.compare("0.4.0-beta.1","0.3.0")>0)
  assertFalse(SignedUpdates.eligible("0.4.0-beta.1",true,false))
  assertFalse(SignedUpdates.eligible("0.4.0-beta.1",false,false))
  assertTrue(SignedUpdates.eligible("0.4.0-beta.1",true,true))
  assertTrue(SignedUpdates.eligible("0.4.0",false,true))
  assertFalse(SignedUpdates.eligible("../escape",true,true))
  assertFails { SignedUpdates.compare("0.4.0-beta.0","0.3.0") }
 }
 @Test fun `profile selection preserves existing data and rejects unrelated directories`() {
  val profile=temp.resolve("Profiles & worlds");val selected=validateProfileDirectory(profile.toString());assertEquals(profile.toRealPath(),selected)
  profile.resolve("one").createDirectories();profile.resolve("one/instance.json").writeText("{}")
  profile.resolve("one/world.dat").writeText("world");validateProfileDirectory(profile.toString());assertEquals("world",profile.resolve("one/world.dat").readText())
  profile.resolve("unrelated.txt").writeText("keep");assertFailsWith<IllegalArgumentException> { validateProfileDirectory(profile.toString()) };assertEquals("keep",profile.resolve("unrelated.txt").readText())
  assertFailsWith<IllegalArgumentException> { validateProfileDirectory("relative") }
 }
}
