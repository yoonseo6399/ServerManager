package local.servermanager

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude=[org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration::class])
@EnableScheduling
class Application
fun main(args: Array<String>) {
    if(args.contains("--configure-agent-key") || args.contains("--check-agent-api") || args.contains("--list-agent-keys") || args.any {it.startsWith("--remove-agent-key=")}) {
        val root=java.nio.file.Path.of(System.getenv("SERVER_MANAGER_DATA") ?: ".local-data").toAbsolutePath()
        val store=AgentApiKeyStore(root.resolve("security/agent"))
        val pool=GeminiKeyPoolStore(root.resolve("security/agent"),{System.getenv("GEMINI_API_KEY")?.takeIf {it.isNotBlank()} ?: store.read()})
        val model=System.getenv("LUONNOTAR_AGENT_MODEL") ?: "gemini-3.8-flash"
        if(args.contains("--list-agent-keys")) {
            pool.configs().forEach {println("${it.id}: project=${it.project}, enabled=${it.enabled}")}
            println(GeminiQuotaTracker(pool,model).status(pool.configs()));return
        }
        args.firstOrNull {it.startsWith("--remove-agent-key=")}?.let {pool.remove(it.substringAfter('='));println("API key removed from pool.");return}
        if(args.contains("--configure-agent-key")) {
            fun option(name:String)=args.firstOrNull {it.startsWith("--$name=")}?.substringAfter('=')
            val id=option("key-id") ?: "default"
            val project=option("project-id") ?: pool.configs().find {it.id==id}?.project ?: "default"
            val previous=pool.configs().find {it.project==project}
            val config=PoolKeyConfig(id,project,option("rpm")?.toInt() ?: previous?.rpm ?: 5,
                option("tpm")?.toLong() ?: previous?.tpm ?: 250000,option("rpd")?.toInt() ?: previous?.rpd ?: 20)
            val console=System.console() ?: error("키 입력에는 대화형 터미널이 필요합니다.")
            val chars=console.readPassword("Gemini API key (hidden): ") ?: error("키 입력이 취소되었습니다.")
            try {pool.upsert(config,String(chars));println("Gemini API 키를 암호화해 저장했습니다: $id (project=$project)")} finally {chars.fill('\u0000')}
        } else {
            check(pool.credentials().isNotEmpty()) { "Gemini API 키를 먼저 설정하세요." }
            val provider=PooledGeminiModel(pool,model,GeminiQuotaTracker(pool,model),{println(it)})
            try {
                if(args.contains("--batch")) {
                    val temp=java.nio.file.Files.createTempDirectory("luonnotar-batch-check-")
                    try {
                        java.nio.file.Files.writeString(temp.resolve("Example.kt"),"fun answer() = 1\n")
                        java.nio.file.Files.writeString(temp.resolve("gradlew"),"if /usr/bin/grep -q '= 2' Example.kt; then echo batch-smoke-ok; exit 0; else exit 1; fi\n")
                        val files=listOf("Example.kt")
                        var verified=false
                        val result=CodeSubagent(provider).run("Example.kt의 반환값을 1에서 2로 수정한 뒤 run_tests를 실행하세요. 수정과 테스트를 한 배치로 요청하세요.",files,AgentWorkspace(temp,files,true,true)) {event ->
                            if(event.kind=="output" && event.text.startsWith("exitCode=0\n"))verified=true
                        }
                        check(result.completed && verified && java.nio.file.Files.readString(temp.resolve("Example.kt")).contains("= 2")) { "배치 수정·테스트 검증에 실패했습니다." }
                        println("Gemini batch check passed: calls=${result.calls}, tokens=${result.tokens}")
                    } finally {java.nio.file.Files.walk(temp).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {java.nio.file.Files.deleteIfExists(it)}}}
                    return
                }
                val response=provider.generate(com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree("""{"contents":[{"parts":[{"text":"Reply OK."}]}],"generationConfig":{"maxOutputTokens":64}}"""))
                check(response.path("candidates").size()>0) { "모델이 응답을 반환하지 않았습니다." }
                println("Gemini 연결 성공 · 모델 $model · 사용 토큰 ${response.path("usageMetadata").path("totalTokenCount").asLong()}")
            } catch(e:IllegalStateException) {System.err.println(e.message);kotlin.system.exitProcess(1)}
        }
        return
    }
    if(args.contains("--game-host")) {
        val home=args.firstOrNull {it.startsWith("--host-home=")}?.substringAfter('=') ?: error("--host-home=경로 가 필요합니다")
        GameHostMain.run(java.nio.file.Path.of(home).toAbsolutePath().normalize(),RunnerAgent.version())
        return
    }
    if(args.any { it=="--runner" || it=="--runner-pair" || it=="--runner-status" || it.startsWith("--runner-relay-consent=") }) {
        RunnerMain.run(args)
        return
    }
    if(args.contains("--recover-owner")) {
        val dir=java.nio.file.Path.of(System.getenv("SERVER_MANAGER_DATA") ?: ".local-data").toAbsolutePath().resolve("security")
        val ownerId=args.firstOrNull { it.startsWith("--recover-owner-id=") }?.substringAfter('=')
        AuthStore(dir).use { it.recoverOwner(ownerId) }
        println("소유자 세션과 패스키를 폐기했습니다. 새 등록 코드는 $dir/setup-code.txt 에 저장했습니다 (30분 유효).")
        return
    }
    runApplication<Application>(*args)
}
