package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

object Launchers {
    val types=listOf("NeoForge","Forge","Fabric","Paper","Vanilla")
    fun validate(loader:String,minecraft:String,version:String) {
        require(loader in types) { "지원하지 않는 서버 종류: $loader" }
        require(minecraft.matches(Regex("(?:1\\.(?:1[89]|2[0-9])|2[6-9]\\.[0-9]+)(?:\\.[0-9]+)?"))) { "정식 Minecraft 1.18 이상 버전을 선택하세요" }
        require(version.length in 1..80 && version.matches(Regex("[0-9]+(?:\\.[0-9]+)*(?:-[a-zA-Z0-9.]+)?"))) { "잘못된 런처 버전입니다" }
        if(loader=="Vanilla")require(version==minecraft) { "Vanilla 버전은 Minecraft 버전과 같아야 합니다" }
        if(loader=="Paper")require(version.matches(Regex("[1-9][0-9]*"))) { "Paper 빌드 번호를 선택하세요" }
        if(loader=="NeoForge")require(version.startsWith(neoPrefix(minecraft)+".")) { "선택한 Minecraft와 NeoForge 버전이 맞지 않습니다" }
    }
    fun neoPrefix(minecraft:String):String {
        val parts=minecraft.split('.')
        return if(parts[0]=="1") "${parts[1]}.${parts.getOrElse(2){"0"}}" else "${parts[0]}.${parts[1]}"
    }
    fun arguments(i:Instance,server:Path,java:String=i.javaPath,windows:Boolean=System.getProperty("os.name").startsWith("Windows")):List<String> {
        validate(i.loader,i.minecraft,i.loaderVersion)
        val tail=when(i.loader) {
            "NeoForge","Forge" -> {
                val library=if(i.loader=="NeoForge") "net/neoforged/neoforge/${i.loaderVersion}" else "net/minecraftforge/forge/${i.minecraft}-${i.loaderVersion}"
                val args=server.resolve("libraries/$library/${if(windows) "win_args.txt" else "unix_args.txt"}")
                require(args.isRegularFile()) { "${i.loader} 실행 파일이 없습니다. 먼저 런처를 설치하세요" }
                listOf("@${args.toAbsolutePath()}","nogui")
            }
            else -> {
                val metadata=server.resolve("server-manager-runtime.json")
                val entry=if(metadata.isRegularFile()) {
                    require(metadata.fileSize()<=65536) { "설치 메타데이터가 너무 큽니다" }
                    jacksonObjectMapper().readTree(metadata.readText()).path("entryJar").asText("").takeIf {it.isNotBlank()}
                } else null
                require(entry==null || (entry.endsWith(".jar") && '/' !in entry && '\\' !in entry && ':' !in entry && entry!="..")) { "잘못된 서버 JAR 경로입니다" }
                val jar=server.resolve(entry ?: if(i.loader=="Fabric") "fabric-server-launch.jar" else "server.jar")
                require(jar.isRegularFile()) { "${i.loader} 서버 JAR가 없습니다. 먼저 런처를 설치하세요" }
                listOf("-jar",jar.toAbsolutePath().toString(),"nogui")
            }
        }
        return listOf(java,"-Xms512M","-Xmx${i.memoryMb}M")+tail
    }
    fun requiredJava(i:Instance,server:Path):Int {
        val file=server.resolve("server-manager-runtime.json")
        if(!file.exists()) { require(i.loader=="NeoForge" && i.minecraft=="1.21.1") { "설치 메타데이터가 없습니다. 런처를 다시 설치하세요" };return 21 }
        val node=jacksonObjectMapper().readTree(file.readText())
        require(node.path("loader").asText()==i.loader && node.path("minecraft").asText()==i.minecraft && node.path("loaderVersion").asText()==i.loaderVersion) { "설치된 런처가 서버 설정과 일치하지 않습니다" }
        return node.path("requiredJava").asInt().also { require(it in 8..99) }
    }
    fun javaMajor(text:String):Int = Regex("(?:version\\s+\"|openjdk\\s+)([0-9]+)(?:\\.([0-9]+))?").find(text)?.let {
        if(it.groupValues[1]=="1")it.groupValues[2].toInt() else it.groupValues[1].toInt()
    } ?: error("Java 버전을 확인하지 못했습니다")
    fun verifyJava(java:String,required:Int) {
        val p=ProcessBuilder(java,"-version").redirectErrorStream(true).start()
        if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroy();error("Java 버전 확인 시간이 초과됐습니다")}
        require(p.exitValue()==0 && javaMajor(p.inputStream.bufferedReader().readText())>=required) { "이 서버는 Java $required 이상이 필요합니다. 실행할 컴퓨터의 Java를 확인하세요" }
    }
}

class LauncherDownloads {
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    companion object {
        val hosts=setOf("piston-meta.mojang.com","piston-data.mojang.com","launchermeta.mojang.com","launcher.mojang.com","meta.fabricmc.net","maven.fabricmc.net","maven.neoforged.net","maven.minecraftforge.net","fill.papermc.io","fill-data.papermc.io")
        fun allowed(url:String):URI=URI(url).also { require(it.scheme=="https" && it.host in hosts && it.userInfo==null && it.port in listOf(-1,443)) { "허용되지 않은 런처 다운로드 주소" } }
    }
    private fun response(url:String):HttpResponse<java.io.InputStream> {
        var uri=allowed(url)
        repeat(5) {
            val r=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).header("User-Agent","ServerManager/0.4 (https://github.com/yoonseo6399/ServerManager)").GET().build(),HttpResponse.BodyHandlers.ofInputStream())
            if(r.statusCode() in listOf(301,302,303,307,308)) { r.body().close();uri=allowed(uri.resolve(r.headers().firstValue("Location").orElseThrow()).toString()) }
            else { if(r.statusCode()!=200){r.body().close();error("런처 다운로드 HTTP ${r.statusCode()} (${uri.host})")};return r }
        }
        error("런처 다운로드 리디렉션이 너무 많습니다")
    }
    fun text(url:String):String=response(url).body().use { val b=it.readNBytes(8*1024*1024+1);require(b.size<=8*1024*1024);b.toString(Charsets.UTF_8) }
    fun file(url:String,target:Path,algorithm:String,expected:String,size:Long?=null) {
        require(expected.matches(Regex(if(algorithm=="SHA-256") "[a-fA-F0-9]{64}" else "[a-fA-F0-9]{40}"))) { "공식 다운로드 해시가 없습니다" }
        val tmp=Files.createTempFile(target.parent,".launcher-",".part")
        try {
            val hash=MessageDigest.getInstance(algorithm);var total=0L
            response(url).body().use { input -> tmp.outputStream().use { output ->
                val buffer=ByteArray(65536)
                while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=512L*1024*1024);hash.update(buffer,0,n);output.write(buffer,0,n)}
            } }
            require(size==null || size==total) { "런처 다운로드 크기가 일치하지 않습니다" }
            require(hash.digest().joinToString(""){"%02x".format(it)}.equals(expected,true)) { "런처 다운로드 해시 불일치" }
            Files.move(tmp,target,ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { tmp.deleteIfExists() }
    }
}

class LauncherCatalog(private val directory:Path,private val fetch:(String)->String=LauncherDownloads()::text) {
    private val mapper=jacksonObjectMapper()
    private val entries=ConcurrentHashMap<String,VersionListing>()
    private val locks=ConcurrentHashMap<String,Any>()
    companion object {
        const val MINECRAFT="https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val NEO="https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
        const val FORGE="https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
        fun maven(xml:String)=Regex("<version>\\s*([^<>\\s]+)\\s*</version>").findAll(xml).map { it.groupValues[1] }.toList()
    }
    fun json(url:String):JsonNode=mapper.readTree(fetch(url))
    private fun listing(key:String,refresh:Boolean,load:()->List<String>):VersionListing = synchronized(locks.computeIfAbsent(key){Any()}) {
        val file=directory.resolve("$key.json")
        val cached=entries[key] ?: runCatching { mapper.readValue<VersionListing>(file.readText()) }.getOrNull()?.also { entries[key]=it }
        if(!refresh && cached!=null && Duration.between(Instant.parse(cached.fetchedAt),Instant.now()).toHours()<6)return@synchronized cached
        try {
            val result=VersionListing(load().distinct(),Instant.now().toString())
            require(result.versions.isNotEmpty()) { "이 조합의 공식 버전이 없습니다" }
            entries[key]=result
            runCatching { directory.createDirectories();val temp=Files.createTempFile(directory,".catalog-",".tmp");try {temp.writeText(mapper.writeValueAsString(result));Files.move(temp,file,ATOMIC_MOVE,REPLACE_EXISTING)} finally {temp.deleteIfExists()} }
            result
        } catch(e:Exception) {
            cached?.copy(stale=true,warning="공식 조회 실패로 저장된 목록을 사용합니다: ${e.message}") ?: throw IllegalStateException("공식 버전을 가져오지 못했습니다: ${e.message}")
        }
    }
    private fun modern(value:String)=runCatching { Launchers.validate("Vanilla",value,value);true }.getOrDefault(false)
    fun games(loader:String,refresh:Boolean=false):VersionListing {
        require(loader in Launchers.types)
        return listing("games-$loader",refresh) {
            when(loader) {
                "NeoForge" -> maven(fetch(NEO)).filter {it.substringBefore('.').toIntOrNull()?.let {n->n>=20}==true}.mapNotNull { v -> val parts=v.split('.');if(parts.size<3)null else if(parts[0].toIntOrNull() in 20..25) "1.${parts[0]}"+(if(parts[1]=="0") "" else ".${parts[1]}") else "${parts[0]}.${parts[1]}" }.filter(::modern).reversed()
                "Forge" -> maven(fetch(FORGE)).map { it.substringBefore('-') }.filter(::modern).reversed()
                "Fabric" -> json("https://meta.fabricmc.net/v2/versions/game").filter { it.path("stable").asBoolean() }.map { it.path("version").asText() }.filter(::modern)
                "Paper" -> json("https://fill.papermc.io/v3/projects/paper").path("versions").elements().asSequence().flatMap { it.elements().asSequence() }.map { it.asText() }.filter(::modern).toList()
                else -> json(MINECRAFT).path("versions").filter { it.path("type").asText()=="release" }.map { it.path("id").asText() }.filter(::modern)
            }
        }
    }
    fun versions(loader:String,minecraft:String,refresh:Boolean=false):VersionListing {
        require(loader in Launchers.types);require(modern(minecraft))
        return listing("versions-$loader-$minecraft",refresh) {
            when(loader) {
                "NeoForge" -> maven(fetch(NEO)).filter { it.startsWith(Launchers.neoPrefix(minecraft)+".") }.reversed()
                "Forge" -> maven(fetch(FORGE)).filter { it.startsWith("$minecraft-") }.map { it.removePrefix("$minecraft-") }.reversed()
                "Fabric" -> json("https://meta.fabricmc.net/v2/versions/loader/$minecraft").filter { it.path("loader").path("stable").asBoolean() }.map { it.path("loader").path("version").asText() }
                "Paper" -> json("https://fill.papermc.io/v3/projects/paper/versions/$minecraft/builds").filter { it.path("channel").asText()=="STABLE" }.map { it.path("id").asText() }
                else -> listOf(minecraft).also { minecraftMetadata(minecraft) }
            }
        }
    }
    fun minecraftMetadata(version:String):JsonNode {
        val entry=json(MINECRAFT).path("versions").firstOrNull { it.path("id").asText()==version && it.path("type").asText()=="release" } ?: error("공식 Minecraft 버전이 아닙니다")
        return json(entry.path("url").asText()).also { require(it.path("id").asText()==version && it.path("downloads").has("server")) }
    }
}

class LauncherInstaller(private val catalog:LauncherCatalog,private val downloads:LauncherDownloads=LauncherDownloads()) {
    fun install(i:Instance,server:Path,installer:Path,process:(List<String>)->Int,log:(String)->Unit) {
        Launchers.validate(i.loader,i.minecraft,i.loaderVersion)
        require(i.loaderVersion in catalog.versions(i.loader,i.minecraft).versions) { "선택한 Minecraft와 런처 조합이 공식 목록에 없습니다" }
        val metadata=catalog.minecraftMetadata(i.minecraft)
        val requiredJava=metadata.path("javaVersion").path("majorVersion").asInt(17)
        Launchers.verifyJava(i.javaPath,requiredJava)
        log("${i.loader} ${i.loaderVersion} · Minecraft ${i.minecraft} · Java ${requiredJava}+ 설치")
        when(i.loader) {
            "Vanilla" -> { val d=metadata.path("downloads").path("server");downloads.file(d.path("url").asText(),server.resolve("server.jar"),"SHA-1",d.path("sha1").asText(),d.path("size").asLong()) }
            "Paper" -> {
                val build=catalog.json("https://fill.papermc.io/v3/projects/paper/versions/${i.minecraft}/builds").first { it.path("id").asText()==i.loaderVersion && it.path("channel").asText()=="STABLE" }
                val d=build.path("downloads").path("server:default")
                downloads.file(d.path("url").asText(),server.resolve("server.jar"),"SHA-256",d.path("checksums").path("sha256").asText(),d.path("size").asLong())
            }
            else -> {
                val url=when(i.loader) {
                    "NeoForge" -> "https://maven.neoforged.net/releases/net/neoforged/neoforge/${i.loaderVersion}/neoforge-${i.loaderVersion}-installer.jar"
                    "Forge" -> "https://maven.minecraftforge.net/net/minecraftforge/forge/${i.minecraft}-${i.loaderVersion}/forge-${i.minecraft}-${i.loaderVersion}-installer.jar"
                    else -> catalog.json("https://meta.fabricmc.net/v2/versions/installer").first { it.path("stable").asBoolean() }.path("url").asText()
                }
                val checksum=runCatching { "SHA-256" to downloads.text("$url.sha256").trim().substringBefore(' ') }.getOrElse { "SHA-1" to downloads.text("$url.sha1").trim().substringBefore(' ') }
                downloads.file(url,installer,checksum.first,checksum.second)
                val args=if(i.loader=="Fabric") listOf("server","-mcversion",i.minecraft,"-loader",i.loaderVersion,"-downloadMinecraft") else listOf("--installServer")
                check(process(listOf(i.javaPath,"-jar",installer.toAbsolutePath().toString())+args)==0) { "${i.loader} 설치기가 오류로 종료됐습니다" }
                if(i.loader=="Fabric") { val d=metadata.path("downloads").path("server");require(digest(server.resolve("server.jar"),"SHA-1")==d.path("sha1").asText()) { "Fabric의 Minecraft 서버 해시가 일치하지 않습니다" } }
            }
        }
        Launchers.arguments(i,server)
        val mapper=jacksonObjectMapper()
        val tmp=Files.createTempFile(server,".runtime-",".tmp")
        try {
            tmp.writeBytes(mapper.writeValueAsBytes(mapOf("loader" to i.loader,"minecraft" to i.minecraft,"loaderVersion" to i.loaderVersion,"requiredJava" to requiredJava)))
            Files.move(tmp,server.resolve("server-manager-runtime.json"),ATOMIC_MOVE,REPLACE_EXISTING)
        } finally { tmp.deleteIfExists() }
    }
    private fun digest(path:Path,algorithm:String):String {
        val hash=MessageDigest.getInstance(algorithm)
        path.inputStream().use { input -> val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;hash.update(b,0,n)} }
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
}
