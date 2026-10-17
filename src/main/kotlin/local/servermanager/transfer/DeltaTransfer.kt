package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlin.io.path.*

internal data class DeltaJournal(val protocol:Int=1,val base:String,val before:RunnerFileManifest,val after:RunnerFileManifest,
    val phase:String="PREPARED",val createdAt:Long=System.currentTimeMillis())

/** A stopped-server transaction. Unchanged files never move or share mutable backup inodes. */
object DeltaTransfer {
    private val mapper=jacksonObjectMapper()
    private const val RETAIN_COUNT=3
    private const val RETAIN_BYTES=10L*1024*1024*1024
    private val name=Regex("delta-[a-f0-9-]{36}")

    fun validate(manifest:RunnerFileManifest,base:Path) {
        require(manifest.files.size<=100000 && manifest.directories.size<=100000)
        require(manifest.files.keys.intersect(manifest.directories).isEmpty())
        (manifest.files.keys+manifest.directories).forEach { relative ->
            FileInventory.safePath(base,relative)
            require(relative.split('/').none {it.isBlank() || it=="."}) {"잘못된 파일 목록"}
            var parent=relative.substringBeforeLast('/',"")
            while(parent.isNotEmpty()) {
                require(parent in manifest.directories) {"파일 목록의 부모 폴더가 없습니다: $relative"}
                parent=parent.substringBeforeLast('/',"")
            }
        }
        manifest.files.values.forEach {require(it.size in 0..1024L*1024*1024*1024 && it.sha256.matches(Regex("[a-f0-9]{64}")))}
    }
    private fun safe(base:Path,relative:String):Path {
        val path=FileInventory.safePath(base,relative)
        var p:Path?=path
        while(p!=null && p.startsWith(base)) {require(!Files.isSymbolicLink(p)) {"전송 경로에 심볼릭 링크가 있습니다"};p=p.parent}
        return path
    }
    private fun changed(before:RunnerFileManifest,after:RunnerFileManifest)=after.files.filter {(p,f)->before.files[p]!=f}.keys
    private fun removed(before:RunnerFileManifest,after:RunnerFileManifest)=before.files.filter {(p,f)->after.files[p]!=f}.keys
    private fun journal(folder:Path,value:DeltaJournal) {
        val tmp=Files.createTempFile(folder,".journal-",".tmp")
        try {
            FileChannel.open(tmp,WRITE).use {channel->
                val encoded=mapper.writeValueAsBytes(value)
                require(encoded.size<=64*1024*1024) {"전송 복구 기록이 너무 큽니다"}
                val bytes=ByteBuffer.wrap(encoded);while(bytes.hasRemaining())channel.write(bytes);channel.force(true)
            }
            Files.move(tmp,folder.resolve("transaction.json"),ATOMIC_MOVE,REPLACE_EXISTING)
        } finally {tmp.deleteIfExists()}
    }
    private fun read(folder:Path,base:Path):DeltaJournal {
        require(!Files.isSymbolicLink(folder) && folder.isDirectory(NOFOLLOW_LINKS))
        val file=safe(folder,"transaction.json")
        require(file.isRegularFile(NOFOLLOW_LINKS) && file.fileSize()<=64L*1024*1024) {"전송 복구 기록을 확인하세요"}
        return mapper.readValue<DeltaJournal>(file.toFile()).also {
            require(it.protocol==1 && it.base==base.toRealPath().toString() && it.phase in setOf("PREPARED","COMMITTED","ROLLED_BACK")) {"전송 복구 경로 또는 상태가 다릅니다"}
            validate(it.before,base);validate(it.after,base)
        }
    }
    fun recover(base:Path,history:Path) {
        if(!history.exists())return
        require(history.isDirectory(NOFOLLOW_LINKS) && !Files.isSymbolicLink(history))
        history.listDirectoryEntries().filter {name.matches(it.name)}.sortedBy {it.name}.forEach {folder->
            // An empty directory can precede the first durable journal; no server file moved yet.
            if(!folder.resolve("transaction.json").exists()) {
                require(!Files.isSymbolicLink(folder) && folder.isDirectory(NOFOLLOW_LINKS))
                require(folder.listDirectoryEntries().all {it.name.startsWith(".journal-") && it.isRegularFile(NOFOLLOW_LINKS)}) {"전송 복구 기록이 없습니다"}
                removeTree(folder)
            } else {
                val record=read(folder,base)
                if(record.phase=="PREPARED")rollback(base,folder,record)
            }
        }
    }
    /** incoming contains only changed files on the same volume. Caller excludes start/write/import. */
    fun apply(base:Path,incoming:Path,before:RunnerFileManifest,after:RunnerFileManifest,history:Path,
        checkAllowed:()->Unit={},afterStep:(String)->Unit={}):Path? {
        validate(before,base);validate(after,base)
        require(!Files.isSymbolicLink(base) && !Files.isSymbolicLink(incoming))
        recover(base,history)
        // Transfer-time checks compare names and sizes only; re-hashing the whole tree with SHA-256 is intentionally skipped.
        check(runCatching {checkShape(base,before)}.isSuccess) {"전송 중 원래 파일이 변경되어 반영하지 않았습니다"}
        val changes=changed(before,after)
        val uploads=Files.walk(incoming).use {paths->paths.filter {it.isRegularFile(NOFOLLOW_LINKS)}.toList().associate {incoming.relativize(it).toString().replace('\\','/') to it.fileSize()}}
        check(uploads==after.files.filterKeys {it in changes}.mapValues {it.value.size}) {"변경 파일의 목록·크기가 일치하지 않습니다"}
        checkAllowed()
        if(before==after)return null
        history.createDirectories()
        require(!Files.isSymbolicLink(history) && Files.getFileStore(base)==Files.getFileStore(history) && Files.getFileStore(base)==Files.getFileStore(incoming)) {"전송 작업 폴더는 서버와 같은 볼륨에 있어야 합니다"}
        val folder=history.resolve("delta-${UUID.randomUUID()}").also {it.createDirectory()}
        val record=DeltaJournal(base=base.toRealPath().toString(),before=before,after=after)
        journal(folder,record)
        try {
            removed(before,after).sorted().forEach {relative->
                val backup=safe(folder,"files/$relative");backup.parent.createDirectories()
                Files.move(safe(base,relative),backup,ATOMIC_MOVE);afterStep("backup:$relative")
            }
            (before.directories-after.directories).sortedByDescending {it.count {c->c=='/'}}.forEach {relative->
                Files.delete(safe(base,relative));afterStep("rmdir:$relative")
            }
            after.directories.sortedBy {it.count {c->c=='/'}}.forEach {safe(base,it).createDirectories()}
            changes.sorted().forEach {relative->
                Files.move(safe(incoming,relative),safe(base,relative),ATOMIC_MOVE);afterStep("install:$relative")
            }
            checkShape(base,after)
            checkAllowed();afterStep("before-commit")
            journal(folder,record.copy(phase="COMMITTED"))
        } catch(e:Exception) {
            try {rollback(base,folder,record)} catch(failure:Exception) {e.addSuppressed(failure);throw IllegalStateException("파일 반영 복구가 필요합니다. 서버를 시작하지 말고 전송 복구 기록을 확인하세요",e)}
            throw e
        }
        // Retention failure must never turn a committed handoff into a failed transfer.
        runCatching {prune(base,history,folder)}.onFailure {System.err.println("전송 백업 정리 보류: ${it.javaClass.simpleName}")}
        return folder
    }
    private fun checkShape(base:Path,manifest:RunnerFileManifest) {
        val files=mutableMapOf<String,Long>();val dirs=mutableSetOf<String>()
        Files.walk(base).use {paths->paths.forEach {p->if(p!=base) {
            val relative=base.relativize(p).toString().replace('\\','/');safe(base,relative)
            if(p.isDirectory(NOFOLLOW_LINKS))dirs.add(relative) else {require(p.isRegularFile(NOFOLLOW_LINKS));files[relative]=p.fileSize()}
        }}}
        check(dirs==manifest.directories && files==manifest.files.mapValues {it.value.size}) {"반영된 파일 목록이 다릅니다"}
    }
    private fun rollback(base:Path,folder:Path,record:DeltaJournal) {
        changed(record.before,record.after).sortedByDescending {it.count {c->c=='/'}}.forEach {relative->
            val target=safe(base,relative)
            val saved=safe(folder,"files/$relative")
            if((relative !in record.before.files || saved.exists()) && target.isRegularFile(NOFOLLOW_LINKS)) {
                check(target.fileSize()==record.after.files.getValue(relative).size) {"복구 대상에 외부 변경이 있습니다: $relative"}
                Files.delete(target)
            }
        }
        (record.after.directories-record.before.directories).sortedByDescending {it.count {c->c=='/'}}.forEach {relative->
            val p=safe(base,relative);if(p.isDirectory(NOFOLLOW_LINKS))Files.delete(p)
        }
        record.before.directories.sortedBy {it.count {c->c=='/'}}.forEach {safe(base,it).createDirectories()}
        removed(record.before,record.after).sorted().forEach {relative->
            val saved=safe(folder,"files/$relative")
            if(saved.exists()) {val target=safe(base,relative);check(!target.exists()) {"복구 경로가 이미 사용 중입니다: $relative"};Files.move(saved,target,ATOMIC_MOVE)}
        }
        checkShape(base,record.before)
        journal(folder,record.copy(phase="ROLLED_BACK"))
    }
    private fun prune(base:Path,history:Path,newest:Path) {
        val candidates=history.listDirectoryEntries().filter {name.matches(it.name) && it.resolve("transaction.json").exists()}
            .map {it to read(it,base)}.filter {it.second.phase!="PREPARED"}
            .sortedWith(compareByDescending<Pair<Path,DeltaJournal>> {it.first==newest}.thenByDescending {it.second.createdAt})
        var bytes=0L;var count=0
        candidates.forEach {(folder,_)->
            val size=Files.walk(folder).use {paths->paths.filter {it.isRegularFile(NOFOLLOW_LINKS)}.mapToLong {it.fileSize()}.sum()}
            // Always retain the newest recovery copy even if a single changed file exceeds the budget.
            if(count==0 || (count<RETAIN_COUNT && size<=RETAIN_BYTES-bytes)) {bytes+=size;count++}
            else removeTree(folder)
        }
    }
    private fun removeTree(folder:Path) {Files.walk(folder).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.delete(it)}}}
}
