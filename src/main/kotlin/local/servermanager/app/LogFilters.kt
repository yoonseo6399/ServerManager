package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.io.path.*

data class LogFilterRules(val keywords:List<String> = emptyList(),val mods:List<String> = emptyList(),val onlyDebug:Boolean=true)
data class LogFilterPreset(val id:String,val name:String,val rules:LogFilterRules)
data class LogFilterConfig(val enabled:Boolean=true,val rules:LogFilterRules=LogFilterRules(),val presets:List<LogFilterPreset> = emptyList(),val activeId:String?=null)
data class LogFilterView(val config:LogFilterConfig=LogFilterConfig(),val revision:Long=0)

/** User preferences only, never parsed logs. Viewer accounts can save their own display rules. */
class LogFilterStore(private val root:Path) {
    private val mapper=jacksonObjectMapper()
    private fun file(user:String,instance:String):Path {
        require(instance.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val account=MessageDigest.getInstance("SHA-256").digest(user.toByteArray()).joinToString(""){"%02x".format(it)}
        return root.resolve(account).resolve("$instance.json")
    }
    private fun safe(path:Path) {
        var p:Path?=path
        while(p!=null&&p.startsWith(root)){require(!Files.isSymbolicLink(p)){"필터 설정에 심볼릭 링크를 사용할 수 없습니다"};p=p.parent}
    }
    @Synchronized fun get(user:String,instance:String):LogFilterView {
        val p=file(user,instance);safe(p)
        return if(p.exists()) {require(p.fileSize()<=256*1024);mapper.readValue(p.readText())} else LogFilterView()
    }
    @Synchronized fun save(user:String,instance:String,request:LogFilterView):LogFilterView {
        val before=get(user,instance)
        check(request.revision==before.revision){"다른 화면에서 로그 필터가 변경됐습니다. 저장된 필터를 다시 불러오세요."}
        fun rules(r:LogFilterRules) {
            for(values in listOf(r.keywords,r.mods)) {
                require(values.size<=40&&values.all {it.isNotBlank()&&it.length<=120&&it.none(Char::isISOControl)}){"필터마다 120자 이내의 항목을 최대 40개 입력하세요"}
                require(values.map {it.trim().lowercase()}.distinct().size==values.size){"중복 필터 항목을 제거하세요"}
            }
        }
        val c=request.config;rules(c.rules)
        require(c.presets.size<=20){"저장된 필터는 서버마다 최대 20개입니다"}
        require(c.presets.map {it.id}.distinct().size==c.presets.size)
        c.presets.forEach {require(it.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))&&it.name.isNotBlank()&&it.name.length<=60&&it.name.none(Char::isISOControl));rules(it.rules)}
        require(c.activeId==null||c.presets.any {it.id==c.activeId})
        val next=LogFilterView(c,before.revision+1);val bytes=mapper.writeValueAsBytes(next)
        require(bytes.size<=256*1024){"저장할 필터의 전체 용량이 큽니다. 프리셋이나 항목을 줄여주세요"}
        val p=file(user,instance);safe(p)
        p.parent.createDirectories();root.createDirectories()
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));Files.setPosixFilePermissions(p.parent,PosixFilePermissions.fromString("rwx------"))
        val tmp=Files.createTempFile(p.parent,".filters-",".tmp",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {tmp.writeBytes(bytes);Files.move(tmp,p,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)} finally {tmp.deleteIfExists()}
        return next
    }
}

@Service
class LogFilters(manager:ServerManager) {val store=LogFilterStore(manager.root.resolve("security/log-filters"))}

@RestController
@RequestMapping("/api/log-filters/{id}")
class LogFiltersApi(private val filters:LogFilters,private val accounts:AuthStore,private val manager:ServerManager) {
    private fun check(id:String,auth:Authentication) {
        if(!accounts.allowed(auth.name,id,false))throw ResponseStatusException(HttpStatus.FORBIDDEN,"이 서버를 조회할 권한이 없습니다")
        manager.instance(id)
    }
    @GetMapping fun get(@PathVariable id:String,auth:Authentication):ResponseEntity<LogFilterView> {
        check(id,auth);return ResponseEntity.ok().header("Cache-Control","no-store").body(filters.store.get(auth.name,id))
    }
    @PutMapping fun save(@PathVariable id:String,auth:Authentication,@RequestBody request:LogFilterView):LogFilterView {
        check(id,auth);return filters.store.save(auth.name,id,request)
    }
}
