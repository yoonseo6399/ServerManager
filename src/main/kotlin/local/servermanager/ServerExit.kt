package local.servermanager

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*

/** Exit metadata only; never a persisted parsed log or stack trace. */
data class RunnerExitReport(val code:Int?=null,val crashReport:String?=null)
data class ServerExit(val code:Int?,val requested:Boolean,val reason:String,val crashReport:String?=null,val at:String=Instant.now().toString()) {
    fun summary():String=when(reason) {
        "IDLE"->"플레이어가 없는 상태가 이어져 절전 종료했습니다."
        "RESTART"->"재시작 요청으로 정상 종료했습니다."
        "SHUTDOWN"->"관리 앱 종료를 위해 서버를 종료했습니다."
        "MANUAL"->"사용자 요청으로 서버를 종료했습니다."
        "FORCED"->"사용자 요청으로 서버를 강제 종료했습니다. 저장되지 않은 변경은 손실될 수 있습니다."
        else->if(crashReport!=null)"크래시 리포트를 생성하고 예기치 않게 종료했습니다." else "예기치 않게 서버가 종료되었습니다."
    }+(code?.let {" (종료 코드 $it)"} ?: " (종료 코드 확인 불가)")
}

object CrashReports {
    const val MAX_BYTES=8*1024*1024
    private fun folder(base:Path):Path? {
        val dir=base.resolve("crash-reports")
        return dir.takeIf {!Files.isSymbolicLink(base)&&!Files.isSymbolicLink(dir)&&Files.isDirectory(dir,NOFOLLOW_LINKS)}
    }
    fun validName(name:String)=name.length<=240 && Regex("[A-Za-z0-9][A-Za-z0-9._ -]*\\.txt").matches(name)
    fun snapshot(base:Path):Map<String,String> = runCatching {
        val dir=folder(base) ?: return emptyMap()
        Files.list(dir).use {paths->paths.filter {validName(it.name)&&Files.isRegularFile(it,NOFOLLOW_LINKS)}.limit(10000).iterator().asSequence().associate {p->
            p.name to "${p.fileSize()}:${p.getLastModifiedTime()}:${Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey()}"
        }}
    }.getOrDefault(emptyMap())
    fun changed(base:Path,before:Map<String,String>):String? {
        val after=snapshot(base);val dir=folder(base) ?: return null
        return after.filter {(name,stamp)->before[name]!=stamp}.keys.maxByOrNull {dir.resolve(it).getLastModifiedTime().toMillis()}
    }
    fun attachment(base:Path,name:String):ByteArray? = runCatching {
        require(validName(name))
        val dir=folder(base) ?: return null
        val file=dir.resolve(name)
        require(Files.isRegularFile(file,NOFOLLOW_LINKS)&&file.fileSize() in 1..MAX_BYTES.toLong())
        Files.newInputStream(file,READ,NOFOLLOW_LINKS).use {input->input.readNBytes(MAX_BYTES+1).takeIf {it.size in 1..MAX_BYTES}}
    }.getOrNull()
}
