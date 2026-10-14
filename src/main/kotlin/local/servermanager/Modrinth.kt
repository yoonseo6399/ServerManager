package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** No caller-provided URL is fetched. Web links are converted into validated API identifiers. */
data class ModrinthLink(val project:String,val version:String?=null) {
    companion object {
        fun identifier(value:String):String = value.also {require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {"잘못된 Modrinth 식별자입니다"}}
        fun versionReference(value:String):String=value.also {require(it.matches(Regex("[A-Za-z0-9_+.-]{1,160}")) && !it.contains("..") && it!=".") {"잘못된 Modrinth 버전입니다"}}
        fun parse(input:String):ModrinthLink {
            val value=input.trim();require(value.length in 1..2048)
            if(!value.contains("://"))return ModrinthLink(identifier(value))
            val uri=URI(value)
            require(uri.scheme=="https" && uri.host in setOf("modrinth.com","www.modrinth.com") && uri.userInfo==null && uri.port in setOf(-1,443)) {"Modrinth의 HTTPS 모드 링크를 입력하세요"}
            val parts=uri.path.trimEnd('/').split('/').drop(1)
            require(parts.size in setOf(2,4) && parts[0]=="mod" && (parts.size==2 || parts[2]=="version")) {"Modrinth 모드 또는 모드 버전 링크를 입력하세요"}
            return ModrinthLink(identifier(parts[1]),if(parts.size==4)versionReference(parts[3]) else null)
        }
    }
}
data class ModrinthTarget(val minecraft:String,val loader:String,val allowPrerelease:Boolean=false) {
    init {require(minecraft.matches(Regex("[0-9]+(?:\\.[0-9]+){1,2}")));require(loader in setOf("fabric","forge","neoforge")) {"이 모드 기능은 Fabric·Forge·NeoForge 서버를 지원합니다"}}
}
data class RinProject(val id:String,val slug:String,val title:String,val serverSide:String,val type:String="mod",val environment:String="unknown",
    val description:String="",val iconUrl:String?=null)
/** Modrinth environments where a dedicated server cannot load the mod. */
val CLIENT_ENVIRONMENTS=setOf("client_only","singleplayer_only")
data class RinDependency(val projectId:String?,val versionId:String?,val type:String,val filename:String?=null)
data class RinFile(val filename:String,val url:String,val size:Long,val sha512:String,val sha1:String,val primary:Boolean) {
    fun validate():RinFile {
        require(filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+() -]{0,199}\\.jar",RegexOption.IGNORE_CASE)) && !filename.contains("..")) {"안전한 JAR 파일 이름이 아닙니다"}
        require(filename.substringBefore('.').uppercase() !in setOf("CON","PRN","AUX","NUL","COM1","COM2","COM3","COM4","COM5","COM6","COM7","COM8","COM9","LPT1","LPT2","LPT3","LPT4","LPT5","LPT6","LPT7","LPT8","LPT9"))
        downloadUri(url)
        require(size in 1..256L*1024*1024) {"모드 파일은 256MiB 이하여야 합니다"}
        require(sha512.matches(Regex("[a-f0-9]{128}")) && sha1.matches(Regex("[a-f0-9]{40}"))) {"공식 모드 파일 해시가 없습니다"}
        return this
    }
    companion object {
        fun downloadUri(value:String):URI=URI(value).also {require(it.scheme=="https" && it.host=="cdn.modrinth.com" && it.userInfo==null && it.port in setOf(-1,443) && it.fragment==null) {"허용되지 않은 Modrinth 파일 주소입니다"}}
    }
}
data class RinVersion(val id:String,val projectId:String,val name:String,val number:String,val type:String,val published:String,
    val games:Set<String>,val loaders:Set<String>,val files:List<RinFile>,val dependencies:List<RinDependency>,val environment:String="unknown") {
    fun compatible(target:ModrinthTarget)=target.minecraft in games && target.loader in loaders && environment !in setOf("client_only","singleplayer_only")
    fun primaryFile():RinFile {
        val jars=files.filter {it.filename.endsWith(".jar",true)}
        val main=jars.filter {it.primary}
        return (if(main.size==1)main.single() else if(main.isEmpty() && jars.size==1)jars.single() else error("설치할 기본 JAR를 하나로 결정할 수 없습니다: $name")).validate()
    }
}
data class RinSearchHit(val projectId:String,val slug:String,val title:String,val description:String)
data class RinSearch(val hits:List<RinSearchHit>,val offset:Int,val total:Int)
/** Hash and bulk-project lookups used to build the installed mod dependency graph. */
interface ModrinthLookup {
    fun versionsFromHashes(hashes:List<String>):Map<String,RinVersion>
    fun projects(ids:Collection<String>):List<RinProject>
}
interface ModrinthRepository {
    fun project(id:String):RinProject
    fun versions(project:String,target:ModrinthTarget):List<RinVersion>
    fun version(id:String):RinVersion
    fun projectVersion(project:String,idOrNumber:String):RinVersion
}

class ModrinthApi(private val fetch:(String)->String=Transport()::get,private val post:(String,String)->String=Transport()::post):ModrinthRepository,ModrinthLookup {
    private val mapper=jacksonObjectMapper()
    private fun enc(s:String)=URLEncoder.encode(s,Charsets.UTF_8)
    private fun token(s:String)=ModrinthLink.identifier(s)
    private fun strings(n:JsonNode)=n.map {it.asText()}.toSet()
    private fun nullable(n:JsonNode):String?=n.takeUnless {it.isNull || it.isMissingNode}?.asText()?.takeIf {it.isNotBlank()}
    private fun parseVersion(n:JsonNode)=RinVersion(token(n.path("id").asText()),token(n.path("project_id").asText()),n.path("name").asText(),n.path("version_number").asText(),n.path("version_type").asText(),n.path("date_published").asText(),strings(n.path("game_versions")),strings(n.path("loaders")),n.path("files").map {f->RinFile(f.path("filename").asText(),f.path("url").asText(),f.path("size").asLong(-1),f.path("hashes").path("sha512").asText().lowercase(),f.path("hashes").path("sha1").asText().lowercase(),f.path("primary").asBoolean())},n.path("dependencies").map {d->RinDependency(nullable(d.path("project_id"))?.let(::token),nullable(d.path("version_id"))?.let(::token),d.path("dependency_type").asText(),nullable(d.path("file_name"))).also {require(it.type in setOf("required","optional","incompatible","embedded")) {"알 수 없는 모드 의존성 종류입니다"}}},nullable(n.path("environment")) ?: "unknown")
    /** Project `environment` is an array of every version's environment; versions carry a single string. */
    private fun projectEnvironment(n:JsonNode):String {
        val values=if(n.isArray)n.map {it.asText()}.filter {it.isNotBlank() && it!="unknown"} else listOfNotNull(nullable(n)?.takeIf {it!="unknown"})
        return if(values.isEmpty())"unknown" else values.firstOrNull {it !in CLIENT_ENVIRONMENTS} ?: values.first()
    }
    private fun parseProject(n:JsonNode)=RinProject(token(n.path("id").asText()),token(n.path("slug").asText()),n.path("title").asText().take(200),n.path("server_side").asText("unknown"),n.path("project_type").asText(),projectEnvironment(n.path("environment")),
        n.path("description").asText().take(500),nullable(n.path("icon_url"))?.takeIf {runCatching {URI(it).let {u->u.scheme=="https" && u.host=="cdn.modrinth.com"}}.getOrDefault(false)})
    override fun project(id:String):RinProject=parseProject(mapper.readTree(fetch("/project/${token(id)}")))
    override fun projects(ids:Collection<String>):List<RinProject> {
        val wanted=ids.map(::token).distinct();require(wanted.size<=2000)
        return wanted.chunked(100).flatMap {batch->
            val n=mapper.readTree(fetch("/projects?ids=${enc(mapper.writeValueAsString(batch))}"));require(n.isArray && n.size()<=batch.size)
            n.map(::parseProject).also {rows->check(rows.all {it.id in batch}) {"요청하지 않은 Modrinth 프로젝트가 반환됐습니다"}}
        }
    }
    override fun versions(project:String,target:ModrinthTarget):List<RinVersion> {
        val path="/project/${token(project)}/version?loaders=${enc(mapper.writeValueAsString(listOf(target.loader)))}&game_versions=${enc(mapper.writeValueAsString(listOf(target.minecraft)))}&include_changelog=false"
        val n=mapper.readTree(fetch(path));require(n.isArray && n.size()<=20000) {"모드 버전 목록을 확인하세요"}
        return n.map(::parseVersion).filter {it.compatible(target)}
    }
    override fun version(id:String)=parseVersion(mapper.readTree(fetch("/version/${token(id)}")))
    override fun projectVersion(project:String,idOrNumber:String)=parseVersion(mapper.readTree(fetch("/project/${token(project)}/version/${enc(ModrinthLink.versionReference(idOrNumber))}")))
    fun search(query:String,target:ModrinthTarget,offset:Int=0):RinSearch {
        require(query.trim().length in 1..120 && offset in 0..10000)
        val facets=listOf(listOf("project_type:mod"),listOf("versions:${target.minecraft}"),listOf("categories:${target.loader}"))
        val n=mapper.readTree(fetch("/search?query=${enc(query.trim())}&facets=${enc(mapper.writeValueAsString(facets))}&limit=20&offset=$offset"))
        require(n.path("hits").isArray && n.path("hits").size()<=20)
        return RinSearch(n.path("hits").map {h->RinSearchHit(token(h.path("project_id").asText()),token(h.path("slug").asText()),h.path("title").asText().take(200),h.path("description").asText().take(1000))},offset,n.path("total_hits").asInt())
    }
    override fun versionsFromHashes(hashes:List<String>):Map<String,RinVersion> {
        require(hashes.size in 1..500 && hashes.all {it.matches(Regex("[a-f0-9]{128}"))}) {"SHA-512 파일 해시를 입력하세요"}
        val requested=hashes.toSet()
        val n=mapper.readTree(post("/version_files",mapper.writeValueAsString(mapOf("hashes" to requested,"algorithm" to "sha512"))))
        require(n.isObject && n.size()<=requested.size)
        return n.fields().asSequence().associate {(hash,value)->
            check(hash in requested) {"요청하지 않은 모드 해시가 반환됐습니다"}
            val v=parseVersion(value);check(v.files.any {it.sha512==hash}) {"모드 파일 출처와 해시가 일치하지 않습니다"}
            hash to v
        }
    }
    private class Transport {
        private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
        fun get(path:String):String=request(path,null)
        fun post(path:String,body:String):String=request(path,body)
        private fun request(path:String,body:String?):String {
            require(path.startsWith('/') && !path.startsWith("//"))
            val uri=URI("https://api.modrinth.com/v2$path");require(uri.host=="api.modrinth.com" && uri.rawPath.startsWith("/v2/"))
            val builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("User-Agent","yoonseo6399/ServerManager (https://github.com/yoonseo6399/ServerManager)")
            if(body==null)builder.GET() else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body))
            val r=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream())
            return r.body().use {input->
                if(r.statusCode()==429)error("Modrinth 조회 한도에 도달했습니다. 잠시 후 다시 시도하세요")
                check(r.statusCode()==200) {"Modrinth 응답 HTTP ${r.statusCode()}"}
                val b=input.readNBytes(16*1024*1024+1);require(b.size<=16*1024*1024) {"Modrinth 응답이 너무 큽니다"};b.toString(Charsets.UTF_8)
            }
        }
    }
}

data class RinPlannedMod(val project:RinProject,val version:RinVersion,val file:RinFile,val alreadyInstalled:Boolean)
data class RinInstallPlan(val target:ModrinthTarget,val rootProject:String,val mods:List<RinPlannedMod>,val warnings:List<String>)

/** Bounded constraint search: existing linked versions are pinned; dependency conflicts never silently upgrade them. */
class ModrinthResolver(private val repository:ModrinthRepository) {
    fun plan(input:String,target:ModrinthTarget,installed:List<RinVersion> = emptyList()):RinInstallPlan {
        require(installed.size<=1000);check(installed.map {it.projectId}.distinct().size==installed.size) {"같은 프로젝트의 여러 모드가 설치되어 있습니다"}
        val link=ModrinthLink.parse(input);val projects=mutableMapOf<String,RinProject>();val versions=mutableMapOf<String,RinVersion>();val catalogs=mutableMapOf<String,List<RinVersion>>()
        fun project(id:String)=projects.getOrPut(id){repository.project(id)}.also {projects[it.id]=it}
        fun version(id:String)=versions.getOrPut(id){repository.version(id)}.also {check(it.id==id) {"Modrinth 버전 식별자가 일치하지 않습니다"}}
        val root=project(link.project)
        val pinned=link.version?.let {repository.projectVersion(root.id,it).also {v->check(v.projectId==root.id) {"다른 프로젝트의 버전입니다"};versions[v.id]=v}.id}
        fun dependency(d:RinDependency):Pair<String,String?> {
            val v=d.versionId?.let(::version)
            if(v!=null && d.projectId!=null)check(v.projectId==d.projectId) {"필수 의존성의 프로젝트와 버전이 일치하지 않습니다"}
            return (d.projectId ?: v?.projectId ?: error("파일 이름만 있는 필수 의존성은 자동으로 설치할 수 없습니다")) to v?.id
        }
        var attempts=0;val reasons=linkedSetOf<String>()
        fun solve(assigned:Map<String,RinVersion>,required:Map<String,String>,needed:Set<String>,processed:Set<String>,depth:Int):Map<String,RinVersion>? {
            check(++attempts<=500 && depth<=128 && needed.size<=128) {"의존성 탐색 범위를 초과했습니다. 모드 버전을 직접 지정하세요"}
            if(required.any {(p,v)->assigned[p]?.id?.let {it!=v}==true})return null
            val next=(needed-processed).sortedBy {if(it in required)0 else 1}.firstOrNull()
            if(next==null) {
                for(v in assigned.values) for(d in v.dependencies.filter {it.type=="incompatible"}) {
                    val vId=d.versionId;val pId=d.projectId ?: vId?.let(::version)?.projectId
                    if((pId!=null && assigned[pId]?.let {vId==null || it.id==vId}==true) || (pId==null && d.filename!=null && assigned.values.any {it.primaryFile().filename==d.filename})) {
                        reasons.add("호환되지 않는 모드 조합: ${v.number} / ${pId ?: d.filename}");return null
                    }
                }
                return assigned.filterKeys {it in needed}
            }
            val p=project(next)
            if(p.type!="mod") {reasons.add("서버에서 지원하지 않는 프로젝트: ${p.title}");return null}
            val candidates=assigned[next]?.let {listOf(it)} ?: required[next]?.let {listOf(version(it))} ?: catalogs.getOrPut(next) {
                repository.versions(next,target).filter {it.projectId==next && it.compatible(target) && (target.allowPrerelease || it.type=="release")}
                    .sortedByDescending {runCatching {Instant.parse(it.published)}.getOrDefault(Instant.EPOCH)}
            }
            for(v in candidates) {
                if(v.projectId!=next || !v.compatible(target) || (required[next]!=null && required[next]!=v.id))continue
                val environment=if(v.environment=="unknown")p.environment else v.environment
                if(environment in setOf("client_only","singleplayer_only") || (environment=="unknown" && p.serverSide=="unsupported"))continue
                v.primaryFile();val fixed=required.toMutableMap();val pending=needed.toMutableSet();var conflict=false
                for(d in v.dependencies.filter {it.type=="required"}) {
                    val (dep,exact)=dependency(d);pending.add(dep)
                    if(exact!=null) {if(fixed[dep]?.let {it!=exact}==true){conflict=true;break};fixed[dep]=exact}
                }
                if(!conflict)solve(assigned+(next to v),fixed,pending,processed+next,depth+1)?.let {return it}
            }
            reasons.add("${p.title}에 맞는 ${target.minecraft}/${target.loader} 버전 또는 의존성 조합이 없습니다")
            return null
        }
        val selected=solve(installed.associateBy {it.projectId},pinned?.let {mapOf(root.id to it)} ?: emptyMap(),setOf(root.id),emptySet(),0)
            ?: error(reasons.take(8).joinToString("\n").ifEmpty {"설치된 모드와 요청한 버전이 충돌합니다"})
        val mods=selected.map {(id,v)->RinPlannedMod(project(id),v,v.primaryFile(),installed.any {it.id==v.id && it.projectId==id})}
        val files=(installed.filter {it.projectId !in selected}+selected.values).map {it.primaryFile()}
        check(files.map {it.filename.lowercase()}.distinct().size==files.size) {"여러 모드가 같은 파일 이름을 사용합니다"}
        check(files.map {it.sha512}.distinct().size==files.size) {"동일한 모드 파일이 여러 프로젝트로 중복됩니다"}
        return RinInstallPlan(target,root.id,mods,buildList {if(mods.any {it.version.dependencies.any {d->d.type=="embedded"}})add("JAR에 내장된 의존성은 별도로 다운로드하지 않습니다");if(installed.isNotEmpty())add("기존 Modrinth 연결 버전은 고정하며 자동으로 교체하지 않습니다")})
    }
}
