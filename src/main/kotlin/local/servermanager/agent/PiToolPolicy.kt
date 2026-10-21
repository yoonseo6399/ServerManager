package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.*

/** Application authorization only. Native read/edit/write/bash remain Pi implementations. */
internal class PiToolPolicy(private val profile:Path,private val home:Path,private val tools:ProjectAgentTools,private val tier:String,private val outputTemp:Path?=null) {
    private val mapper=jacksonObjectMapper()
    data class Checked(val guard:ProjectAgentPlan,val display:ProjectAgentPlan,val native:Boolean)
    private fun canonical(path:Path):Path {var existing=path.toAbsolutePath().normalize();val suffix=mutableListOf<String>();while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)){suffix.add(existing.fileName.toString());existing=existing.parent ?: error("경로 확인 실패")};return suffix.asReversed().fold(existing.toRealPath()){a,b->a.resolve(b)}}
    fun check(name:String,args:JsonNode):Checked {
        require(args.isObject && args.toString().length<=100000)
        if(name=="bash")require(tier==AgentAccess.OWNER) {"친구 역할은 임의 셸 명령을 실행할 수 없습니다."}
        if(name in setOf("write","edit"))require(tier!=AgentAccess.READ) {"읽기 전용 역할은 파일을 수정할 수 없습니다."}
        if(args.has("path")) {
            val raw=args.path("path").asText();require(raw.isNotBlank() && raw.length<=2000 && !raw.contains('\u0000'))
            val target=canonical(home.resolve(raw));val actualProfile=profile.toRealPath();val root=if(actualProfile.fileName.toString()=="agent" && actualProfile.parent?.fileName?.toString()=="security")actualProfile.parent else actualProfile;val project=home.toRealPath()
            check(name=="read" && outputTemp!=null && target.startsWith(canonical(outputTemp)) || !target.startsWith(root) || project.startsWith(root) && project!=root && target.startsWith(project)) {"관리 앱의 인증·설정·Pi 상태 폴더는 모델 파일 도구에서 접근할 수 없습니다."}
            require(target.none {ProjectAgentShell.isSecret(it.toString()) || it.toString() in setOf(".release-keys","private.der")}) {"비밀 값이 포함될 수 있는 파일은 Pi 파일 도구에서 읽거나 변경할 수 없습니다."}
        }
        val mapped=when(name) {
            "read"->ProjectAgentTool("read_file",mapper.valueToTree(mapOf("path" to args.path("path").asText())))
            "edit"->ProjectAgentTool("write_file",mapper.valueToTree(mapOf("path" to args.path("path").asText(),"content" to "")))
            "write"->ProjectAgentTool("write_file",args)
            "bash"->ProjectAgentTool("run_command",mapper.valueToTree(mapOf("command" to args.path("command").asText(),"timeout_seconds" to args.path("timeout").asLong(900))))
            else->ProjectAgentTool(name,args)
        }
        if(name in setOf("search","sh"))require(!profile.toRealPath().startsWith(home.toRealPath())) {"관리 앱 데이터가 포함된 상위 폴더에서는 광역 검색을 허용하지 않습니다. 하위 프로젝트를 지정하세요."}
        val guard=tools.plan(home,mapped)
        val native=name in setOf("read","write","edit","bash")
        val description=if(native)"Pi $name\n"+guard.description+(if(name=="edit")"\n수정 내용: ${args.path("edits").toString().take(12000)}"else "")else guard.description
        return Checked(guard,guard.copy(tool=ProjectAgentTool(name,args),description=description),native)
    }
    fun verify(checked:Checked){tools.verify(home,checked.guard)}
    companion object {
        fun extras(tier:String):List<Map<String,Any>> {
            fun str()=mapOf("type" to "string")
            fun num()=mapOf("type" to "integer")
            fun tool(name:String,description:String,properties:Map<String,Any>,vararg required:String)=mapOf("name" to name,"description" to description,"parameters" to mapOf("type" to "object","properties" to properties,"required" to required.toList(),"additionalProperties" to false))
            return listOf(
                tool("list_files","List project files/directories. No file contents.",mapOf("path" to str(),"limit" to num())),
                tool("search","Search bounded original source with line numbers. Use focused patterns and ranges.",mapOf("pattern" to str(),"path" to str(),"glob" to str(),"fixed" to mapOf("type" to "boolean"),"context" to num())),
                tool("make_new_project","Create/register a project, optionally clone an HTTPS repository.",mapOf("name" to str(),"path" to str(),"repo" to str()),"name"),
                tool("list_attachments","List attachments in this Discord project channel.",emptyMap()),
                tool("read_attachment","Read bounded UTF-8 attachment text without executing it.",mapOf("message_id" to str(),"attachment_id" to str(),"start_line" to num(),"lines" to num()),"message_id","attachment_id"),
                tool("save_attachment","Save an attachment to the approved project path.",mapOf("message_id" to str(),"attachment_id" to str(),"path" to str()),"message_id","attachment_id","path"),
                tool("upload_file","Publish a file to this Discord channel; requires backend review/approval.",mapOf("path" to str()),"path"),
                tool("read_result","Read a stored bounded original tool output.",mapOf("id" to str(),"start_line" to num(),"lines" to num()),"id"),
                tool("sh","Restricted in-process read-only project command line, not a shell. pwd ls cat head tail wc grep find git status/diff/log allowed. No redirects/substitution.",mapOf("command" to str()),"command")
            ).filter {it["name"] in ProjectAgentTools.allowed(tier)}
        }
    }
}
