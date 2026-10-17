package local.servermanager

import java.nio.file.*
import java.security.MessageDigest
import kotlin.io.path.*

/** Narrow project-scoped consent, never a generic shell auto-approval rule. */
internal object ProjectAgentGradle {
    fun argv(plan:ProjectAgentPlan):List<String>? {
        if(plan.tool.name !in setOf("build","test","run_command"))return null
        val words=plan.tool.arguments.path("command").asText().trim().split(Regex("\\s+"))
        if(words.firstOrNull()!="./gradlew" || words.size<2)return null
        val tasks=setOf("build","test","check","assemble","clean")
        val flags=setOf("--no-daemon","--offline","--stacktrace","--info","--console=plain")
        if(words.drop(1).any {it !in tasks && it !in flags} || words.drop(1).none {it in tasks-setOf("clean")})return null
        return words+listOf("--console=plain")
    }
    fun fingerprint(plan:ProjectAgentPlan):String? {
        if(argv(plan)==null || plan.scope=="OUTSIDE")return null
        val home=Path.of(plan.cwd).toRealPath()
        if(!home.resolve("gradlew").isRegularFile())return null
        val global=Path.of(System.getenv("GRADLE_USER_HOME") ?: Path.of(System.getProperty("user.home"),".gradle").toString())
        if(global.resolve("init.gradle").exists() || global.resolve("init.gradle.kts").exists() || global.resolve("init.d").let {it.exists() && it.listDirectoryEntries().isNotEmpty()})return null
        val digest=MessageDigest.getInstance("SHA-256");digest.update(home.toString().toByteArray())
        var bytes=0L;var count=0
        return runCatching {
            Files.walk(home,12).use {paths->paths.filter {p->home.relativize(p).none {it.toString() in setOf(".git",".gradle","build","node_modules")}}.sorted().forEach {file->
                val relative=home.relativize(file).toString()
                check(!Files.isSymbolicLink(file)) {"링크가 있는 프로젝트는 빌드를 개별 승인합니다."}
                if(file.isRegularFile() && (relative.startsWith("gradle/") || relative.startsWith("buildSrc/") || relative.startsWith("build-logic/") || file.name in setOf("gradlew","gradlew.bat","gradle.properties") || file.name.endsWith(".gradle") || file.name.endsWith(".gradle.kts"))){
                    check(++count<=1000);bytes+=file.fileSize();check(bytes<=10_485_760)
                    digest.update(relative.toByteArray());file.inputStream().use {input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
                }
            }}
            home.toString()+":"+digest.digest().joinToString(""){"%02x".format(it)}
        }.getOrNull()
    }
}
