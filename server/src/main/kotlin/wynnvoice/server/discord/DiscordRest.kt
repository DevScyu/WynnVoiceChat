package wynnvoice.server.discord

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** One authenticated call against the Discord REST API; the future fails on any non-2xx status. */
fun interface DiscordRest {
    fun send(method: String, path: String, contentType: String, body: ByteArray): CompletableFuture<String>
}

class HttpDiscordRest(
    private val botToken: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : DiscordRest {
    override fun send(method: String, path: String, contentType: String, body: ByteArray): CompletableFuture<String> {
        val request = HttpRequest.newBuilder(URI(BASE_URL + path))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bot $botToken")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", contentType)
            .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
            if (response.statusCode() / 100 != 2) throw IOException("$method $path returned HTTP ${response.statusCode()}: ${response.body().take(300)}")
            response.body()
        }
    }

    companion object {
        const val BASE_URL = "https://discord.com/api/v10"
        const val USER_AGENT = "DiscordBot (WynnVoice-relay, 0.1.0)"
    }
}

/** `payload_json` plus `files[n]` parts, the shape Discord wants for messages with attachments. */
class Multipart(private val payloadJson: String, private val files: List<File>) {
    val boundary = "----WynnVoice" + UUID.randomUUID().toString().replace("-", "")
    val contentType get() = "multipart/form-data; boundary=$boundary"

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        fun part(disposition: String, type: String, content: ByteArray) {
            out.write("--$boundary\r\nContent-Disposition: form-data; $disposition\r\nContent-Type: $type\r\n\r\n".toByteArray())
            out.write(content)
            out.write("\r\n".toByteArray())
        }
        part("name=\"payload_json\"", "application/json", payloadJson.toByteArray())
        files.forEachIndexed { i, file -> part("name=\"files[$i]\"; filename=\"${file.name}\"", "audio/ogg", file.readBytes()) }
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }
}
