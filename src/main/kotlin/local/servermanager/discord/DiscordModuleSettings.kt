package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.*

data class DiscordModuleState(val revision:Long=0,val disabled:Set<String> = emptySet())
data class DiscordModuleSave(val revision:Long,val disabled:Set<String>)

/** Separate from the token-bearing legacy settings so older versions can still read their original file. */
class DiscordModuleSettings(private val directory:Path) {
    companion object {val BUILT_INS=setOf("minecraft","music","agent","ai","assistant","permission")}
    private val file=directory.resolve("module-settings.json")
    private val mapper=jacksonObjectMapper()
    @Volatile private var state:DiscordModuleState
    @Volatile var warning:String="";private set
    init {
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(file)) {"모듈 설정에 심볼릭 링크를 사용할 수 없습니다."}
        directory.createDirectories()
        state=if(!file.exists())DiscordModuleState()else runCatching {
            mapper.readValue<DiscordModuleState>(file.readText()).also {require(it.revision>=0 && it.disabled.all {id->id in BUILT_INS})}
        }.getOrElse {warning="모듈 설정을 읽지 못해 기능을 껐습니다. 이 화면에서 다시 저장하세요.";DiscordModuleState(disabled=BUILT_INS)}
    }
    fun view()=state
    fun enabled(id:String)=id !in state.disabled
    @Synchronized fun save(request:DiscordModuleSave):DiscordModuleState {
        check(request.revision==state.revision) {"다른 화면에서 모듈 설정이 변경됐습니다. 새로고침하세요."}
        require(request.disabled.all {it in BUILT_INS}) {"알 수 없는 모듈입니다."}
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(file)) {"모듈 설정 경로를 확인하세요."}
        val next=DiscordModuleState(Math.addExact(state.revision,1),request.disabled.toSet())
        val tmp=Files.createTempFile(directory,"modules-",".tmp")
        try {
            Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"))
            tmp.writeText(mapper.writeValueAsString(next));Files.move(tmp,file,ATOMIC_MOVE,REPLACE_EXISTING)
        }finally {Files.deleteIfExists(tmp)}
        state=next;warning="";return next
    }
}
