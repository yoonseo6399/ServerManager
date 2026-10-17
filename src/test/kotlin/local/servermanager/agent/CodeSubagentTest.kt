package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class CodeSubagentTest {
    @TempDir lateinit var root:Path
    private val mapper=jacksonObjectMapper()
    private fun action(vararg pairs:Pair<String,String>)=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf(*pairs))
    private fun file() {root.resolve("A.kt").writeText("fun answer() = 1\n")}
    private fun response(plan:String)=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("candidates" to listOf(mapOf("finishReason" to "STOP","content" to mapOf("parts" to listOf(mapOf("text" to plan)))))))

    @Test fun `large development documents can be inspected but not edited`() {
        root.resolve("docs").createDirectory();root.resolve("docs/DEVELOPMENT.md").writeText("a".repeat(139000))
        val files=AgentPresentation.fileNames("/docs/DEVELOPMENT.md")
        assertEquals(listOf("docs/DEVELOPMENT.md"),files)
        val workspace=AgentWorkspace(root,files,false)
        assertEquals(139000,workspace.execute(action("action" to "read_file","path" to files.single())).length)
        val error=assertFailsWith<IllegalArgumentException> {AgentWorkspace(root,files,true)}
        assertContains(error.message!!,"수정 모드는 48KB")
        assertFalse(AgentPresentation.startFailure("파일 검증",error).contains("권한"))
        root.resolve("docs/DEVELOPMENT.md").writeText("a".repeat(256001))
        assertFailsWith<IllegalArgumentException> {AgentWorkspace(root,files,false)}
    }

    @Test fun `missing paths and discord failures have distinct guidance`() {
        val error=assertFailsWith<IllegalArgumentException> {AgentWorkspace(root,listOf("missing.md"),false)}
        assertContains(AgentPresentation.startFailure("파일 검증",error),"파일이 없습니다: missing.md")
        assertContains(AgentPresentation.startFailure("공개 스레드 생성",java.io.IOException()),"공개 스레드 생성 단계에서 실패")
        assertFailsWith<IllegalArgumentException> {AgentWorkspace(root,AgentPresentation.fileNames("/../outside.md"),false)}
    }

    @Test fun `prefetched file edits and tests complete in one model call`() {
        file();root.resolve("gradlew").writeText("echo verified\nexit 0\n")
        var calls=0
        val model=AgentModel { request ->
            calls++;assertContains(request.path("contents").toString(),"fun answer() = 1")
            response("""{"actions":[{"action":"replace_text","path":"A.kt","before":"= 1","after":"= 2"},{"action":"run_tests"}],"finish":true,"message":"값 변경 후 테스트를 실행합니다."}""")
        }
        val events=mutableListOf<AgentEvent>()
        val result=CodeSubagent(model).run("수정하고 테스트",listOf("A.kt"),AgentWorkspace(root,listOf("A.kt"),true,true),events::add)
        assertTrue(result.completed);assertEquals(1,calls)
        assertContains(root.resolve("A.kt").readText(),"= 2")
        assertTrue(events.any {it.kind=="summary" && it.text.contains("테스트 성공 (exitCode=0)")})
        assertEquals("call_start",events.first().kind)
        assertEquals("call_end",events.last().kind)
        assertTrue(events.all {it.call==1})
    }
    @Test fun `failed test log triggers one repair batch then stops after actual success`() {
        file();root.resolve("gradlew").writeText("if /usr/bin/grep -q '= 3' A.kt; then exit 0; else echo failed-check; exit 1; fi\n")
        var calls=0
        val model=AgentModel { request ->
            if(calls>0)assertContains(request.path("contents").toString(),"failed-check")
            val before=if(calls==0)"= 1" else "= 2"
            val after=if(calls++==0)"= 2" else "= 3"
            response("""{"actions":[{"action":"replace_text","path":"A.kt","before":"$before","after":"$after"},{"action":"run_tests"}],"finish":true,"message":"수정 및 검증"}""")
        }
        val result=CodeSubagent(model).run("수정하고 테스트",listOf("A.kt"),AgentWorkspace(root,listOf("A.kt"),true,true)) {}
        assertTrue(result.completed);assertEquals(2,calls);assertContains(root.resolve("A.kt").readText(),"= 3")
    }
    @Test fun `failed batch edit prevents tests and returns failure to model`() {
        file();root.resolve("gradlew").writeText("touch ran-tests\nexit 0\n")
        var calls=0
        val model=AgentModel { request ->
            if(calls++==0)response("""{"actions":[{"action":"replace_text","path":"A.kt","before":"missing","after":"2"},{"action":"run_tests"}],"finish":true,"message":"수정 및 검증"}""")
            else {assertContains(request.path("contents").toString(),"도구 거부");response("""{"actions":[],"finish":true,"message":"수정이 거부되어 종료합니다."}""")}
        }
        val result=CodeSubagent(model).run("수정하고 테스트",listOf("A.kt"),AgentWorkspace(root,listOf("A.kt"),true,true)) {}
        assertFalse(result.completed)
        assertEquals(2,calls);assertFalse(root.resolve("ran-tests").exists());assertContains(root.resolve("A.kt").readText(),"= 1")
    }
    @Test fun `multiple file edits complete together without inventing test success`() {
        file();root.resolve("B.kt").writeText("val b = 1\n")
        val model=AgentModel {response("""{"actions":[{"action":"replace_text","path":"A.kt","before":"= 1","after":"= 2"},{"action":"replace_text","path":"B.kt","before":"= 1","after":"= 3"}],"finish":true,"message":"두 파일을 수정합니다."}""")}
        val events=mutableListOf<AgentEvent>()
        val files=listOf("A.kt","B.kt")
        val result=CodeSubagent(model).run("두 파일 수정",files,AgentWorkspace(root,files,true),events::add)
        assertEquals(1,result.calls);assertEquals(files,result.changedFiles)
        assertTrue(events.any {it.kind=="summary" && it.text.contains("테스트 미실행")})
    }

    @Test fun `API key is encrypted private and tampering fails closed`() {
        val directory=root.resolve("secrets")
        val store=AgentApiKeyStore(directory)
        assertNull(store.read())
        val fake="fake-gemini-key-for-local-tests-only"
        store.save(fake)
        assertEquals(fake,AgentApiKeyStore(directory).read())
        assertFalse(directory.resolve("gemini.enc").readBytes().toString(Charsets.UTF_8).contains(fake))
        assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("gemini.enc"))))
        directory.resolve("key.bin").writeBytes(ByteArray(32))
        val error=assertFailsWith<IllegalStateException> {store.read()}
        assertFalse(error.message!!.contains(fake))
    }

    @Test fun `failed model call always closes its numbered boundary`() {
        file();val events=mutableListOf<AgentEvent>()
        val model=AgentModel {throw IllegalStateException("network failure")}
        assertFailsWith<IllegalStateException> {CodeSubagent(model).run("조사",listOf("A.kt"),AgentWorkspace(root,listOf("A.kt"),false),events::add)}
        assertEquals(listOf("call_start","call_end"),events.map {it.kind})
        assertTrue(events.all {it.call==1});assertContains(events.last().text,"실패")
    }

    @Test fun `thread title uses agent and goal and activity labels identify calls`() {
        assertEquals("분석 에이전트 · 권한 검사",AgentPresentation.threadName("분석 에이전트","권한\n검사"))
        assertTrue(AgentPresentation.threadName("에이전트","가".repeat(200)).length<=95)
        assertEquals("호출 #2 · 실제 실행 결과",AgentPresentation.heading(AgentEvent("output","exitCode=0",2)))
        assertEquals("호출 #2 · ■ 호출 종료",AgentPresentation.heading(AgentEvent("call_end","종료",2)))
    }

    @Test fun `worker reads edits and reports actual tool output to model`() {
        file()
        val actions=listOf(action("action" to "read_file","path" to "A.kt"),
            action("action" to "replace_text","path" to "A.kt","before" to "= 1","after" to "= 2"),
            action("action" to "done","message" to "수정했습니다. 테스트는 실행하지 않았습니다."))
        var calls=0
        val model=AgentModel { request ->
            if(calls==2)assertContains(request.path("contents").toString(),"수정 완료")
            mapper.valueToTree(mapOf("candidates" to listOf(mapOf("finishReason" to "STOP","content" to mapOf("parts" to listOf(mapOf("text" to actions[calls++].toString()))))),"usageMetadata" to mapOf("totalTokenCount" to 10)))
        }
        val events=mutableListOf<AgentEvent>()
        val result=CodeSubagent(model).run("1을 2로 변경",listOf("A.kt"),AgentWorkspace(root,listOf("A.kt"),true),events::add)
        assertEquals("fun answer() = 2\n",root.resolve("A.kt").readText())
        assertEquals(listOf("A.kt"),result.changedFiles)
        assertEquals(30L,result.tokens)
        assertFalse(events.any {it.text.contains("fun answer()")})
        assertTrue(events.any {it.kind=="output" && it.text.contains("수정 완료")})
    }
    @Test fun `inspect mode and disabled tests cannot mutate or execute`() {
        file();val workspace=AgentWorkspace(root,listOf("A.kt"),false)
        workspace.execute(action("action" to "read_file","path" to "A.kt"))
        assertFailsWith<IllegalStateException> {workspace.execute(action("action" to "replace_text","path" to "A.kt","before" to "1","after" to "2"))}
        assertFailsWith<IllegalStateException> {workspace.execute(action("action" to "run_tests"))}
        assertContains(root.resolve("A.kt").readText(),"1")
    }
    @Test fun `test runner returns real exit code and fixed arguments with isolated profile`() {
        file()
        root.resolve("gradlew").writeText("""
            #!/bin/bash
            printf 'args:%s\n' "${'$'}*"
            if [ -n "${'$'}{GEMINI_API_KEY:-}" ]; then exit 99; fi
            case "${'$'}SERVER_MANAGER_DATA" in */build/agent-test-profile) echo isolated-profile;; *) exit 98;; esac
            echo test-failure
            exit 7
        """.trimIndent())
        val workspace=AgentWorkspace(root,listOf("A.kt"),false,true)
        val result=workspace.execute(action("action" to "run_tests"))
        assertContains(result,"exitCode=7")
        assertContains(result,"args:test --console=plain --no-daemon")
        assertContains(result,"isolated-profile")
        assertContains(result,"test-failure")
        workspace.execute(action("action" to "run_tests"))
        assertFailsWith<IllegalStateException> {workspace.execute(action("action" to "run_tests"))}
    }
    @Test fun `file scope traversal hidden paths and symlinks are rejected`() {
        file();root.resolve("B.kt").writeText("secret")
        assertFailsWith<IllegalArgumentException> {AgentWorkspace(root,listOf("../B.kt"),true)}
        root.resolve(".secret.kt").writeText("secret")
        assertFailsWith<IllegalArgumentException> {AgentWorkspace(root,listOf(".secret.kt"),true)}
        Files.createSymbolicLink(root.resolve("Link.kt"),root.resolve("B.kt"))
        assertFailsWith<IllegalArgumentException> {AgentWorkspace(root,listOf("Link.kt"),true)}
        val workspace=AgentWorkspace(root,listOf("A.kt"),true)
        assertFailsWith<IllegalArgumentException> {workspace.execute(action("action" to "read_file","path" to "B.kt"))}
        root.resolve("build.gradle.kts").writeText("plugins {}")
        assertFailsWith<IllegalArgumentException> {AgentWorkspace(root,listOf("build.gradle.kts"),true)}
    }
    @Test fun `edits require fresh reads and a unique match`() {
        file();val workspace=AgentWorkspace(root,listOf("A.kt"),true)
        val edit=action("action" to "replace_text","path" to "A.kt","before" to "1","after" to "2")
        assertFailsWith<IllegalStateException> {workspace.execute(edit)}
        workspace.execute(action("action" to "read_file","path" to "A.kt"))
        root.resolve("A.kt").writeText("fun answer() = 11\n")
        assertFailsWith<IllegalStateException> {workspace.execute(edit)}
        workspace.execute(action("action" to "read_file","path" to "A.kt"))
        assertFailsWith<IllegalArgumentException> {workspace.execute(edit)}
    }
    @Test fun `unfinished responses cannot execute partial changes`() {
        file()
        val model=AgentModel {mapper.readTree("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{}"}]}}]}""")}
        assertFailsWith<IllegalStateException> {CodeSubagent(model).run("수정",listOf("A.kt"),AgentWorkspace(root,listOf("A.kt"),true)) {}}
        assertEquals("fun answer() = 1\n",root.resolve("A.kt").readText())
    }
    @Test fun `repeated tool failures stop at bounded model calls`() {
        file();var calls=0
        val model=AgentModel {
            calls++
            mapper.readTree("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"action\":\"run_tests\"}"}]}}]}""")
        }
        val result=CodeSubagent(model).run("테스트",listOf("A.kt"),AgentWorkspace(root,listOf("A.kt"),false)) {}
        assertFalse(result.completed);assertEquals(12,calls)
    }
}
