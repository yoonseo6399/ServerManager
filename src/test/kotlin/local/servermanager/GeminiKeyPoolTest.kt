package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.io.path.*
import kotlin.test.*

class GeminiKeyPoolTest {
    @TempDir lateinit var root:Path
    private val mapper=jacksonObjectMapper()
    private fun store()=GeminiKeyPoolStore(root)
    private fun secret(id:String)="test-gemini-key-$id-not-a-real-credential"
    private fun add(store:GeminiKeyPoolStore,id:String,project:String,rpm:Int=5,tpm:Long=250000,rpd:Int=20) {store.upsert(PoolKeyConfig(id,project,rpm,tpm,rpd),secret(id))}
    private fun response(input:Long=10,total:Long=20)=mapper.readTree("""{"usageMetadata":{"promptTokenCount":$input,"totalTokenCount":$total}}""")

    @Test fun `keys are encrypted legacy preserved and duplicate credentials rejected`() {
        AgentApiKeyStore(root).save(secret("legacy"))
        val store=store();assertEquals("default",store.credentials().single().config.id)
        add(store,"second","other")
        assertEquals(2,store.credentials().size)
        assertFalse(root.resolve("key-pool.json").readText().contains(secret("second")))
        assertFalse(root.resolve("keys/second/gemini.enc").readBytes().toString(Charsets.UTF_8).contains(secret("second")))
        assertFailsWith<IllegalArgumentException> {store.upsert(PoolKeyConfig("duplicate","third"),secret("second"))}
        assertEquals("[redacted]",store.redact(secret("second")))
        store.remove("second");assertEquals("default",store.credentials().single().config.id)
    }
    @Test fun `same project keys rotate but share RPM and RPD across restart`() {
        val store=store();add(store,"a","shared",rpm=2,rpd=2);add(store,"b","shared",rpm=2,rpd=2)
        var time=Instant.parse("2026-10-15T10:00:00Z").toEpochMilli()
        val tracker=GeminiQuotaTracker(store,"model"){time}
        val a=tracker.reserve(store.credentials(),100).lease!!
        val b=tracker.reserve(store.credentials(),100).lease!!
        assertNotEquals(a.credential.config.id,b.credential.config.id)
        val other=GeminiQuotaTracker(store(),"model"){time}
        assertNull(other.reserve(store.credentials(),100).lease)
        time+=61000
        assertContains(other.reserve(store.credentials(),100).reason,"RPD 소진")
    }
    @Test fun `TPM reservation reconciles actual input and totals`() {
        val store=store();add(store,"a","p",tpm=200)
        val tracker=GeminiQuotaTracker(store,"model"){100000L}
        val lease=tracker.reserve(store.credentials(),150).lease!!
        assertNull(tracker.reserve(store.credentials(),100).lease)
        tracker.complete(lease,response(20,70))
        assertNotNull(tracker.reserve(store.credentials(),100).lease)
        val status=tracker.status(store.configs())
        assertContains(status,"입력 TPM 120/200");assertContains(status,"오늘 사용 토큰 70")
    }
    @Test fun `RPD resets on Pacific midnight including daylight saving`() {
        val store=store();add(store,"a","p",rpd=1)
        var time=Instant.parse("2026-10-15T06:59:59Z").toEpochMilli()
        val tracker=GeminiQuotaTracker(store,"model"){time}
        assertNotNull(tracker.reserve(store.credentials(),10).lease)
        assertNull(tracker.reserve(store.credentials(),10).lease)
        time+=2000
        assertNotNull(tracker.reserve(store.credentials(),10).lease)
    }
    @Test fun `quota errors cool down whole project and fall over to another project`() {
        val store=store();add(store,"a","first");add(store,"b","first");add(store,"c","second")
        val tracker=GeminiQuotaTracker(store,"model"){100000L}
        val used=mutableListOf<String>()
        val model=PooledGeminiModel(store,"model",tracker,factory={key,_->AgentModel {
            used.add(key)
            if(key==secret("a"))throw GeminiApiException(429,60000,true)
            response()
        }},now={100000L},sleep={error("must not sleep")})
        model.generate(mapper.readTree("{}"))
        assertEquals(listOf(secret("a"),secret("c")),used)
        assertContains(tracker.status(store.configs()),"RPD 20/20")
    }
    @Test fun `minute limits wait without spending extra provider requests`() {
        val store=store();add(store,"a","p",rpm=1)
        var time=100000L
        val tracker=GeminiQuotaTracker(store,"model"){time}
        tracker.reserve(store.credentials(),10)
        var calls=0
        val model=PooledGeminiModel(store,"model",tracker,factory={_,_->AgentModel {calls++;response()}},now={time},sleep={time+=it})
        model.generate(mapper.readTree("{}"))
        assertEquals(1,calls);assertTrue(time>=160000L)
    }
    @Test fun `temporary 503 is retried with cooldown and is not reported as invalid key`() {
        val store=store();add(store,"a","p")
        var time=100000L;var calls=0
        val tracker=GeminiQuotaTracker(store,"model"){time}
        val provider=PooledGeminiModel(store,"model",tracker,factory={_,_->AgentModel {
            if(calls++==0)throw GeminiApiException(503,60000)
            response()
        }},now={time},sleep={time+=it})
        provider.generate(mapper.readTree("{}"))
        assertEquals(2,calls);assertTrue(time>=105000L)
        assertContains(GeminiApiException(503).message!!,"일시적인 오류")
    }
    @Test fun `authentication failure rotates only failed credential without resetting group usage`() {
        val store=store();add(store,"a","p");add(store,"b","p")
        val tracker=GeminiQuotaTracker(store,"model"){100000L}
        val used=mutableListOf<String>()
        val model=PooledGeminiModel(store,"model",tracker,factory={key,_->AgentModel {
            used.add(key);if(key==secret("a"))throw GeminiApiException(401);response()
        }},now={100000L})
        model.generate(mapper.readTree("{}"))
        assertEquals(listOf(secret("a"),secret("b")),used)
        assertContains(tracker.status(store.configs()),"RPD 2/20")
    }
    @Test fun `503 backoff applies across projects and final error identifies provider failure`() {
        val store=store();add(store,"a","one");add(store,"b","two");add(store,"c","three")
        var time=100000L
        val calls=mutableListOf<Long>();val notices=mutableListOf<String>()
        val tracker=GeminiQuotaTracker(store,"model"){time}
        val provider=PooledGeminiModel(store,"model",tracker,notify=notices::add,factory={_,_->AgentModel {
            calls.add(time);throw GeminiApiException(503)
        }},now={time},sleep={time+=it})
        val error=assertFailsWith<IllegalStateException> {provider.generate(mapper.readTree("{}"))}
        assertEquals(4,calls.size)
        assertTrue(calls[1]-calls[0]>=2000);assertTrue(calls[2]-calls[1]>=4000);assertTrue(calls[3]-calls[2]>=8000)
        assertContains(error.message!!,"Gemini HTTP 503");assertContains(error.message!!,"제공자 일시 오류")
        assertFalse(error.message!!.contains("키 설정을 확인"))
        assertEquals(3,notices.count {it.contains("후 재시도")})
    }
    @Test fun `503 retry after is honored without bypassing wait budget`() {
        val store=store();add(store,"a","one");add(store,"b","two")
        var time=100000L;var calls=0
        val provider=PooledGeminiModel(store,"model",GeminiQuotaTracker(store,"model"){time},factory={_,_->AgentModel {
            if(calls++==0)throw GeminiApiException(503,10000,false,true)
            response()
        }},now={time},sleep={time+=it})
        provider.generate(mapper.readTree("{}"));assertTrue(time>=110000L);assertEquals(2,calls)
        val blocked=PooledGeminiModel(store,"model",GeminiQuotaTracker(store,"model"){time},factory={_,_->AgentModel {
            throw GeminiApiException(503,120000,false,true)
        }},now={time},sleep={error("must not sleep past budget")})
        assertContains(assertFailsWith<IllegalStateException> {blocked.generate(mapper.readTree("{}"))}.message!!,"대기 한도")
    }
    @Test fun `oversized prompts do not dispatch or consume RPD`() {
        val store=store();add(store,"a","p",tpm=100)
        val tracker=GeminiQuotaTracker(store,"model"){100000L}
        val model=PooledGeminiModel(store,"model",tracker,factory={_,_->error("must not dispatch")})
        assertFailsWith<IllegalStateException> {model.generate(mapper.readTree("{}"))}
        assertContains(tracker.status(store.configs()),"RPD 0/20")
    }
    @Test fun `concurrent reservations across tracker instances never exceed project limit`() {
        val store=store();add(store,"a","p",rpm=3)
        val executor=Executors.newFixedThreadPool(8)
        try {
            val tasks=(1..12).map {executor.submit<Boolean> {GeminiQuotaTracker(store(),"model"){100000L}.reserve(store.credentials(),10).lease!=null}}
            assertEquals(3,tasks.count {it.get()})
        } finally {executor.shutdownNow()}
    }
    @Test fun `updating project limits applies to all its keys`() {
        val store=store();add(store,"a","p",rpm=5)
        add(store,"b","p",rpm=10)
        assertTrue(store.configs().all {it.rpm==10})
    }
}
