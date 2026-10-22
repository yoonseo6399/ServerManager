package local.servermanager

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlin.io.path.*

data class ProjectAgentFileChange(val path:String,val before:String?,val after:String?,val diff:String)

/** Display-only bounded snapshots. They never authorize a read, feed file bytes to the model, or undo changes. */
internal class ProjectAgentFileChanges(home:Path,profile:Path) {
    private val aliasHome=home.toAbsolutePath().normalize()
    private val home=home.toRealPath()
    private val profile=profile.toRealPath()
    private fun normalized(path:Path):Path {val p=path.toAbsolutePath().normalize();return if(p.startsWith(aliasHome))home.resolve(aliasHome.relativize(p))else p}
    data class Capture(val files:Map<String,String?>,val existing:Set<String>,val complete:Boolean)
    data class Snapshot(val captured:Capture,val broad:Boolean,val paths:List<Path>)
    private val ignored=setOf(".git","node_modules",".gradle","build","target",".idea",".venv","venv","__pycache__")
    private fun safe(path:Path):Boolean {
        val p=normalized(path);val root=profile.toAbsolutePath().normalize();val project=home.toAbsolutePath().normalize()
        if(p.startsWith(root) && !(project.startsWith(root) && project!=root && p.startsWith(project)))return false
        if(p.any {ProjectAgentShell.isSecret(it.toString()) || it.toString() in setOf(".release-keys","private.der")})return false
        var ancestor:Path?=p;while(ancestor!=null){if(Files.isSymbolicLink(ancestor))return false;ancestor=ancestor.parent};return true
    }
    private fun text(path:Path):String?=runCatching {
        if(!safe(path) || !path.isRegularFile() || path.fileSize()>262144)return null
        val bytes=path.inputStream().use {it.readNBytes(262145)};if(bytes.size>262144 || bytes.any {it==0.toByte()})return null
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    private fun collect(paths:List<Path>,broad:Boolean):Capture {
        val values=linkedMapOf<String,String?>();val existing=mutableSetOf<String>();var complete=true;var bytes=0;var visited=0
        fun add(path:Path) {existing.add(path.toAbsolutePath().normalize().toString());if(values.size>=1000 || bytes>=4*1024*1024){complete=false;return};val value=text(path);if(value!=null){bytes+=value.length*2;if(bytes<=4*1024*1024)values[path.toAbsolutePath().normalize().toString()]=value else complete=false}}
        if(!broad){paths.forEach {p->if(safe(p)){val key=p.toAbsolutePath().normalize().toString();if(Files.exists(p,LinkOption.NOFOLLOW_LINKS))add(p)else values[key]=null}};return Capture(values,existing,true)}
        Files.walkFileTree(home,setOf(),12,object:SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir:Path,attrs:BasicFileAttributes):FileVisitResult {
                if(++visited>5000){complete=false;return FileVisitResult.TERMINATE}
                return if(!safe(dir) || dir!=home && dir.fileName.toString() in ignored)FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }
            override fun visitFile(file:Path,attrs:BasicFileAttributes):FileVisitResult {if(++visited>5000){complete=false;return FileVisitResult.TERMINATE};if(attrs.isRegularFile)add(file);return FileVisitResult.CONTINUE}
            override fun visitFileFailed(file:Path,error:java.io.IOException)=FileVisitResult.CONTINUE
        });return Capture(values,existing,complete)
    }
    fun before(plan:ProjectAgentPlan):Snapshot? {
        if(plan.readOnly || plan.tool.name in ProjectAgentServicePort.names || plan.tool.name=="ask_user" || plan.tool.name=="upload_file" || plan.tool.name=="make_new_project")return null
        val broad=plan.tool.name in setOf("bash","run_command","build","test","git","make_new_project")
        val paths=plan.resolvedTargets.map {normalized(Path.of(it))}
        return runCatching {Snapshot(collect(paths,broad),broad,paths)}.getOrNull()
    }
    fun after(snapshot:Snapshot?):List<ProjectAgentFileChange> {
        if(snapshot==null)return emptyList()
        return runCatching {val after=collect(snapshot.paths,snapshot.broad);if(!snapshot.captured.complete || !after.complete)return@runCatching emptyList()
            val changes=(snapshot.captured.files.keys+after.files.keys).distinct().asSequence().mapNotNull {path->
            val before=snapshot.captured.files[path];val next=after.files[path]
            if(before==null && path in snapshot.captured.existing || before==null && !snapshot.captured.complete || next==null && Files.exists(Path.of(path),LinkOption.NOFOLLOW_LINKS))null
            else if(before==next)null else Triple(path,before,next)
        }.take(2).toList()
            changes.singleOrNull()?.let {(path,before,next)->listOf(ProjectAgentFileChange(path,before,next,unified(path,before,next)))} ?: emptyList()}.getOrDefault(emptyList())
    }
    companion object {
        /** Bounded LCS diff. Large files use an honest excerpt rather than claiming a complete patch. */
        fun unified(path:String,before:String?,after:String?):String {
            fun lines(s:String?)=s?.takeIf {it.isNotEmpty()}?.replace("\r\n","\n")?.split('\n')?.let {if(s.endsWith('\n'))it.dropLast(1)else it}.orEmpty()
            val a=lines(before);val b=lines(after);val out=StringBuilder("--- ${if(before==null)"/dev/null"else path}\n+++ ${if(after==null)"/dev/null"else path}\n")
            if(a.size>1200 || b.size>1200){out.append("@@ large file: excerpt only; use 자세히 for original @@\n");a.take(30).forEach {out.append('-').append(it).append('\n')};b.take(30).forEach {out.append('+').append(it).append('\n')};return out.toString().take(12000)}
            val matrix=Array(a.size+1){IntArray(b.size+1)}
            for(i in a.indices.reversed())for(j in b.indices.reversed())matrix[i][j]=if(a[i]==b[j])matrix[i+1][j+1]+1 else maxOf(matrix[i+1][j],matrix[i][j+1])
            out.append("@@ -1,${a.size} +1,${b.size} @@\n");var i=0;var j=0
            while(i<a.size || j<b.size){if(i<a.size && j<b.size && a[i]==b[j]){out.append(' ').append(a[i++]).append('\n');j++}else if(j<b.size && (i==a.size || matrix[i][j+1]>matrix[i+1][j]))out.append('+').append(b[j++]).append('\n')else out.append('-').append(a[i++]).append('\n')
                if(out.length>11000){out.append("… diff truncated; 자세히 contains originals\n");break}}
            if(before!=null && before.isNotEmpty() && !before.endsWith('\n'))out.append("\\ No newline at end of original\n")
            if(after!=null && after.isNotEmpty() && !after.endsWith('\n'))out.append("\\ No newline at end of result\n")
            if(before!=null && after!=null && before.replace("\r\n","\n")==after.replace("\r\n","\n") && before!=after)out.append("Line endings changed; compare original attachments.\n")
            return out.toString()
        }
    }
}

internal class ProjectAgentFilePreviews(private val clock:()->Long={System.currentTimeMillis()}) {
    data class Entry(val channel:String,val user:String,val epoch:Long,val change:ProjectAgentFileChange,val expires:Long)
    private val entries=linkedMapOf<String,Entry>()
    @Synchronized fun put(channel:String,user:String,epoch:Long,change:ProjectAgentFileChange):String {
        entries.entries.removeIf {it.value.expires<=clock()}
        while(entries.size>=40)entries.remove(entries.keys.first())
        val id=UUID.randomUUID().toString();entries[id]=Entry(channel,user,epoch,change,clock()+600000);return id
    }
    @Synchronized fun get(id:String,channel:String,user:String,epoch:Long):Entry {
        val entry=entries[id] ?: error("파일 원문 미리보기가 만료되었습니다.")
        check(entry.channel==channel && entry.user==user && entry.epoch==epoch && clock()<entry.expires) {"본인의 현재 채널 작업 원문만 10분 이내 조회할 수 있습니다."};return entry
    }
    @Synchronized fun clear(){entries.clear()}
}

internal object DiscordAgentCode {
    fun wrap(text:String,language:String="text"):String {val longest=Regex("`{3,}").findAll(text).maxOfOrNull {it.value.length} ?: 2;val fence="`".repeat(maxOf(3,longest+1));return "$fence$language\n$text\n$fence"}
}
