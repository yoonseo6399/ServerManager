package local.servermanager

import org.springframework.web.bind.annotation.*
import org.springframework.security.core.Authentication
import org.springframework.http.ResponseEntity
import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/api/instances/{id}/log-search")
class LogSearchApi(private val manager:ServerManager,private val remote:RemoteFiles,private val accounts:AuthStore) {
    private data class Ticket(val user:String,val instance:String,val runner:String?,@Volatile var touched:Long=System.currentTimeMillis())
    private val tickets=ConcurrentHashMap<String,Ticket>()
    private val locals=ConcurrentHashMap<String,LogSearch>()
    private fun local(id:String)=locals.computeIfAbsent(id){LogSearch(manager.profileDir(id))}
    private fun check(id:String,user:String){check(accounts.allowed(user,id,false)){"이 서버를 조회할 권한이 없습니다"};manager.instance(id)}
    private fun ticket(id:String,job:String,user:String):Ticket {check(id,user);val t=tickets[job] ?: error("검색이 만료되었습니다. 다시 검색하세요");check(t.user==user && t.instance==id && t.runner==manager.instance(id).runnerId){"이 검색에 접근할 수 없습니다"};t.touched=System.currentTimeMillis();return t}
    @PostMapping fun start(@PathVariable id:String,@RequestBody request:LogSearch.Request,auth:Authentication):ResponseEntity<LogSearch.View> {
        check(id,auth.name)
        val expired=System.currentTimeMillis()-120000;tickets.entries.removeIf {it.value.touched<expired}
        // Replacing a query cancels that account's previous scan for this server, without affecting another viewer.
        tickets.entries.filter {it.value.user==auth.name && it.value.instance==id}.forEach {(job,t)->runCatching {if(t.runner!=null)remote.searchCancel(id,job)else local(id).cancel(job)};tickets.remove(job)}
        val result=if(remote.remote(id))remote.searchStart(id,request)else local(id).start(request)
        tickets[result.id]=Ticket(auth.name,id,manager.instance(id).runnerId)
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(result)
    }
    @GetMapping("/{job}") fun view(@PathVariable id:String,@PathVariable job:String,@RequestParam(defaultValue="0") offset:Int,auth:Authentication):ResponseEntity<LogSearch.View> {
        val t=ticket(id,job,auth.name);val result=if(t.runner!=null)remote.searchView(id,job,offset)else local(id).view(job,offset)
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(result)
    }
    @DeleteMapping("/{job}") fun cancel(@PathVariable id:String,@PathVariable job:String,auth:Authentication):Map<String,String> {val t=ticket(id,job,auth.name);try {if(t.runner!=null)remote.searchCancel(id,job)else local(id).cancel(job)}finally{tickets.remove(job)};return mapOf("status" to "CANCELLED")}
}
