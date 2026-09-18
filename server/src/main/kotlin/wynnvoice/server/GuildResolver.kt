package wynnvoice.server

import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import io.micrometer.core.instrument.Gauge
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import wynnvoice.server.Metrics.counters

fun interface ApiFetcher {
    /** Response body, or null when the resource does not exist. */
    fun get(uri: URI): CompletableFuture<String?>
}

class HttpApiFetcher(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : ApiFetcher {
    override fun get(uri: URI): CompletableFuture<String?> {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build()
        val start = System.nanoTime()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
            record(uri, response, System.nanoTime() - start)
            when (response?.statusCode()) {
                200 -> response.body()
                404 -> null
                null -> throw error
                else -> throw IOException("${uri.path} returned HTTP ${response.statusCode()}")
            }
        }
    }

    private fun record(uri: URI, response: HttpResponse<String>?, nanos: Long) {
        val endpoint = endpointOf(uri) ?: return
        val timers = if (endpoint == "profile_by_name") MOJANG else WYNN
        timers.getValue(endpoint).getValue(Metrics.httpOutcome(response?.statusCode() ?: 0)).record(nanos, TimeUnit.NANOSECONDS)
        val remaining = response?.headers()?.firstValue("RateLimit-Remaining")?.orElse(null)?.toDoubleOrNull() ?: return
        WYNN_RATELIMIT_REMAINING[endpoint]?.set(remaining)
    }

    companion object {
        private val MOJANG = Metrics.httpTimers("voice_mojang_request_seconds", "has_joined", "profile_by_name")
        private val WYNN = Metrics.httpTimers("voice_wynn_api_request_seconds", "player", "guild")
        /** Set from the `RateLimit-Remaining` header; the bucket is named after the endpoint. */
        private val WYNN_RATELIMIT_REMAINING = listOf("player", "guild").associateWith { endpoint ->
            AtomicReference(Double.NaN).also { Gauge.builder("voice_wynn_api_ratelimit_remaining", it) { r -> r.get() }.tag("bucket", endpoint.uppercase()).register(Metrics.registry) }
        }

        fun endpointOf(uri: URI): String? = when {
            uri.host == "api.mojang.com" -> "profile_by_name"
            uri.host == "api.wynncraft.com" && uri.path.startsWith("/v3/player/") -> "player"
            uri.host == "api.wynncraft.com" && uri.path.startsWith("/v3/guild/") -> "guild"
            else -> null
        }
    }
}

/**
 * Looks up a player's guild and its member names from the Wynncraft public API, so guild membership
 * can never be forged by a client. Lookups and their failures are cached for ten minutes.
 */
class GuildResolver(private val api: ApiFetcher, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Lookup { HIT, MISS, NO_GUILD }

    private class Cached(val expiresAt: Long, val members: CompletableFuture<Set<String>>)

    private val byPlayer = ConcurrentHashMap<UUID, Cached>()
    private val byGuild = ConcurrentHashMap<UUID, Cached>()

    fun membersOf(player: UUID): CompletableFuture<Set<String>> {
        val fresh = byPlayer[player]?.let { it.expiresAt > clock() } == true
        lookups.getValue(if (fresh) Lookup.HIT else Lookup.MISS).increment()
        return cached(byPlayer, player) {
            fetch("/v3/player/$player").thenCompose { body ->
                when (val guild = body?.let(::parseGuildUuid)) {
                    null -> CompletableFuture.completedFuture(emptySet<String>()).also { lookups.getValue(Lookup.NO_GUILD).increment() }
                    else -> cached(byGuild, guild) { fetch("/v3/guild/uuid/$guild").thenApply { it?.let(::parseMembers) ?: emptySet() } }
                }
            }
        }
    }

    private fun fetch(path: String) = api.get(URI(BASE_URL + path))

    private fun cached(cache: ConcurrentHashMap<UUID, Cached>, key: UUID, load: () -> CompletableFuture<Set<String>>): CompletableFuture<Set<String>> {
        val now = clock()
        cache.values.removeIf { it.expiresAt <= now && it.members.isDone }
        return cache.compute(key) { _, entry ->
            if (entry != null && entry.expiresAt > now) entry
            else Cached(now + CACHE_MS, load().exceptionally { error ->
                log.warn("Guild lookup for {} failed: {}", key, error.message)
                emptySet()
            })
        }!!.members
    }

    companion object {
        private val log = LoggerFactory.getLogger(GuildResolver::class.java)
        private val lookups = Metrics.registry.counters<Lookup>("voice_guild_lookups_total", "result")
        const val CACHE_MS = 10 * 60_000L
        private const val BASE_URL = "https://api.wynncraft.com"
        fun parseGuildUuid(playerJson: String): UUID? =
            JsonParser.parseString(playerJson).asJsonObject["guild"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("uuid")?.asString?.let(UUID::fromString)

        /** `members` is keyed by rank, each rank by player name. */
        fun parseMembers(guildJson: String): Set<String> =
            JsonParser.parseString(guildJson).asJsonObject.getAsJsonObject("members").entrySet()
                .filter { it.value.isJsonObject }
                .flatMapTo(HashSet()) { it.value.asJsonObject.keySet() }
    }
}
