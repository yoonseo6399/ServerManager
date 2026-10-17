package local.servermanager

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

data class StopWatchStatus(val remainingSeconds:Long,val lastProgress:String,val forced:Boolean=false)

/** Uses monotonic time. Only distinct lifecycle milestones or actual world writes extend the deadline. */
internal class StopProgressClock(now:Long) {
    private var last=now
    private val milestones=linkedSetOf<String>()
    private var detail="정상 종료 신호 전송"
    private val progress=Regex("(?i)(Saving (?:players|worlds|chunks for level .+)|ThreadedAnvilChunkStorage.+(?:saved|closed)|All (?:chunks|dimensions) are saved|Unloading (?:world|dimension).+)")
    @Synchronized fun log(line:String,now:Long) {
        if(line.contains("ERROR")||line.contains("Exception"))return
        val milestone=progress.find(line)?.value ?: return
        if(milestones.size<1024 && milestones.add(milestone)){last=now;detail=milestone.take(200)}
    }
    @Synchronized fun worldWrite(now:Long){last=now;detail="월드 저장 파일 쓰기 감지"}
    @Synchronized fun status(now:Long)=StopWatchStatus(((180_000-(now-last)).coerceAtLeast(0)+999)/1000,detail)
}

/** Watches only world directories; never hashes/copies/traverses region file contents. */
private class WorldSaveWrites(root:Path):AutoCloseable {
    private val watch=root.fileSystem.newWatchService()
    init {
        val properties=Properties();runCatching {root.resolve("server.properties").inputStream().use(properties::load)}
        val configured=root.resolve(properties.getProperty("level-name","world")).normalize()
        val roots=linkedSetOf<Path>()
        if(configured.startsWith(root.normalize()))roots.add(configured)
        runCatching {Files.list(root).use {paths->paths.limit(128).filter {Files.isDirectory(it,LinkOption.NOFOLLOW_LINKS)&&it.resolve("level.dat").isRegularFile()}.forEach {roots.add(it)}}}
        var count=0
        roots.filter {Files.isDirectory(it,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(it)}.forEach {world->
            Files.walkFileTree(world,emptySet(),7,object:SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir:Path,attrs:BasicFileAttributes):FileVisitResult {
                    if(++count>512||Files.isSymbolicLink(dir))return FileVisitResult.SKIP_SUBTREE
                    if(dir==world || dir.fileName.toString() in setOf("region","entities","poi")) {
                        dir.register(watch,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_CREATE)
                        if(dir!=world)return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file:Path,exc:java.io.IOException)=FileVisitResult.SKIP_SUBTREE
            })
        }
    }
    fun changed():Boolean {
        var written=false
        while(true){val key=watch.poll() ?: break
            key.pollEvents().forEach {e->val name=e.context()?.toString().orEmpty();if(e.kind()!=StandardWatchEventKinds.OVERFLOW&&(name.endsWith(".mca")||name in setOf("level.dat","level.dat_new","level.dat_old")))written=true}
            key.reset()
        }
        return written
    }
    override fun close(){watch.close()}
}

internal class StopMonitor(private val process:Process,root:Path,private val current:()->Boolean,private val time:()->Long={now()},private val timeout:()->Unit):AutoCloseable {
    private val clock=StopProgressClock(time())
    private val writes=runCatching {WorldSaveWrites(root)}.getOrNull()
    @Volatile private var done=false
    @Volatile private var timedOut=false
    private val task=executor.scheduleWithFixedDelay({runCatching {tick()}},1,1,TimeUnit.SECONDS)
    fun log(line:String){if(!done)clock.log(line,time())}
    fun status()=clock.status(time()).copy(forced=timedOut)
    internal fun tick() {
        if(done)return
        if(!process.isAlive||!current()){close();return}
        if(writes?.changed()==true)clock.worldWrite(time())
        if(clock.status(time()).remainingSeconds==0L){close();if(process.isAlive&&current()){timedOut=true;timeout()}}
    }
    override fun close(){done=true;task.cancel(false);runCatching {writes?.close()}}
    companion object {
        private val executor=ScheduledThreadPoolExecutor(1){r->Thread(r,"minecraft-stop-watchdog").apply {isDaemon=true}}.apply {removeOnCancelPolicy=true}
        private fun now()=System.nanoTime()/1_000_000
    }
}
