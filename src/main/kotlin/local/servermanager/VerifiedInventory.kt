package local.servermanager

import java.nio.file.Path
import java.util.concurrent.*

/** Hashing never runs on an HTTP thread. Results are memory-only and revalidated before use. */
class VerifiedInventory(
    private val collect:(Path)->RunnerFileManifest={path->FileInventory.collect(path).let {(f,d)->RunnerFileManifest(f,d)}},
    private val maxEntries:Int=4,
    private val maxPaths:Int=200_000
):AutoCloseable {
    private data class Verified(val path:Path,val context:String,val stamp:Map<String,String>,val manifest:RunnerFileManifest)
    private val pool=ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,ArrayBlockingQueue(8),{r->Thread(r,"transfer-inventory").apply {isDaemon=true}})
    private val jobs=linkedMapOf<String,Future<Verified>>()
    private val monitor=Any()
    /** null means a single background verification is pending; poll without restarting it. */
    fun request(id:String,path:Path,context:()->String):RunnerFileManifest? {
        val expected=context()
        val future=synchronized(monitor) {
            jobs[id] ?: run {
                if(jobs.size>=maxEntries) {
                    val oldest=jobs.entries.firstOrNull {it.value.isDone} ?: return null
                    jobs.remove(oldest.key)
                }
                val job=FutureTask {
                    check(context()==expected) {"파일 검증 중 실행 상태가 변경됐습니다"}
                    val before=FileInventory.snapshot(path,maxPaths)
                    val manifest=collect(path)
                    check(manifest.files.size+manifest.directories.size<=maxPaths)
                    check(FileInventory.snapshot(path,maxPaths)==before && context()==expected) {"파일 검증 중 원본이 변경됐습니다. 다시 시도하세요"}
                    Verified(path,expected,before,manifest)
                }
                try {pool.execute(job)}catch(_:RejectedExecutionException){return null}
                jobs[id]=job;job
            }
        }
        if(!future.isDone)return null
        val result=try {future.get()}catch(e:ExecutionException){synchronized(monitor){jobs.remove(id,future)};throw e.cause ?: e}
        // Include the root identity, nanosecond FileTime, size and inode. Symlinks and
        // nonregular entries fail closed. No transfer lock is held during this walk.
        if(result.path!=path || result.context!=expected ||
            FileInventory.snapshot(path,maxPaths)!=result.stamp || context()!=expected) {
            synchronized(monitor){jobs.remove(id,future)}
            return null
        }
        return result.manifest
    }
    fun invalidate(id:String) {synchronized(monitor){jobs.remove(id)?.cancel(true)}}
    override fun close(){pool.shutdownNow();synchronized(monitor){jobs.clear()}}
}
