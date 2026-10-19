package local.servermanager

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*

data class ModDependency(val projectId:String?,val type:String,val versionId:String?=null,val filename:String?=null)
/** Modrinth identity of one exact file, matched only by its SHA-512. */
@JsonIgnoreProperties(ignoreUnknown=true)
data class ModSource(val projectId:String,val slug:String,val title:String,val description:String="",val iconUrl:String?=null,
    val versionId:String,val versionNumber:String,val environment:String="unknown",val clientOnly:Boolean=false,val dependencies:List<ModDependency> = emptyList(),val origin:String="MODRINTH") {
    @get:JsonIgnore val required get()=dependencies.filter {it.type=="required"}.mapNotNull {it.projectId}.toSet()
    /** Projects this file satisfies: itself plus libraries Modrinth lists as embedded in the JAR. */
    @get:JsonIgnore val provides get()=dependencies.filter {it.type=="embedded"}.mapNotNull {it.projectId}.toSet()+projectId
}
data class ModProjectRef(val id:String,val slug:String,val title:String,val iconUrl:String?=null)
/** Saved per server so later scans, toggles and searches reuse dependencies without asking Modrinth again. */
@JsonIgnoreProperties(ignoreUnknown=true)
data class ModSourceCache(val sources:Map<String,ModSource> = emptyMap(),val notFound:Map<String,String> = emptyMap(),val projects:Map<String,ModProjectRef> = emptyMap(),val incomplete:Set<String> = emptySet())
data class ModSourceLookup(val cache:ModSourceCache,val warnings:List<String>)

object ModSources {
    private val mapper=jacksonObjectMapper()
    /** Version environment wins; the deprecated server_side field is only a fallback when Modrinth has no environment. */
    fun clientOnly(version:RinVersion,project:RinProject?):Boolean {
        val environment=version.environment.takeUnless {it=="unknown"} ?: project?.environment?.takeUnless {it=="unknown"}
        return if(environment!=null)environment in CLIENT_ENVIRONMENTS else project?.serverSide=="unsupported"
    }
    fun load(file:Path):ModSourceCache=file.takeIf {it.isRegularFile()}?.let {
        require(it.fileSize()<32L*1024*1024) {"저장된 모드 의존성 정보가 너무 큽니다"}
        runCatching {mapper.readValue<ModSourceCache>(it.toFile())}.getOrNull()
    } ?: ModSourceCache()
    fun save(file:Path,cache:ModSourceCache)=atomicText(file,mapper.writeValueAsString(cache))
    /** Only uncached hashes are sent. Files Modrinth did not know are asked again only when [retryMissing] is set. */
    fun lookup(api:ModrinthLookup,hashes:Collection<String>,cache:ModSourceCache,retryMissing:Boolean):ModSourceLookup {
        val wanted=hashes.toSet()
        val pending=wanted.filter {(it !in cache.sources || retryMissing && it in cache.incomplete) && (retryMissing || it !in cache.notFound)}
        if(pending.isEmpty())return ModSourceLookup(cache,emptyList())
        val warnings=mutableListOf<String>()
        val failed=mutableSetOf<String>()
        var isolationBudget=32
        // Split invalid rows only, with a hard request budget. Network outages are not retried per file.
        fun <T> partial(ids:List<String>,label:String,fetch:(List<String>)->Map<String,T>):Map<String,T> {
            if(ids.isEmpty())return emptyMap()
            return try {fetch(ids).also {rows->check(rows.keys.all {it in ids}) {"요청하지 않은 조회 결과입니다"}}}
            catch(e:Exception) {
                val message=e.message.orEmpty()
                val invalid=e is IllegalArgumentException || e is ModrinthMetadataException
                if(ids.size>1 && invalid && isolationBudget>1) {
                    isolationBudget-=2
                    val halves=ids.chunked((ids.size+1)/2)
                    halves.flatMap {partial(it,label,fetch).entries}.associate {it.key to it.value}
                } else {
                    failed.addAll(ids)
                    warnings.add("Modrinth $label ${ids.size}개 조회 실패 · 나머지 모드는 유지합니다: ${message.take(160).ifBlank {e.javaClass.simpleName}}")
                    emptyMap()
                }
            }
        }
        val versions=pending.chunked(100).flatMap {batch->partial(batch,"파일",api::versionsFromHashes).entries}.associate {it.key to it.value}
        // A published project ID plus exact version ID is already enough for matching. Resolve only version-only edges.
        val dependencyIds=versions.values.flatMap {v->v.dependencies.filter {it.projectId==null}.mapNotNull {it.versionId}}.distinct()
        val dependencyVersions=if(dependencyIds.size<=4000)dependencyIds.chunked(100).flatMap {batch->
            partial(batch,"의존성 버전") {api.versionsByIds(it).associateBy {v->v.id}}.entries
        }.associate {it.key to it.value} else {warnings.add("의존성 조회 범위를 초과해 버전 전용 의존성은 미확인으로 표시합니다");emptyMap()}
        val dependencies=versions.mapValues {(_,v)->v.dependencies.map {d->
            ModDependency(d.projectId ?: d.versionId?.let {dependencyVersions[it]?.projectId},d.type,d.versionId,d.filename)
        }.distinct()}
        val ids=(versions.values.map {it.projectId}+dependencies.values.flatten().mapNotNull {it.projectId}).distinct()
        if(ids.size>4000)warnings.add("프로젝트 정보 조회 범위를 초과해 일부 모드는 버전 정보만 표시합니다")
        val projectIds=ids.take(4000).filter {id->id in versions.values.map {it.projectId} || id !in cache.projects}
        val projects=projectIds.chunked(100).flatMap {batch->partial(batch,"프로젝트") {api.projects(it).associateBy {p->p.id}}.entries}.associate {it.key to it.value}
        if(projectIds.any {it !in projects})warnings.add("프로젝트 이름·설명 일부를 확인하지 못했습니다. 파일·버전 정보는 유지합니다")
        val incomplete=versions.filter {(hash,v)->v.projectId !in projects || dependencies.getValue(hash).any {it.projectId==null && it.versionId!=null}}.keys
        val sources=versions.mapValues {(hash,v)->
            val p=projects[v.projectId]
            val previous=cache.sources[hash]?.takeIf {it.projectId==v.projectId}
            ModSource(v.projectId,p?.slug ?: previous?.slug ?: v.projectId,p?.title ?: previous?.title ?: v.name,
                p?.description ?: previous?.description.orEmpty(),p?.iconUrl ?: previous?.iconUrl,v.id,v.number,
                v.environment.takeUnless {it=="unknown"} ?: p?.environment ?: previous?.environment ?: "unknown",
                if(p==null && v.environment=="unknown")previous?.clientOnly ?: false else clientOnly(v,p),dependencies.getValue(hash))
        }
        val missingDependencies=dependencyIds-dependencyVersions.keys
        if(missingDependencies.isNotEmpty())warnings.add("의존성 버전 ${missingDependencies.size}개를 확인하지 못했습니다. 모드는 그대로 표시하며 의존성을 충족했다고 가정하지 않습니다")
        val now=Instant.now().toString()
        // Only a successful negative lookup is cached as not-found; transient failures stay retryable.
        val notFound=cache.notFound-sources.keys+(pending-sources.keys-failed).associateWith {now}
        val merged=ModSourceCache((cache.sources+sources).filterKeys {it in wanted},notFound.filterKeys {it in wanted},
            cache.projects+projects.mapValues {(_,p)->ModProjectRef(p.id,p.slug,p.title,p.iconUrl)},(cache.incomplete-versions.keys+incomplete).intersect(wanted))
        return ModSourceLookup(if(sources.isEmpty() && failed.containsAll(pending))cache else merged,warnings.distinct().take(30))
    }
}

/** Shared Modrinth access for mod management and mrpack imports. */
@Service
class ModrinthCatalog internal constructor(val api:ModrinthLookup) {
    constructor():this(ModrinthApi())
    /** An explicit link can repair a hash-index miss, but must still match exact published file bytes. */
    fun link(store:Path,snapshot:ModInventorySnapshot,filename:String,input:String):ModSourceLookup {
        val mod=snapshot.mods.single {it.filename==filename}
        val repository=api as? ModrinthRepository ?: error("Modrinth 링크 조회를 지원하지 않습니다")
        val link=ModrinthLink.parse(input);require(link.version!=null) {"정확한 Modrinth 버전 링크를 입력하세요"}
        val project=repository.project(link.project)
        val version=repository.projectVersion(project.id,link.version)
        check(version.projectId==project.id && version.files.count {it.sha512==mod.sha512 && it.size==mod.size}==1) {"선택한 파일과 Modrinth 버전의 해시·크기가 다릅니다"}
        val adapter=object:ModrinthLookup {
            override fun versionsFromHashes(hashes:List<String>)=mapOf(mod.sha512 to version)
            override fun projects(ids:Collection<String>)=api.projects(ids)
            override fun version(id:String)=api.version(id)
            override fun versionsByIds(ids:Collection<String>)=api.versionsByIds(ids)
        }
        val cached=ModSources.load(store)
        val result=ModSources.lookup(adapter,listOf(mod.sha512),cached.copy(sources=cached.sources-mod.sha512,notFound=cached.notFound-mod.sha512),true)
        require(mod.sha512 in result.cache.sources) {result.warnings.joinToString()}
        val merged=result.copy(cache=ModSourceCache(cached.sources+result.cache.sources,cached.notFound-mod.sha512,cached.projects+result.cache.projects,(cached.incomplete-mod.sha512)+result.cache.incomplete))
        ModSources.save(store,merged.cache);return merged
    }
    fun lookup(store:Path,hashes:Collection<String>,retryMissing:Boolean):ModSourceLookup {
        val cached=ModSources.load(store)
        return ModSources.lookup(api,hashes,cached,retryMissing).also {if(it.cache!=cached)ModSources.save(store,it.cache)}
    }
}
