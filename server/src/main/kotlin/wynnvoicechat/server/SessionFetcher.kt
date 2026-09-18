package wynnvoicechat.server

import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun interface SessionFetcher {
    fun hasJoined(username: String, serverId: String): CompletableFuture<UUID?>
}

class MojangSessionFetcher(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : SessionFetcher {
    override fun hasJoined(username: String, serverId: String): CompletableFuture<UUID?> {
        val uri = URI("https://sessionserver.mojang.com/session/minecraft/hasJoined" +
            "?username=${URLEncoder.encode(username, Charsets.UTF_8)}&serverId=$serverId")
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build()
        val start = System.nanoTime()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
            val status = response?.statusCode() ?: 0
            HAS_JOINED.getValue(if (status == 204) Metrics.HttpOutcome.NOT_FOUND else Metrics.httpOutcome(status)).record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
            when (status) {
                200 -> parseProfileId(response.body())
                204 -> null
                0 -> throw error
                else -> throw IOException("hasJoined returned HTTP $status")
            }
        }
    }

    companion object {
        private val HAS_JOINED = Metrics.httpTimers("voice_mojang_request_seconds", "has_joined", "profile_by_name", "profile_by_uuid").getValue("has_joined")

        fun parseProfileId(json: String): UUID? {
            val hex = JsonParser.parseString(json).asJsonObject["id"]?.asString?.takeIf { it.length == 32 } ?: return null
            return UUID(hex.substring(0, 16).toULong(16).toLong(), hex.substring(16).toULong(16).toLong())
        }

        fun parseProfileName(json: String): String? = JsonParser.parseString(json).asJsonObject["name"]?.asString
    }
}
