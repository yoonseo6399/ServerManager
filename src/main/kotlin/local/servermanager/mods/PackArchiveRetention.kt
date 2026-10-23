package local.servermanager

import java.nio.file.*
import java.time.Instant
import kotlin.io.path.*

/** Only UUID-owned upload directories are eligible. Never traverse links or remove active work. */
object PackArchiveRetention {
    const val TTL_SECONDS=86400L
    const val MAX_ARCHIVES=8
    private val token=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    fun sweep(root:Path,active:Set<String>,now:Instant=Instant.now()) {
        if(!root.isDirectory(LinkOption.NOFOLLOW_LINKS))return
        root.listDirectoryEntries().filter {token.matches(it.name)&&it.name !in active&&!Files.isSymbolicLink(it)&&it.isDirectory(LinkOption.NOFOLLOW_LINKS)}.forEach {dir->
            val pack=dir.resolve("pack.mrpack")
            val stamp=Files.getLastModifiedTime(if(Files.exists(pack,LinkOption.NOFOLLOW_LINKS))pack else dir,LinkOption.NOFOLLOW_LINKS).toInstant()
            if(!now.isBefore(stamp.plusSeconds(TTL_SECONDS)))Files.walk(dir).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}
        }
    }
    fun requireCapacity(root:Path) {
        val count=root.listDirectoryEntries().count {token.matches(it.name)}
        require(count<MAX_ARCHIVES) {"보관 중인 팩 미리보기가 ${MAX_ARCHIVES}개입니다. 만료된 업로드는 24시간 후 자동 정리됩니다. 기존 팩을 가져오거나 나중에 다시 시도하세요."}
        require(Files.getFileStore(root).usableSpace>PackFormat.MAX_FILE+128L*1024*1024) {"팩 업로드를 위한 디스크 여유 공간이 부족합니다"}
    }
}
