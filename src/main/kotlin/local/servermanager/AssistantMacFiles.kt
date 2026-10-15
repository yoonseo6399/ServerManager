package local.servermanager

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import java.nio.file.Path
import java.util.UUID

/** Darwin 64-bit descriptor API. No cwd changes, shell, path-based fallback or link following. */
internal class AssistantMacDirectory private constructor(private val fd:Int):AutoCloseable {
    private interface LibC:Library {
        fun open(path:String,flags:Int,mode:Int):Int
        fun openat(dir:Int,path:String,flags:Int,mode:Int):Int
        fun close(fd:Int):Int
        fun fstat64(fd:Int,stat:Memory):Int
        fun read(fd:Int,buffer:com.sun.jna.Pointer,count:NativeLong):NativeLong
        fun write(fd:Int,buffer:com.sun.jna.Pointer,count:NativeLong):NativeLong
        fun fchmod(fd:Int,mode:Int):Int
        fun fsync(fd:Int):Int
        fun renameatx_np(from:Int,source:String,to:Int,destination:String,flags:Int):Int
        fun unlinkat(dir:Int,name:String,flags:Int):Int
    }
    data class Stat(val device:Int,val inode:Long,val mode:Int,val links:Int,val size:Long)
    data class Contents(val bytes:ByteArray,val stat:Stat)
    companion object {
        // Apple xnu sys/fcntl.h and sys/stdio.h, Darwin arm64/x86_64 ABI.
        private const val NOFOLLOW=0x100;private const val DIRECTORY=0x100000;private const val CLOEXEC=0x1000000
        private const val NONBLOCK=4;private const val CREATE=0x200;private const val EXCLUSIVE=0x800
        private const val SWAP=2;private const val EXCL=4
        private val libc:LibC by lazy {Native.load("System",LibC::class.java)}
        private fun checked(value:Int):Int {check(value>=0) {"안전한 파일 접근 실패 (errno ${Native.getLastError()}). 링크·권한·외부 변경을 확인하세요."};return value}
        fun open(path:Path):AssistantMacDirectory {
            require(path.isAbsolute && path==path.normalize())
            var fd=checked(libc.open("/",DIRECTORY or NOFOLLOW or CLOEXEC,0))
            try {
                path.forEach {part->require(part.toString() !in setOf(".",".."));val child=checked(libc.openat(fd,part.toString(),DIRECTORY or NOFOLLOW or CLOEXEC,0));libc.close(fd);fd=child}
                return AssistantMacDirectory(fd)
            } catch(e:Exception) {libc.close(fd);throw e}
        }
        private fun stat(fd:Int):Stat {
            // struct stat64 is 144 bytes on supported Darwin64 ABIs; overallocate defensively.
            val data=Memory(256);checked(libc.fstat64(fd,data))
            return Stat(data.getInt(0),data.getLong(8),data.getShort(4).toInt() and 0xffff,data.getShort(6).toInt() and 0xffff,data.getLong(96))
        }
    }
    private fun name(value:String) {require(value.isNotEmpty() && !value.contains('/') && !value.contains('\u0000') && value !in setOf(".",".."))}
    fun read(name:String):Contents {
        name(name);val file=checked(libc.openat(fd,name,NOFOLLOW or CLOEXEC or NONBLOCK,0))
        try {
            val metadata=stat(file)
            require(metadata.mode and 0xf000==0x8000 && metadata.links==1 && metadata.size in 0..49152) {"링크가 없는 일반 텍스트 파일(최대 48KB)만 허용합니다."}
            val buffer=Memory(49153);var total=0
            while(total<49153) {val count=libc.read(file,buffer.share(total.toLong()),NativeLong((49153-total).toLong())).toLong();check(count>=0);if(count==0L)break;total+=count.toInt()}
            require(total<=49152);return Contents(buffer.getByteArray(0,total),metadata)
        } finally {libc.close(file)}
    }
    private fun create(name:String,bytes:ByteArray,mode:Int) {
        name(name);val file=checked(libc.openat(fd,name,1 or CREATE or EXCLUSIVE or NOFOLLOW or CLOEXEC,0x180)) // 0600 until complete
        try {
            val buffer=Memory(bytes.size.coerceAtLeast(1).toLong());buffer.write(0,bytes,0,bytes.size);var written=0
            while(written<bytes.size) {val count=libc.write(file,buffer.share(written.toLong()),NativeLong((bytes.size-written).toLong())).toLong();check(count>0);written+=count.toInt()}
            checked(libc.fchmod(file,mode and 0x1ff));checked(libc.fsync(file))
        } finally {libc.close(file)}
    }
    fun apply(leaf:String,before:String,replacement:ByteArray):String {
        name(leaf);val original=read(leaf);check(AssistantWorkspace.digest(original.bytes)==before) {"반영 직전 파일이 변경되었습니다."}
        val backup=".assistant-backup-${UUID.randomUUID()}"
        create(backup,replacement,original.stat.mode)
        var exchanged=false
        try {
            checked(libc.renameatx_np(fd,backup,fd,leaf,SWAP));exchanged=true
            val displaced=read(backup)
            check(displaced.stat.inode==original.stat.inode && displaced.stat.device==original.stat.device && AssistantWorkspace.digest(displaced.bytes)==before) {"반영 직전 원본이 교체되었습니다."}
            checked(libc.fsync(fd));return backup
        } catch(e:Exception) {
            if(exchanged) {
                // Swapping links never follows them. Restore only while our replacement still occupies the leaf.
                if(runCatching {read(leaf).bytes.contentEquals(replacement)}.getOrDefault(false))runCatching {checked(libc.renameatx_np(fd,backup,fd,leaf,SWAP))}
            } else runCatching {checked(libc.unlinkat(fd,backup,0))}
            throw IllegalStateException("파일 반영 충돌입니다. 복구 사본 $backup 을 확인하세요. ${e.message}",e)
        }
    }
    fun rollback(leaf:String,backup:String,replacement:ByteArray) {
        if(read(leaf).bytes.contentEquals(replacement))checked(libc.renameatx_np(fd,backup,fd,leaf,SWAP))
    }
    override fun close() {libc.close(fd)}
}
