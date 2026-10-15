package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*

/** Downloads the same signed application JAR used by the desktop updater. The runner supervisor applies it after idle. */
class RunnerUpdater(private val home:Path,private val current:String,
    private val client:HttpClient=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build(),
    private val key:ByteArray=RunnerUpdater::class.java.getResourceAsStream("/update-public-key.der")!!.use {it.readAllBytes()},
    private val changed:(RunnerUpdateStatus)->Unit={}) {
    private val mapper=jacksonObjectMapper()
    private val repository=RunnerUpdater::class.java.getResourceAsStream("/update-repository.txt")!!.bufferedReader().use { it.readText().trim() }
    private val beta="-beta." in current
    private val checking=AtomicBoolean(false)
    private val updates=home.resolve("updates")
    @Volatile private var state=RunnerUpdateStatus()
    fun status()=state
    fun busy()=checking.get()
    fun phase(value:String){publish(state.copy(phase=value,latest=pendingVersion() ?: state.latest))}
    private fun publish(next:RunnerUpdateStatus){state=next.validated();changed(state)}
    private fun pendingVersion():String?=runCatching {updates.resolve("pending.txt").takeIf {it.exists()}?.readText()?.trim()?.takeIf {SignedUpdates.compare(it,current)>0}}.getOrNull()
    fun pending()=pendingVersion()!=null
    fun check() {
        if(!checking.compareAndSet(false,true))return
        try {
            publish(RunnerUpdateStatus("CHECKING"))
            updates.createDirectories()
            val bytes=get(URI("https://api.github.com/repos/$repository/releases?per_page=100"),4*1024*1024)
            val releases=mapper.readTree(bytes).toList()
            val release=releases.filter { !it.path("draft").asBoolean() && SignedUpdates.eligible(it.path("tag_name").asText().removePrefix("v"),it.path("prerelease").asBoolean(),beta) }
                .maxWithOrNull { a,b->SignedUpdates.compare(a.path("tag_name").asText().removePrefix("v"),b.path("tag_name").asText().removePrefix("v")) } ?: run {publish(RunnerUpdateStatus("CURRENT",current));return}
            val version=release.path("tag_name").asText().removePrefix("v")
            if(SignedUpdates.compare(version,current)<=0){publish(RunnerUpdateStatus("CURRENT",current));return}
            if(home.resolve("failed-update-version.txt").takeIf {it.exists()}?.readText()?.trim()==version){publish(RunnerUpdateStatus("FAILED",version));return}
            val base="https://github.com/$repository/releases/download/v$version/"
            val manifest=get(URI(base+"update.properties"),65536)
            val signature=get(URI(base+"update.sig"),1024)
            val properties=SignedUpdates.manifest(manifest,signature,key)
            require(properties.getProperty("version")==version)
            val folder=updates.resolve(version).also { it.createDirectories() }
            val jar=folder.resolve(properties.getProperty("file"))
            if(!SignedUpdates.validFile(jar,properties)) {
                val temp=Files.createTempFile(folder,".download-",".part")
                try {
                    val limit=properties.getProperty("size").toLong()
                    publish(RunnerUpdateStatus("DOWNLOADING",version,0,limit));var lastReport=0L
                    readBody(URI(base+properties.getProperty("file"))) { input->temp.outputStream().use { output ->
                        val buffer=ByteArray(65536);var total=0L
                        while(true) { val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit);output.write(buffer,0,n);val now=System.nanoTime();if(now-lastReport>500_000_000){publish(RunnerUpdateStatus("DOWNLOADING",version,total,limit));lastReport=now} }
                        require(total==limit)
                    } }
                    publish(RunnerUpdateStatus("VERIFYING",version,limit,limit))
                    require(SignedUpdates.validFile(temp,properties)) { "실행기 업데이트 해시 불일치" }
                    Files.move(temp,jar,ATOMIC_MOVE,REPLACE_EXISTING)
                } finally { temp.deleteIfExists() }
            }
            atomicBytes(folder.resolve("update.properties"),manifest)
            atomicBytes(folder.resolve("update.sig"),signature)
            atomicText(updates.resolve("pending.txt"),version)
            publish(RunnerUpdateStatus("READY",version))
        } catch(e:Exception){publish(state.copy(phase="FAILED"));throw e} finally { checking.set(false) }
    }
    private fun atomicBytes(path:Path,bytes:ByteArray) {
        val temp=Files.createTempFile(path.parent,".metadata-",".part")
        try {temp.writeBytes(bytes);Files.move(temp,path,ATOMIC_MOVE,REPLACE_EXISTING)}finally{temp.deleteIfExists()}
    }
    private fun response(uri:URI):HttpResponse<java.io.InputStream> {
        var next=uri
        repeat(5) {
            require(next.scheme=="https" && next.userInfo==null && next.host in setOf("api.github.com","github.com","release-assets.githubusercontent.com","objects.githubusercontent.com"))
            val result=client.send(HttpRequest.newBuilder(next).timeout(Duration.ofMinutes(2)).header("User-Agent","ServerManager-Runner/$current").GET().build(),HttpResponse.BodyHandlers.ofInputStream())
            if(result.statusCode() in setOf(301,302,303,307,308)) { result.body().close();next=next.resolve(result.headers().firstValue("Location").orElseThrow());return@repeat }
            if(result.statusCode()!=200){result.body().close();error("업데이트 서버 HTTP ${result.statusCode()}")}
            return result
        }
        error("업데이트 리디렉션 한도 초과")
    }
    private fun <T> readBody(uri:URI,read:(java.io.InputStream)->T):T {
        val body=response(uri).body()
        val timeout=deadlines.schedule({runCatching {body.close()}},120,java.util.concurrent.TimeUnit.SECONDS)
        try {return body.use(read)}finally{timeout.cancel(false)}
    }
    private fun get(uri:URI,limit:Int)=readBody(uri) { input->input.readNBytes(limit+1).also { require(it.size<=limit) } }
    companion object {
        private val deadlines=java.util.concurrent.Executors.newSingleThreadScheduledExecutor {task->Thread(task,"runner-update-deadline").apply {isDaemon=true}}
    }
}
