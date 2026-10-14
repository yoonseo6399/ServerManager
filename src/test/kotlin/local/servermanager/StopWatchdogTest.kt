package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Path
import java.util.stream.Stream
import kotlin.test.*

class StopWatchdogTest {
    @TempDir lateinit var temp:Path
    @Test fun `repeated milestones and noisy logs never hide a stuck stop but distinct saving resets deadline`() {
        val clock=StopProgressClock(0)
        clock.log("[12:00:00] [Server thread/INFO]: Saving worlds",10000)
        assertEquals(1,clock.status(189001).remainingSeconds)
        clock.log("[12:02:59] [Server thread/INFO]: Saving worlds",189000)
        clock.log("[Server thread/ERROR]: Saving worlds Exception",189000)
        clock.log("[Thread/INFO]: Chunky task status 90%",189000)
        assertEquals(0,clock.status(190000).remainingSeconds)
        clock.log("[Server thread/INFO]: Saving chunks for level 'ServerLevel[world]'/minecraft:overworld",190000)
        assertEquals(180,clock.status(190000).remainingSeconds)
        clock.worldWrite(360000)
        assertEquals(1,clock.status(539001).remainingSeconds)
        assertEquals(0,clock.status(540000).remainingSeconds)
    }
    @Test fun `process replacement exit and handoff cancel timer while only unchanged live process can time out`() {
        var now=0L;var current=true;var fired=0
        val process=mock(Process::class.java);`when`(process.isAlive).thenReturn(true)
        val monitor=StopMonitor(process,temp,{current},{now}){fired++}
        now=179999;monitor.tick();assertEquals(0,fired)
        current=false;now=180000;monitor.tick();assertEquals(0,fired)
        current=true;now=999999;monitor.tick();assertEquals(0,fired)
        now=0
        val second=StopMonitor(process,temp,{true},{now}){fired++}
        now=180000;second.tick();second.tick();assertEquals(1,fired)
        now=0
        val exited=StopMonitor(process,temp,{true},{now}){fired++}
        `when`(process.isAlive).thenReturn(false);now=180000;exited.tick();assertEquals(1,fired)
    }
    @Test fun `actual region writes extend timeout without reading or hashing world file contents`() {
        val world=temp.resolve("world");java.nio.file.Files.createDirectories(world.resolve("region"))
        java.nio.file.Files.writeString(world.resolve("level.dat"),"fixture")
        val region=world.resolve("region/r.0.0.mca");java.nio.file.Files.writeString(region,"before")
        var now=0L;var fired=0
        val process=mock(Process::class.java);`when`(process.isAlive).thenReturn(true)
        val monitor=StopMonitor(process,temp,{true},{now}){fired++}
        try {
            now=170000;java.nio.file.Files.writeString(region,"saved chunk progress")
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
            while(monitor.status().lastProgress!="월드 저장 파일 쓰기 감지"&&System.nanoTime()<deadline){monitor.tick();Thread.sleep(100)}
            assertEquals("월드 저장 파일 쓰기 감지",monitor.status().lastProgress)
            now=180001;monitor.tick();assertEquals(0,fired)
            now=350000;monitor.tick();assertEquals(1,fired)
        }finally{monitor.close()}
    }
    @Test fun `timeout reason is explicit and optional exit metadata remains old-reader compatible when absent`() {
        assertContains(ServerExit(137,true,"STOP_TIMEOUT").summary(),"3분")
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        assertFalse(mapper.writeValueAsString(RunnerExitReport(0)).contains("reason"))
        assertContains(mapper.writeValueAsString(RunnerExitReport(137,reason="STOP_TIMEOUT")),"STOP_TIMEOUT")
    }
}
