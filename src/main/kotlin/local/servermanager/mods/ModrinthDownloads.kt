package local.servermanager

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*

data class ModDownloadResponse(val status:Int,val length:Long?,val body:InputStream)
data class DownloadedMod(val projectId:String,val versionId:String,val file:RinFile,val sha256:String)
class StagedMods internal constructor(val directory:Path,val files:List<DownloadedMod>):AutoCloseable {
    override fun close() {if(directory.exists(NOFOLLOW_LINKS))Files.walk(directory).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.delete(it)}}}
}

/** Not an installer. Only produces verified files in a fresh private staging directory. */
class ModrinthDownloads(private val open:(URI)->ModDownloadResponse=Transport()::open,private val timeout:Duration=Duration.ofMinutes(3)) {
    init {require(!timeout.isNegative && !timeout.isZero && timeout<=Duration.ofMinutes(10))}
    companion object {
        private val deadlines=ScheduledThreadPoolExecutor(1) {task->Thread(task,"modrinth-download-deadline").apply {isDaemon=true}}.apply {removeOnCancelPolicy=true}
        private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    }
    fun stage(plan:RinInstallPlan,parent:Path,progress:(String,Long,Long)->Unit={_,_,_->}):StagedMods {
        val wanted=plan.mods.filter {!it.alreadyInstalled}
        require(wanted.size<=128 && wanted.sumOf {it.file.validate().size}<=2L*1024*1024*1024) {"한 번에 받을 모드는 128개·합계 2GiB까지입니다"}
        require(wanted.all {it.project.type=="mod" && it.version.projectId==it.project.id && it.version.compatible(plan.target) && it.file==it.version.primaryFile()}) {"다운로드 계획의 프로젝트·버전·기본 파일을 확인하세요"}
        require(wanted.map {it.file.filename.lowercase()}.distinct().size==wanted.size)
        require(parent.isDirectory(NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent))
        val folder=Files.createTempDirectory(parent,"modrinth-")
        try {
            if(Files.getFileStore(folder).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(folder,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            val files=wanted.map {item->DownloadedMod(item.project.id,item.version.id,item.file,download(item.file,folder,progress))}
            return StagedMods(folder,files)
        } catch(e:Exception) {try {StagedMods(folder,emptyList()).close()} catch(cleanup:Exception) {e.addSuppressed(cleanup)};throw e}
    }
    private fun download(file:RinFile,folder:Path,progress:(String,Long,Long)->Unit):String {
        file.validate()
        val response=open(RinFile.downloadUri(file.url))
        response.body.use {input->
            check(response.status==200) {if(response.status==429)"Modrinth 다운로드 한도에 도달했습니다" else "모드 다운로드 HTTP ${response.status}"}
            require(response.length==null || response.length==file.size) {"모드 다운로드 크기가 일치하지 않습니다"}
            val expired=AtomicBoolean(false)
            val tmp=Files.createTempFile(folder,".download-",".part")
            val deadline=deadlines.schedule({expired.set(true);runCatching {input.close()}},timeout.toNanos(),TimeUnit.NANOSECONDS)
            try {
                val hashes=listOf("SHA-1","SHA-256","SHA-512").map {MessageDigest.getInstance(it)}
                var count=0L;var reported=0L
                tmp.outputStream().use {out->
                    val buffer=ByteArray(64*1024)
                    while(true) {
                        val n=input.read(buffer);check(!expired.get()) {"모드 다운로드 시간이 초과되었습니다"};if(n<0)break
                        count+=n;require(count<=file.size) {"모드 파일이 선언된 크기보다 큽니다"}
                        hashes.forEach {it.update(buffer,0,n)};out.write(buffer,0,n)
                        val now=System.nanoTime();if(now-reported>=250_000_000 || count==file.size) {reported=now;progress(file.filename,count,file.size)}
                    }
                }
                check(count==file.size) {"모드 다운로드가 중간에 끝났습니다"}
                val actual=hashes.map {hex(it.digest())}
                check(actual[0]==file.sha1 && actual[2]==file.sha512) {"Modrinth 공식 파일 해시가 일치하지 않습니다"}
                val target=folder.resolve(file.filename);check(!target.exists(NOFOLLOW_LINKS))
                Files.move(tmp,target,ATOMIC_MOVE)
                return actual[1]
            } finally {deadline.cancel(false);tmp.deleteIfExists()}
        }
    }
    private class Transport {
        private val client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build()
        fun open(uri:URI):ModDownloadResponse {
            RinFile.downloadUri(uri.toString())
            val r=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).header("User-Agent","yoonseo6399/ServerManager (https://github.com/yoonseo6399/ServerManager)").GET().build(),HttpResponse.BodyHandlers.ofInputStream())
            return ModDownloadResponse(r.statusCode(),r.headers().firstValueAsLong("Content-Length").let {if(it.isPresent)it.asLong else null},r.body())
        }
    }
}
