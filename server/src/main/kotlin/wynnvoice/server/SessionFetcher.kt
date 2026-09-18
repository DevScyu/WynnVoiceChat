package wynnvoice.server

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
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
            when (response.statusCode()) {
                200 -> parseProfileId(response.body())
                204 -> null
                else -> throw IOException("hasJoined returned HTTP ${response.statusCode()}")
            }
        }
    }

    companion object {
        fun parseProfileId(json: String): UUID? {
            val hex = JsonParser.parseString(json).asJsonObject["id"]?.asString?.takeIf { it.length == 32 } ?: return null
            return UUID(hex.substring(0, 16).toULong(16).toLong(), hex.substring(16).toULong(16).toLong())
        }
    }
}
