package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.*

data class ModLinkRequest(val revision:String,val filename:String,val link:String)
data class ModSelection(val revision:String,val files:Set<String>,val enabled:Boolean=false,val ignoreDependencies:Boolean=false)
data class ModTogglePreview(val revision:String,val enabled:Boolean,val selected:List<String>,val affected:List<String>,val warnings:List<String> = emptyList(),val ignoreDependencies:Boolean=false)
data class ModSearchRequest(val revision:String,val suspects:Set<String>,val keep:Set<String> = emptySet())
data class ModTrial(val number:Int,val round:Int,val groups:List<String>,val disabled:List<String>,var started:Boolean=false,
    var verdict:String?=null,var note:String="",val createdAt:String=java.time.Instant.now().toString())
data class ModDiagnostic(val id:String,val snapshot:ModInventorySnapshot,val plan:ModSearchPlan,var phase:String="COPYING",var round:Int=1,
    val excluded:MutableList<String> = mutableListOf(),val findings:MutableList<List<String>> = mutableListOf(),val trials:MutableList<ModTrial> = mutableListOf(),var error:String?=null)
data class ModVerdict(val trial:Int,val verdict:String,val note:String="")
data class ModJob(val phase:String,val message:String,val files:Int=0,val bytes:Long=0)
data class ModDiagnosticView(val id:String,val phase:String,val round:Int,val plan:ModSearchPlan,val trials:List<ModTrial>,val findings:List<List<String>>,val error:String?)
data class ModPage(val inventory:ModInventorySnapshot?,val inventoryRevision:String?,val job:ModJob?,val diagnostic:ModDiagnosticView?,val next:SearchStep?,val active:Boolean)

@Service
class ModManagement(private val manager:ServerManager,private val catalog:ModrinthCatalog) {
    private val mapper=jacksonObjectMapper()
    private val executor=Executors.newFixedThreadPool(2)
    private val locks=ConcurrentHashMap<String,Any>()
    private val inventories=ConcurrentHashMap<String,ModInventorySnapshot>()
    private val inventoryTokens=ConcurrentHashMap<String,String>()
    private fun publishInventory(id:String,snapshot:ModInventorySnapshot) {
        inventories[id]=snapshot
        inventoryTokens[id]=java.util.UUID.randomUUID().toString()
    }
    private val jobs=ConcurrentHashMap<String,ModJob>()
    private val sessions=ConcurrentHashMap<String,ModDiagnostic>()
    private val published=ConcurrentHashMap<String,ModDiagnostic>()
    private fun <T> locked(id:String,f:()->T)=synchronized(locks.computeIfAbsent(id){Any()}) {f()}
    private fun base(id:String)=manager.root.resolve(id)
    private fun stateFile(id:String)=base(id).resolve("mod-diagnostic.json")
    private fun session(id:String):ModDiagnostic?=sessions[id] ?: stateFile(id).takeIf {it.exists()}?.let {
        require(it.fileSize()<16L*1024*1024)
        mapper.readValue<ModDiagnostic>(it.toFile()).also {s->require(UUID.fromString(s.id).toString()==s.id);sessions[id]=s}
    }
    private fun save(id:String,s:ModDiagnostic){val json=mapper.writeValueAsString(s);require(json.toByteArray().size<16*1024*1024) {"검사 이력이 너무 큽니다. 현재 검사를 종료하세요"};atomicText(stateFile(id),json);sessions[id]=s;published[id]=mapper.readValue(json)}
    private fun root(id:String,s:ModDiagnostic)=base(id).resolve("diagnostics").resolve(s.id)
    private fun loader(id:String)=manager.instance(id).loader.lowercase().also {require(it in setOf("fabric","forge","neoforge")) {"Fabric·Forge·NeoForge 모드 서버에서 사용할 수 있습니다"}}
    private fun files(id:String)=ModInventory.scan(manager.originalServerDir(id).resolve("mods").also {loader(id)})
    /** Dependencies are fetched from Modrinth once per file hash and saved with the server. */
    private fun scan(id:String,retryMissing:Boolean=false,publishRaw:Boolean=false):ModInventorySnapshot {
        val snapshot=files(id)
        if(publishRaw)publishInventory(id,ModInventory.attach(snapshot,ModSourceLookup(ModSources.load(base(id).resolve("mod-sources.json")),listOf("파일 목록을 불러왔습니다. Modrinth 정보를 확인 중입니다"))))
        return ModInventory.attach(snapshot,catalog.lookup(base(id).resolve("mod-sources.json"),snapshot.mods.map {it.sha512},retryMissing))
    }
    fun page(id:String,includeInventory:Boolean=true):ModPage {
        manager.instance(id)
        // Polling never hashes files or waits on a long copy/apply operation.
        val s=published[id] ?: if(sessions[id]!=null)null else locked(id){session(id)?.let {mapper.readValue<ModDiagnostic>(mapper.writeValueAsString(it)).also {copy->published[id]=copy}}}
        val inventory=inventories[id] ?: s?.snapshot
        val view=s?.let {ModDiagnosticView(it.id,it.phase,it.round,it.plan,it.trials,it.findings,it.error)}
        return ModPage(if(includeInventory)inventory else null,inventory?.let {inventoryTokens.computeIfAbsent(id){java.util.UUID.randomUUID().toString()}},jobs[id],view,s?.let {next(it)},manager.runtime(id).diagnosticActive)
    }
    private fun next(s:ModDiagnostic):SearchStep {
        val all=s.plan.groups.map {it.id}-s.excluded.toSet()
        if(all.isEmpty())return SearchStep("EXHAUSTED",emptyList(),"모든 후보 묶음을 확인했습니다. 찾은 원인 목록을 확인하세요.")
        return ManualModSearch.next(all,s.trials.filter {it.round==s.round && it.verdict!=null}.map {SearchObservation(it.groups,it.verdict!!)})
    }
    private fun inventory(id:String,revision:String)=inventories[id]?.also {check(it.revision==revision) {"모드 목록이 바뀌었습니다. 다시 검사하세요"}} ?: error("먼저 모드 목록을 불러오세요")
    /** Reservation prevents normal starts, edits and runner transfers without holding the global manager lock during I/O. */
    private fun job(id:String,diagnostic:Boolean=false,action:()->Unit) {
        manager.reserveMods(id,diagnostic)
        jobs[id]=ModJob("WORKING","모드 파일을 확인하고 있습니다")
        try {executor.submit {
            try {action();jobs[id]=ModJob("COMPLETE","작업을 완료했습니다")}
            catch(e:Exception){jobs[id]=ModJob("FAILED",e.message?.take(500) ?: "모드 작업 실패");locked(id){session(id)?.let {it.error=e.message?.take(500);save(id,it)}}}
            finally {manager.releaseMods(id)}
        }} catch(e:Exception){manager.releaseMods(id);throw e}
    }
    fun refresh(id:String)=locked(id) {
        loader(id)
        if(manager.modsViewOnly(id))viewJob(id) else job(id,manager.runtime(id).diagnosticActive){publishInventory(id,scan(id,true,true))}
    }
    /** Read-only listing while the server is busy: nothing is reserved, so every edit stays blocked by the normal reservation checks. */
    private fun viewJob(id:String) {
        check(jobs[id]?.phase!="WORKING") {"모드 목록을 불러오는 중입니다"}
        jobs[id]=ModJob("WORKING","모드 목록을 불러오고 있습니다")
        executor.submit {
            try {publishInventory(id,scan(id,true,true));jobs[id]=ModJob("COMPLETE","모드 목록을 불러왔습니다")}
            catch(e:Exception){jobs[id]=ModJob("FAILED",e.message?.take(500) ?: "모드 목록을 불러오지 못했습니다")}
        }
    }
    fun link(id:String,req:ModLinkRequest)=locked(id) {
        check(!manager.runtime(id).diagnosticActive) {"진행 중인 검사를 먼저 종료하세요"}
        val snapshot=inventory(id,req.revision);require(snapshot.mods.any {it.filename==req.filename})
        job(id) {
            check(files(id).revision==req.revision) {"모드 파일이 변경되었습니다"}
            catalog.link(base(id).resolve("mod-sources.json"),snapshot,req.filename,req.link)
            publishInventory(id,scan(id))
        }
    }
    fun preview(id:String,req:ModSelection):ModTogglePreview=locked(id) {
        check(!manager.runtime(id).diagnosticActive) {"진행 중인 검사를 먼저 중단하세요"}
        val snapshot=inventory(id,req.revision);require(req.files.isNotEmpty())
        val graph=ModGraph(snapshot)
        val (files,warnings)=if(!req.enabled)graph.disable(req.files,req.ignoreDependencies) to buildList {
            if(req.ignoreDependencies) {
                add("의존성 검사를 무시하고 선택한 모드만 강제로 끕니다. 이 모드를 필요로 하는 다른 모드는 그대로 유지되므로 서버가 시작되지 않거나 충돌할 수 있습니다.")
                val users=req.files.flatMap {graph.dependencyReasons[it].orEmpty()}.toSet()-req.files
                if(users.isNotEmpty())add("필수 의존성이 없어지는 모드: "+users.sorted().joinToString())
            }
            val unknown=(graph.unknown-req.files).sorted()
            if(unknown.isNotEmpty())add("Modrinth에서 찾지 못한 모드 ${unknown.size}개는 의존성을 확인하지 못해, 이 변경이 그 모드에 영향을 줄 수 있습니다: "+unknown.take(8).joinToString())
        }
            else ModGraph.enable(snapshot,req.files,req.ignoreDependencies)
        ModTogglePreview(req.revision,req.enabled,req.files.sorted(),files.sorted(),warnings,req.ignoreDependencies)
    }
    fun toggle(id:String,req:ModSelection)=locked(id) {
        val preview=preview(id,req)
        job(id) {
            val fresh=files(id);check(fresh.revision==req.revision) {"검사 후 파일이 변경됐습니다. 다시 불러오세요"}
            val changes=preview.affected.associateWith {if(req.enabled)it.dropLast(9) else "$it.disabled"}
            check(changes.values.none {target->fresh.mods.any {it.filename.equals(target,true)}}) {"같은 이름의 파일이 이미 있어 변경할 수 없습니다"}
            val guard=base(id).resolve("import.pending");check(!guard.exists());atomicText(guard,"mod-toggle")
            try {
                ModFiles.rename(manager.originalServerDir(id).resolve("mods"),changes,base(id).resolve("mod-backups"))
                guard.deleteIfExists();publishInventory(id,scan(id))
            } catch(e:Exception) {
                // Rollback is durable; leave the old-version-compatible start guard if recovery fails.
                DeltaTransfer.recover(manager.originalServerDir(id).resolve("mods"),base(id).resolve("mod-backups"));guard.deleteIfExists();throw e
            }
        }
    }
    fun create(id:String,req:ModSearchRequest)=locked(id) {
        require(session(id)?.phase in setOf(null,"FINISHED")) {"기존 검사를 재개하거나 종료하세요"}
        val snapshot=inventory(id,req.revision);val plan=ModGraph(snapshot).plan(req.suspects,req.keep)
        job(id) {
            val fresh=scan(id);check(fresh.revision==req.revision) {"모드 파일이 바뀌었습니다. 다시 불러오세요"}
            val s=ModDiagnostic(UUID.randomUUID().toString(),fresh,plan)
            locked(id){save(id,s)}
            manager.diagnosticContext(id,s.id)
            val home=root(id,s).also {it.createDirectories()}
            try {
                var last=0L
                ModFiles.sandbox(manager.originalServerDir(id),home.resolve("server")){count,bytes->
                    val now=System.nanoTime();if(now-last>250_000_000){jobs[id]=ModJob("COPYING","원본 월드를 제외하고 검사 환경을 복사하고 있습니다",count,bytes);last=now}
                }
                val mods=home.resolve("server/mods").also {it.createDirectories()}
                val store=home.resolve("original-mods").createDirectory()
                fresh.mods.forEach {m->val file=mods.resolve(m.filename);check(FileInventory.digest(file)==m.sha256) {"복사 중 모드가 변경됐습니다"};Files.copy(file,store.resolve(m.filename))}
                locked(id){prepare(id,s)}
            } catch(e:Exception){locked(id){s.phase="ERROR";s.error=e.message;save(id,s)};throw e}
        }
    }
    private fun prepare(id:String,s:ModDiagnostic) {
        check(manager.runtime(id).diagnosticSession==s.id)
        val step=next(s)
        if(step.status !in setOf("TEST","INCONCLUSIVE")){s.phase="RESULT";save(id,s);return}
        check(s.trials.size<500) {"검사 기록이 500개입니다. 검사를 종료한 뒤 새로 시작하세요"}
        val current=s.trials.lastOrNull()
        check(current==null || current.verdict!=null) {"현재 시도의 결과를 먼저 판정하세요"}
        val graph=ModGraph(s.snapshot);val disabled=graph.disabled(s.plan,step.groups.toSet())
        val trial=ModTrial(s.trials.size+1,s.round,step.groups,disabled.sorted())
        s.phase="PREPARING";s.error=null;s.trials.add(trial);save(id,s)
        val home=root(id,s);val server=home.resolve("server");val mods=server.resolve("mods").also {it.createDirectories()}
        // Always restore from the immutable per-session JAR copy, never the production folder.
        val before=FileInventory.collect(mods).let {(f,d)->RunnerFileManifest(f,d)}
        val afterFiles=s.snapshot.mods.associate {m->(if(m.enabled && m.filename in disabled)"${m.filename}.disabled" else m.filename) to CloudFile(m.sha256,m.size)}
        val after=RunnerFileManifest(afterFiles,emptySet())
        val stage=home.resolve("mod-stage-${UUID.randomUUID()}").createDirectory()
        try {
            afterFiles.filter {(p,f)->before.files[p]!=f}.forEach {(p,_)->
                val source=s.snapshot.mods.single {m->p==m.filename || m.enabled && p=="${m.filename}.disabled"}
                val original=home.resolve("original-mods").resolve(source.filename)
                check(FileInventory.digest(original)==source.sha256) {"검사 원본 모드가 변경됐습니다"}
                Files.copy(original,stage.resolve(p))
            }
            DeltaTransfer.apply(mods,stage,before,after,home.resolve("mod-backups"))
        } finally {stage.toFile().deleteRecursively()}
        val propsFile=server.resolve("server.properties");val props=Properties().apply {if(propsFile.exists())propsFile.inputStream().use {load(it)}}
        props.setProperty("level-name","sm-debug-${s.id.take(8)}-${trial.number}")
        propsFile.outputStream().use {props.store(it,"Isolated manual mod test; original worlds are untouched")}
        // Logs of previous completed trials are retained separately; this trial starts clean.
        server.resolve("logs").toFile().deleteRecursively();server.resolve("crash-reports").toFile().deleteRecursively();home.resolve("console.log").deleteIfExists()
        synchronized(manager.runtime(id)){manager.runtime(id).events.clear()}
        s.phase="READY";save(id,s)
    }
    fun start(id:String)=locked(id) {
        val s=session(id) ?: error("검사를 먼저 만드세요")
        check(s.phase=="READY" && manager.runtime(id).diagnosticSession==s.id && !manager.runtime(id).modBusy)
        val trial=s.trials.last();check(trial.verdict==null)
        trial.started=true;s.phase="PLAYING";s.error=null;save(id,s)
        try {manager.startDiagnostic(id,s.id)}catch(e:Exception){s.phase="READY";trial.started=false;s.error=e.message;save(id,s);throw e}
    }
    private fun archiveLogs(id:String,s:ModDiagnostic,trial:ModTrial) {
        val home=root(id,s);val out=home.resolve("trials/${trial.number}").createDirectories()
        for(sub in listOf("logs","crash-reports")) {
            val source=home.resolve("server/$sub");val dest=out.resolve("server/$sub")
            if(source.exists())Files.walk(source).use {paths->paths.forEach {p->
                require(!Files.isSymbolicLink(p));val target=dest.resolve(source.relativize(p))
                if(p.isDirectory())target.createDirectories() else if(p.isRegularFile()){target.parent.createDirectories();Files.copy(p,target,REPLACE_EXISTING)}
            }}
        }
        home.resolve("console.log").takeIf {it.exists()}?.let {Files.copy(it,out.resolve("console.log"),REPLACE_EXISTING)}
    }
    fun verdict(id:String,req:ModVerdict)=locked(id) {
        require(req.verdict in setOf("PASS","FAIL","INVALID") && req.note.length<=2000)
        val s=session(id) ?: error("검사가 없습니다");check(s.phase !in setOf("PAUSED","FINISHED","COPYING","PREPARING","ERROR"))
        val trial=s.trials.singleOrNull {it.number==req.trial} ?: error("시도를 찾을 수 없습니다")
        require(trial.started) {"실제로 실행한 시도만 판정할 수 있습니다"}
        check(trial.round==s.round) {"이전 차수는 이미 다음 원인 탐색에 사용됐습니다. 이전 차수를 수정하려면 현재 검사를 종료하고 새 검사를 만드세요"}
        val current=s.trials.last()
        if(req.verdict=="FAIL" && trial===current) {
            val r=manager.runtime(id)
            val missing=synchronized(r){r.events.takeLast(1000).any {e->Regex("(?i)(missing (?:or unsupported )?(?:mandatory |required )?(?:mod|dependenc)|requires .{1,180}(?:which is missing|not installed)|requires .{1,180} but .{0,60} (?:is|are) missing)").containsMatchIn(e.text)}}
            check(!missing) {"이번 로그에 의존성 누락이 있습니다. 원래 오류의 재현으로 세지 말고 ‘판정 불가’를 선택한 뒤 의존성 설정을 확인하세요"}
        }
        if(trial!==current)check(current.verdict!=null || !current.started) {"현재 실행한 시도를 먼저 판정하세요"}
        if(manager.runtime(id).state in setOf("STARTING","RUNNING")) {
            check(trial===current) {"이전 판정 수정은 현재 시도를 종료한 뒤 할 수 있습니다"}
            trial.verdict=req.verdict;trial.note=req.note;s.phase="JUDGED";s.error=null;save(id,s)
            manager.stop(id,false)
        } else {
            job(id,true) {locked(id){
                if(current.started)archiveLogs(id,s,current)
                if(trial!==current && !current.started && current.verdict==null)s.trials.removeLast()
                trial.verdict=req.verdict;trial.note=req.note;s.phase="JUDGED";s.error=null;save(id,s)
            }}
        }
    }
    fun advance(id:String)=locked(id) {
        val s=session(id) ?: error("검사가 없습니다");check(s.phase in setOf("JUDGED","RESULT"))
        job(id,true){locked(id){s.trials.lastOrNull()?.takeIf {it.started}?.let {archiveLogs(id,s,it)};prepare(id,s)}}
    }
    fun more(id:String)=locked(id) {
        val s=session(id) ?: error("검사가 없습니다");val result=next(s)
        check(result.status=="FOUND" && s.trials.lastOrNull()?.verdict!=null)
        job(id,true){locked(id){
            s.trials.lastOrNull()?.takeIf {it.started}?.let {archiveLogs(id,s,it)};s.findings.add(result.groups);s.excluded.addAll(result.groups);s.round++
            if(s.excluded.toSet().size==s.plan.groups.size){s.phase="RESULT";save(id,s)}else prepare(id,s)
        }}
    }
    fun pause(id:String,finish:Boolean)=locked(id) {
        val s=session(id) ?: error("검사가 없습니다")
        job(id,manager.runtime(id).diagnosticActive){locked(id){
            if(manager.runtime(id).diagnosticActive){s.trials.lastOrNull()?.takeIf {it.started}?.let {archiveLogs(id,s,it)};manager.diagnosticContext(id,null)}
            s.phase=if(finish)"FINISHED" else "PAUSED";save(id,s)
        }}
    }
    fun resume(id:String)=locked(id) {
        val s=session(id) ?: error("검사가 없습니다");check(s.phase=="PAUSED")
        job(id){locked(id){
            check(files(id).revision==s.snapshot.revision) {"원본 모드가 변경됐습니다. 기존 검사를 종료하고 새 검사를 만드세요"}
            val home=root(id,s)
            check(s.snapshot.mods.all {m->home.resolve("original-mods").resolve(m.filename).let {it.isRegularFile() && FileInventory.digest(it)==m.sha256}}) {"검사 복사본이 완성되지 않았습니다. 검사를 종료하고 다시 만드세요"}
            manager.diagnosticContext(id,s.id)
            val last=s.trials.lastOrNull()
            if(last==null || !last.started && last.verdict==null){if(last!=null)s.trials.removeLast();prepare(id,s)}
            else {s.phase=if(last.verdict==null)"PLAYING" else "JUDGED";save(id,s)}
        }}
    }
    fun logs(id:String,trial:Int):LogFiles=locked(id) {
        val s=session(id) ?: error("검사가 없습니다");require(s.trials.any {it.number==trial})
        LogFiles(root(id,s).resolve("trials/$trial"))
    }
    @PreDestroy fun close(){executor.shutdown()}
}

@RestController
@RequestMapping("/api/instances/{id}/mods")
class ModApi(private val mods:ModManagement) {
    @ExceptionHandler(ModDependencyBlocked::class)
    fun dependencyBlocked(e:ModDependencyBlocked)=org.springframework.http.ResponseEntity.badRequest().body(mapOf("message" to e.message,"code" to "MOD_DEPENDENCY_BLOCKED"))
    @GetMapping fun page(@PathVariable id:String,@RequestParam(defaultValue="false") inventory:Boolean)=mods.page(id,inventory)
    @PostMapping("/refresh") fun refresh(@PathVariable id:String)=mods.refresh(id)
    @PostMapping("/link") fun link(@PathVariable id:String,@RequestBody req:ModLinkRequest)=mods.link(id,req)
    @PostMapping("/toggle-preview") fun preview(@PathVariable id:String,@RequestBody req:ModSelection)=mods.preview(id,req)
    @PostMapping("/toggle") fun toggle(@PathVariable id:String,@RequestBody req:ModSelection)=mods.toggle(id,req)
    @PostMapping("/diagnostic") fun create(@PathVariable id:String,@RequestBody req:ModSearchRequest)=mods.create(id,req)
    @PostMapping("/diagnostic/start") fun start(@PathVariable id:String)=mods.start(id)
    @PostMapping("/diagnostic/verdict") fun verdict(@PathVariable id:String,@RequestBody req:ModVerdict)=mods.verdict(id,req)
    @PostMapping("/diagnostic/next") fun next(@PathVariable id:String)=mods.advance(id)
    @PostMapping("/diagnostic/more") fun more(@PathVariable id:String)=mods.more(id)
    @PostMapping("/diagnostic/pause") fun pause(@PathVariable id:String)=mods.pause(id,false)
    @PostMapping("/diagnostic/finish") fun finish(@PathVariable id:String)=mods.pause(id,true)
    @PostMapping("/diagnostic/resume") fun resume(@PathVariable id:String)=mods.resume(id)
    @GetMapping("/diagnostic/trials/{trial}/logs") fun logs(@PathVariable id:String,@PathVariable trial:Int)=mods.logs(id,trial).list()
    @GetMapping("/diagnostic/trials/{trial}/log-page") fun logPage(@PathVariable id:String,@PathVariable trial:Int,@RequestParam path:String,@RequestParam(defaultValue="0") offset:Long)=mods.logs(id,trial).page(path,offset)
}
