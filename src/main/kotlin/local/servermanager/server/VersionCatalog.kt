package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.*

data class VersionListing(val versions: List<String>, val fetchedAt: String, val stale: Boolean = false, val warning: String? = null)

class VersionCatalog(private val cache: Path, private val download: () -> String = {
    val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
        .send(HttpRequest.newBuilder(URI("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"))
            .header("User-Agent", "ServerManager/0.1 (local Minecraft manager)").timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "NeoForge 저장소 HTTP ${response.statusCode()}" }
    response.body()
}) {
    private val mapper = jacksonObjectMapper()
    private var cached: VersionListing? = runCatching { mapper.readValue<VersionListing>(cache.readText()) }.getOrNull()
    private var lastAttempt = Instant.EPOCH

    @Synchronized fun get(refresh: Boolean = false): VersionListing {
        val now = Instant.now()
        cached?.let { if (!refresh && (Duration.between(Instant.parse(it.fetchedAt), now).toHours() < 6 || Duration.between(lastAttempt,now).seconds < 60)) return it }
        lastAttempt = now
        var failure: Exception? = null
        repeat(2) {
            try {
                val versions = parse(download())
                check(versions.isNotEmpty()) { "응답에 Minecraft 1.21.1용 NeoForge 버전이 없습니다" }
                val result = VersionListing(versions, now.toString())
                cached = result
                runCatching { cache.parent.createDirectories(); cache.writeText(mapper.writeValueAsString(result)) }
                return result
            } catch (e: Exception) { failure = e }
        }
        cached?.let { return it.copy(stale = true, warning = "최신 조회 실패: ${failure?.message}. 마지막 성공 목록을 사용합니다.").also { cached = it } }
        throw IllegalStateException("NeoForge 버전을 가져오지 못했습니다: ${failure?.message}. 다시 시도하거나 21.1.x 버전을 직접 입력하세요.")
    }

    companion object {
        fun parse(xml: String): List<String> = Regex("<version>\\s*(21\\.1\\.[0-9]+(?:-beta)?)\\s*</version>")
            .findAll(xml).map { it.groupValues[1] }.distinct()
            .sortedWith(compareByDescending<String> { it.substringAfterLast('.').substringBefore('-').toInt() }.thenBy { it.contains('-') }).toList()
    }
}
