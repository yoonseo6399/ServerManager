package local.servermanager

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.*

/** Real pinned SDK/stock tools. Only the inference transport is deterministic. No legacy actor adapter. */
internal class PiTestFixture(temp:Path,explicit:Boolean=true,val tier:String=AgentAccess.OWNER,clock:()->Instant={Instant.now()},approvalSeconds:Long=900):AutoCloseable {
    val root=temp.resolve("pi-${java.util.UUID.randomUUID()}").createDirectories()
    val home=root.resolve("project").createDirectories().toRealPath()
    val profile=root.resolve("profile").createDirectories()
    val store=ProjectAgentStore(profile).also {
        it.addChannel(ProjectAgentChannel("g","c","u","demo",home.toString(),executionWindow=16384,contextLimit=7000,outputTokens=2048))
        it.settings("g","u","14b",true,explicit)
    }
    val prefs get()=store.preferences("g","u")
    val events=CopyOnWriteArrayList<ProjectAgentEvent>()
    val engine=ProjectAgentEngine(store,{s->ProjectAgentTools(profile.resolve("logs")){name,path->store.register(s.guild,s.user,name,path)}},clock,approvalSeconds,runtime=runtime)
    fun run(model:Model,request:String="fixture request",turns:Int=12,name:String="Tester",authorize:()->Unit={},onEvent:((ProjectAgentEvent)->Unit)?=null) {
        engine.run("c","u",name,request,prefs,model,ProjectAgentLimits(turns,tier),authorize) {e->
            events.add(e)
            if(onEvent!=null)onEvent(e)else if(e.kind=="approval")engine.approve("c","u",e.token!!,true){}
        }
    }
    fun model(risk:String=ALLOW,inputTokens:Int=100,script:(JsonNode)->JsonNode)=Model(risk,inputTokens,script)
    class Model(var risk:String,private val tokens:Int,private val script:(JsonNode)->JsonNode):ProjectAgentInference {
        val requests=CopyOnWriteArrayList<JsonNode>();val reviews=CopyOnWriteArrayList<List<ProjectAgentMessage>>();var actorCalls=0;private var raw:String?=null
        override fun generate(messages:List<ProjectAgentMessage>,think:Boolean,progress:(QwenProgress)->Unit):String {
            val q=PiWire.decode(messages)
            if(q==null){reviews.add(messages);raw=null;return risk}
            requests.add(q);if(q.path("tools").size()>0)actorCalls++
            val message=script(q);raw=message.toString();return message.path("content").asText("")
        }
        override fun nativeMessage()=raw
        override fun usage()=QwenUsage(input=tokens.toLong(),output=10,executionWindow=16384)
    }
    override fun close()=engine.close()
    companion object {
        private val json=jacksonObjectMapper()
        val runtime=PiRuntime(Path.of("build/pi-functional-runtime").toAbsolutePath())
        const val ALLOW="""{"action":"ALLOW","risk":"LOW","projectScope":"INSIDE","reason":"요청된 시험 작업"}"""
        fun call(name:String,args:Any)=json.valueToTree<JsonNode>(mapOf("role" to "assistant","content" to "","tool_calls" to listOf(mapOf("id" to java.util.UUID.randomUUID().toString(),"type" to "function","function" to mapOf("name" to name,"arguments" to json.writeValueAsString(args))))))
        fun answer(text:String)=json.valueToTree<JsonNode>(mapOf("role" to "assistant","content" to text,"tool_calls" to emptyList<Any>()))
    }
}
