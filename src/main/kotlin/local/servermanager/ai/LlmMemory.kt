package local.servermanager

import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** Sampled on the inference computer. OS use and backend allocation are different measurements. */
data class LlmMemory(val totalBytes:Long?=null,val usedBytes:Long?=null,val swapUsedBytes:Long?=null,val pressure:String?=null,val modelBytes:Long?=null,val modelMetric:String?=null,val wiredLimitMb:Long?=null,val sampledAt:Long=System.currentTimeMillis()) {
    fun compact():String {
        fun gib(n:Long)=String.format(java.util.Locale.ROOT,"%.1f",n/1073741824.0)
        return listOfNotNull(if(usedBytes!=null && totalBytes!=null)"RAM ${gib(usedBytes)}/${gib(totalBytes)} GiB"else null,swapUsedBytes?.let {"스왑 ${gib(it)} GiB"},pressure?.let {"압력 $it"},modelBytes?.let {"모델 ${gib(it)} GiB ($modelMetric)"}).joinToString(" · ").ifBlank {"메모리 미수신"}
    }
    companion object {
        internal fun command(vararg args:String):String? {
            if(Thread.currentThread().isInterrupted)return null
            var process:Process?=null
            return try {
                val p=ProcessBuilder(*args).redirectError(ProcessBuilder.Redirect.DISCARD).start();process=p
                val read=java.util.concurrent.CompletableFuture.supplyAsync {p.inputStream.use {it.readNBytes(262144).toString(Charsets.UTF_8).trim()}}
                if(!p.waitFor(2,TimeUnit.SECONDS)){p.destroyForcibly();read.cancel(true);null}else read.get(2,TimeUnit.SECONDS).takeIf {p.exitValue()==0}
            }catch(e:Exception){process?.destroyForcibly();if(e is InterruptedException)Thread.currentThread().interrupt();null}
        }
        fun sample(modelBytes:Long?=null,metric:String?=null):LlmMemory {
            val os=ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            val total=os?.totalMemorySize?.takeIf {it>0};val free=os?.freeMemorySize?.takeIf {it>=0}
            val mac=System.getProperty("os.name").contains("Mac",true)
            val swap=if(mac)command("/usr/sbin/sysctl","-n","vm.swapusage")?.let(::parseSwap)else os?.let {(it.totalSwapSpaceSize-it.freeSwapSpaceSize).coerceAtLeast(0)}
            val pressure=if(mac)when(command("/usr/sbin/sysctl","-n","kern.memorystatus_vm_pressure_level")){"1"->"정상";"2"->"주의";"4"->"위험";else->null}else null
            val wired=if(mac)command("/usr/sbin/sysctl","-n","iogpu.wired_limit_mb")?.toLongOrNull()else null
            return LlmMemory(total,if(total!=null && free!=null)(total-free).coerceIn(0,total)else null,swap,pressure,modelBytes,metric,wired)
        }
        internal fun parseSwap(text:String):Long?=Regex("used = ([0-9.]+)([MG])").find(text)?.let { (it.groupValues[1].toDouble() * if(it.groupValues[2]=="G")1073741824 else 1048576).toLong() }
        fun rate(tokens:Long,nanos:Long?):Double?=nanos?.takeIf {it>0 && tokens>0}?.let {tokens*1e9/it}?.takeIf {it.isFinite() && it>0}
    }
}
