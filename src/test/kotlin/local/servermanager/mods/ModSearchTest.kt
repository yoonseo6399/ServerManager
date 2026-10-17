package local.servermanager

import org.junit.jupiter.api.Test
import kotlin.test.*

class ModSearchTest {
    private fun source(id:String,deps:List<ModDependency>,clientOnly:Boolean=false)=ModSource(id,id,id,versionId="v$id",versionNumber="1",clientOnly=clientOnly,dependencies=deps)
    private fun mod(id:String,vararg deps:String)=InstalledMod("$id.jar",true,10,id,id,source(id,deps.map {ModDependency(it,"required")}))
    private fun graph(vararg mods:InstalledMod)=ModGraph(ModInventorySnapshot("r",mods.toList(),emptyList()))
    @Test fun `disabling a provider also disables mods that require it while plans keep providers fixed`() {
        val lib=mod("lib").let {it.copy(source=source("lib",listOf(ModDependency("alias","embedded"))))}
        val g=graph(mod("main","alias"),lib,mod("extra","main"),mod("other"))
        assertTrue(assertFailsWith<IllegalArgumentException>{g.disable(setOf("lib.jar"))}.message!!.contains("main.jar"))
        assertEquals(listOf("main.jar"),g.dependencyReasons["lib.jar"])
        assertEquals(setOf("extra.jar"),g.disable(setOf("extra.jar")))
        val p=g.plan(setOf("lib.jar","main.jar","other.jar"),setOf("main.jar"))
        assertEquals(listOf("lib.jar","main.jar"),p.protectedSuspects)
        assertEquals(listOf("other.jar"),p.groups.single().files)
        assertEquals(setOf("other.jar"),g.disabled(p,emptySet()))
    }
    @Test fun `a second provider of the same project keeps dependents enabled`() {
        val copy=mod("lib").copy(filename="lib-copy.jar",sha512="lib2")
        assertFailsWith<IllegalArgumentException>{graph(mod("main","lib"),mod("lib"),copy).disable(setOf("lib.jar"))}
    }
    @Test fun `all transitive providers and cross-file cycles are fixed even if selected as suspects`() {
        val g=graph(mod("a","bridge"),mod("bridge","b"),mod("b","a"),mod("c"),mod("addon","c"))
        val p=g.plan(setOf("a.jar","b.jar","c.jar","addon.jar"),emptySet())
        assertEquals(listOf("addon.jar"),p.groups.single().files)
        assertEquals(setOf("a.jar","b.jar","bridge.jar","c.jar"),p.fixed.toSet())
        assertEquals(setOf("addon.jar"),g.disabled(p,emptySet()))
        assertFailsWith<IllegalArgumentException>{g.disable(setOf("c.jar"))}
        assertFailsWith<IllegalArgumentException>{g.disable(setOf("a.jar"))}
    }
    @Test fun `missing and unknown dependencies are reported without blocking other mods`() {
        val missing=graph(mod("a","missing"),mod("b"))
        assertEquals(mapOf("a.jar" to setOf("missing")),missing.missing)
        assertFailsWith<IllegalArgumentException>{missing.plan(setOf("b.jar"),emptySet())}
        assertEquals(setOf("a.jar"),missing.disable(setOf("a.jar")))
        val unknown=graph(mod("a").copy(source=null),mod("b","a"))
        assertTrue(unknown.dependencyFiles.isEmpty());assertEquals(setOf("a.jar"),unknown.disable(setOf("a.jar")))
        val client=graph(mod("lib"),mod("shader","lib").let {it.copy(source=it.source!!.copy(clientOnly=true))})
        assertEquals(setOf("lib.jar"),client.dependencyFiles)
    }
    @Test fun `enabling restores disabled providers and rejects duplicate or incompatible projects`() {
        fun off(m:InstalledMod)=m.copy(filename=m.filename+".disabled",enabled=false)
        fun snapshot(vararg mods:InstalledMod)=ModInventorySnapshot("r",mods.toList(),emptyList(),projects=mapOf("ghost" to ModProjectRef("ghost","ghost","Ghost Lib")))
        val (files,warnings)=ModGraph.enable(snapshot(off(mod("main","lib")),off(mod("lib","core")),off(mod("core")),off(mod("unrelated"))),setOf("main.jar.disabled"))
        assertEquals(setOf("main.jar.disabled","lib.jar.disabled","core.jar.disabled"),files);assertTrue(warnings.isEmpty())
        assertFailsWith<IllegalArgumentException>{ModGraph.enable(snapshot(off(mod("main","ghost"))),setOf("main.jar.disabled"))}
        assertFailsWith<IllegalArgumentException>{ModGraph.enable(snapshot(mod("main"),off(mod("main")).copy(filename="main-old.jar.disabled")),setOf("main-old.jar.disabled"))}
        val bad=mod("bad").let {it.copy(source=source("bad",listOf(ModDependency("main","incompatible"))))}
        assertFailsWith<IllegalArgumentException>{ModGraph.enable(snapshot(mod("main"),off(bad)),setOf("bad.jar.disabled"))}
        assertFailsWith<IllegalArgumentException>{ModGraph.enable(snapshot(mod("main")),setOf("main.jar"))}
    }
    @Test fun `Modrinth lookup caches by hash and only retries unknown files when asked`() {
        val calls=mutableListOf<List<String>>()
        val dep=RinDependency("lib",null,"required")
        val version=RinVersion("v1","main","Main","1.0","release","2026-01-01T00:00:00Z",setOf("1.21.1"),setOf("neoforge"),emptyList(),listOf(dep),"client_and_server")
        val api=object:ModrinthLookup {
            override fun versionsFromHashes(hashes:List<String>):Map<String,RinVersion> {calls.add(hashes.sorted());return if("a" in hashes)mapOf("a" to version) else emptyMap()}
            override fun projects(ids:Collection<String>)=ids.map {RinProject(it,it,it.uppercase(),"required",environment="client_and_server")}
        }
        val first=ModSources.lookup(api,listOf("a","b"),ModSourceCache(),false)
        val source=first.cache.sources.getValue("a")
        assertEquals("MAIN",source.title);assertEquals(setOf("lib"),source.required);assertFalse(source.clientOnly)
        assertEquals(listOf(ModDependency("lib","required")),source.dependencies);assertEquals("LIB",first.cache.projects.getValue("lib").title)
        assertTrue("b" in first.cache.notFound)
        assertSame(first.cache,ModSources.lookup(api,listOf("a","b"),first.cache,false).cache);assertEquals(1,calls.size)
        ModSources.lookup(api,listOf("a","b"),first.cache,true);assertEquals(listOf("b"),calls.last())
        assertFalse("x" in ModSources.lookup(api,listOf("a","b"),first.cache.copy(notFound=first.cache.notFound+("x" to "t")),true).cache.notFound)
        val failing=object:ModrinthLookup {override fun versionsFromHashes(hashes:List<String>):Map<String,RinVersion> =error("offline");override fun projects(ids:Collection<String>)=emptyList<RinProject>()}
        val offline=ModSources.lookup(failing,listOf("c"),first.cache,false)
        assertSame(first.cache,offline.cache);assertTrue(offline.warnings.single().contains("offline"))
    }
    @Test fun `client only uses the version environment before deprecated server side`() {
        val v=RinVersion("v","p","n","1","release","",emptySet(),emptySet(),emptyList(),emptyList())
        assertTrue(ModSources.clientOnly(v.copy(environment="client_only"),null))
        assertTrue(ModSources.clientOnly(v.copy(environment="singleplayer_only"),null))
        assertFalse(ModSources.clientOnly(v.copy(environment="client_only_server_optional"),null))
        assertFalse(ModSources.clientOnly(v.copy(environment="client_and_server"),RinProject("p","p","p","unsupported")))
        assertTrue(ModSources.clientOnly(v,RinProject("p","p","p","required",environment="client_only")))
        assertTrue(ModSources.clientOnly(v,RinProject("p","p","p","unsupported")))
        assertFalse(ModSources.clientOnly(v,RinProject("p","p","p","optional")))
    }
    @Test fun `unknown mods only warn and never block unrelated mods while known dependencies stay protected`() {
        val g=graph(mod("private").copy(source=null),mod("lib"),mod("addon","lib"))
        assertFailsWith<IllegalArgumentException>{g.disable(setOf("lib.jar"))}
        assertEquals(setOf("addon.jar"),g.disable(setOf("addon.jar")))
        assertEquals(setOf("private.jar"),g.disable(setOf("private.jar")))
        assertEquals(setOf("private.jar"),g.unknown)
        assertEquals(setOf("lib.jar"),g.blockedReasons.keys)
        assertEquals(listOf("addon.jar"),g.plan(setOf("addon.jar"),emptySet()).groups.flatMap {it.files})
    }
    @Test fun `exact dependency versions and version specific incompatibility cannot match another version`() {
        fun off(m:InstalledMod)=m.copy(filename=m.filename+".disabled",enabled=false)
        val main=mod("main").copy(source=source("main",listOf(ModDependency("lib","required","v2"))))
        val old=mod("lib").copy(source=source("lib",emptyList()).copy(versionId="v1"))
        val correct=off(mod("lib")).copy(source=source("lib",emptyList()).copy(versionId="v2"))
        assertTrue(graph(main,old).missing["main.jar"]!!.contains("lib@v2"))
        assertFails {ModGraph.enable(ModInventorySnapshot("r",listOf(off(main),old),emptyList()),setOf("main.jar.disabled"))}
        assertEquals(setOf("main.jar.disabled","lib.jar.disabled"),ModGraph.enable(ModInventorySnapshot("r",listOf(off(main),correct),emptyList()),setOf("main.jar.disabled")).first)
        val bad=off(mod("bad").copy(source=source("bad",listOf(ModDependency("lib","incompatible","v2")))))
        assertEquals(setOf("bad.jar.disabled"),ModGraph.enable(ModInventorySnapshot("r",listOf(old,bad),emptyList()),setOf("bad.jar.disabled")).first)
        assertFails {ModGraph.enable(ModInventorySnapshot("r",listOf(correct.copy(enabled=true),bad),emptyList()),setOf("bad.jar.disabled"))}
    }
    @Test fun `version only dependencies are resolved persisted and reused offline with exact identity`() {
        val version=RinVersion("mainV","main","Main","1","release","",emptySet(),emptySet(),emptyList(),listOf(RinDependency(null,"libV2","required")))
        var versionCalls=0
        val api=object:ModrinthLookup {
            override fun versionsFromHashes(hashes:List<String>)=mapOf("hash" to version)
            override fun version(id:String):RinVersion {versionCalls++;return version.copy(id=id,projectId="lib",dependencies=emptyList())}
            override fun projects(ids:Collection<String>)=ids.map {RinProject(it,it,it,"required")}
        }
        val result=ModSources.lookup(api,listOf("hash"),ModSourceCache(),false)
        assertEquals(listOf(ModDependency("lib","required","libV2")),result.cache.sources.getValue("hash").dependencies)
        assertEquals(1,versionCalls)
        assertSame(result.cache,ModSources.lookup(api,listOf("hash"),result.cache,false).cache)
        assertEquals(1,versionCalls)
    }
    private fun solve(all:List<String>,fails:(List<String>)->Boolean):Pair<SearchStep,List<SearchObservation>> {
        val history=mutableListOf<SearchObservation>()
        repeat(200){val s=ManualModSearch.next(all,history);if(s.status!="TEST")return s to history;history.add(SearchObservation(s.groups,if(fails(s.groups))"FAIL" else "PASS"))}
        error("search did not finish")
    }
    @Test fun `single culprit uses logarithmic trials and requires human baseline comparisons`() {
        val all=(1..128).map {"m$it"}
        val (result,history)=solve(all){"m93" in it}
        assertEquals("FOUND",result.status);assertEquals(listOf("m93"),result.groups)
        assertTrue(history.size<=18);assertEquals(all.sorted(),history.first().groups);assertTrue(history[1].groups.isEmpty())
        assertEquals("OUTSIDE_SELECTION",solve(all){true}.first.status)
        assertEquals("NOT_REPRODUCED",solve(all){false}.first.status)
    }
    @Test fun `interacting mods and multiple independent causes do not claim every culprit is found`() {
        val all=listOf("a","b","c","d","e")
        assertEquals(listOf("b","e"),solve(all){it.containsAll(listOf("b","e"))}.first.groups)
        val first=solve(all){"b" in it || "e" in it}.first
        assertEquals(1,first.groups.size)
        val second=solve(all-first.groups.toSet()){"b" in it || "e" in it}.first
        assertEquals(setOf("b","e"),(first.groups+second.groups).toSet())
    }
    @Test fun `inconclusive blocks advancement and correction replays decisions`() {
        val all=listOf("a","b","c","d")
        val history=mutableListOf(SearchObservation(all,"FAIL"),SearchObservation(emptyList(),"PASS"))
        val next=ManualModSearch.next(all,history)
        history.add(SearchObservation(next.groups,"INVALID"))
        assertEquals("INCONCLUSIVE",ManualModSearch.next(all,history).status)
        assertEquals(next.groups,ManualModSearch.next(all,history).groups)
        history.add(SearchObservation(next.groups,"FAIL"))
        assertTrue(ManualModSearch.next(all,history).groups.size<next.groups.size)
        history.add(SearchObservation(all,"PASS"))
        assertEquals("NOT_REPRODUCED",ManualModSearch.next(all,history).status)
    }
}
