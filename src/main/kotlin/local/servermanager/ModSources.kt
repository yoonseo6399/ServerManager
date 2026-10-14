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
data class ModSourceCache(val sources:Map<String,ModSource> = emptyMap(),val notFound:Map<String,String> = emptyMap(),val projects:Map<String,ModProjectRef> = emptyMap())
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
        val pending=wanted.filter {it !in cache.sources && (retryMissing || it !in cache.notFound)}
        if(pending.isEmpty())return ModSourceLookup(cache,emptyList())
        return try {
            val versions=pending.chunked(500).flatMap {batch->api.versionsFromHashes(batch).also {rows->check(rows.keys.all {it in batch}) {"요청하지 않은 모드 해시입니다"}}.entries}.associate {it.key to it.value}
            val dependencyIds=versions.values.flatMap {v->v.dependencies.mapNotNull {it.versionId}}.toSet()
            require(dependencyIds.size<=4000) {"의존성 버전 조회 범위가 너무 큽니다"}
            val dependencyVersions=api.versionsByIds(dependencyIds).associateBy {it.id}
            check(dependencyVersions.keys==dependencyIds) {"정확한 의존성 버전이 없습니다"}
            val dependencies=versions.mapValues {(_,v)->v.dependencies.map {d->
                val exact=d.versionId?.let {id->dependencyVersions.getValue(id)}
                check(exact==null || d.projectId==null || exact.projectId==d.projectId) {"의존성 프로젝트와 버전이 다릅니다"}
                ModDependency(d.projectId ?: exact?.projectId,d.type,d.versionId,d.filename)
            }}
            val ids=(versions.values.map {it.projectId}+dependencies.values.flatten().mapNotNull {it.projectId}).toSet()
            // Own projects are always fetched for their environment; dependency projects only for missing display names.
            val projects=api.projects(ids.filter {id->id in versions.values.map {it.projectId} || id !in cache.projects}).associateBy {it.id}
            val sources=versions.mapValues {(hash,v)->
                val p=projects[v.projectId]
                ModSource(v.projectId,p?.slug ?: v.projectId,p?.title ?: v.name,p?.description.orEmpty(),p?.iconUrl,v.id,v.number,
                    v.environment.takeUnless {it=="unknown"} ?: p?.environment ?: "unknown",clientOnly(v,p),
                    dependencies.getValue(hash).distinct())
            }
            val now=Instant.now().toString()
            // Entries for files no longer present are dropped so the store stays bounded by the mods folder.
            val keep={hash:String->hash in wanted}
            val merged=ModSourceCache((cache.sources+sources).filterKeys(keep),(cache.notFound-sources.keys+(pending-sources.keys).associateWith {now}).filterKeys(keep),
                cache.projects+projects.mapValues {(_,p)->ModProjectRef(p.id,p.slug,p.title,p.iconUrl)})
            ModSourceLookup(merged,emptyList())
        } catch(e:Exception) {
            ModSourceLookup(cache,listOf("Modrinth 조회에 실패해 저장된 의존성 정보만 사용합니다: ${e.message?.take(200) ?: e.javaClass.simpleName}"))
        }
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
        val merged=result.copy(cache=ModSourceCache(cached.sources+result.cache.sources,cached.notFound-mod.sha512,cached.projects+result.cache.projects))
        ModSources.save(store,merged.cache);return merged
    }
    fun lookup(store:Path,hashes:Collection<String>,retryMissing:Boolean):ModSourceLookup {
        val cached=ModSources.load(store)
        return ModSources.lookup(api,hashes,cached,retryMissing).also {if(it.cache!=cached)ModSources.save(store,it.cache)}
    }
}
