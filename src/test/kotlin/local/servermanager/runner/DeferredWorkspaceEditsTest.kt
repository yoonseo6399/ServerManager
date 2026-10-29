package local.servermanager

import org.junit.jupiter.api.Test
import java.util.concurrent.*
import kotlin.test.*

class DeferredWorkspaceEditsTest {
 @Test fun `slow backup never blocks file commit acknowledgement and adjacent edits are coalesced`() {
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
  DeferredWorkspaceEdits(30).use {publisher->
   publisher.mark("server"){error("superseded action must not run")}
   val start=System.nanoTime();publisher.mark("server"){entered.countDown();release.await(3,TimeUnit.SECONDS);done.countDown();true}
   assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(1L,done.count)
   release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS))
  }
 }
 @Test fun `failed announcement retries without a new user upload`() {
  val done=CountDownLatch(1);var attempts=0
  DeferredWorkspaceEdits(20).use {publisher->publisher.mark("server"){if(++attempts<2)error("temporarily offline");done.countDown();true};assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(2,attempts)}
 }
}
