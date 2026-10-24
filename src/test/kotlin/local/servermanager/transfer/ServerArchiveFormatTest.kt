package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.*
import kotlin.io.path.*
import kotlin.test.*

class ServerArchiveFormatTest {
    @TempDir lateinit var dir:Path
    private fun zip(vararg files:Pair<String,String>):Path=dir.resolve("${java.util.UUID.randomUUID()}.zip").also {path->ZipOutputStream(path.outputStream()).use {out->files.forEach {(name,text)->out.putNextEntry(ZipEntry(name));out.write(text.toByteArray());out.closeEntry()}}}
    @Test fun `outer folder and NeoForge installation are detected and all world and disabled mod bytes survive`() {
        val file=zip("README.txt" to "outside", "Pack/server.properties" to "level-name=world\n", "Pack/libraries/net/neoforged/neoforge/21.1.264/unix_args.txt" to "launch args", "Pack/world/level.dat" to "world", "Pack/mods/custom.jar.disabled" to "disabled", "__MACOSX/._junk" to "ignored")
        val plan=ServerArchiveFormat.inspect(file,"server.zip")
        assertEquals("Pack/",plan.root);assertEquals("NeoForge",plan.loader);assertEquals("1.21.1",plan.minecraft);assertEquals("21.1.264",plan.loaderVersion)
        val target=dir.resolve("out");ServerArchiveFormat.extract(file,plan.root,target)
        assertEquals("world",target.resolve("world/level.dat").readText());assertEquals("disabled",target.resolve("mods/custom.jar.disabled").readText());assertFalse(target.resolve("Pack").exists());assertFalse(target.resolve("README.txt").exists())
        assertTrue(plan.warnings.any {it.contains("바깥")})
    }
    @Test fun `unlabelled jar does not invent a server type and version`() {
        val plan=ServerArchiveFormat.inspect(zip("server.properties" to "", "custom.jar" to "jar"),"Archive.ZIP")
        assertNull(plan.loader);assertNull(plan.minecraft);assertNull(plan.loaderVersion);assertEquals("custom.jar",plan.entryJar);assertEquals("Archive",plan.name)
    }
    @Test fun `Forge Paper and Fabric hints are detected without executing jars`() {
        val forge=ServerArchiveFormat.inspect(zip("server.properties" to "", "libraries/net/minecraftforge/forge/1.20.1-47.2.0/win_args.txt" to "args"),"forge.zip")
        assertEquals("Forge",forge.loader);assertEquals("1.20.1",forge.minecraft);assertEquals("47.2.0",forge.loaderVersion)
        val paper=ServerArchiveFormat.inspect(zip("paper-1.21.1-123.jar" to "jar"),"paper.zip")
        assertEquals("Paper",paper.loader);assertEquals("123",paper.loaderVersion)
        val fabric=ServerArchiveFormat.inspect(zip("fabric-server-launch.jar" to "jar","libraries/net/fabricmc/fabric-loader/0.16.9/loader.jar" to "library"),"fabric.zip")
        assertEquals("Fabric",fabric.loader);assertEquals("0.16.9",fabric.loaderVersion);assertNull(fabric.minecraft)
    }
    @Test fun `traversal duplicates Unicode aliases file directory conflicts and multiple servers are rejected`() {
        val bad=listOf(
            arrayOf("../outside" to "bad","server.properties" to ""),arrayOf("/tmp/outside" to "bad","server.properties" to ""),
            arrayOf("C:/outside" to "bad","server.properties" to ""),arrayOf("a\\b" to "bad","server.properties" to ""),
            arrayOf("World/data" to "a","world/DATA" to "b","server.properties" to ""),
            arrayOf("caf\u00e9/a" to "a","cafe\u0301/a" to "b","server.properties" to ""),
            arrayOf("world" to "file","world/data" to "bad","server.properties" to ""),
            arrayOf("a/server.properties" to "","b/server.properties" to ""))
        for(files in bad)assertFailsWith<IllegalArgumentException>{ServerArchiveFormat.inspect(zip(*files),"bad.zip")}
        assertFalse(dir.resolve("outside").exists())
    }
    @Test fun `CRC mismatch is detected before a staged archive is published`() {
        val path=dir.resolve("crc.zip");val payload="UNIQUE-WORLD-CONTENT".toByteArray()
        ZipOutputStream(path.outputStream()).use {out->
            out.putNextEntry(ZipEntry("server.properties"));out.closeEntry()
            out.putNextEntry(ZipEntry("world/level.dat").apply {method=ZipEntry.STORED;size=payload.size.toLong();compressedSize=size;crc=CRC32().apply {update(payload)}.value});out.write(payload);out.closeEntry()
        }
        val bytes=path.readBytes();val index=bytes.indices.first {i->i+payload.size<=bytes.size && bytes.copyOfRange(i,i+payload.size).contentEquals(payload)};bytes[index]=(bytes[index].toInt() xor 1).toByte();path.writeBytes(bytes)
        val plan=ServerArchiveFormat.inspect(path,"crc.zip")
        assertFailsWith<IllegalArgumentException>{ServerArchiveFormat.extract(path,plan.root,dir.resolve("stage"))};Unit
    }
    @Test fun `large declared expansion and giant control files are rejected during preview`() {
        val huge=zip(*((0..100).map {"data/$it" to ""}+listOf("server.properties" to "")).toTypedArray())
        val bytes=huge.readBytes();var count=0
        for(i in 0 until bytes.size-46)if(bytes[i]==0x50.toByte() && bytes[i+1]==0x4b.toByte() && bytes[i+2]==1.toByte() && bytes[i+3]==2.toByte()) {bytes[i+24]=0;bytes[i+25]=0;bytes[i+26]=0;bytes[i+27]=0x40;count++}
        assertEquals(102,count);huge.writeBytes(bytes)
        assertFailsWith<IllegalArgumentException>{ServerArchiveFormat.inspect(huge,"huge.zip")}
        assertFailsWith<IllegalArgumentException>{ServerArchiveFormat.inspect(zip("server.properties" to "", "eula.txt" to "x".repeat(65537)),"eula.zip")};Unit
    }
}
