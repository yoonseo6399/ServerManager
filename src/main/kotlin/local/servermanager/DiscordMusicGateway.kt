package local.servermanager

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Avoid requesting an unapproved privileged intent, which would disconnect the entire bot. */
object DiscordMusicGateway {
    fun allowsMessageContent(flags:Long)=flags and ((1L shl 18) or (1L shl 19))!=0L
    fun messageContentAllowed(token:String):Boolean=runCatching {
        val request=HttpRequest.newBuilder(URI("https://discord.com/api/v10/oauth2/applications/@me"))
            .header("Authorization","Bot $token").timeout(Duration.ofSeconds(10)).GET().build()
        val response=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            .send(request,HttpResponse.BodyHandlers.ofInputStream())
        response.body().use {body ->
            if(response.statusCode()!=200)return@use false
            val bytes=body.readNBytes(128001)
            bytes.size<=128000 && allowsMessageContent(jacksonObjectMapper().readTree(bytes).path("flags").asLong())
        }
    }.getOrDefault(false)
}
