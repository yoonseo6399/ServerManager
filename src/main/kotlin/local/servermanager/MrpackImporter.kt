package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.io.path.*

data class PackFile(val path: String, val hashes: Map<String,String>, val downloads: List<String>, val fileSize: Long, val server: String)
data class PackPlan(val name: String, val version: String, val minecraft: String, val loaderVersion: String, val files: List<PackFile>, val overrides: List<String>, val warnings: List<String>,val loader:String="NeoForge")
data class PackPreview(val token: String, val plan: PackPlan, val expiresAt: String)
data class PackImportRequest(val token: String, val name: String, val port: Int, val memoryMb: Int, val javaPath: String, val excludeClientOnly: Boolean = true)

object PackFormat {
    private val mapper = jacksonObjectMapper()
    const val MAX_FILE = 256L * 1024 * 1024
    const val MAX_TOTAL = 2L * 1024 * 1024 * 1024
    fun relative(value: String): String {
        require(value.isNotBlank() && !value.startsWith('/') && !value.contains('\\') && !value.contains(':') && !value.contains('\u0000')) { "잘못된 팩 파일 경로: $value" }
        require(value.split('/').none { it == ".." || it == "." }) { "팩 경로가 서버 폴더를 벗어납니다: $value" }
        return value.trimEnd('/')
    }
    fun url(value: String): URI {
        val uri = URI(value)
        val host = uri.host?.lowercase() ?: ""
        require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1,443) &&
            (host in setOf("cdn.modrinth.com","github.com","raw.githubusercontent.com","gitlab.com","objects.githubusercontent.com","release-assets.githubusercontent.com"))) {
            "허용되지 않은 모드 다운로드 주소: $value"
        }
        return uri
    }
    fun inspect(archive: Path): PackPlan = ZipFile(archive.toFile()).use { zip ->
        val entries = zip.entries().toList()
        require(entries.size <= 20000) { "팩의 파일 수가 너무 많습니다" }
        val seen = mutableSetOf<String>(); var total = 0L
        entries.forEach { e ->
            val path = relative(e.name)
            require(seen.add(path.lowercase())) { "중복 팩 경로: $path" }
            require(e.size in 0..MAX_FILE) { "팩 내부 파일 크기가 허용 범위를 넘었습니다: $path" }
            total += e.size; require(total <= MAX_TOTAL) { "팩 압축 해제 크기는 2 GiB 이하여야 합니다" }
        }
        val index = zip.getEntry("modrinth.index.json") ?: error("modrinth.index.json이 없는 mrpack입니다")
        require(index.size in 1..(8L*1024*1024)) { "잘못된 팩 메타데이터 크기입니다" }
        val node = zip.getInputStream(index).use { mapper.readTree(it.readNBytes(8*1024*1024+1)) }
        require(node.path("formatVersion").asInt() == 1 && node.path("game").asText() == "minecraft") { "Minecraft mrpack formatVersion 1만 지원합니다" }
        val deps = node.path("dependencies")
        val names=mapOf("neoforge" to "NeoForge","forge" to "Forge","fabric-loader" to "Fabric")
        val frameworks=deps.fieldNames().asSequence().filter {it in names}.toList()
        require(frameworks.size==1 && deps.fieldNames().asSequence().all {it=="minecraft" || it in names}) { "mrpack은 NeoForge·Forge·Fabric 중 한 로더를 지정해야 합니다" }
        val loader=names.getValue(frameworks.single())
        val minecraft=deps.path("minecraft").asText();val loaderVersion=deps.path(frameworks.single()).asText()
        Launchers.validate(loader,minecraft,loaderVersion)
        require(node.path("files").isArray) { "팩 files 목록이 없습니다" }
        val paths = mutableSetOf<String>()
        val files = node.path("files").map { f ->
            val path=relative(f.path("path").asText())
            require(paths.add(path.lowercase())) { "중복 다운로드 경로: $path" }
            val size=f.path("fileSize").asLong(-1)
            require(size in 0..MAX_FILE) { "다운로드 파일 크기가 잘못되었습니다: $path" }
            val hashes=mapOf("sha1" to f.path("hashes").path("sha1").asText(), "sha512" to f.path("hashes").path("sha512").asText())
            require(hashes.getValue("sha1").matches(Regex("[a-fA-F0-9]{40}")) && hashes.getValue("sha512").matches(Regex("[a-fA-F0-9]{128}"))) { "SHA-1/SHA-512 해시가 잘못되었습니다: $path" }
            val urls=f.path("downloads").map { it.asText().also { value -> url(value) } }
            require(urls.isNotEmpty()) { "다운로드 주소가 없습니다: $path" }
            val env=f.path("env").path("server").asText("required")
            require(env in setOf("required","optional","unsupported")) { "잘못된 server 환경: $path" }
            PackFile(path,hashes,urls,size,env)
        }
        require(files.sumOf { it.fileSize } + total <= MAX_TOTAL) { "팩 전체 크기는 2 GiB 이하여야 합니다" }
        val overrides=entries.filter { !it.isDirectory && (it.name.startsWith("overrides/") || it.name.startsWith("server-overrides/")) }.map { it.name }
        val warnings=buildList {
            if(files.any { it.server == "unsupported" }) add("클라이언트 전용 표시 파일은 제외 후보입니다. 서버 모드가 Modrinth에서 필수로 쓰는 파일은 유지합니다.")
            if(files.any { it.server == "optional" }) add("서버 optional 파일은 포함합니다.")
            add("가져오는 중 Modrinth에서 각 모드의 실행 환경과 의존성을 확인합니다. 찾지 못한 모드는 유지합니다.")
        }
        PackPlan(node.path("name").asText("가져온 모드팩"),node.path("versionId").asText(),minecraft,loaderVersion,files,overrides,warnings,loader)
    }

    fun copyBounded(input: InputStream, output: Path, limit: Long): Long {
        output.parent.createDirectories()
        return output.outputStream().use { sink ->
            val buffer=ByteArray(65536);var count=0L
            while(true) { val n=input.read(buffer);if(n<0)break;count+=n;require(count<=limit) { "팩 파일 크기 제한을 초과했습니다" };sink.write(buffer,0,n) };count
        }
    }
    fun verify(path: Path, file: PackFile) {
        require(path.fileSize()==file.fileSize) { "파일 크기 불일치: ${file.path}" }
        val sha1=MessageDigest.getInstance("SHA-1");val sha512=MessageDigest.getInstance("SHA-512")
        path.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0)break;sha1.update(buffer,0,n);sha512.update(buffer,0,n) } }
        fun hex(d:MessageDigest)=d.digest().joinToString("") { "%02x".format(it) }
        require(hex(sha1).equals(file.hashes["sha1"],true) && hex(sha512).equals(file.hashes["sha512"],true)) { "해시 불일치: ${file.path}" }
    }
    fun overrides(archive: Path, target: Path): Set<String> = ZipFile(archive.toFile()).use { zip ->
        val replaced=mutableSetOf<String>()
        for(prefix in listOf("overrides/","server-overrides/")) {
            zip.entries().toList().filter { !it.isDirectory && it.name.startsWith(prefix) }.forEach { e ->
                val path=relative(e.name.removePrefix(prefix));replaced.add(path)
                zip.getInputStream(e).use { copyBounded(it,target.resolve(path),MAX_FILE) }
            }
        }
        replaced
    }
}

/**
 * Removes client-only files from a staged pack. A file is client-only when the pack marks it `env.server=unsupported`
 * or its Modrinth version environment cannot run on a dedicated server. Any candidate a kept mod requires on
 * Modrinth is kept instead, repeated until no kept mod loses a required project.
 */
object ClientModFilter {
    private fun sha512(file:Path)=file.inputStream().use {input->
        val digest=MessageDigest.getInstance("SHA-512");val buffer=ByteArray(1024*1024)
        while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
        digest.digest().joinToString("") {"%02x".format(it)}
    }
    fun exclude(stage:Path,plan:PackPlan,replaced:Set<String>,lookup:(Collection<String>)->ModSourceLookup,log:(String)->Unit) {
        val mods=stage.resolve("mods")
        val jars=if(mods.isDirectory())Files.list(mods).use {paths->paths.filter {it.isRegularFile() && it.name.endsWith(".jar",true)}.toList()} else emptyList()
        val hashes=jars.associateWith {sha512(it)}
        val result=lookup(hashes.values.toSet());result.warnings.forEach(log)
        val sources=hashes.mapValues {result.cache.sources[it.value]}
        fun label(file:Path)=sources[file]?.title?.let {"$it (${file.fileName})"} ?: stage.relativize(file).toString()
        val declared=plan.files.filter {it.server=="unsupported" && it.path !in replaced}.map {stage.resolve(it.path)}.filter {it.exists()}
        val candidates=(declared+sources.filterValues {it?.clientOnly==true}.keys).toMutableSet()
        // Unknown consumers may depend on any removable library. Do not guess from file names or JAR contents.
        sources.filterValues {it==null}.keys.forEach {candidates.remove(it)}
        val unknown=jars.filter {it !in candidates && sources[it]==null}
        if(unknown.isNotEmpty()) {
            candidates.removeAll(jars.toSet())
            log("의존성 미확인 모드가 있어 JAR 자동 제외를 보류합니다: ${unknown.take(10).joinToString {it.fileName.toString()}}")
        }
        fun installed(file:Path)=InstalledMod(file.fileName.toString(),true,Files.size(file),"","",sources[file])
        // A retained client-only library can itself require another candidate. Repeat through the entire closure.
        var changed=true
        while(changed) {
            changed=false
            val kept=jars.filter {it !in candidates}
            for(file in candidates.filter {it in sources}.toList()) {
                val users=kept.filter {owner->sources[owner]?.dependencies.orEmpty().any {it.type=="required" && ModGraph.matches(installed(file),it)}}
                if(users.isNotEmpty()){candidates.remove(file);changed=true;log("서버 모드 ${users.take(3).joinToString {label(it)}}의 필수 의존성이라 유지: ${label(file)}")}
            }
        }
        val kept=jars.filter {it !in candidates}.map(::installed)
        ModGraph(ModInventorySnapshot("pack",kept,emptyList())).validateEnabled()
        candidates.sortedBy {it.toString()}.forEach {it.deleteIfExists();log("클라이언트 전용 제외: ${label(it)}")}

    }
}

@Service
class MrpackImporter(private val manager: ServerManager,private val catalog: ModrinthCatalog) {
    private val mapper=jacksonObjectMapper()
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val jobs=Executors.newSingleThreadExecutor()
    private val imports=manager.root.resolve("imports").also { it.createDirectories() }
    fun preview(input: InputStream): PackPreview {
        val token=UUID.randomUUID().toString();val dir=imports.resolve(token);dir.createDirectories()
        try {
            input.use { PackFormat.copyBounded(it,dir.resolve("pack.mrpack"),PackFormat.MAX_FILE) }
            val result=PackPreview(token,PackFormat.inspect(dir.resolve("pack.mrpack")),Instant.now().plusSeconds(86400).toString())
            dir.resolve("preview.json").writeText(mapper.writeValueAsString(result));return result
        } catch(e:Exception) { dir.toFile().deleteRecursively();throw e }
    }
    fun import(req: PackImportRequest): Map<String,Any?> {
        require(req.token.matches(Regex("[a-f0-9-]{36}"))) { "잘못된 가져오기 토큰" }
        val dir=imports.resolve(req.token);val zip=dir.resolve("pack.mrpack")
        require(zip.exists() && Instant.now().isBefore(Files.getLastModifiedTime(zip).toInstant().plusSeconds(86400))) { "팩 미리보기가 만료되었습니다. 다시 업로드하세요" }
        val plan=PackFormat.inspect(zip)
        synchronized(this) { require(!dir.resolve("consumed").exists()) { "이미 사용한 팩입니다. 다시 업로드하세요" };dir.resolve("consumed").createFile() }
        val row=try { manager.create(CreateRequest(req.name,plan.loaderVersion,req.port,req.memoryMb,req.javaPath,plan.loader,plan.minecraft)) } catch(e:Exception) { dir.resolve("consumed").deleteIfExists();throw e }
        val id=(row.getValue("config") as Instance).id;val r=manager.runtime(id)
        manager.root.resolve(id).resolve("import.pending").writeText(req.token)
        synchronized(r) { r.state="IMPORTING";r.event("system","mrpack 가져오기: ${plan.name} ${plan.version}") }
        jobs.submit {
            val stage=dir.resolve("stage")
            try {
                stage.createDirectories()
                plan.files.forEachIndexed { index,file ->
                    r.event("system","팩 파일 ${index+1}/${plan.files.size}: ${file.path}")
                    download(file,stage.resolve(file.path))
                }
                val replaced=PackFormat.overrides(zip,stage)
                // Looked-up dependencies are saved with the new server for the mod management page.
                if(req.excludeClientOnly) ClientModFilter.exclude(stage,plan,replaced,{ catalog.lookup(manager.root.resolve(id).resolve("mod-sources.json"),it,true) }) { r.event("system",it) }
                else r.event("warn","클라이언트 전용 모드 제외가 꺼져 있습니다. 서버에서 실행되지 않는 모드가 포함될 수 있습니다.")
                // Commit only after all files pass checks. The target is a new, never-started instance.
                Files.walk(stage).use { paths -> paths.filter { it.isRegularFile() }.forEach { src ->
                    val dest=manager.serverDir(id).resolve(stage.relativize(src));dest.parent.createDirectories();Files.move(src,dest,StandardCopyOption.REPLACE_EXISTING)
                } }
                synchronized(r) { r.state="NEW" }
                manager.update(id,CreateRequest(req.name,plan.loaderVersion,req.port,req.memoryMb,req.javaPath,plan.loader,plan.minecraft))
                manager.acceptEula(id)
                manager.root.resolve(id).resolve("mrpack.json").writeText(mapper.writeValueAsString(plan))
                manager.root.resolve(id).resolve("import.pending").deleteExisting()
                r.event("system","모드팩 파일 검증 완료. ${plan.loader} 설치를 시작합니다.")
                manager.install(id)
                dir.toFile().deleteRecursively()
            } catch(e:Exception) {
                synchronized(r) { r.state="IMPORT_FAILED";r.event("error","mrpack 가져오기 실패: ${e.message}. 원본 팩을 다시 가져오세요.") }
                stage.toFile().deleteRecursively()
            }
        }
        return manager.view(id)
    }
    private fun download(file: PackFile,dest: Path) {
        var error: Exception?=null
        for(source in file.downloads) {
            try {
                var uri=PackFormat.url(source)
                for(attempt in 0..5) {
                    val response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build(),HttpResponse.BodyHandlers.ofInputStream())
                    if(response.statusCode() in 300..399) {
                        response.body().close();check(attempt<5) { "리디렉션 횟수 초과" }
                        uri=PackFormat.url(uri.resolve(response.headers().firstValue("Location").orElseThrow()).toString());continue
                    }
                    response.body().use { body ->
                        check(response.statusCode()==200) { "다운로드 HTTP ${response.statusCode()}" }
                        PackFormat.copyBounded(body,dest,file.fileSize)
                    }
                    PackFormat.verify(dest,file);return
                }
            } catch(e:Exception) { error=e;dest.deleteIfExists() }
        }
        throw IllegalStateException("${file.path}: ${error?.message}")
    }
    @PreDestroy fun shutdown() { jobs.shutdownNow() }
}

@RestController
@RequestMapping("/api/mrpacks")
class MrpackApi(private val importer: MrpackImporter) {
    @PostMapping("/preview") fun preview(@RequestParam file: MultipartFile): PackPreview {
        require(file.originalFilename?.endsWith(".mrpack",true)==true) { ".mrpack 파일을 선택하세요" }
        return importer.preview(file.inputStream)
    }
    @PostMapping("/import") fun import(@RequestBody req: PackImportRequest)=importer.import(req)
}
