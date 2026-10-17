package local.servermanager

import org.junit.jupiter.api.Test
import kotlin.test.*

class TransferMeterTest {
    @Test fun `rate and eta use real recent bytes reset cleanly at resume and stop guessing on stalls`() {
        var now=100000L;val meter=TransferMeter{now}
        fun sample(bytes:Long)=FolderImportProgress("UPLOADING",bytes=bytes,totalBytes=10000)
        meter.reset();assertNull(meter.view(sample(0)).bytesPerSecond)
        now+=2000;meter.observe(sample(2000));assertEquals(1000.0,meter.view(sample(2000)).bytesPerSecond);assertEquals(8L,meter.view(sample(2000)).remainingSeconds)
        now+=2000;meter.observe(sample(4000));assertEquals(6L,meter.view(sample(4000)).remainingSeconds)
        now+=11000;assertEquals(0.0,meter.view(sample(4000)).bytesPerSecond);assertNull(meter.view(sample(4000)).remainingSeconds)
        meter.reset(8000);now+=2000;meter.observe(sample(9000));assertEquals(500.0,meter.view(sample(9000)).bytesPerSecond);assertEquals(2L,meter.view(sample(9000)).remainingSeconds)
        val waiting=meter.view(sample(9000).copy(phase="RETRYING"));assertEquals(0.0,waiting.bytesPerSecond);assertNull(waiting.remainingSeconds)
        assertTrue(TransferDisplay.text(waiting).contains("연결 대기"))
        assertEquals(0L,meter.view(sample(10000).copy(phase="COMPLETE")).remainingSeconds)
    }
    @Test fun `Discord shows matching speed eta bytes and phase without mentioning anyone`() {
        val value=FolderImportProgress("UPLOADING",bytes=2*1024*1024,totalBytes=10*1024*1024,bytesPerSecond=1024.0*1024,remainingSeconds=8)
        val text=TransferDisplay.text(value)
        assertTrue(text.contains("20.0%"));assertTrue(text.contains("1.0 MiB/s"));assertTrue(text.contains("8초"));assertFalse(text.contains("@"))
        assertTrue(TransferDisplay.text(value.copy(bytesPerSecond=null,remainingSeconds=null)).contains("계산 중"))
    }
}
