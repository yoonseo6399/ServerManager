package local.servermanager

import java.nio.ByteBuffer
import java.nio.file.*
import java.security.MessageDigest

/** Capability-based Darwin access; unsupported systems fail closed, without path-only fallbacks. */
class AssistantWorkspace(private val root:Path, private val forbidden:List<Path> = emptyList()) {
    data class File(val path:String,val content:String,val digest:String)
    data class Change(val path:String,val before:String,val after:String)
    private val denied=Regex("(?i)(^|/)(\\.assistant-[^/]*|\\.git|\\.ssh|\\.aws|\\.codex|\\.agents|\\.gnupg|\\.kube|\\.azure|\\.docker|\\.config|\\.ollama|Library|AppData|node_modules|build|security|profile|world|world_nether|world_the_end)(/|$)|(^|/)(\\.env[^/]*|.*\\.(pem|key|p12|pfx|der|keystore)|.*(credential|secret|token|password).*|workspace\\.txt)$")
    init {
        check(com.sun.jna.Platform.isMac()) {"파일 비서는 현재 macOS 관리 앱에서만 지원합니다. 다른 OS에서는 안전한 파일 핸들 기능 구현 전까지 접근을 차단합니다."}
        require(root.isAbsolute && root==root.normalize() && root==root.toRealPath() && Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) {"폴더의 실제 전체 경로를 선택하세요. /tmp 대신 /private/tmp처럼 실제 경로를 입력하세요."}
        require(!denied.containsMatchIn(root.toString())) {"인증·운영 데이터 폴더는 작업 폴더로 사용할 수 없습니다."}
        require(forbidden.none {val path=if(Files.exists(it))it.toRealPath() else it.toAbsolutePath().normalize();root.startsWith(path) || path.startsWith(root)}) {"관리 앱 데이터나 그 상위 폴더는 작업 폴더로 사용할 수 없습니다."}
        AssistantMacDirectory.open(root).use {}
    }
    companion object {fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}}
    private fun relative(value:String):Path {
        require(value.length in 1..500 && !value.contains('\\') && !value.contains('\u0000') && !denied.containsMatchIn(value)) {"허용되지 않은 파일 경로입니다."}
        val path=Path.of(value)
        require(!path.isAbsolute && path.none {it.toString() in setOf(".","..","")} && path.normalize()==path) {"폴더 밖 경로는 허용하지 않습니다."}
        return path
    }
    fun read(name:String):File {
        val path=relative(name)
        val data=AssistantMacDirectory.open(root.resolve(path).parent).use {it.read(path.fileName.toString()).bytes}
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val text=decoder.decode(ByteBuffer.wrap(data)).toString()
        require(!text.contains('\u0000')) {"바이너리 파일은 지원하지 않습니다."}
        return File(name,text,digest(data))
    }
    fun snapshot(names:List<String>):List<File> {
        require(names.size in 1..5 && names.distinct().size==names.size) {"서로 다른 기존 파일 1~5개를 선택하세요."}
        return names.map(::read)
    }
    /** Each file replacement is atomic; multi-file failure triggers best-effort conflict-preserving rollback. */
    @Synchronized fun apply(base:List<File>,changes:List<Change>):List<String> {
        require(changes.isNotEmpty() && changes.map {it.path}.distinct().size==changes.size)
        val originals=base.associateBy {it.path}
        changes.forEach {change->
            val before=originals[change.path] ?: error("승인 범위 밖 파일입니다.")
            require(change.before==before.digest && change.after.toByteArray().size<=49152)
            check(read(change.path).digest==before.digest) {"외부에서 파일이 변경되어 반영을 중단했습니다: ${change.path}"}
        }
        val committed=mutableListOf<Pair<Change,String>>()
        try {
            changes.forEach {change->
                val path=relative(change.path)
                AssistantMacDirectory.open(root.resolve(path).parent).use {dir->committed.add(change to dir.apply(path.fileName.toString(),change.before,change.after.toByteArray(Charsets.UTF_8)))}
            }
            return committed.map {(change,backup)->root.resolve(relative(change.path)).parent.resolve(backup).toString()}
        } catch(e:Exception) {
            committed.asReversed().forEach {(change,backup)->runCatching {
                val path=relative(change.path);AssistantMacDirectory.open(root.resolve(path).parent).use {dir->dir.rollback(path.fileName.toString(),backup,change.after.toByteArray(Charsets.UTF_8))}
            }}
            throw IllegalStateException("파일 충돌로 반영을 중단했습니다. 여러 파일의 복구는 외부 변경을 보존하면서 시도합니다. 복구 사본 .assistant-backup-* 를 확인하세요. ${e.message}",e)
        }
    }
}
