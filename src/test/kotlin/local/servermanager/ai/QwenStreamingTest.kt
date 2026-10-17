package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class QwenStreamingTest {
    @Test fun `partial reply decoder handles escapes and never leaks tool arguments`() {
        assertEquals("안녕",QwenVisibleText.message("""{"message":"안녕"""))
        assertEquals("a\nb",QwenVisibleText.message("""{"message":"a\nb"""))
        assertEquals("a",QwenVisibleText.message("""{"message":"a\uD5"""))
        assertEquals("a한",QwenVisibleText.message("""{"message":"a\uD55C"""))
        assertEquals("",QwenVisibleText.message("""{"tool":{"arguments":{"message":"SECRET"}}"""))
        assertEquals("visible",QwenVisibleText.message("""{"tool":{"arguments":{"message":"SECRET"}},"message":"visible"""))
    }
    @Test fun `slow Discord edits coalesce and terminal reply wins over stale thinking`() {
        val scheduler=Executors.newSingleThreadScheduledExecutor();val callbacks=CopyOnWriteArrayList<()->Unit>();val values=CopyOnWriteArrayList<String>();val first=CountDownLatch(1);val last=CountDownLatch(1)
        try {
            val edits=CoalescedMessageEdits<String>({value,complete->values.add(value);callbacks.add(complete);if(value=="DONE")last.countDown() else first.countDown()},scheduler,10)
            edits.offer("Thinking first");assertTrue(first.await(2,TimeUnit.SECONDS))
            repeat(100){edits.offer("Thinking $it")};edits.offer("DONE",true);edits.offer("stale Thinking")
            assertEquals(listOf("Thinking first"),values.toList());callbacks[0]()
            assertTrue(last.await(2,TimeUnit.SECONDS));assertEquals(listOf("Thinking first","DONE"),values.toList());callbacks[1]()
        }finally{scheduler.shutdownNow()}
    }
    @Test fun `reply text reaches callback before Ollama marks response done`() {
        val mapper=jacksonObjectMapper();val allowFinal=CountDownLatch(1);val visible=CountDownLatch(1);val frame=AtomicReference<QwenProgress>();val pool=Executors.newCachedThreadPool()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);server.executor=pool
        server.createContext("/api/ps"){x->val bytes="""{"models":[]}""".toByteArray();x.sendResponseHeaders(200,bytes.size.toLong());x.responseBody.use {it.write(bytes)}}
        server.createContext("/api/chat"){x->x.sendResponseHeaders(200,0);x.responseBody.use {out->
            fun chunk(content:String,done:Boolean=false){out.write((mapper.writeValueAsString(mapOf("message" to mapOf("content" to content),"done" to done))+"\n").toByteArray());out.flush()}
            chunk("{\"message\":\"안");allowFinal.await(5,TimeUnit.SECONDS);chunk("녕하세요\",\"tool\":null}",true)
        };x.close()};server.start()
        try {
            val future=pool.submit<LocalLlm.Answer> {LocalLlm(URI("http://127.0.0.1:${server.address.port}")).conversation("14b",listOf(ProjectAgentMessage("user","hello")),false){frame.set(it);if(it.text=="안")visible.countDown()}}
            assertTrue(visible.await(3,TimeUnit.SECONDS));assertFalse(future.isDone);assertEquals("",frame.get().thinking)
            allowFinal.countDown();assertTrue(future.get(3,TimeUnit.SECONDS).text.contains("안녕하세요"));assertEquals("안녕하세요",frame.get().text)
        }finally{allowFinal.countDown();server.stop(0);pool.shutdownNow()}
    }
    @Test fun `interrupt closes in-flight Ollama stream and frees the request slot`() {
        val firstFrame=CountDownLatch(1);val disconnected=CountDownLatch(1);val failure=AtomicReference<Throwable>();val pool=Executors.newCachedThreadPool()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);server.executor=pool
        server.createContext("/api/ps"){x->val bytes="""{"models":[]}""".toByteArray();x.sendResponseHeaders(200,bytes.size.toLong());x.responseBody.use {it.write(bytes)}}
        server.createContext("/api/chat"){x->try{x.sendResponseHeaders(200,0);x.responseBody.use {out->repeat(100){out.write(("""{"message":{"thinking":"token "},"done":false}"""+"\n").toByteArray());out.flush();Thread.sleep(40)}}}catch(_:java.io.IOException){disconnected.countDown()}finally{x.close()}}
        server.start();val llm=LocalLlm(URI("http://127.0.0.1:${server.address.port}"))
        val thread=Thread {try{llm.conversation("14b",listOf(ProjectAgentMessage("user","hello"))){firstFrame.countDown()}}catch(e:Throwable){failure.set(e)}}
        try {
            thread.start();assertTrue(firstFrame.await(3,TimeUnit.SECONDS));thread.interrupt();thread.join(2500)
            assertFalse(thread.isAlive);assertContains(failure.get().message.orEmpty(),"중단");assertTrue(disconnected.await(3,TimeUnit.SECONDS))
            server.removeContext("/api/chat");server.createContext("/api/chat"){x->val b=("""{"message":{"content":"{\"message\":\"done\",\"tool\":null}"},"done":true}"""+"\n").toByteArray();x.sendResponseHeaders(200,b.size.toLong());x.responseBody.use {it.write(b)}}
            assertTrue(llm.conversation("14b",listOf(ProjectAgentMessage("user","next")),false).text.contains("done"))
        }finally{thread.interrupt();thread.join(1000);server.stop(0);pool.shutdownNow()}
    }
}
