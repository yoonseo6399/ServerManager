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
    fun collect(base:Path,onProgress:(Int,Long)->Unit={_,_->}):Pair<Map<String,CloudFile>,Set<String>> {
        val files=linkedMapOf<String,CloudFile>()
        val dirs=linkedSetOf<String>()
        var bytes=0L
        Files.walk(base).use { paths -> paths.forEach { path ->
            if(path==base) return@forEach
            require(!Files.isSymbolicLink(path)) { "심볼릭 링크가 있어 동기화를 중단했습니다" }
            val relative=base.relativize(path).toString().replace('\\','/')
            safePath(base,relative)
            if(path.isDirectory()) dirs.add(relative)
            else if(path.isRegularFile()) {val size=path.fileSize();files[relative]=CloudFile(digest(path),size);bytes+=size;onProgress(files.size,bytes)}
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
