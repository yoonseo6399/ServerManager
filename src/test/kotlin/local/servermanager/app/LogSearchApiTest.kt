package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class LogSearchApiTest {
    @TempDir lateinit var root:Path
    @Test fun `viewers can search their readable file but cannot read or cancel another account search`() {
        val manager=mock(ServerManager::class.java);val remote=mock(RemoteFiles::class.java);val accounts=mock(AuthStore::class.java)
        val i=Instance("a","Test",loaderVersion="21.1.264",port=25565,memoryMb=1024,javaPath="java")
        `when`(manager.instance("a")).thenReturn(i);`when`(manager.profileDir("a")).thenReturn(root);`when`(accounts.allowed("viewer","a",false)).thenReturn(true);`when`(accounts.allowed("other","a",false)).thenReturn(true)
        root.resolve("console.log").writeText("needle\n");val api=LogSearchApi(manager,remote,accounts);val viewer=UsernamePasswordAuthenticationToken("viewer",null,emptyList());val other=UsernamePasswordAuthenticationToken("other",null,emptyList())
        val job=api.start("a",LogSearch.Request("console.log","needle"),viewer).body!!
        assertFailsWith<ResponseStatusException>{api.view("a",job.id,0,other)};assertFailsWith<ResponseStatusException>{api.cancel("a",job.id,other)}
        val deadline=System.nanoTime()+5_000_000_000L
        while(api.view("a",job.id,0,viewer).body!!.state!="COMPLETE"){check(System.nanoTime()<deadline);Thread.sleep(5)}
        assertEquals(1L,api.view("a",job.id,0,viewer).body!!.matches);api.cancel("a",job.id,viewer)
        verify(accounts,never()).allowed("viewer","a",true)
    }
}
