package local.servermanager

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ResourceSample(val pid: Long, val cpuPercent: Double?, val rssMb: Double?, val uptimeSeconds: Long?, val sampledAt: String, val cpuSource: String = "unavailable")

class ProcessMetrics {
    private data class Cpu(val at: Long, val cpu: Long)
    private val previous = ConcurrentHashMap<Long, Cpu>()
    fun sample(handle: ProcessHandle): ResourceSample {
        val info = handle.info()
        val now = System.nanoTime()
        val windows=if(System.getProperty("os.name").startsWith("Windows")) windowsSample(handle.pid()) else null
        val cpu = info.totalCpuDuration().orElse(null)?.toNanos() ?: windows?.first?.let { (it*1_000_000_000).toLong() }
        val old = if (cpu != null) previous.put(handle.pid(), Cpu(now, cpu)) else null
        val deltaPercent = if (cpu != null && old != null && now > old.at) maxOf(0.0, 100.0 * (cpu-old.cpu)/(now-old.at)) else null
        val ps = if(windows==null && !System.getProperty("os.name").startsWith("Windows")) runCatching {
            val builder = ProcessBuilder("/bin/ps", "-o", "%cpu=,rss=", "-p", handle.pid().toString())
            builder.environment()["LC_ALL"] = "C"
            val process = builder.start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); null }
            else if (process.exitValue() == 0) parsePs(process.inputStream.bufferedReader().readText()) else null
        }.getOrNull() else null
        val percent = deltaPercent ?: ps?.first
        val uptime = info.startInstant().orElse(null)?.let { java.time.Duration.between(it, Instant.now()).seconds }
        return ResourceSample(handle.pid(), percent, windows?.second ?: ps?.second, uptime, Instant.now().toString(), if(deltaPercent!=null) "cpu-time-delta" else if(ps!=null) "ps" else "unavailable")
    }
    private fun windowsSample(pid:Long):Pair<Double,Double>? = runCatching {
        val command="\$p=Get-Process -Id $pid -ErrorAction Stop; \$c=if(\$null -eq \$p.CPU){0}else{\$p.CPU}; Write-Output (\$c.ToString([System.Globalization.CultureInfo]::InvariantCulture)+' '+\$p.WorkingSet64)"
        val process=ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",command).start()
        if(!process.waitFor(3,TimeUnit.SECONDS)) { process.destroyForcibly();null }
        else if(process.exitValue()!=0) null
        else process.inputStream.bufferedReader().readText().trim().split(Regex("\\s+")).takeIf { it.size==2 }?.let { values ->
            val seconds=values[0].toDoubleOrNull();val bytes=values[1].toDoubleOrNull()
            if(seconds!=null && bytes!=null && seconds.isFinite() && bytes.isFinite() && seconds>=0 && bytes>=0) seconds to bytes/(1024*1024) else null
        }
    }.getOrNull()
    fun retain(pids: Set<Long>) { previous.keys.retainAll(pids) }
    companion object {
        fun parsePs(text: String): Pair<Double,Double>? {
            val parts=text.trim().split(Regex("\\s+"))
            if(parts.size!=2)return null
            val cpu=parts[0].toDoubleOrNull() ?: return null
            val rss=parts[1].toDoubleOrNull() ?: return null
            return if(cpu.isFinite() && rss.isFinite() && cpu>=0 && rss>=0) cpu to rss/1024 else null
        }
    }
}
