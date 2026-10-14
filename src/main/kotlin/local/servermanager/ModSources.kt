package local.servermanager

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*

data class ModDependency(val projectId:String,val type:String)
/** Modrinth identity of one exact file, matched only by its SHA-512. */
@JsonIgnoreProperties(ignoreUnknown=true)
data class ModSource(val projectId:String,val slug:String,val title:String,val description:String="",val iconUrl:String?=null,
    val versionId:String,val versionNumber:String,val environment:String="unknown",val clientOnly:Boolean=false,val dependencies:List<ModDependency> = emptyList()) {
    @get:JsonIgnore val required get()=dependencies.filter {it.type=="required"}.map {it.projectId}.toSet()
    /** Projects this file satisfies: itself plus libraries Modrinth lists as embedded in the JAR. */
    @get:JsonIgnore val provides get()=dependencies.filter {it.type=="embedded"}.map {it.projectId}.toSet()+projectId
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
            val versions=pending.chunked(500).flatMap {api.versionsFromHashes(it).entries}.associate {it.key to it.value}
            val ids=(versions.values.map {it.projectId}+versions.values.flatMap {v->v.dependencies.mapNotNull {it.projectId}}).toSet()
            // Own projects are always fetched for their environment; dependency projects only for missing display names.
            val projects=api.projects(ids.filter {id->id in versions.values.map {it.projectId} || id !in cache.projects}).associateBy {it.id}
            val sources=versions.mapValues {(_,v)->
                val p=projects[v.projectId]
                ModSource(v.projectId,p?.slug ?: v.projectId,p?.title ?: v.name,p?.description.orEmpty(),p?.iconUrl,v.id,v.number,
                    v.environment.takeUnless {it=="unknown"} ?: p?.environment ?: "unknown",clientOnly(v,p),
                    v.dependencies.mapNotNull {d->d.projectId?.takeIf {it!=v.projectId}?.let {ModDependency(it,d.type)}}.distinct())
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
    fun lookup(store:Path,hashes:Collection<String>,retryMissing:Boolean):ModSourceLookup {
        val cached=ModSources.load(store)
        return ModSources.lookup(api,hashes,cached,retryMissing).also {if(it.cache!=cached)ModSources.save(store,it.cache)}
    }
}
