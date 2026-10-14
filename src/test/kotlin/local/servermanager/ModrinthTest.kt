package local.servermanager

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.security.MessageDigest
import kotlin.test.*

class ModrinthTest {
    private val target=ModrinthTarget("1.21.1","neoforge")
    private fun file(name:String)=RinFile("$name.jar","https://cdn.modrinth.com/data/project/versions/id/$name.jar",100,
        MessageDigest.getInstance("SHA-512").digest(name.toByteArray()).joinToString(""){"%02x".format(it)},"1".repeat(40),true)
    private fun version(project:String,id:String,deps:List<RinDependency> = emptyList(),loader:String="neoforge",type:String="release",date:String="2026-01-01T00:00:00Z")=
        RinVersion(id,project,id,id,type,date,setOf("1.21.1"),setOf(loader),listOf(file(id)),deps)
    private class Repo(val values:List<RinVersion>,val projectOverrides:Map<String,RinProject> = emptyMap()):ModrinthRepository {
        override fun project(id:String)=projectOverrides[id] ?: RinProject(id,id,id,"required")
        override fun versions(project:String,target:ModrinthTarget)=values.filter {it.projectId==project}
        override fun version(id:String)=values.single {it.id==id}
        override fun projectVersion(project:String,idOrNumber:String)=values.single {it.projectId==project && (it.id==idOrNumber || it.number==idOrNumber)}
    }
    private fun required(project:String?,version:String?=null)=RinDependency(project,version,"required")
    @Test fun `links accept project and version number while rejecting SSRF encoded paths and non-mod links`() {
        assertEquals(ModrinthLink("sodium"),ModrinthLink.parse("https://modrinth.com/mod/sodium/?utm_source=test"))
        assertEquals(ModrinthLink("fabric-api","0.116.5+1.21.1"),ModrinthLink.parse("https://www.modrinth.com/mod/fabric-api/version/0.116.5%2B1.21.1"))
        assertEquals(ModrinthLink("fabric-api"),ModrinthLink.parse(" fabric-api "))
        for(bad in listOf("http://modrinth.com/mod/a","https://modrinth.com.evil.com/mod/a","https://modrinth.com@localhost/mod/a","https://modrinth.com:9443/mod/a","https://modrinth.com/modpack/a","https://modrinth.com/mod/%2e%2e","https://modrinth.com/mod/a%2fb","https://modrinth.com/mod/a/version/%2F.."))assertFails(bad){ModrinthLink.parse(bad)}
    }
    @Test fun `compatible exact loader release is selected and explicit incompatible version never falls back`() {
        val stable=version("root","stable");val alpha=version("root","alpha",type="alpha",date="2026-02-01T00:00:00Z");val forge=version("root","forge",loader="forge",date="2026-03-01T00:00:00Z")
        val resolver=ModrinthResolver(Repo(listOf(forge,alpha,stable)))
        assertEquals("stable",resolver.plan("root",target).mods.single().version.id)
        assertEquals("alpha",resolver.plan("root",target.copy(allowPrerelease=true)).mods.single().version.id)
        assertEquals("alpha",resolver.plan("https://modrinth.com/mod/root/version/alpha",target).mods.single().version.id)
        assertFailsWith<IllegalStateException>{resolver.plan("https://modrinth.com/mod/root/version/forge",target)}
        assertFailsWith<IllegalArgumentException>{ModrinthTarget("1.21.1","paper")}
    }
    @Test fun `required closure follows pinned version-only dependencies and cycles but skips optional embedded`() {
        val root=version("root","root1",listOf(required(null,"dep1"),RinDependency("optional",null,"optional"),RinDependency("bundled",null,"embedded")))
        val dep=version("dep","dep1",listOf(required("leaf")));val leaf=version("leaf","leaf1",listOf(required("root","root1")))
        val plan=ModrinthResolver(Repo(listOf(root,dep,leaf))).plan("root",target)
        assertEquals(setOf("root","dep","leaf"),plan.mods.map {it.project.id}.toSet());assertTrue(plan.warnings.any {it.contains("내장")})
    }
    @Test fun `backtracking preserves installed dependency and chooses older compatible root instead of overwriting`() {
        val dep1=version("dep","dep1");val dep2=version("dep","dep2");val old=version("root","old",listOf(required("dep","dep1")))
        val latest=version("root","latest",listOf(required("dep","dep2")),date="2026-02-01T00:00:00Z")
        val resolver=ModrinthResolver(Repo(listOf(latest,old,dep1,dep2)));val plan=resolver.plan("root",target,listOf(dep1))
        assertEquals("old",plan.mods.single {it.project.id=="root"}.version.id);assertTrue(plan.mods.single {it.project.id=="dep"}.alreadyInstalled)
        assertFailsWith<IllegalStateException>{resolver.plan("https://modrinth.com/mod/root/version/latest",target,listOf(dep1))}
        assertFailsWith<IllegalStateException>{resolver.plan("root",target,listOf(dep1,dep2))}
    }
    @Test fun `diamond constraints backtrack and unsatisfiable exact versions are rejected`() {
        val one=version("shared","one");val two=version("shared","two");val a=version("a","a1",listOf(required("shared","one")))
        val b1=version("b","b1",listOf(required("shared","one")));val b2=version("b","b2",listOf(required("shared","two")),date="2026-02-01T00:00:00Z")
        val root=version("root","root1",listOf(required("a"),required("b")))
        assertEquals(setOf("root1","a1","b1","one"),ModrinthResolver(Repo(listOf(root,a,b1,b2,one,two))).plan("root",target).mods.map {it.version.id}.toSet())
        assertFailsWith<IllegalStateException>{ModrinthResolver(Repo(listOf(root,a,b2,one,two))).plan("root",target)}
    }
    @Test fun `server-only safety incompatible declarations and ambiguous files are enforced`() {
        val root=version("root","root1",listOf(RinDependency("other",null,"incompatible")));val other=version("other","other1")
        assertFailsWith<IllegalStateException>{ModrinthResolver(Repo(listOf(root,other))).plan("root",target,listOf(other))}
        assertFailsWith<IllegalStateException>{ModrinthResolver(Repo(listOf(root),mapOf("root" to RinProject("root","root","Client only","unsupported")))).plan("root",target)}
        assertFailsWith<IllegalStateException>{root.copy(files=listOf(file("one"),file("two"))).primaryFile()}
        assertFailsWith<IllegalStateException>{ModrinthResolver(Repo(listOf(root.copy(dependencies=listOf(required(null)))))).plan("root",target)}
        assertFailsWith<IllegalStateException>{ModrinthResolver(Repo(listOf(root.copy(dependencies=listOf(required("wrong","other1"))),other))).plan("root",target)}
    }
    @Test fun `file hashes CDN portable names and case-insensitive duplicate paths are validated`() {
        val valid=file("safe")
        for(bad in listOf(valid.copy(filename="../escape.jar"),valid.copy(filename="CON.jar"),valid.copy(url="https://cdn.modrinth.com.evil.com/a.jar"),valid.copy(url="https://localhost/a.jar"),valid.copy(sha512="abc"),valid.copy(size=0)))assertFails{bad.validate()}
        val root=version("root","root1",listOf(required("dep"))).copy(files=listOf(valid));val dep=version("dep","dep1").copy(files=listOf(file("SAFE")))
        assertFailsWith<IllegalStateException>{ModrinthResolver(Repo(listOf(root,dep))).plan("root",target)}
    }
    @Test fun `API search facets intersect game loader and mod type and version numbers are encoded`() {
        val calls=mutableListOf<String>()
        val api=ModrinthApi(fetch={path->calls.add(path);if(path.startsWith("/search"))"""{"hits":[],"total_hits":0}""" else """{"id":"version1","project_id":"root","version_number":"1.0+mc1.21.1","files":[],"dependencies":[]}"""})
        assertEquals(0,api.search("Create & additions",target,20).total)
        val decoded=java.net.URLDecoder.decode(calls.single(),Charsets.UTF_8)
        assertContains(decoded,"[[\"project_type:mod\"],[\"versions:1.21.1\"],[\"categories:neoforge\"]]");assertContains(calls.single(),"Create+%26+additions")
        api.projectVersion("root","1.0+mc1.21.1");assertTrue(calls.last().endsWith("/version/1.0%2Bmc1.21.1"))
    }
    @Test fun `version environment takes precedence over legacy project metadata and client only versions are excluded`() {
        val good=version("root","root1").copy(environment="client_and_server")
        val project=RinProject("root","root","root","unsupported")
        assertEquals("root1",ModrinthResolver(Repo(listOf(good),mapOf("root" to project))).plan("root",target).mods.single().version.id)
        for(env in listOf("client_only","singleplayer_only"))assertFailsWith<IllegalStateException>{ModrinthResolver(Repo(listOf(good.copy(environment=env)))).plan("root",target)}
        val optional=good.copy(environment="client_only_server_optional")
        assertTrue(ModrinthResolver(Repo(listOf(optional))).plan("root",target).mods.isNotEmpty())
    }
    @Test fun `bulk SHA512 lookup returns only requested content identities and rejects unrelated or false hashes`() {
        val f=file("safe");val json="""{"id":"v1","project_id":"p1","files":[{"filename":"${f.filename}","url":"${f.url}","size":100,"hashes":{"sha512":"${f.sha512}","sha1":"${f.sha1}"}}],"dependencies":[]}"""
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val api=ModrinthApi(fetch={error("unexpected GET")},post={path,body->
            assertEquals("/version_files",path);assertEquals("sha512",mapper.readTree(body).path("algorithm").asText());"""{"${f.sha512}":$json}"""
        })
        assertEquals("v1",api.versionsFromHashes(listOf(f.sha512)).getValue(f.sha512).id)
        assertFailsWith<IllegalArgumentException>{api.versionsFromHashes(listOf("not-a-hash"))}
        assertFailsWith<IllegalStateException>{api.versionsFromHashes(listOf("0".repeat(128)))}
        val falseIdentity=ModrinthApi(post={_,_->"""{"${f.sha512}":${json.replace(f.sha512,"0".repeat(128))}}"""})
        assertFailsWith<IllegalStateException>{falseIdentity.versionsFromHashes(listOf(f.sha512))}
    }
    @Test fun `actual public API project versions and dependencies resolve without downloads or server mutation`() {
        assumeTrue(System.getenv("SM_TEST_MODRINTH_LIVE")=="1")
        val api=ModrinthApi();val t=ModrinthTarget("1.21.1","fabric");val project=api.project("fabric-api")
        assertEquals("fabric-api",project.slug)
        assertTrue(api.search("Fabric API",t).hits.any {it.projectId==project.id})
        val plan=ModrinthResolver(api).plan("https://modrinth.com/mod/fabric-api",t)
        assertEquals(project.id,plan.rootProject);assertTrue(plan.mods.isNotEmpty());plan.mods.forEach {assertTrue(it.version.compatible(t));it.file.validate()}
        val hash=plan.mods.first().file.sha512
        assertEquals(plan.mods.first().version.id,api.versionsFromHashes(listOf(hash)).getValue(hash).id)
    }
    @Test fun `actual public API environments separate client-only mods from server mods`() {
        assumeTrue(System.getenv("SM_TEST_MODRINTH_LIVE")=="1")
        val api=ModrinthApi();val projects=api.projects(listOf("AANobbMI","LNytGWDc")).associateBy {it.slug}
        assertEquals("client_only",projects.getValue("sodium").environment)
        assertFalse(projects.getValue("create").environment in CLIENT_ENVIRONMENTS)
        assertTrue(projects.getValue("sodium").iconUrl!!.startsWith("https://cdn.modrinth.com/"))
        // versions() drops client-only builds for installs, so a known Sodium NeoForge 1.21.1 version is read directly.
        val sodium=api.version("uMOpc5uV");assertEquals("client_only",sodium.environment)
        val hash=sodium.files.first().sha512
        val source=ModSources.lookup(api,listOf(hash),ModSourceCache(),false).cache.sources.getValue(hash)
        assertTrue(source.clientOnly);assertEquals("sodium",source.slug)
    }
}
