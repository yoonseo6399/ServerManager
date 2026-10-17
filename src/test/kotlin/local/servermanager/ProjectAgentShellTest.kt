package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class ProjectAgentShellTest {
    @TempDir lateinit var temp:Path
    private lateinit var home:Path
    private fun shell():ProjectAgentShell {
        home=temp.resolve("project").createDirectories().toRealPath()
        home.resolve("src").createDirectories()
        home.resolve("src/A.kt").writeText("fun alpha() {}\n// TODO first\nfun beta() {}\n// TODO second\n")
        home.resolve("src/B.kt").writeText("class B\n// todo lower\n")
        home.resolve("README.md").writeText("hello\nworld\n")
        home.resolve(".env").writeText("TOKEN=super-secret\n")
        home.resolve("build").createDirectories().resolve("generated.kt").writeText("// TODO generated\n")
        temp.resolve("outside.txt").writeText("outside-secret")
        return ProjectAgentShell(home)
    }
    private fun run(line:String)=shell().run(line)

    @Test fun `grep finds matches recursively with line numbers and skips build output and secrets`() {
        val r=run("grep -rn TODO .")
        assertEquals(0,r.status);assertTrue(r.output.contains("src/A.kt:2:// TODO first"));assertTrue(r.output.contains("src/A.kt:4:// TODO second"))
        assertFalse(r.output.contains("generated.kt"));assertFalse(r.output.contains("super-secret"));assertFalse(r.output.contains("B.kt:2"))
        assertTrue(run("grep -rni todo src").output.contains("B.kt:2:// todo lower"))
        assertTrue(run("grep -rn TODO src --include=B.kt").status==1)
    }
    @Test fun `grep status drives and-or chains like a shell`() {
        assertTrue(run("grep -c TODO src/A.kt && echo found").output.endsWith("found"))
        val miss=run("grep nomatch README.md && echo SHOULD-NOT-RUN");assertEquals(1,miss.status);assertFalse(miss.output.contains("SHOULD-NOT-RUN"))
        assertEquals("fallback",run("grep nomatch README.md || echo fallback").output)
        assertTrue(run("grep nomatch README.md ; echo after").output.contains("after"))
        assertEquals("hello",run("cat README.md | head -n 1").output)
        assertEquals("2",run("grep -rn TODO src | wc -l").output.trim())
    }
    @Test fun `pipelines use the previous output and context lines work`() {
        assertEquals("fun alpha() {}\n// TODO first",run("sed -n '1,2p' src/A.kt").output)
        assertEquals("// TODO first\nfun beta() {}",run("grep -A1 first src/A.kt").output)
        assertEquals("A.kt\nB.kt",run("ls src").output)
        assertEquals("1",run("find . -name '*.md' | wc -l").output.trim())
        assertEquals("world",run("tail -n 1 README.md").output)
    }
    @Test fun `redirection substitution and background operators are rejected before running`() {
        val shell=shell()
        for(line in listOf("echo hi > pwned.txt","cat README.md < /etc/passwd","echo \$(whoami)","echo `id`","sleep 5 &","echo \"\$HOME\"","(cat README.md)")) {
            assertFailsWith<IllegalArgumentException>(line) {shell.validate(line)}
        }
        assertFalse(home.resolve("pwned.txt").exists())
        assertFailsWith<IllegalArgumentException> {shell.validate("rm -rf .")};assertFailsWith<IllegalArgumentException> {shell.validate("curl http://x")}
        assertFailsWith<IllegalArgumentException> {shell.validate("grep foo &&")};assertFailsWith<IllegalArgumentException> {shell.validate("| cat")}
        shell.validate("grep -rn 'foo\$' src && git status --short | head -n 5")
    }
    @Test fun `paths outside the project and secret files are refused`() {
        val shell=shell()
        for(line in listOf("cat ../outside.txt","cat ${temp.resolve("outside.txt").toString().replace('\\','/')}","ls ..","grep -r outside-secret ..","find .. -name '*.txt'","sed -n '1p' ../outside.txt")) {
            val r=shell.run(line);assertEquals(2,r.status,line);assertFalse(r.output.contains("outside-secret"),line)
        }
        val secret=shell.run("cat .env");assertEquals(2,secret.status);assertFalse(secret.output.contains("super-secret"))
        assertFalse(shell.run("grep -r TOKEN .").output.contains("super-secret"))
    }
    @Test fun `a symlink that leaves the project is not followed`() {
        val shell=shell()
        val link=home.resolve("escape")
        val created=runCatching {Files.createSymbolicLink(link,temp)}.isSuccess
        org.junit.jupiter.api.Assumptions.assumeTrue(created,"symbolic links are not available here")
        assertEquals(2,shell.run("cat escape/outside.txt").status);assertFalse(shell.run("grep -r outside-secret .").output.contains("outside-secret"))
    }
    @Test fun `git is limited to read only subcommands and safe arguments`() {
        val shell=shell()
        for(line in listOf("git push","git commit -m x","git checkout main","git branch evil","git diff --output=x","git -C .. status","git diff --no-index a b","git log ../..","git config user.name x","git diff -O/etc/passwd")) {
            assertFailsWith<IllegalArgumentException>(line) {shell.validate(line)}
        }
        shell.validate("git status --short");shell.validate("git log --oneline -n 5 -- src");shell.validate("git diff --stat HEAD~1..HEAD");shell.validate("git branch --list")
        val init=runCatching {ProcessBuilder("git","init","-q").directory(home.toFile()).redirectErrorStream(true).start().also {it.waitFor()}.exitValue()}.getOrDefault(1)
        org.junit.jupiter.api.Assumptions.assumeTrue(init==0,"git is not installed")
        assertTrue(shell.run("git status --short").output.contains("README.md"))
    }
    @Test fun `long output is bounded and a broken pattern is reported instead of thrown`() {
        val shell=shell();home.resolve("big.txt").writeText((1..20000).joinToString("\n") {"line $it of the big file"})
        val r=shell.run("cat big.txt");assertTrue(r.output.length<=ProjectAgentShell.LIMIT+400);assertTrue(r.output.contains("출력 일부 생략"))
        assertEquals("line 20000 of the big file",shell.run("tail -n 1 big.txt").output)
        assertEquals(2,shell.run("grep '(' README.md").status)
    }
}
