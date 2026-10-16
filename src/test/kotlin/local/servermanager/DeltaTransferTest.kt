package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.*
import kotlin.test.*

class DeltaTransferTest {
    @TempDir lateinit var temp:Path
    private fun inventory(base:Path)=FileInventory.collect(base).let {(f,d)->RunnerFileManifest(f,d)}
    private fun file(base:Path,path:String,text:String) {val p=base.resolve(path);p.parent.createDirectories();p.writeText(text)}
    private fun incoming(base:Path,before:RunnerFileManifest,after:RunnerFileManifest):Path {
        val dir=Files.createTempDirectory(temp,"upload-")
        after.files.filter {(p,f)->before.files[p]!=f}.forEach {(p,_)->val target=dir.resolve(p);target.parent.createDirectories();Files.copy(base.resolve(p),target)}
        return dir
    }
    @Test fun `unchanged bytes stay in place and only superseded bytes are backed up without inode sharing`() {
        val base=temp.resolve("server").createDirectory();val wanted=temp.resolve("wanted").createDirectory()
        base.resolve("large.dat").outputStream().use {out->repeat(16){out.write(ByteArray(512*1024){7})}}
        file(base,"world/level.dat","old");file(base,"deleted.dat","deleted")
        val before=inventory(base);val attrs=Files.readAttributes(base.resolve("large.dat"),BasicFileAttributes::class.java)
        Files.copy(base.resolve("large.dat"),wanted.resolve("large.dat"));file(wanted,"world/level.dat","new");file(wanted,"added.txt","added")
        val after=inventory(wanted);val history=temp.resolve("backups")
        val backup=assertNotNull(DeltaTransfer.apply(base,incoming(wanted,before,after),before,after,history))
        assertEquals(after,inventory(base));assertFalse(backup.resolve("files/large.dat").exists())
        assertEquals("old",backup.resolve("files/world/level.dat").readText());assertEquals("deleted",backup.resolve("files/deleted.dat").readText())
        if(attrs.fileKey()!=null)assertEquals(attrs.fileKey(),Files.readAttributes(base.resolve("large.dat"),BasicFileAttributes::class.java).fileKey())
        base.resolve("world/level.dat").outputStream().use {it.write("edited in place".toByteArray())}
        assertEquals("old",backup.resolve("files/world/level.dat").readText())
        val noChange=inventory(base)
        assertNull(DeltaTransfer.apply(base,temp.resolve("empty").createDirectory(),noChange,noChange,history))
        assertEquals(1,history.listDirectoryEntries().size)
    }
    @Test fun `bad data and externally changed originals never replace files`() {
        val base=temp.resolve("server").createDirectory();file(base,"level.dat","original")
        val wanted=temp.resolve("wanted").createDirectory();file(wanted,"level.dat","new")
        val before=inventory(base);val after=inventory(wanted);val stage=incoming(wanted,before,after);val history=temp.resolve("backups")
        file(stage,"level.dat","corrupted")
        assertFailsWith<IllegalStateException>{DeltaTransfer.apply(base,stage,before,after,history)}
        assertEquals(before,inventory(base));assertFalse(history.exists())
        file(stage,"level.dat","new");file(base,"level.dat","external edit")
        assertFailsWith<IllegalStateException>{DeltaTransfer.apply(base,stage,before,after,history)}
        assertEquals("external edit",base.resolve("level.dat").readText())
    }
    @Test fun `every interrupted mutation recovers original content including file directory swaps and empty directories`() {
        val steps=mutableListOf<String>()
        fun run(failAt:String?,crash:Boolean) {
            val root=Files.createTempDirectory(temp,"case-");val base=root.resolve("server").createDirectory();val wanted=root.resolve("wanted").createDirectory()
            file(base,"file-to-dir","old file");file(base,"dir-to-file/old.txt","old child");file(base,"changed","old")
            base.resolve("removed-empty").createDirectory();file(base,"keep","keep")
            file(wanted,"file-to-dir/new.txt","new child");file(wanted,"dir-to-file","new file");file(wanted,"changed","new")
            wanted.resolve("new-empty").createDirectory();file(wanted,"keep","keep")
            val before=inventory(base);val after=inventory(wanted);val stage=incoming(wanted,before,after);val history=root.resolve("backups")
            val action={DeltaTransfer.apply(base,stage,before,after,history,afterStep={step->
                if(failAt==null)steps.add(step)
                if(step==failAt) {if(crash)throw SimulatedCrash() else error("injected I/O failure")}
            })}
            if(failAt==null) {action();assertEquals(after,inventory(base))}
            else {
                if(crash)assertFailsWith<SimulatedCrash>{action()} else assertFailsWith<IllegalStateException>{action()}
                DeltaTransfer.recover(base,history);assertEquals(before,inventory(base))
                DeltaTransfer.recover(base,history);assertEquals(before,inventory(base))
            }
        }
        run(null,false)
        for(step in steps) {run(step,false);run(step,true)}
        assertTrue(steps.any {it.startsWith("rmdir:")});assertTrue(steps.contains("before-commit"))
    }
    private class SimulatedCrash:Error()
    @Test fun `retention bounds new delta backups and preserves historical full backups`() {
        val base=temp.resolve("server").createDirectory();file(base,"changed","0")
        val history=temp.resolve("backups").createDirectory();file(history,"server-legacy/valuable","untouched")
        repeat(5) {i->
            val before=inventory(base);val wanted=Files.createTempDirectory(temp,"new-");file(wanted,"changed","${i+1}")
            val after=inventory(wanted);DeltaTransfer.apply(base,incoming(wanted,before,after),before,after,history)
        }
        assertEquals(3,history.listDirectoryEntries().count {it.name.startsWith("delta-")})
        assertEquals("untouched",history.resolve("server-legacy/valuable").readText())
        assertEquals("5",base.resolve("changed").readText())
    }
    @Test fun `symlinks and incompatible manifest topology are refused`() {
        val base=temp.resolve("server").createDirectory();val empty=RunnerFileManifest(emptyMap(),emptySet())
        val hash="a".repeat(64)
        assertFailsWith<IllegalArgumentException>{DeltaTransfer.validate(RunnerFileManifest(mapOf("file/child" to CloudFile(hash,1)),emptySet()),base)}
        if(!System.getProperty("os.name").startsWith("Windows")) {
            val outside=temp.resolve("outside").createDirectory();file(outside,"important","keep")
            val history=temp.resolve("backups");Files.createSymbolicLink(history,outside)
            assertFailsWith<IllegalArgumentException>{DeltaTransfer.apply(base,temp.resolve("empty").createDirectory(),empty,empty,history)}
            assertEquals("keep",outside.resolve("important").readText())
        }
    }
    @Test fun `byte budget removes an older large delta but keeps the latest backup`() {
        val base=temp.resolve("server").createDirectory();file(base,"data","old")
        val history=temp.resolve("backups").createDirectory()
        val old=history.resolve("delta-${java.util.UUID.randomUUID()}").createDirectory()
        val payload=old.resolve("files/deleted-large.bin");payload.parent.createDirectories()
        // Sparse data exercises logical size accounting without allocating or hashing 11 GiB.
        java.nio.channels.FileChannel.open(payload,java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.StandardOpenOption.SPARSE).use {
            it.position(11L*1024*1024*1024);it.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
        }
        val beforeLarge=RunnerFileManifest(mapOf("deleted-large.bin" to CloudFile("a".repeat(64),payload.fileSize())),emptySet())
        com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValue(old.resolve("transaction.json").toFile(),
            DeltaJournal(base=base.toRealPath().toString(),before=beforeLarge,after=RunnerFileManifest(emptyMap(),emptySet()),phase="COMMITTED",createdAt=0))
        val wanted=temp.resolve("wanted").createDirectory();file(wanted,"data","new")
        val before=inventory(base);val after=inventory(wanted)
        val newest=assertNotNull(DeltaTransfer.apply(base,incoming(wanted,before,after),before,after,history))
        assertFalse(old.exists());assertEquals("old",newest.resolve("files/data").readText())
    }
}
