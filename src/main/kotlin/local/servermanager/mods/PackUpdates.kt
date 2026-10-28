package local.servermanager

import jakarta.annotation.PreDestroy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.*

data class PackUpdateRequest(val token:String,val mode:String,val overwriteConfig:Boolean=false,val excludeClientOnly:Boolean=true)
data class PackUpdatePlan(val pack:PackPlan,val currentMods:List<String>,val incomingMods:List<String>,val configFiles:List<String>,val excludedClientMods:List<String>,val ignoredFiles:List<String>,val warnings:List<String>)
data class PackUpdateStatus(val token:String,val phase:String,val message:String,val plan:PackUpdatePlan?=null,val completed:Int=0,val total:Int=0)

/** Only these paths can participate, even when the archive contains worlds or executable settings. */
object PackUpdatePaths {
    fun mod(path:String)=path.startsWith("mods/") && path.removePrefix("mods/").let { '/' !in it && (it.endsWith(".jar",true) || it.endsWith(".jar.disabled",true)) }
    fun allowed(path:String):Boolean {PackFormat.relative(path);return (mod(path) && !path.startsWith("mods/servermanager-sable-bridge-")) || path.startsWith("config/")}
    fun changes(plan:PackUpdatePlan,req:PackUpdateRequest):Pair<Set<String>,Set<String>> {
        require(req.mode in setOf("merge","replace")) {"모드 업데이트 방식을 선택하세요"}
        val incoming=plan.incomingMods.filterNot {req.excludeClientOnly && it in plan.excludedClientMods}.toSet()
        val writes=incoming + if(req.overwriteConfig)plan.configFiles else emptyList()
        val removes=if(req.mode=="replace")plan.currentMods.toSet()-incoming else emptySet()
        require((writes+removes).all(::allowed))
        return writes to removes
    }
}

internal interface PackWorkspace {
    fun list(path:String):List<FileEntry>
    fun revision(path:String):String
    fun read(path:String,target:Path)
    fun write(path:String,source:Path)
    fun delete(path:String)
}
internal class PackRollbackFailed(cause:Throwable):IllegalStateException("업데이트 복구를 완료하지 못했습니다. 서버 시작을 차단했습니다. 백업과 pack-update 작업 기록을 확인하세요",cause)

/** Back up every affected original before writing anything; keep a persistent marker across process death. */
internal object PackUpdateTransaction {
    fun apply(workspace:PackWorkspace,stage:Path,backup:Path,expected:Map<String,String>,writes:Set<String>,removes:Set<String>,progress:(Int,Int)->Unit={_,_->}) {
        require((writes+removes).all(PackUpdatePaths::allowed))
        val affected=(writes+removes).sorted();backup.createDirectories()
        affected.filter {it in expected}.forEach {path->
            check(workspace.revision(path)==expected[path]) {"미리보기 후 파일이 변경되었습니다: $path"}
            val target=backup.resolve(path);target.parent.createDirectories();workspace.read(path,target)
            check(workspace.revision(path)==expected[path]) {"백업 중 파일이 변경되었습니다: $path"}
        }
        val touched=mutableListOf<String>()
        try {
            affected.forEachIndexed {index,path->
                // Re-check immediately before each mutation, including a newly created conflicting file.
                val now=runCatching {workspace.revision(path)}
                if(path in expected)check(now.getOrNull()==expected[path]) {"파일이 변경되었습니다: $path"}
                else check(workspace.list(path.substringBeforeLast('/')).none {it.path==path}) {"새 파일이 생겼습니다: $path"}
                touched+=path
                if(path in writes)workspace.write(path,stage.resolve(path)) else workspace.delete(path)
                progress(index+1,affected.size)
            }
        } catch(e:Exception) {
            var restored=true
            touched.asReversed().forEach {path->runCatching {
                if(path in expected)workspace.write(path,backup.resolve(path))
                else if(workspace.list(path.substringBeforeLast('/')).any {it.path==path})workspace.delete(path)
            }.onFailure {restored=false}}
            if(!restored)throw PackRollbackFailed(e)
            throw IllegalStateException("업데이트 실패 · 변경한 파일은 백업으로 복구했습니다: ${e.message}",e)
        }
    }
}

@Service
class PackUpdates(private val manager:ServerManager,private val importer:MrpackImporter,private val catalog:ModrinthCatalog,private val mods:ModManagement) {
    private val remote=RemoteFiles(manager)
    private val executor=Executors.newFixedThreadPool(2)
    private data class Prepared(val status:PackUpdateStatus,val dir:Path,val expected:Map<String,String> = emptyMap(),val runner:String?=null)
    private val prepared=ConcurrentHashMap<String,Prepared>()
    private val directory=manager.root.resolve("pack-updates").also {it.createDirectories()}
    private fun workspace(id:String):PackWorkspace=object:PackWorkspace {
        private val isRemote=remote.remote(id)
        override fun list(path:String):List<FileEntry> {
            if(path.isNotEmpty()) {
                val parent=path.substringBeforeLast('/',"")
                if(list(parent).none {it.path==path && it.directory})return emptyList()
            }
            return if(isRemote)remote.files(id,path) else manager.files(id,path)
        }
        override fun revision(path:String)=if(isRemote)remote.revision(id,path) else FileInventory.digest(manager.path(id,path).also {require(it.isRegularFile())})
        override fun read(path:String,target:Path) {target.outputStream().use {out->if(isRemote)remote.download(id,path,out,PackFormat.MAX_FILE) else Files.copy(manager.path(id,path),out)}}
        override fun write(path:String,source:Path) {
            source.inputStream().use {input->if(isRemote)remote.writeUnreserved(id,path,input) else {
                val dest=manager.path(id,path);dest.parent.createDirectories();val temp=Files.createTempFile(dest.parent,".pack-update-",".tmp")
                try{Files.copy(input,temp,REPLACE_EXISTING);Files.move(temp,dest,REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)}finally{temp.deleteIfExists()}
            }}
        }
        override fun delete(path:String) {if(isRemote)remote.changeUnreserved(id,"DELETE",path) else manager.path(id,path).deleteExisting()}
    }
    private fun snapshot(workspace:PackWorkspace):Map<String,String> {
        val result=sortedMapOf<String,String>()
        fun visit(path:String,depth:Int=0) {
            require(depth<32) {"설정 폴더가 너무 깊습니다"}
            workspace.list(path).forEach {entry->
                if(entry.directory){if(path=="config" || path.startsWith("config/"))visit(entry.path,depth+1)}
                else if(PackUpdatePaths.allowed(entry.path)) {require(result.size<10000);result[entry.path]=workspace.revision(entry.path)}
            }
        }
        visit("mods");visit("config");return result
    }
    @Synchronized fun preview(id:String,input:InputStream):PackUpdateStatus {
        val instance=manager.instance(id).copy()
        check(prepared[id]?.status?.phase !in setOf("PREPARING","APPLYING")) {"팩 업데이트 작업 중입니다"}
        val token=UUID.randomUUID().toString();val dir=directory.resolve(token);dir.createDirectories()
        val plan=try {
            input.use {PackFormat.copyBounded(it,dir.resolve("pack.mrpack"),PackFormat.MAX_FILE)}
            PackFormat.inspect(dir.resolve("pack.mrpack")).also {plan->require(plan.loader==instance.loader && plan.minecraft==instance.minecraft) {"기존 서버와 같은 Minecraft·로더 종류의 팩만 업데이트할 수 있습니다"}}
        } catch(e:Exception){dir.toFile().deleteRecursively();throw e}
        val initial=PackUpdateStatus(token,"PREPARING","팩 파일과 서버 변경 내역을 확인합니다")
        prepared[id]=Prepared(initial,dir,runner=instance.runnerId)
        executor.submit {
            try {
                val stage=dir.resolve("stage");stage.createDirectories()
                plan.files.forEachIndexed {index,file->
                    if(PackUpdatePaths.allowed(file.path))importer.downloadVerified(file,stage.resolve(file.path))
                    prepared[id]=prepared.getValue(id).copy(status=initial.copy(message="파일 검증 ${index+1}/${plan.files.size}: ${file.path}",completed=index+1,total=plan.files.size))
                }
                // Extract only permitted overrides; worlds and server properties never enter the stage.
                val replaced=mutableSetOf<String>()
                java.util.zip.ZipFile(dir.resolve("pack.mrpack").toFile()).use {zip->for(prefix in listOf("overrides/","server-overrides/"))zip.entries().asSequence().filter {!it.isDirectory && it.name.startsWith(prefix)}.forEach {entry->
                    val path=PackFormat.relative(entry.name.removePrefix(prefix))
                    if(PackUpdatePaths.allowed(path)){zip.getInputStream(entry).use {PackFormat.copyBounded(it,stage.resolve(path),PackFormat.MAX_FILE)};replaced+=path}
                }}
                val warnings=mutableListOf<String>()
                val excluded=ClientModFilter.candidates(stage,plan,replaced,{catalog.lookup(dir.resolve("sources.json"),it,true)},warnings::add).map {stage.relativize(it).toString()}.sorted()
                val incoming=Files.walk(stage).use {paths->paths.filter {it.isRegularFile()}.map {stage.relativize(it).toString()}.toList()}.sorted()
                val existing=snapshot(workspace(id))
                val ignored=(plan.files.map {it.path}+plan.overrides.map {it.substringAfter('/')}).filterNot(PackUpdatePaths::allowed).distinct()
                if(instance.loaderVersion!=plan.loaderVersion)warnings+="팩 로더 ${plan.loaderVersion}와 현재 ${instance.loaderVersion}가 다릅니다. 이번 작업은 로더를 교체하지 않습니다. 모드 호환성을 확인하세요."
                val diff=PackUpdatePlan(plan,existing.keys.filter(PackUpdatePaths::mod),incoming.filter(PackUpdatePaths::mod),incoming.filter {it.startsWith("config/")},excluded,ignored,warnings)
                prepared[id]=Prepared(initial.copy(phase="READY",message="업데이트 방법을 선택하세요",plan=diff),dir,existing,instance.runnerId)
            } catch(e:Exception){prepared[id]=prepared.getValue(id).copy(status=initial.copy(phase="FAILED",message=e.message ?: "팩 확인 실패"))}
        }
        return initial
    }
    fun status(id:String):PackUpdateStatus? {manager.instance(id);return prepared[id]?.status}
    @Synchronized fun apply(id:String,req:PackUpdateRequest):PackUpdateStatus {
        val job=prepared[id] ?: error("팩을 먼저 업로드하세요")
        check(job.status.token==req.token && job.status.phase=="READY") {"업데이트 미리보기를 다시 확인하세요"}
        val plan=job.status.plan!!;val (writes,removes)=PackUpdatePaths.changes(plan,req)
        require(writes.isNotEmpty() || removes.isNotEmpty()) {"변경할 파일이 없습니다"}
        val applying=job.status.copy(phase="APPLYING",message="원본 백업 후 업데이트합니다",completed=0,total=(writes+removes).size)
        prepared[id]=job.copy(status=applying)
        executor.submit {
            val marker=manager.root.resolve(id).resolve("import.pending")
            try {
                manager.withRemoteFiles(id,write=remote.remote(id),stopped=true) {
                    val runtime=manager.runtime(id)
                    check(runtime.state in setOf("STOPPED","FAILED","NEW") && runtime.process?.isAlive!=true && runtime.remotePid==null) {"서버를 종료한 뒤 업데이트하세요"}
                    check(manager.instance(id).runnerId==job.runner) {"실행기가 변경되었습니다. 다시 업로드하세요"}
                    val ws=workspace(id)
                    check(snapshot(ws)==job.expected) {"미리보기 후 모드·설정 파일이 변경되었습니다. 다시 업로드하세요"}
                    check(!marker.exists()) {"다른 가져오기 작업이 진행 중입니다"}
                    job.dir.resolve("update.json").writeText(com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(mapOf("instanceId" to id,"request" to req,"writes" to writes,"removes" to removes,"original" to job.expected)))
                    marker.writeText("pack-update:${req.token}")
                    try {
                        PackUpdateTransaction.apply(ws,job.dir.resolve("stage"),job.dir.resolve("backup"),job.expected,writes,removes) {done,total->prepared[id]=job.copy(status=applying.copy(message="파일 반영 중",completed=done,total=total))}
                        marker.deleteExisting()
                    } catch(e:Exception){if(e !is PackRollbackFailed)marker.deleteIfExists();throw e}
                    runtime.event("system","mrpack 업데이트 완료: ${plan.pack.name} ${plan.pack.version}. 월드·데이터 보존, config ${if(req.overwriteConfig)"덮어쓰기" else "보존"}.")
                }
                val refresh=runCatching {mods.refresh(id)}
                prepared[id]=job.copy(status=applying.copy(phase="COMPLETE",message="업데이트 완료 · 월드와 데이터 보존 · 원본 백업: ${job.dir.resolve("backup")}${if(refresh.isFailure)" · 모드 목록 새로고침을 다시 실행하세요" else ""}",completed=applying.total))
            } catch(e:Exception){prepared[id]=job.copy(status=applying.copy(phase="FAILED",message=e.message ?: "업데이트 실패"))}
        }
        return applying
    }
    @Scheduled(fixedDelay=3600000) @Synchronized fun cleanup() {
        if(!directory.exists())return
        val active=prepared.values.filter {it.status.phase in setOf("PREPARING","APPLYING","READY")}.map {it.dir}.toSet()
        directory.listDirectoryEntries().filter {it.isDirectory() && it !in active && !it.resolve("backup").exists() && Files.getLastModifiedTime(it).toMillis()<System.currentTimeMillis()-86400000}.forEach {it.toFile().deleteRecursively()}
    }
    @PreDestroy fun close(){executor.shutdownNow()}
}

@RestController
@RequestMapping("/api/instances/{id}/pack-update")
class PackUpdateApi(private val updates:PackUpdates) {
    @PostMapping("/preview") fun preview(@PathVariable id:String,@RequestParam file:MultipartFile):PackUpdateStatus {
        require(file.originalFilename?.endsWith(".mrpack",true)==true) {".mrpack 파일을 선택하세요"}
        return updates.preview(id,file.inputStream)
    }
    @GetMapping fun status(@PathVariable id:String)=updates.status(id)
    @PostMapping fun apply(@PathVariable id:String,@RequestBody request:PackUpdateRequest)=updates.apply(id,request)
}
