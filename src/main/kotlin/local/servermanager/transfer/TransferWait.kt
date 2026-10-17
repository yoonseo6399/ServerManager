package local.servermanager

import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

/** Expected verification is not a failed connection and must not restart a local SHA scan. */
class TransferWait(private val now:()->Long=System::nanoTime,private val sleep:(Long)->Unit=Thread::sleep) {
    fun await(checkpoint:()->Unit={},waiting:()->Unit={},request:()->HttpResponse<ByteArray>):HttpResponse<ByteArray> {
        val deadline=now()+TimeUnit.MINUTES.toNanos(30)
        while(true) {
            checkpoint()
            val response=request()
            val pending=response.statusCode()==202 || response.statusCode()==503 && response.headers().firstValue("Retry-After").orElse("")=="5"
            if(!pending)return response
            if(now()>=deadline)throw java.io.IOException("파일 검증 대기 시간이 길어졌습니다. 받은 파일은 보존하고 연결 후 재개합니다")
            waiting()
            val seconds=response.headers().firstValue("Retry-After").orElse("5").toLongOrNull()?.coerceIn(1,10) ?: 5
            sleep(seconds*1000)
        }
    }
}
