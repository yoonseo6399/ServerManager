package local.servermanager

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Long command output kept verbatim so the model can page through it with read_result instead of getting a summary.
 * Results are separated per scope (channel); an id is validated and never used as a path.
 */
internal class ProjectAgentResults(private val root:Path,private val now:()->Long = System::currentTimeMillis) {
    companion object {
        const val THRESHOLD=2400
        const val MAX_CHARS=1_100_000
        const val MAX_FILES=20
        const val TTL_MILLIS=24L*60*60*1000
        const val PAGE_CHARS=3600
        val ID=Regex("out-[0-9a-f]{16}")
    }
    private val random=SecureRandom()
    private fun dir(scope:String):Path {
        val name=scope.filter {it.isLetterOrDigit() || it=='-' || it=='_'}.take(64)
        require(name.isNotEmpty()) {"결과 보관 범위가 올바르지 않습니다."}
        return root.resolve("results").resolve(name)
    }
    @Synchronized fun save(scope:String,text:String):String {
        val directory=dir(scope).also {it.createDirectories()}
        prune(directory)
        val id="out-"+ByteArray(8).also(random::nextBytes).joinToString("") {"%02x".format(it)}
        directory.resolve("$id.txt").writeText(text.take(MAX_CHARS))
        return id
    }
    @Synchronized fun read(scope:String,id:String,startLine:Int,lines:Int,startChar:Int=1):String {
        require(ID.matches(id)) {"결과 ID 형식이 올바르지 않습니다."}
        require(startLine>=1 && lines in 1..400 && startChar>=1) {"start_line/start_char는 1 이상, lines는 1~400이어야 합니다."}
        val file=dir(scope).resolve("$id.txt")
        require(file.isRegularFile() && !Files.isSymbolicLink(file)) {"결과를 찾을 수 없습니다. 보관 기간(24시간)이 지났거나 다른 채널의 결과일 수 있습니다."}
        val original=file.readText();val all=original.lines().let {if(original.endsWith("\n") || original.endsWith("\r"))it.dropLast(1)else it}
        val total=all.size
        require(startLine<=total) {"start_line이 총 ${total}줄을 넘었습니다."}
        require(startChar<=all[startLine-1].length+1) {"start_char가 해당 줄 길이를 넘었습니다."}
        val out=StringBuilder();var line=startLine;var offset=startChar;var lastRead=startLine-1
        while(line<=total && line<startLine+lines) {
            val source=all[line-1];val part=source.drop(offset-1).take(2000)
            val row="$line: $part\n"
            if(out.length+row.length>PAGE_CHARS && out.isNotEmpty())break
            out.append(row);lastRead=line
            if(offset-1+part.length<source.length){offset+=part.length;out.append("[이 줄 일부 발췌: 다음 문자부터 이어 읽으세요.]\n");break}
            line++;offset=1
        }
        val next=if(line<=total)" 이어 읽기: read_result {id:\"$id\",start_line:$line,start_char:$offset}" else " 지정한 시작 위치에서 끝까지 읽었습니다."
        return "결과 $id 줄 ${startLine}-${lastRead}/$total (줄 번호 접두사는 표시용)\n$out[${next.trim()}]"
    }
    private fun prune(directory:Path) {
        val files=Files.list(directory).use {s->s.filter {it.isRegularFile()}.toList()}.sortedBy {it.getLastModifiedTime().toMillis()}
        files.filter {now()-it.getLastModifiedTime().toMillis()>TTL_MILLIS}.forEach {it.deleteIfExists()}
        val left=files.filter {it.isRegularFile()}
        if(left.size>=MAX_FILES)left.take(left.size-MAX_FILES+1).forEach {it.deleteIfExists()}
    }
}
