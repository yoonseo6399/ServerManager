package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*

data class AgentToolProof(val id:String,val tool:String,val targets:List<String>,val exit:Int?,val verified:Boolean,val fingerprints:Map<String,String> = emptyMap(),val change:String="")
data class ProjectAgentTaskPlan(val request:String="",val steps:List<String> = emptyList(),val completed:List<AgentToolProof> = emptyList(),val updated:Long=0)

/** Deterministic execution facts. Neither prose claims nor a successful unrelated tool establish completion. */
internal class ProjectAgentProgress(private val home:Path) {
    private data class Read(val hash:String,val text:String)
    private val reads=mutableMapOf<String,Read>()
    private val attempts=mutableMapOf<String,Int>()
    private var mutation=0
    private val observations=mutableSetOf<String>()
    var repeatedObservation=false;private set
    val proofs=mutableListOf<AgentToolProof>()
    private fun hash(path:Path)=MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString(""){"%02x".format(it)}
    private fun source(path:Path):String?=if(path.isRegularFile() && !Files.isSymbolicLink(path) && path.fileSize()<=1_048_576)path.readText()else null
    private fun stable(n:JsonNode):String=when {n.isObject->n.properties().asSequence().sortedBy {it.key}.joinToString(prefix="{",postfix="}"){it.key+":"+stable(it.value)};n.isArray->n.joinToString(prefix="[",postfix="]"){stable(it)};else->n.toString()}

    fun attempt(call:ProjectAgentTool):String? {
        val key="$mutation:${call.name}:${stable(call.arguments)}"
        val count=(attempts[key] ?: 0)+1;attempts[key]=count
        return if(count>1)"같은 도구·입력을 상태 변화 없이 반복했습니다. 이전 결과를 사용하거나 경로/검색/수정 범위를 바꾸세요. 같은 명령을 다시 실행하지 마세요."else null
    }
    fun before(plan:ProjectAgentPlan,request:String):String? {
        val name=plan.tool.name
        if(name in setOf("edit_file","write_file") && plan.beforeFingerprint!=null && plan.scope=="INSIDE") {
            for(target in plan.resolvedTargets) {
                val path=Path.of(target)
                val read=reads[target] ?: return "기존 파일을 변경하기 전에 read_file로 해당 범위를 읽으세요: $target"
                if(read.hash!=hash(path))return "읽은 뒤 파일이 바뀌었습니다. 현재 범위를 다시 읽으세요: $target"
                if(name=="write_file") {
                    val content=plan.tool.arguments.path("content").asText()
                    val whole=Regex("전체.*(?:교체|덮어|다시)|(?:rewrite|replace).*(?:entire|whole)",RegexOption.IGNORE_CASE).containsMatchIn(request)
                    if(!whole && !content.startsWith(read.text) && !content.endsWith(read.text))return "기존 파일의 일부분 변경은 edit_file을 사용하세요. write_file로 전체 원문을 재작성하지 마세요."
                }
            }
        }
        if(name=="delete_path" && Regex("합[쳐치]|병합|merge|combine",RegexOption.IGNORE_CASE).containsMatchIn(request)) {
            for(target in plan.resolvedTargets) {
                val original=reads[target] ?: return "병합 전에 삭제할 원본을 먼저 읽으세요: $target"
                val preserved=proofs.filter {it.verified && it.tool in setOf("write_file","edit_file")}.any {proof->proof.targets.any {dest->
                    dest!=target && source(Path.of(dest))?.contains(original.text.trimEnd('\r','\n'))==true && proof.fingerprints[dest]==hash(Path.of(dest))
                }}
                if(!preserved)return "원본 삭제 전에 원문이 포함된 병합 결과를 작성하고 저장 결과를 검증해야 합니다: $target"
            }
        }
        return null
    }
    fun record(turn:Int,plan:ProjectAgentPlan,result:ProjectAgentToolResult):AgentToolProof {
        val success=result.exitCode==null || result.exitCode==0
        val fingerprints=mutableMapOf<String,String>()
        val observation=plan.tool.name+":"+plan.resolvedTargets.sorted().joinToString()+":"+MessageDigest.getInstance("SHA-256").digest(result.full.toByteArray()).joinToString(""){"%02x".format(it)}
        repeatedObservation=success && plan.readOnly && !observations.add(observation)
        var newRead=false
        if(success)plan.resolvedTargets.forEach {target->
            val path=Path.of(target)
            if(source(path)!=null)fingerprints[target]=hash(path)
            if(plan.tool.name=="read_file")source(path)?.let {val fingerprint=hash(path);if(reads[target]?.hash!=fingerprint){newRead=true;repeatedObservation=false};reads[target]=Read(fingerprint,it)}
        }
        val verified=success && when(plan.tool.name) {
            "write_file"->plan.resolvedTargets.isNotEmpty() && plan.resolvedTargets.all {source(Path.of(it))==plan.tool.arguments.path("content").asText()}
            "edit_file"->plan.resolvedTargets.isNotEmpty() && fingerprints.size==plan.resolvedTargets.size
            "delete_path"->plan.resolvedTargets.isNotEmpty() && plan.resolvedTargets.none {Files.exists(Path.of(it),java.nio.file.LinkOption.NOFOLLOW_LINKS)}
            else->true
        }
        val args=plan.tool.arguments
        val change=if(!verified)""else when(plan.tool.name) {
            "edit_file"->if(args.has("find"))"SAVED replacement: "+args.path("find").asText().take(80)+" -> "+args.path("replace").asText().take(80) else "SAVED line edit: "+args.path("start_line").asText(args.path("insert_after_line").asText())+" -> "+args.path("replace").asText().take(100)
            "write_file"->"SAVED UTF-8 file; content prefix (may be partial): "+args.path("content").asText().take(100)
            "delete_path"->"REMOVED; confirmed absent"
            "make_new_project"->"CREATED project directory"
            else->""
        }
        val proof=AgentToolProof("T$turn",plan.tool.name,plan.resolvedTargets,result.exitCode,verified,fingerprints,change.take(200))
        proofs.add(proof)
        if(success && (plan.tool.name in setOf("write_file","edit_file","delete_path","make_new_project","save_attachment") || newRead))mutation++
        return proof
    }
    fun currentChanges()=proofs.filter {it.verified && it.change.isNotBlank() && when(it.tool) {
        "delete_path"->it.targets.none {target->Files.exists(Path.of(target),java.nio.file.LinkOption.NOFOLLOW_LINKS)}
        "make_new_project"->it.targets.all {target->Path.of(target).isDirectory()}
        else->it.fingerprints.isNotEmpty() && it.fingerprints.all {(target,expected)->source(Path.of(target))!=null && hash(Path.of(target))==expected}
    }}
    fun proves(text:String,ids:List<String>):Boolean {
        val selected=proofs.filter {it.id in ids && it.verified && (it.exit==null || it.exit==0)}
        val writes=selected.filter {it.tool in setOf("write_file","edit_file","delete_path","make_new_project","save_attachment","run_command","build","test","git")}
        if(writes.isEmpty() || ids.any {id->selected.none {it.id==id}})return false
        val names=Regex("[\\p{L}\\p{N}_./-]+\\.[A-Za-z0-9_-]{1,12}").findAll(text).map {it.value.trimEnd('.')}.toMutableSet()
        // Preserve explicit extensionless names such as “a 와 b 파일”, not only a.txt/b.txt.
        Regex("([A-Za-z0-9_./-]+(?:\\s*(?:와|과|,|및|&)\\s*[A-Za-z0-9_./-]+)*)\\s*파일").findAll(text).forEach {group->
            names.addAll(group.groupValues[1].split(Regex("\\s*(?:와|과|,|및|&)\\s*")).map {it.trim()})
        }
        val fileChange=Regex("대치|대체|삽입|만들|생성|추가|수정|삭제|교체|변경|create|write|edit|delete|replace|change|insert",RegexOption.IGNORE_CASE).containsMatchIn(text)
        if(fileChange && !names.all {name->writes.any {proof->proof.targets.any {target->if('/' in name)Path.of(target).normalize()==home.resolve(name).normalize()else Path.of(target).fileName.toString()==name}}})return false
        return writes.all {proof->(proof.tool!="delete_path" || proof.targets.none {Files.exists(Path.of(it))}) && (proof.tool!="make_new_project" || proof.targets.all {Path.of(it).isDirectory()}) && proof.fingerprints.all {(target,expected)->val path=Path.of(target);path.isRegularFile() && hash(path)==expected}}
    }
}
