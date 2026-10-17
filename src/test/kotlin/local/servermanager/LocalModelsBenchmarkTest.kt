package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.*
import kotlin.test.assertTrue

/** One isolated end-to-end edit per model, not a general coding leaderboard. No Discord/production writes. */
@EnabledIfEnvironmentVariable(named="LOCAL_MODELS_BENCHMARK",matches="true")
class LocalModelsBenchmarkTest {
    @TempDir lateinit var temp:Path
    private val mapper=jacksonObjectMapper()
    private fun swap()=runCatching {ProcessBuilder("/usr/sbin/sysctl","vm.swapusage").start().inputStream.bufferedReader().readText().trim()}.getOrDefault("unavailable")
    @Test fun `compare exact middle insertion through the real approval and tool harness`() {
        val ids=(System.getenv("LOCAL_MODELS_BENCH_IDS") ?: "30b,qwen3-coder-30b,gemma4-12b,gemma4-31b").split(',')
        val results=mutableListOf<Map<String,Any?>>()
        val report=Path.of(System.getenv("LOCAL_MODELS_BENCH_REPORT") ?: "build/reports/local-model-benchmark.json")
        for(id in ids){
            LocalModels.get(id)
            val llm=LocalLlm();llm.unloadQwen()
            val home=temp.resolve(id).createDirectories().toRealPath();val file=home.resolve("note.txt")
            val original="alpha\nkeep=42\nomega\n";val expected="alpha\nkeep=42\nBENCH_ADDED_7D31\nomega\n";file.writeText(original)
            val store=ProjectAgentStore(home.resolve("settings"));store.addChannel(ProjectAgentChannel("bench","channel","owner","fixture",home.toString()))
            val events=mutableListOf<ProjectAgentEvent>();val trace=mutableListOf<Map<String,Any?>>()
            var calls=0;var approvals=0;var input=0L;var output=0L;var failure:String?=null
            val peak=AtomicLong();val monitoring=AtomicBoolean(true)
            val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            val sampler=Thread.ofVirtual().start {while(monitoring.get()){
                runCatching {
                    val request=HttpRequest.newBuilder(URI("http://127.0.0.1:11434/api/ps")).timeout(Duration.ofSeconds(2)).GET().build()
                    val rows=mapper.readTree(http.send(request,HttpResponse.BodyHandlers.ofString()).body()).path("models")
                    rows.filter {it.path("name").asText()==LocalLlm.tag(id)}.forEach {m->peak.accumulateAndGet(m.path("size_vram").asLong(),::maxOf)}
                };Thread.sleep(1000)
            }}
            val before=swap();val started=System.nanoTime()
            val engine=ProjectAgentEngine(store,{ProjectAgentTools(home.resolve("logs"))})
            val timer=Executors.newSingleThreadScheduledExecutor()
            timer.schedule({engine.stop("channel","owner")},360,TimeUnit.SECONDS)
            println("BENCH-START model=$id swap=$before")
            try {
                val inference=ProjectAgentInference {messages,think,progress->
                    check(++calls<=12) {"Benchmark exceeded 12 model calls"}
                    val callStart=System.nanoTime();var first:Double?=null
                    val answer=llm.conversation(id,messages,think) {frame->
                        if(first==null && (frame.text.isNotBlank() || frame.thinking.isNotBlank()))first=(System.nanoTime()-callStart)/1e9
                        progress(frame)
                    }
                    input+=answer.inputTokens ?: 0;output+=answer.tokens
                    trace.add(mapOf("call" to calls,"role" to messages.first().content.take(35),"think" to LocalModels.thinking(id,think),"seconds" to answer.seconds,"firstProgressSeconds" to first,"inputTokens" to answer.inputTokens,"outputTokens" to answer.tokens,"limited" to answer.limited,"reply" to answer.text.take(1200)))
                    println("BENCH-CALL model=$id call=$calls seconds=${answer.seconds} tokens=${answer.tokens} limited=${answer.limited}")
                    check(!answer.limited) {"Model output token limit"};answer.text
                }
                engine.run("channel","owner","벤치마크 사용자","note.txt를 먼저 read_file로 읽어줘. 기존 원문을 모두 보존하고 keep=42 줄 바로 다음, omega 줄 바로 앞에 BENCH_ADDED_7D31 한 줄을 삽입해서 write_file로 저장해줘. 파일 끝에 붙이면 안 돼. 저장 후 반드시 read_file로 다시 읽어서 위치와 원문 보존을 검증한 뒤 보고해줘. 도구는 read_file과 write_file만 사용해.",ProjectAgentPreferences(model=id,think=LocalModels.thinking(id,true),explicitPermission=true),inference,{}) {event->
                    events.add(event)
                    event.plan?.let {plan->check(plan.tool.name in setOf("read_file","write_file") && plan.resolvedTargets==listOf(file.toString())) {"Benchmark forbids other tools or paths"}}
                    if(event.kind=="approval"){approvals++;engine.approve("channel","owner",event.token!!,true){}}
                    if(event.kind in setOf("done","denied","tool_end"))println("BENCH-EVENT model=$id ${event.kind} ${event.plan?.tool?.name} ${event.text.take(250)}")
                }
            }catch(e:Exception){failure=e.message.orEmpty().take(500);println("BENCH-ERROR model=$id $failure")}
            finally {timer.shutdownNow();engine.close();Thread.interrupted();monitoring.set(false);sampler.join(4000)}
            val elapsed=(System.nanoTime()-started)/1e9;val after=swap()
            val starsStart=System.nanoTime();var starsCode="";var starsOutput="";var starsError:String?=null;var starsSuccess=false;var starsInput:Long?=null;var starsTokens=0L
            try {
                val answer=llm.conversation(id,listOf(ProjectAgentMessage("system","Write a Python program. Return ONLY JSON {\"code\":\"complete executable Python source\"}. No markdown. No imports, file/network access, input(), or external libraries. Only compute and print the requested pattern. You may use print, range, len, str, int and simple functions or loops."),ProjectAgentMessage("user","n=5인 중앙 정렬 별 피라미드를 출력하는 Python 코드를 작성해줘. 첫 줄 별 1개부터 매 줄 2개씩 증가하여 마지막 줄 별 9개가 되어야 해. 앞쪽 공백은 4,3,2,1,0개야. 설명이나 다른 출력은 없이 5줄만 출력하고, 각 줄 끝에는 줄바꿈을 넣어줘. n 값을 사용해서 반복문으로 구현해.")),true)
                starsInput=answer.inputTokens;starsTokens=answer.tokens;check(!answer.limited) {"Star code output token limit"}
                starsCode=mapper.readTree(answer.text).path("code").asText();check(starsCode.isNotBlank()) {"No Python code returned"}
                val executed=LocalModelStars.execute(starsCode,home);starsSuccess=executed.first;starsOutput=executed.second
            }catch(e:Exception){starsError=e.message.orEmpty().take(500)}
            val stars=mapOf("success" to starsSuccess,"seconds" to (System.nanoTime()-starsStart)/1e9,"inputTokens" to starsInput,"outputTokens" to starsTokens,"code" to starsCode,"stdout" to starsOutput,"error" to starsError)
            println("BENCH-STARS model=$id "+mapper.writeValueAsString(stars))
            val exact=file.readBytes().contentEquals(expected.toByteArray(Charsets.UTF_8))
            val order=events.filter {it.kind=="tool_end"}.map {it.plan!!.tool.name};val write=order.indexOf("write_file")
            val chain=write>0 && order.take(write).contains("read_file") && order.drop(write+1).contains("read_file")
            val completed=events.lastOrNull()?.let {it.kind=="done" && !it.text.startsWith("작업 중단") && !it.text.startsWith("12턴")}==true
            val result=mapOf("model" to id,"tag" to LocalLlm.tag(id),"thinking" to LocalModels.thinking(id,true),"seconds" to elapsed,"exactFileBytes" to exact,"readWriteReread" to chain,"completed" to completed,"success" to (exact&&chain&&completed&&failure==null),"tools" to order,"writeApprovals" to approvals,"calls" to calls,"inputTokens" to input,"outputTokens" to output,"peakOllamaVramBytes" to peak.get(),"swapBefore" to before,"swapAfter" to after,"error" to failure,"actualFile" to file.readText(),"trace" to trace,"stars" to stars)
            results.add(result);report.parent.createDirectories();report.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("task" to "Exact middle insertion plus executed 5-row Python star pyramid, one run/task/model","numCtx" to 8192,"numPredict" to 4096,"results" to results)))
            println("BENCH-RESULT "+mapper.writeValueAsString(result.filterKeys {it!="trace"}))
            llm.unload(LocalLlm.tag(id));assertTrue(file.startsWith(temp))
        }
        assertTrue(results.size==ids.size)
    }
}
