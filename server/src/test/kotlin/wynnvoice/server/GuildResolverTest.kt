package wynnvoice.server

import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuildResolverTest {
    private fun sample(series: String) = MetricsTest.sample(Metrics.scrape(), series)!!.toDouble()

    private val player = UUID.fromString("1ed075fc-5aa9-42e0-a29f-640326c1d80c")
    private val requests = ArrayList<String>()
    private var responses = HashMap<String, () -> String?>()
    private var now = 0L
    private val resolver = GuildResolver({ uri ->
        requests.add(uri.toString())
        runCatching { CompletableFuture.completedFuture(responses.getValue(uri.path)()) }.getOrElse { CompletableFuture.failedFuture(it) }
    }) { now }

    private fun playerJson(guild: String) = """{"username":"Salted","online":false,"server":null,"uuid":"$player","rank":"Administrator","guild":$guild,"guildHistory":[{"uuid":"deadbeef-0000-4000-8000-000000000000","name":"Old"}],"globalData":{"wars":6}}"""
    private val inGuild = playerJson("""{"uuid":"18d19092-684b-427b-aa58-574230befe79","name":"Wynncraft","prefix":"WYNN","rank":"OWNER","rankStars":"★★★★★"}""")
    private val guildJson = """{"uuid":"18d19092-684b-427b-aa58-574230befe79","name":"Wynncraft","prefix":"WYNN","level":25,"members":{"total":3,"owner":{"Salted":{"uuid":"1ed075fc-5aa9-42e0-a29f-640326c1d80c","legacyName":"Salted","online":false,"server":null,"globalData":{"guildRaids":{"total":0,"list":{"Orphion's Nexus of Light":0}}}}},"chief":{"Eilaa":{"uuid":"fab8829b-6058-462b-b326-48a25f8db4a5","online":false}},"strategist":{},"captain":{},"recruiter":{},"recruit":{"Grian":{"uuid":"5f8eb3f2-5f3a-4b4b-9d8a-3c6f1e2d4a5b","online":true,"server":"WC1"}}},"online":1,"banner":{"layers":[{"colour":"WHITE","pattern":"STRIPE_TOP"}]},"seasonRanks":{"1":{"rating":100,"finalTerritories":0}}}"""

    private fun serve(vararg entries: Pair<String, () -> String?>) {
        responses = hashMapOf(*entries)
    }

    @Test
    fun `parses guild uuid from the player and member names from the guild`() {
        assertEquals(UUID.fromString("18d19092-684b-427b-aa58-574230befe79"), GuildResolver.parseGuildUuid(inGuild))
        assertNull(GuildResolver.parseGuildUuid(playerJson("null")))
        assertEquals(setOf("Salted", "Eilaa", "Grian"), GuildResolver.parseMembers(guildJson))
    }

    @Test
    fun `resolves members through both endpoints and caches per guild`() {
        val hits = sample("voice_guild_lookups_total{result=\"hit\"}")
        val misses = sample("voice_guild_lookups_total{result=\"miss\"}")
        serve("/v3/player/$player" to { inGuild }, "/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79" to { guildJson })
        assertEquals(setOf("Salted", "Eilaa", "Grian"), resolver.membersOf(player).join())
        assertEquals(listOf(
            "https://api.wynncraft.com/v3/player/$player",
            "https://api.wynncraft.com/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79",
        ), requests)

        val mate = UUID.randomUUID()
        serve("/v3/player/$mate" to { inGuild })
        assertEquals(setOf("Salted", "Eilaa", "Grian"), resolver.membersOf(mate).join())
        assertEquals(setOf("Salted", "Eilaa", "Grian"), resolver.membersOf(player).join())
        assertEquals(3, requests.size, "guild members and the player's guild are cached")
        assertEquals(hits + 1, sample("voice_guild_lookups_total{result=\"hit\"}"))
        assertEquals(misses + 2, sample("voice_guild_lookups_total{result=\"miss\"}"))

        now += GuildResolver.CACHE_MS
        serve("/v3/player/$player" to { inGuild }, "/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79" to { guildJson })
        resolver.membersOf(player).join()
        assertEquals(5, requests.size, "both lookups expire after ten minutes")
    }

    @Test
    fun `no guild, unknown player or api failure is empty until the cache expires`() {
        serve("/v3/player/$player" to { playerJson("null") })
        assertEquals(emptySet(), resolver.membersOf(player).join())
        val unknown = UUID.randomUUID()
        serve("/v3/player/$unknown" to { null })
        assertEquals(emptySet(), resolver.membersOf(unknown).join())
        val unlucky = UUID.randomUUID()
        serve("/v3/player/$unlucky" to { throw IllegalStateException("api down") })
        assertEquals(emptySet(), resolver.membersOf(unlucky).join())
        assertEquals(3, requests.size)

        serve("/v3/player/$unlucky" to { inGuild }, "/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79" to { throw IllegalStateException("api down") })
        assertEquals(emptySet(), resolver.membersOf(unlucky).join(), "failure is cached, not retried immediately")
        assertEquals(3, requests.size)

        now += GuildResolver.CACHE_MS
        assertEquals(emptySet(), resolver.membersOf(unlucky).join())
        assertEquals(5, requests.size)
        now += GuildResolver.CACHE_MS
        serve("/v3/player/$unlucky" to { inGuild }, "/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79" to { guildJson })
        assertEquals(setOf("Salted", "Eilaa", "Grian"), resolver.membersOf(unlucky).join())
    }
}
