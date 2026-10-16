package local.servermanager

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*

object FileInventory {
    fun safePath(base:Path,relative:String):Path {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.contains('\\'))
        val path=base.resolve(relative).normalize()
        require(path.startsWith(base) && path!=base && !relative.split('/').contains("..")) { "잘못된 동기화 경로" }
        return path
    }
    fun digest(path:Path):String {
        val hash=MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { stream ->
            val buffer=ByteArray(1024*1024)
            while(true) { if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("파일 검증이 중단됐습니다");val count=stream.read(buffer);if(count<0)break;hash.update(buffer,0,count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private data class Stamp(val size:Long,val modified:java.nio.file.attribute.FileTime,val key:String,val changed:String)
    private val hashCache=java.util.concurrent.ConcurrentHashMap<String,Pair<Stamp,String>>()
    private const val CACHE_LIMIT=500_000
    /** A file counts as unchanged only when size, nanosecond mtime, inode and (where available) ctime all match the stamp taken before hashing. */
    private fun stamp(path:Path):Stamp {
        val a=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val changed=if(path.fileSystem.supportedFileAttributeViews().contains("unix"))Files.getAttribute(path,"unix:ctime",java.nio.file.LinkOption.NOFOLLOW_LINKS).toString() else a.creationTime().toString()
        return Stamp(a.size(),a.lastModifiedTime(),a.fileKey()?.toString() ?: "",changed)
    }
    /**
     * Digest for scans only. Reuses the previous hash of an untouched file so a restart/mod toggle does not re-read the whole world.
     * Files modified within the last two seconds are never cached (racy-mtime guard); integrity checks keep using [digest].
     */
    /** How many files of one scan reused an earlier hash and how many had to be read. */
    class ScanStats {val reused=java.util.concurrent.atomic.AtomicInteger();val hashed=java.util.concurrent.atomic.AtomicInteger()}
    fun cachedDigest(path:Path,stats:ScanStats?=null):String {
        val before=stamp(path);val key=path.toAbsolutePath().normalize().toString()
        hashCache[key]?.let {(old,sha)->if(old==before){stats?.reused?.incrementAndGet();return sha}}
        stats?.hashed?.incrementAndGet()
        val sha=digest(path)
        val after=stamp(path)
        if(before==after && System.currentTimeMillis()-after.modified.toMillis()>2000) {
            if(hashCache.size>=CACHE_LIMIT)hashCache.clear()
            hashCache[key]=after to sha
        } else hashCache.remove(key)
        return sha
    }
    /** Same walk and safety checks as [collect], but untouched files are not hashed again. Use only for scans, never for post-transfer verification. */
    fun collectCached(base:Path,stats:ScanStats?=null,onProgress:(Int,Long)->Unit={_,_->}):Pair<Map<String,CloudFile>,Set<String>> = walk(base,onProgress) {cachedDigest(it,stats)}
    fun collect(base:Path,onProgress:(Int,Long)->Unit={_,_->}):Pair<Map<String,CloudFile>,Set<String>> = walk(base,onProgress,::digest)
    private fun walk(base:Path,onProgress:(Int,Long)->Unit,hash:(Path)->String):Pair<Map<String,CloudFile>,Set<String>> {
        val files=linkedMapOf<String,CloudFile>()
        val dirs=linkedSetOf<String>()
        var bytes=0L
        Files.walk(base).use { paths -> paths.forEach { path ->
            if(path==base) return@forEach
            require(!Files.isSymbolicLink(path)) { "심볼릭 링크가 있어 동기화를 중단했습니다" }
            val relative=base.relativize(path).toString().replace('\\','/')
            safePath(base,relative)
            if(path.isDirectory()) dirs.add(relative)
            else if(path.isRegularFile()) {val size=path.fileSize();files[relative]=CloudFile(hash(path),size);bytes+=size;onProgress(files.size,bytes)}
            else error("일반 파일이 아닌 항목이 있어 동기화를 중단했습니다")
        } }
        return files to dirs
    }
    fun snapshot(base:Path,maxPaths:Int=Int.MAX_VALUE):Map<String,String> = Files.walk(base).use { paths ->
        val result=linkedMapOf<String,String>()
        val unix=base.fileSystem.supportedFileAttributeViews().contains("unix")
        paths.forEach {path->
            require(result.size<maxPaths+1L) {"파일 목록이 검증 한도를 초과했습니다"}
            val a=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,java.nio.file.LinkOption.NOFOLLOW_LINKS)
            require(!a.isSymbolicLink && (a.isDirectory || a.isRegularFile)) {"심볼릭 링크 또는 일반 파일이 아닌 항목이 있어 동기화를 중단했습니다"}
            val changed=if(unix)Files.getAttribute(path,"unix:ctime",java.nio.file.LinkOption.NOFOLLOW_LINKS) else null
            result[base.relativize(path).toString()]="${a.isDirectory}|${a.size()}|${a.lastModifiedTime()}|${a.fileKey()}|$changed"
        }
        result
    }
}
