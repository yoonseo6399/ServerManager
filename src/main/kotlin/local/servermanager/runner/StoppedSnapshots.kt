package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.*
import kotlin.io.path.*

data class SnapshotReady(val generation:String,val runToken:String?,val exit:RunnerExitReport?,val localRevision:String?,val editOnly:Boolean=false)
data class StoppedSnapshot(val ready:SnapshotReady,val path:Path)
/** Private, immutable stopped copies; never hard-link files that a restarted game may modify. */
class StoppedSnapshots(private val root:Path) {
    private val mapper=jacksonObjectMapper()
    companion object {fun valid(generation:String)=Regex("[0-9]{19}-[0-9a-f-]{36}").matches(generation) && runCatching {java.util.UUID.fromString(generation.substring(20))}.isSuccess}
    @Synchronized fun list():List<StoppedSnapshot> {
        if(!root.exists())return emptyList()
        require(!Files.isSymbolicLink(root))
        return root.listDirectoryEntries().filter {valid(it.name)}.sortedBy {it.name}.map {dir->
            require(dir.isDirectory(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir))
            val descriptor=dir.resolve("ready.json");require(descriptor.isRegularFile(LinkOption.NOFOLLOW_LINKS) && descriptor.fileSize()<65536)
            StoppedSnapshot(mapper.readValue<SnapshotReady>(descriptor.readText()).also {require(it.generation==dir.name)},dir)
        }
    }
    @Synchronized fun capture(source:Path,runToken:String?,exit:RunnerExitReport?,revision:String?,editOnly:Boolean=false):StoppedSnapshot {
        val before=FileInventory.snapshot(source)
        root.createDirectories();require(!Files.isSymbolicLink(root));check(list().size<32) {"미완료 종료 복사본이 32개입니다. 전송을 완료하세요"}
        val bytes=Files.walk(source).use {paths->paths.filter {it.isRegularFile(LinkOption.NOFOLLOW_LINKS)}.mapToLong {it.fileSize()}.sum()}
        check(Files.getFileStore(root).usableSpace>minOf(bytes,512L*1024*1024)+128L*1024*1024) {"종료 복사본 공간이 부족합니다"}
        val serial=maxOf(System.currentTimeMillis(),(list().lastOrNull()?.ready?.generation?.take(19)?.toLongOrNull() ?: 0)+1)
        val generation="%019d-%s".format(serial,java.util.UUID.randomUUID())
        val stage=Files.createTempDirectory(root,".capturing-");val target=stage.resolve("server").createDirectory()
        try {
            // Darwin cp -c requests APFS copy-on-write. Other filesystems use a regular stopped copy.
            val cloned=if(System.getProperty("os.name").contains("Mac"))runCatching {
                val process=ProcessBuilder("/bin/cp","-cR",source.toAbsolutePath().toString()+"/.",target.toString()).redirectErrorStream(true).start()
                process.inputStream.use {it.copyTo(java.io.OutputStream.nullOutputStream())};process.waitFor()==0
            }.getOrDefault(false) else false
            if(!cloned) {
                target.toFile().deleteRecursively();target.createDirectory()
                Files.walk(source).use {paths->paths.forEach {p->
                    require(!Files.isSymbolicLink(p));val to=target.resolve(source.relativize(p))
                    if(p.isDirectory(LinkOption.NOFOLLOW_LINKS))to.createDirectories() else {require(p.isRegularFile(LinkOption.NOFOLLOW_LINKS));Files.copy(p,to,StandardCopyOption.COPY_ATTRIBUTES)}
                }}
            }
            check(FileInventory.snapshot(source)==before) {"종료 복사 중 원본이 변경됐습니다. 원본을 보존했습니다"}
            // Also reject copied links/special files before publishing this copy for restart.
            FileInventory.snapshot(target)
            val ready=SnapshotReady(generation,runToken,exit,revision,editOnly)
            atomicText(stage.resolve("ready.json"),mapper.writeValueAsString(ready))
            val destination=root.resolve(generation);Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE)
            return StoppedSnapshot(ready,destination)
        } finally {if(stage.exists())stage.toFile().deleteRecursively()}
    }
    @Synchronized fun remove(snapshot:StoppedSnapshot) {require(snapshot.path.parent==root && valid(snapshot.path.name));snapshot.path.toFile().deleteRecursively()}
}
