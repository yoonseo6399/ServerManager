package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class LogFiltersTest {
    @TempDir lateinit var temp:Path
    @Test fun `presets persist per user server and reject stale oversized unsafe data`() {
        val store=LogFilterStore(temp.resolve("filters"));assertEquals(0,store.get("user-a","server-a").revision)
        val rules=LogFilterRules(listOf("repeated message"),listOf("create"),true)
        val config=LogFilterConfig(rules=rules,presets=listOf(LogFilterPreset("p1","Debug noise",rules)),activeId="p1")
        store.save("user-a","server-a",LogFilterView(config,0))
        assertEquals(config,LogFilterStore(temp.resolve("filters")).get("user-a","server-a").config)
        assertTrue(store.get("user-b","server-a").config.presets.isEmpty());assertTrue(store.get("user-a","server-b").config.presets.isEmpty())
        assertFailsWith<IllegalStateException>{store.save("user-a","server-a",LogFilterView(config,0))}
        assertFailsWith<IllegalArgumentException>{store.save("user-a","server-a",LogFilterView(config.copy(rules=rules.copy(keywords=List(41){"k$it"})),1))}
        assertFailsWith<IllegalArgumentException>{store.save("user-a","server-a",LogFilterView(config.copy(rules=rules.copy(mods=listOf("a\nb"))),1))}
        assertFailsWith<IllegalArgumentException>{store.save("user-a","../server-a",LogFilterView(config,0))}
        val large=LogFilterRules(List(40){"긴".repeat(110)+it},List(40){"모".repeat(110)+it},true)
        val oversized=LogFilterConfig(rules=large,presets=List(20){LogFilterPreset("large$it","설정$it",large)})
        assertFailsWith<IllegalArgumentException>{store.save("user-a","server-a",LogFilterView(oversized,1))}
        assertEquals(config,store.get("user-a","server-a").config)
        val file=java.nio.file.Files.walk(temp.resolve("filters")).use {s->s.filter {it.name=="server-a.json"}.findFirst().get()}
        assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(file)))
        file.deleteExisting();java.nio.file.Files.createSymbolicLink(file,temp.resolve("outside.json"))
        assertFailsWith<IllegalArgumentException>{store.get("user-a","server-a")}
    }
    @Test fun `viewer can save own display preferences but cannot access an unreadable server`() {
        val manager=mock(ServerManager::class.java);`when`(manager.root).thenReturn(temp)
        val accounts=mock(AuthStore::class.java)
        val i=Instance("a","A",loaderVersion="21.1.249",port=25565,memoryMb=512,javaPath="java")
        `when`(manager.instance("a")).thenReturn(i);`when`(accounts.allowed("viewer","a",false)).thenReturn(true)
        val api=LogFiltersApi(LogFilters(manager),accounts,manager)
        val auth=UsernamePasswordAuthenticationToken("viewer",null,emptyList())
        val rules=LogFilterRules(mods=listOf("noisy"))
        assertEquals(rules,api.save("a",auth,LogFilterView(LogFilterConfig(rules=rules),0)).config.rules)
        assertFailsWith<ResponseStatusException>{api.get("b",auth)}
        verify(accounts,never()).allowed("viewer","a",true)
    }
}
