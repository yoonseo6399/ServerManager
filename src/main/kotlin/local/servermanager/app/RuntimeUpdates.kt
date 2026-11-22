package local.servermanager

import org.springframework.web.bind.annotation.*
import org.springframework.stereotype.Service
import java.util.concurrent.*
import java.nio.file.Path

@Service
class RuntimeUpdates(private val manager:ServerManager,private val remote:RemoteFiles) {
    data class View(val state:String,val target:RuntimeUpdate.Target,val logs:List<String>,val backup:String?=null,val error:String?=null)
    private val states=ConcurrentHashMap<String,View>()
    private val local=ConcurrentHashMap<String,RuntimeUpdate>()
    private val workers=Executors.newFixedThreadPool(2){r->Thread(r,"runtime-update-coordinator").apply {isDaemon=true}}
    fun status(id:String):View? {manager.instance(id);return states[id]}
    fun start(id:String,target:RuntimeUpdate.Target):View {
        val before=manager.reserveRuntimeUpdate(id,target)
        val initial=View("PREPARING",target,listOf("서버가 종료됐음을 확인했습니다"));states[id]=initial
        workers.submit {
            try {
                val updater=if(before.runnerId==null)local.computeIfAbsent(id){RuntimeUpdate(manager.serverDir(id),manager.profileDir(id).resolve("runtime-updates")){instance,stage,log->
                    LauncherInstaller(LauncherCatalog(manager.root.resolve("launcher-catalogs"))).install(instance,stage,stage.resolve("installer.jar"),{args->val p=ProcessBuilder(args).directory(stage.toFile()).redirectErrorStream(true).start();p.inputStream.bufferedReader().useLines {lines->lines.forEach(log)};p.waitFor()},log)
                }} else null
                var result=updater?.start(before,target) ?: remote.runtimeStart(id,target)
                while(true){if(result.state=="COMPLETE")manager.finishRuntimeUpdate(id,before,target);states[id]=View(result.state,target,result.logs,result.backup,result.error);if(result.state in setOf("COMPLETE","FAILED"))break;Thread.sleep(500);result=updater?.status() ?: remote.runtimeStatus(id)}
            }catch(e:Exception){states[id]=View("FAILED",target,states[id]?.logs.orEmpty(),states[id]?.backup,e.message?.take(500))}
            finally {manager.releaseMods(id)}
        }
        return initial
    }
}
@RestController
@RequestMapping("/api/instances/{id}/runtime-update")
class RuntimeUpdatesApi(private val updates:RuntimeUpdates) {
    @PostMapping fun start(@PathVariable id:String,@RequestBody target:RuntimeUpdate.Target)=updates.start(id,target)
    @GetMapping fun status(@PathVariable id:String)=updates.status(id)
}
