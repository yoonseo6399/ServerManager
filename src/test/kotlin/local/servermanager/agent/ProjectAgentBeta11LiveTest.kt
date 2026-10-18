package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.*
import kotlin.test.*

/** Real local inference, temporary text fixtures only. No Discord sends, production data or system changes. */
@EnabledIfEnvironmentVariable(named="PROJECT_AGENT_BETA11_LIVE",matches="true")
class ProjectAgentBeta11LiveTest {
    @TempDir lateinit var temp:Path
    private val model=System.getenv("PROJECT_AGENT_LIVE_MODEL") ?: "14b"
    private val mapper=jacksonObjectMapper()
    private val nativeMode=System.getenv("PROJECT_AGENT_NATIVE_TOOLS")!="false"
    private val mode get()=if(nativeMode)"native"else "json"
    private val window=System.getenv("PROJECT_AGENT_BETA11_WINDOW")?.toIntOrNull() ?: 8192

    @Test fun `real actor searches reads edits rereads and preserves every unrelated byte`() {
        val home=temp.resolve("project").createDirectories().toRealPath()
        home.resolve("notes").createDirectories()
        val file=home.resolve("notes/example.txt")
        val original="keep header\r\nPATCH_TARGET_9431=old\r\nkeep footer\r\n"
        file.writeText(original)
        val store=ProjectAgentStore(temp.resolve("settings"));store.addChannel(ProjectAgentChannel("g","c","u","fixture",home.toString()))
        store.context("c","u",window=window,output=4096)
        val wireLog=Path.of("build/beta11-evidence").createDirectories().resolve("wire-$mode-$model-$window.jsonl")
        if(!wireLog.exists())wireLog.createFile()
        val prefs=store.settings("g","u",model,true,true);val llm=LocalLlm(trace={kind,value->synchronized(this){wireLog.appendText(mapper.writeValueAsString(mapOf("kind" to kind,"json" to mapper.readTree(value)))+"\n")}});val events=mutableListOf<ProjectAgentEvent>()
        val started=System.nanoTime();var approvals=0
        val inference=object:ProjectAgentInference {
            private var nativeReply:String?=null
            override fun nativeTools()=nativeMode
            override fun nativeMessage()=nativeReply
            override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
            val main=messages.first().content.startsWith("You are 루오노타르")
            val answer=llm.conversationWithBudget(model,messages,think,LocalContextBudget(window,4096).let {if(main)it else it.helper()},progress)
            nativeReply=answer.nativeMessage
            val evidence=Path.of("build/beta11-evidence").createDirectories().resolve("actor-$mode-$model-$window.jsonl")
            synchronized(this){if(!evidence.exists())evidence.createFile();evidence.appendText(mapper.writeValueAsString(mapOf("purpose" to messages.first().content.take(80),"messages" to messages,"native_response" to answer.nativeMessage,"response" to answer.text,"input_tokens" to answer.inputTokens,"output_tokens" to answer.tokens,"seconds" to answer.seconds))+"\n")}
            check(!answer.limited) {"Live request hit the output limit"};return answer.text
            }
        }
        try {
            ProjectAgentEngine(store,{ProjectAgentTools(temp.resolve("logs"))}).use {engine->
                engine.run("c","u","테스트 사용자","notes/example.txt에서 PATCH_TARGET_9431=old를 PATCH_TARGET_9431=new로 한 군데만 대치해. 먼저 search로 위치를 찾고 read_file → edit_file → read_file 순서로 사용해. 나머지 원문과 마지막 개행을 보존하고 저장 후 다시 읽어 검증해. 실제 확인한 결과만 짧게 보고해.",prefs,inference,ProjectAgentLimits(18,AgentAccess.EDIT),{
                    check((System.nanoTime()-started)/1e9<300) {"Live test exceeded 5 minutes"}
                }) {event->
                    events.add(event)
                    if(event.kind=="approval"){assertEquals("edit_file",event.plan!!.tool.name);approvals++;engine.approve("c","u",event.token!!,true){}}
                    if(event.kind in setOf("tool_end","denied","done"))println("BETA11 LIVE ${event.kind} turn=${event.turn} tool=${event.plan?.tool?.name} ${event.text.take(500)}")
                }
            }
        }finally{
            val snapshots=Path.of("build/beta11-evidence").createDirectories()
            snapshots.resolve("fixture-$mode-$model-$window.before.bin").writeBytes(original.toByteArray())
            if(file.exists())snapshots.resolve("fixture-$mode-$model-$window.after.bin").writeBytes(file.readBytes())
            llm.unload(LocalLlm.tag(model))
        }
        val sequence=events.filter {it.kind=="tool_end"}.map {it.plan!!.tool.name}
        assertContentEquals(original.replace("PATCH_TARGET_9431=old","PATCH_TARGET_9431=new").toByteArray(),file.readBytes())
        val search=sequence.indexOf("search");val read=sequence.indexOf("read_file");val edit=sequence.indexOf("edit_file");val verify=sequence.lastIndexOf("read_file")
        assertTrue(search>=0 && read>search && edit>read && verify>edit,sequence.toString());assertEquals(1,approvals)
        println("BETA11 FILE PROOF mode=$mode model=$model window=$window sequence=$sequence approvals=$approvals exact_bytes_preserved=true")
        val final=events.last {it.kind=="done"}.text
        assertFalse(final.contains("미완료") || final.startsWith("작업 중단") || final.contains("턴 한도"),final)
        assertContains(final,"✅ R1")
        println("BETA11 LIVE PROOF mode=$mode model=$model window=$window seconds=${(System.nanoTime()-started)/1e9} sequence=$sequence approvals=$approvals exact_bytes_preserved=true")
    }

    @Test fun `native provider tool protocol can request and use a read result in a separate smoke comparison`() {
        val secret="NATIVE_VALUE_"+java.util.UUID.randomUUID().toString()
        val file=temp.resolve("note.txt").also {it.writeText(secret+"\n")}
        val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val messages=mutableListOf<Any>(mapOf("role" to "system","content" to "You are a file assistant. Use read_file for the requested file. Return the actual file value, not guesses."),mapOf("role" to "user","content" to "Read note.txt with read_file and return its value."))
        val tools=listOf(mapOf("type" to "function","function" to mapOf("name" to "read_file","description" to "Read a UTF-8 text file in the temporary project","parameters" to mapOf("type" to "object","required" to listOf("path"),"properties" to mapOf("path" to mapOf("type" to "string"))))))
        fun call():com.fasterxml.jackson.databind.JsonNode {
            val body=mapper.writeValueAsString(mapOf("model" to LocalLlm.tag(model),"think" to false,"stream" to false,"keep_alive" to "30s","messages" to messages,"tools" to tools,"options" to mapOf("num_ctx" to 8192,"num_predict" to 1024,"temperature" to 0)))
            val request=HttpRequest.newBuilder(URI("http://127.0.0.1:11434/api/chat")).timeout(Duration.ofSeconds(180)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());return mapper.readTree(response.body())
        }
        val llm=LocalLlm()
        try {
            assertTrue(llm.loaded().isEmpty(),"do not interrupt another inference session")
            val first=call();val assistant=first.path("message");val calls=assistant.path("tool_calls")
            assertTrue(calls.isArray && calls.size()==1,first.toString());assertEquals("read_file",calls[0].path("function").path("name").asText())
            val path=calls[0].path("function").path("arguments").path("path").asText()
            assertEquals(file,temp.resolve(path).normalize(),"no arbitrary paths in this smoke harness")
            messages.add(assistant);messages.add(mapOf("role" to "tool","tool_name" to "read_file","content" to file.readText()))
            val final=call();assertContains(final.path("message").path("content").asText(),secret)
            println("BETA11 NATIVE SMOKE model=$model requested_read=true used_real_tool_result=true; full harness completion is tested separately")
        }finally{llm.unload(LocalLlm.tag(model))}
    }
}
