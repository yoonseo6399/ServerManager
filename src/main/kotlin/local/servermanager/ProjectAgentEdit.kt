package local.servermanager

import com.fasterxml.jackson.databind.JsonNode

/**
 * Partial edits for a small model: exact unique text, a line range, or an insertion point. A wrong guess fails with a
 * specific hint instead of silently changing the wrong place, and the approver sees the exact removed/added lines.
 */
internal object ProjectAgentEdit {
    data class Edited(val text:String,val summary:String,val preview:String)
    private const val MAX=1_048_576

    private fun block(prefix:String,rows:List<String>):String {
        val shown=rows.take(20).joinToString("\n") {prefix+it.trimEnd('\r').take(200)}
        return if(rows.isEmpty())"" else shown+(if(rows.size>20)"\n$prefix… (${rows.size-20}줄 더)" else "")
    }
    private fun preview(location:String,removed:List<String>,added:List<String>):String =
        (location+"\n"+listOf(block("- ",removed),block("+ ",added)).filter {it.isNotEmpty()}.joinToString("\n")).take(1800)

    fun apply(original:String,args:JsonNode):Edited {
        require(original.length<=MAX) {"1MB를 넘는 파일은 수정하지 않습니다."}
        val crlf=original.contains("\r\n")
        fun normalize(value:String)=if(crlf)value.replace("\r\n","\n").replace("\n","\r\n") else value
        require(args.path("replace").isTextual) {"replace(새 내용) 문자열이 필요합니다."}
        val replacement=normalize(args.path("replace").asText())
        val edited:Edited
        when {
            args.hasNonNull("find") -> {
                val find=normalize(args.path("find").asText());require(find.isNotEmpty()) {"find 는 비어 있을 수 없습니다."}
                var count=0;var at=original.indexOf(find)
                while(at>=0){count++;at=original.indexOf(find,at+find.length)}
                if(count==0) {
                    val firstLine=find.lines().first().trim()
                    val near=if(firstLine.isNotEmpty())original.lines().indexOfFirst {it.trim()==firstLine}+1 else 0
                    throw IllegalArgumentException("find 텍스트가 파일에 없습니다. 공백·들여쓰기까지 정확히 복사해야 합니다."+(if(near>0)" 첫 줄과 비슷한 줄이 ${near}번째에 있습니다. sed -n '${maxOf(1,near-2)},${near+8}p' 로 다시 읽으세요." else " grep -n 으로 위치를 다시 찾으세요."))
                }
                val all=args.path("replace_all").asBoolean(false)
                require(count==1 || all) {"find 텍스트가 ${count}곳과 일치합니다. 주변 줄을 더 포함해 한 곳만 가리키거나 replace_all:true 를 쓰세요."}
                val text=if(all)original.replace(find,replacement) else original.substring(0,original.indexOf(find))+replacement+original.substring(original.indexOf(find)+find.length)
                val line=original.substring(0,original.indexOf(find)).count {it=='\n'}+1
                edited=Edited(text,"${if(all)count else 1}곳 교체",preview("줄 $line 근처에서 ${if(all)count else 1}곳 교체",find.split("\n"),replacement.split("\n")))
            }
            args.has("start_line") || args.has("insert_after_line") -> {
                val rows=original.split("\n");val total=rows.size-(if(original.endsWith("\n"))1 else 0)
                val added=if(replacement.isEmpty())emptyList() else replacement.removeSuffix("\n").split("\n")
                val from:Int;val to:Int
                if(args.has("start_line")) {
                    from=args.path("start_line").asInt(0);to=args.path("end_line").asInt(from)
                    require(from in 1..total && to in from..total) {"줄 범위가 파일(총 ${total}줄) 밖입니다. 먼저 현재 줄 번호를 다시 확인하세요."}
                } else {
                    val after=args.path("insert_after_line").asInt(-1);require(after in 0..total) {"insert_after_line 은 0~${total} 입니다."}
                    require(replacement.isNotEmpty()) {"삽입할 내용이 비어 있습니다."}
                    from=after+1;to=after
                }
                val removed=rows.subList(from-1,to)
                val text=(rows.subList(0,from-1)+added+rows.subList(to,rows.size)).joinToString("\n")
                edited=Edited(text,if(to<from)"${from-1}줄 뒤에 ${added.size}줄 삽입" else "${from}~${to}줄을 ${added.size}줄로 교체",
                    preview(if(to<from)"${from-1}줄 뒤에 삽입" else "줄 $from~$to 교체",removed,added))
            }
            else -> throw IllegalArgumentException("find+replace, start_line+end_line+replace, insert_after_line+replace 중 하나로 지정하세요.")
        }
        require(edited.text!=original) {"변경되는 내용이 없습니다."}
        require(edited.text.length<=MAX) {"수정 결과가 1MB를 넘습니다."}
        return edited
    }
}
