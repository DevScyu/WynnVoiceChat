package wynnvoice.server

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

fun interface ApiFetcher {
    /** Response body, or null when the resource does not exist. */
    fun get(uri: URI): CompletableFuture<String?>
}

class HttpApiFetcher(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : ApiFetcher {
    override fun get(uri: URI): CompletableFuture<String?> {
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
            when (response.statusCode()) {
                200 -> response.body()
                404 -> null
                else -> throw IOException("${uri.path} returned HTTP ${response.statusCode()}")
            }
        }
    }
}

/**
 * Looks up a player's guild and its member names from the Wynncraft public API, so guild membership
 * can never be forged by a client. Lookups and their failures are cached for ten minutes.
 */
class GuildResolver(private val api: ApiFetcher, private val clock: () -> Long = System::currentTimeMillis) {
    private class Cached(val expiresAt: Long, val members: CompletableFuture<Set<String>>)

    private val byPlayer = ConcurrentHashMap<UUID, Cached>()
    private val byGuild = ConcurrentHashMap<UUID, Cached>()

    fun membersOf(player: UUID): CompletableFuture<Set<String>> = cached(byPlayer, player) {
        fetch("/v3/player/$player").thenCompose { body ->
            when (val guild = body?.let(::parseGuildUuid)) {
                null -> CompletableFuture.completedFuture(emptySet())
                else -> cached(byGuild, guild) { fetch("/v3/guild/uuid/$guild").thenApply { it?.let(::parseMembers) ?: emptySet() } }
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
        const val CACHE_MS = 10 * 60_000L
        private const val BASE_URL = "https://api.wynncraft.com"
        // ponytail: two regexes over the API's compact JSON; swap for a JSON library once the Discord ticket needs one
        private val GUILD_UUID = Regex("\"guild\"\\s*:\\s*\\{[^{}]*?\"uuid\"\\s*:\\s*\"([0-9a-f-]{36})\"")
        private val MEMBER = Regex("\"(\\w{1,16})\"\\s*:\\s*\\{\\s*\"uuid\"\\s*:")

        fun parseGuildUuid(playerJson: String): UUID? = GUILD_UUID.find(playerJson)?.groupValues?.get(1)?.let(UUID::fromString)

        fun parseMembers(guildJson: String): Set<String> = MEMBER.findAll(guildJson).map { it.groupValues[1] }.toSet()
    }
}
