package local.servermanager

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.*

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
data class InstalledMod(val filename:String,val enabled:Boolean,val size:Long,val sha256:String,val sha512:String,
    val source:ModSource?=null,val warning:String?=null)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
data class ModInventorySnapshot(val revision:String,val mods:List<InstalledMod>,val warnings:List<String>,val dependencyFiles:List<String> = emptyList(),val dependencyReasons:Map<String,List<String>> = emptyMap(),
    val missing:Map<String,List<String>> = emptyMap(),val projects:Map<String,ModProjectRef> = emptyMap())
data class ModContentLink(val filename:String,val version:RinVersion,val file:RinFile)

/** An explicit, read-only scan. Do not call from periodic status/UI polling. */
object ModInventory {
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    /** Hashes files only. JAR contents are never parsed; dependencies come from [attach]. */
    fun scan(directory:Path):ModInventorySnapshot {
        val paths=if(!directory.exists())emptyList() else {
            require(directory.isDirectory(NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory))
            Files.list(directory).use {items->
                val entries=items.limit(4001).toList();require(entries.size<=4000) {"모드 폴더 항목이 너무 많습니다"}
                require(entries.none {Files.isSymbolicLink(it)}) {"모드 폴더에 심볼릭 링크가 있습니다"}
                entries.filter {it.name.endsWith(".jar",true)||it.name.endsWith(".jar.disabled",true)}.sortedBy {it.name}
            }
        }
        require(paths.size<=2000)
        val attrs=paths.associateWith {Files.readAttributes(it,BasicFileAttributes::class.java,NOFOLLOW_LINKS)}
        require(attrs.values.all {it.isRegularFile && it.size() in 1..256L*1024*1024}) {"모드는 256MiB 이하 일반 JAR 파일이어야 합니다"}
        require(attrs.values.sumOf {it.size()}<=8L*1024*1024*1024) {"한 번에 검사할 모드는 합계 8GiB까지입니다"}
        val buffer=ByteArray(1024*1024)
        val mods=paths.map {p->
            val before=attrs.getValue(p);val sha256=MessageDigest.getInstance("SHA-256");val sha512=MessageDigest.getInstance("SHA-512")
            p.inputStream().use {input->var count=0L;while(true) {val n=input.read(buffer);if(n<0)break;count+=n;check(count<=before.size()) {"검사 중 모드 크기가 바뀌었습니다"};sha256.update(buffer,0,n);sha512.update(buffer,0,n)};check(count==before.size())}
            val after=Files.readAttributes(p,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            check(after.isRegularFile && before.size()==after.size() && before.lastModifiedTime()==after.lastModifiedTime() && before.fileKey()==after.fileKey()) {"검사 중 모드가 변경되었습니다. 다시 검사하세요"}
            InstalledMod(p.name,!p.name.endsWith(".disabled",true),before.size(),hex(sha256.digest()),hex(sha512.digest()))
        }
        val revision=MessageDigest.getInstance("SHA-256")
        mods.forEach {revision.update("${it.filename}\u0000${it.enabled}\u0000${it.size}\u0000${it.sha512}\n".toByteArray())}
        return ModInventorySnapshot(hex(revision.digest()),mods,emptyList())
    }
    /** Adds saved Modrinth sources and the dependency graph. The revision still identifies file content only. */
    fun attach(snapshot:ModInventorySnapshot,lookup:ModSourceLookup):ModInventorySnapshot {
        val mods=snapshot.mods.map {it.copy(source=lookup.cache.sources[it.sha512],warning=if(it.sha512 in lookup.cache.sources)null else "Modrinth에서 찾지 못해 의존성을 확인할 수 없습니다")}
        val active=mods.filter {it.enabled}
        val warnings=buildList {
            addAll(lookup.warnings)
            if(mods.groupBy {if(it.filename.endsWith(".disabled",true))it.filename.dropLast(9).lowercase() else it.filename.lowercase()}.any {it.value.size>1})add("같은 파일 이름의 활성/비활성 모드가 중복됩니다")
            if(active.groupBy {it.sha512}.any {it.value.size>1})add("내용이 같은 모드 JAR가 중복됩니다")
            active.mapNotNull {it.source}.groupBy {it.projectId}.filter {it.value.size>1}.values.sortedBy {it.first().title}.forEach {add("같은 모드가 여러 파일로 켜져 있습니다: ${it.first().title}")}
            val unknown=mods.count {it.source==null}
            if(unknown>0)add("Modrinth에서 찾지 못한 모드 ${unknown}개는 의존성을 확인하지 못합니다. 직접 받은 파일이거나 다른 사이트의 모드일 수 있습니다")
        }
        val graph=ModGraph(snapshot.copy(mods=mods))
        val names=lookup.cache.projects.filterKeys {id->mods.any {m->m.source?.projectId==id || m.source?.dependencies?.any {it.projectId==id}==true}}
        return snapshot.copy(mods=mods,warnings=warnings,dependencyFiles=graph.dependencyFiles.sorted(),dependencyReasons=graph.dependencyReasons,
            missing=graph.missing.mapValues {it.value.sorted()},projects=names)
    }
    /** A content match never uses the local filename as proof of identity. */
    fun link(snapshot:ModInventorySnapshot,lookup:(List<String>)->Map<String,RinVersion>):List<ModContentLink> {
        val versions=snapshot.mods.map {it.sha512}.distinct().chunked(500).flatMap {batch->
            val result=lookup(batch);check(result.keys.all {it in batch}) {"요청하지 않은 모드 출처가 반환됐습니다"};result.entries
        }.associate {it.key to it.value}
        return snapshot.mods.mapNotNull {mod->versions[mod.sha512]?.let {v->
            val files=v.files.filter {it.sha512==mod.sha512 && it.size==mod.size}
            check(files.size==1) {"모드 출처의 크기·해시를 확인하지 못했습니다"}
            ModContentLink(mod.filename,v,files.single().validate())
        }}
    }
}
