package local.servermanager

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.net.http.HttpHeaders
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TransferWaitTest {
    private fun response(status:Int,retry:String="5"):HttpResponse<ByteArray> {
        @Suppress("UNCHECKED_CAST") val r=mock(HttpResponse::class.java) as HttpResponse<ByteArray>
        `when`(r.statusCode()).thenReturn(status)
        `when`(r.headers()).thenReturn(HttpHeaders.of(mapOf("Retry-After" to listOf(retry))){_,_->true})
        return r
    }
    @Test fun `expected pending keeps transaction alive and checkpoints without rescanning`() {
        var time=0L;var calls=0;var checkpoints=0;var waits=0
        val pending=listOf(response(202),response(503),response(200))
        val result=TransferWait({time},{time+=TimeUnit.MILLISECONDS.toNanos(it)}).await({checkpoints++},{waits++}){pending[calls++]}
        assertEquals(200,result.statusCode());assertEquals(3,calls);assertEquals(3,checkpoints);assertEquals(2,waits)
        assertEquals(TimeUnit.SECONDS.toNanos(10),time)
    }
    @Test fun `actual proxy failure is returned and checkpoint can suspend update safely`() {
        assertEquals(503,TransferWait(sleep={error("Do not wait on Cloudflare failure")}).await{response(503,"60")}.statusCode())
        var calls=0
        assertFailsWith<IllegalStateException>{TransferWait().await(checkpoint={error("Update pause")}){calls++;response(200)}}
        assertEquals(0,calls)
    }
    @Test fun `bounded verification timeout retains control with caller`() {
        var time=0L
        assertFailsWith<java.io.IOException>{TransferWait({time},{time+=TimeUnit.MINUTES.toNanos(31)}).await{response(202)}}
    }
}
