package local.servermanager

/** Runtime-only status; raw files are transferred using the existing checked chunk protocol. */
data class FolderImportProgress(val phase:String,val files:Int=0,val bytes:Long=0,val totalFiles:Int=0,val totalBytes:Long=0,val message:String="",val direction:String="TO_MANAGER",val bytesPerSecond:Double?=null,val remainingSeconds:Long?=null,
    val step:String="",val stepNo:Int=0,val stepTotal:Int=0,val reused:Int=0,val hashed:Int=0) {
    fun validated():FolderImportProgress {
        require(phase in setOf("SCANNING","UPLOADING","VERIFYING","COMPLETE","FAILED","RETRYING"))
        require(files in 0..100000 && totalFiles in 0..100000 && bytes in 0..(1024L*1024*1024*1024) && totalBytes in 0..(1024L*1024*1024*1024))
        require(direction in setOf("TO_MANAGER","TO_RUNNER"))
        require(stepNo in 0..9 && stepTotal in 0..9 && stepNo<=stepTotal && reused in 0..1_000_000 && hashed in 0..1_000_000)
        return copy(message=message.take(500),step=step.replace(Regex("\\p{Cntrl}")," ").take(80))
    }
}

/** Rolling actual bytes, never a percentage difference or a guessed whole-file speed. */
class TransferMeter(private val clock:()->Long={System.currentTimeMillis()}) {
    private val samples=ArrayDeque<Pair<Long,Long>>()
    @Synchronized fun reset(bytes:Long=0) {val now=clock();samples.clear();samples.add(now to bytes)}
    @Synchronized fun observe(value:FolderImportProgress) {
        if(value.phase!="UPLOADING")return
        val now=clock()
        if(samples.isEmpty() || value.bytes<samples.last().second)reset(value.bytes)
        if(value.bytes!=samples.last().second){if(samples.size>1 && now-samples.last().first<250)samples.removeLast();samples.add(now to value.bytes)}
        while(samples.size>2 && samples.elementAt(1).first<now-30000)samples.removeFirst()
    }
    @Synchronized fun view(value:FolderImportProgress):FolderImportProgress {
        val now=clock();val first=samples.firstOrNull();val last=samples.lastOrNull()
        val speed=when {
            value.phase=="RETRYING"->0.0
            value.phase!="UPLOADING" || first==null || last==null->null
            now-last.first>10000->0.0
            last.first-first.first<2000->null
            else->((last.second-first.second)*1000.0/(last.first-first.first)).takeIf {it.isFinite() && it>=0}
        }
        val eta=if(value.phase=="COMPLETE")0L else speed?.takeIf {it>0 && value.totalBytes>=value.bytes}?.let {kotlin.math.ceil((value.totalBytes-value.bytes)/it).toLong()}
        return value.copy(bytesPerSecond=speed,remainingSeconds=eta)
    }
}

object TransferDisplay {
    private fun size(bytes:Double)=if(bytes>=1024*1024*1024)"%.2f GiB".format(java.util.Locale.ROOT,bytes/(1024*1024*1024)) else "%.1f MiB".format(java.util.Locale.ROOT,bytes/(1024*1024))
    fun text(value:FolderImportProgress):String {
        val label=mapOf("SCANNING" to "파일 검사","UPLOADING" to "전송 중","VERIFYING" to "검증·반영","RETRYING" to "연결 대기 · 받은 파일 보존","FAILED" to "전송 실패","COMPLETE" to "전송 완료")[value.phase] ?: "진행 확인"
        val percent=if(value.phase=="UPLOADING" && value.totalBytes>0)" · %.1f%%".format(java.util.Locale.ROOT,(value.bytes*100.0/value.totalBytes).coerceIn(0.0,100.0)) else ""
        if(value.phase in setOf("COMPLETE","FAILED"))return "$label\n${size(value.bytes.toDouble())} / ${size(value.totalBytes.toDouble())}"
        val speed=if(value.phase=="RETRYING")"연결 대기" else if(value.phase=="SCANNING")"전송 전" else if(value.phase=="VERIFYING")"전송 완료" else value.bytesPerSecond?.let {if(it<1024*1024)"%.1f KiB/s".format(java.util.Locale.ROOT,it/1024) else size(it)+"/s"} ?: "계산 중"
        val eta=if(value.phase=="RETRYING")"연결 대기" else if(value.phase=="SCANNING")"파일 검사 중" else if(value.phase=="VERIFYING")"검증·반영 중" else value.remainingSeconds?.let {if(it>=3600)"${it/3600}시간 ${it%3600/60}분" else if(it>=60)"${it/60}분 ${it%60}초" else "${it}초"} ?: "계산 중"
        return "$label$percent\n${size(value.bytes.toDouble())} / ${size(value.totalBytes.toDouble())}\n속도 $speed · 전송 예상 남은 시간 $eta (검증 별도)"
    }
}
