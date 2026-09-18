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
        val timers = if (endpoint.startsWith("profile_by_")) MOJANG else WYNN
        timers.getValue(endpoint).getValue(Metrics.httpOutcome(response?.statusCode() ?: 0)).record(nanos, TimeUnit.NANOSECONDS)
        val remaining = response?.headers()?.firstValue("RateLimit-Remaining")?.orElse(null)?.toDoubleOrNull() ?: return
        WYNN_RATELIMIT_REMAINING[endpoint]?.set(remaining)
    }

    companion object {
        private val MOJANG = Metrics.httpTimers("voice_mojang_request_seconds", "has_joined", "profile_by_name", "profile_by_uuid")
        private val WYNN = Metrics.httpTimers("voice_wynn_api_request_seconds", "player", "guild")
        /** Set from the `RateLimit-Remaining` header; the bucket is named after the endpoint. */
        private val WYNN_RATELIMIT_REMAINING = listOf("player", "guild").associateWith { endpoint ->
            AtomicReference(Double.NaN).also { Gauge.builder("voice_wynn_api_ratelimit_remaining", it) { r -> r.get() }.tag("bucket", endpoint.uppercase()).register(Metrics.registry) }
        }

        fun endpointOf(uri: URI): String? = when {
            uri.host == "api.mojang.com" -> "profile_by_name"
            uri.host == "sessionserver.mojang.com" && uri.path.startsWith("/session/minecraft/profile/") -> "profile_by_uuid"
            uri.host == "api.wynncraft.com" && uri.path.startsWith("/v3/player/") -> "player"
            uri.host == "api.wynncraft.com" && uri.path.startsWith("/v3/guild/") -> "guild"
            else -> null
        }
    }
}

/**
 * Player and guild lookups on the Wynncraft public API: guild membership can never be forged by a client,
 * and a claimed world is spot-checked against the player's live status. Lookups and their failures are
 * cached for ten minutes; the player response is shared by both checks.
 */
class WynnApi(private val api: ApiFetcher, private val clock: () -> Long = System::currentTimeMillis) {
    enum class Lookup { HIT, MISS, NO_GUILD }
    enum class WorldCheck {
        OK, MISMATCH, OFFLINE, RESTRICTED, UNKNOWN;
        val refuses get() = this == MISMATCH || this == OFFLINE
    }
    class PlayerInfo(val guild: UUID?, val online: Boolean?, val server: String?, val restricted: Boolean)
    /** [members] maps player name to rank (`owner`, `chief`, `strategist`, `captain`, `recruiter`, `recruit`). */
    class Guild(val uuid: UUID, val members: Map<String, String>)

    private class Cached<T>(val expiresAt: Long, val value: CompletableFuture<T>)

    private val byPlayer = ConcurrentHashMap<UUID, Cached<PlayerInfo?>>()
    private val byGuild = ConcurrentHashMap<UUID, Cached<Guild?>>()

    /** The player's guild with every member's rank, or null when they have none or the API failed. */
    fun guildOf(player: UUID): CompletableFuture<Guild?> {
        val fresh = byPlayer[player]?.let { it.expiresAt > clock() } == true
        lookups.getValue(if (fresh) Lookup.HIT else Lookup.MISS).increment()
        return playerOf(player).thenCompose { info ->
            when (val guild = info?.guild) {
                null -> CompletableFuture.completedFuture(null as Guild?).also { if (!fresh) lookups.getValue(Lookup.NO_GUILD).increment() }
                else -> cached(byGuild, guild, null) { fetch("/v3/guild/uuid/$guild").thenApply { json -> json?.let { Guild(guild, parseMembers(it)) } } }
            }
        }
    }

    /** Whether Wynncraft agrees the player is on [world]; only a clear contradiction refuses. */
    fun worldCheck(player: UUID, world: String): CompletableFuture<WorldCheck> = playerOf(player).thenApply { info ->
        when {
            info == null -> WorldCheck.UNKNOWN
            info.restricted -> WorldCheck.RESTRICTED
            info.online == false -> WorldCheck.OFFLINE
            info.server == null -> WorldCheck.UNKNOWN
            info.server != world -> WorldCheck.MISMATCH
            else -> WorldCheck.OK
        }.also { worldChecks.getValue(it).increment() }
    }

    private fun playerOf(player: UUID) = cached(byPlayer, player, null) { fetch("/v3/player/$player").thenApply { it?.let(::parsePlayer) } }

    private fun fetch(path: String) = api.get(URI(BASE_URL + path))

    private fun <T> cached(cache: ConcurrentHashMap<UUID, Cached<T>>, key: UUID, fallback: T, load: () -> CompletableFuture<T>): CompletableFuture<T> {
        val now = clock()
        cache.values.removeIf { it.expiresAt <= now && it.value.isDone }
        return cache.compute(key) { _, entry ->
            if (entry != null && entry.expiresAt > now) entry
            else Cached(now + CACHE_MS, load().exceptionally { error ->
                log.warn("Wynncraft lookup for {} failed: {}", key, error.message)
                fallback
            })
        }!!.value
    }

    companion object {
        private val log = LoggerFactory.getLogger(WynnApi::class.java)
        private val lookups = Metrics.registry.counters<Lookup>("voice_guild_lookups_total", "result")
        private val worldChecks = Metrics.registry.counters<WorldCheck>("voice_world_checks_total", "result")
        const val CACHE_MS = 10 * 60_000L
        private const val BASE_URL = "https://api.wynncraft.com"

        fun parsePlayer(playerJson: String): PlayerInfo {
            val player = JsonParser.parseString(playerJson).asJsonObject
            return PlayerInfo(
                guild = player["guild"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("uuid")?.asString?.let(UUID::fromString),
                online = player["online"]?.takeIf { it.isJsonPrimitive }?.asBoolean,
                server = player["server"]?.takeIf { it.isJsonPrimitive }?.asString,
                restricted = player["restrictions"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("onlineStatus")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
            )
        }

        /** `members` is keyed by rank, each rank by player name. */
        fun parseMembers(guildJson: String): Map<String, String> =
            JsonParser.parseString(guildJson).asJsonObject.getAsJsonObject("members").entrySet()
                .filter { it.value.isJsonObject }
                .flatMap { (rank, names) -> names.asJsonObject.keySet().map { it to rank } }
                .toMap()
    }
}
