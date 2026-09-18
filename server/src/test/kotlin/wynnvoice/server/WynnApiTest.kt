package wynnvoice.server

import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WynnApiTest {
    private fun sample(series: String) = MetricsTest.sample(Metrics.scrape(), series)!!.toDouble()

    private val player = UUID.fromString("1ed075fc-5aa9-42e0-a29f-640326c1d80c")
    private val requests = ArrayList<String>()
    private var responses = HashMap<String, () -> String?>()
    private var now = 0L
    private val resolver = WynnApi({ uri ->
        requests.add(uri.toString())
        runCatching { CompletableFuture.completedFuture(responses.getValue(uri.path)()) }.getOrElse { CompletableFuture.failedFuture(it) }
    }) { now }

    private fun playerJson(guild: String, online: Boolean = false, server: String? = null, restricted: Boolean = false) =
        """{"username":"Salted","online":$online,"server":${server?.let { "\"$it\"" }},"uuid":"$player","rank":"Administrator","guild":$guild,"guildHistory":[{"uuid":"deadbeef-0000-4000-8000-000000000000","name":"Old"}],"restrictions":{"mainAccess":false,"onlineStatus":$restricted,"guildHistoryAccess":false},"globalData":{"wars":6}}"""
    private val inGuild = playerJson("""{"uuid":"18d19092-684b-427b-aa58-574230befe79","name":"Wynncraft","prefix":"WYNN","rank":"OWNER","rankStars":"★★★★★"}""")
    private val guildJson = """{"uuid":"18d19092-684b-427b-aa58-574230befe79","name":"Wynncraft","prefix":"WYNN","level":25,"members":{"total":3,"owner":{"Salted":{"uuid":"1ed075fc-5aa9-42e0-a29f-640326c1d80c","legacyName":"Salted","online":false,"server":null,"globalData":{"guildRaids":{"total":0,"list":{"Orphion's Nexus of Light":0}}}}},"chief":{"Eilaa":{"uuid":"fab8829b-6058-462b-b326-48a25f8db4a5","online":false}},"strategist":{},"captain":{},"recruiter":{},"recruit":{"Grian":{"uuid":"5f8eb3f2-5f3a-4b4b-9d8a-3c6f1e2d4a5b","online":true,"server":"WC1"}}},"online":1,"banner":{"layers":[{"colour":"WHITE","pattern":"STRIPE_TOP"}]},"seasonRanks":{"1":{"rating":100,"finalTerritories":0}}}"""

    private fun serve(vararg entries: Pair<String, () -> String?>) {
        responses = hashMapOf(*entries)
    }

    private fun membersOf(player: UUID): Set<String> = resolver.guildOf(player).join()?.members?.keys ?: emptySet()

    @Test
    fun `parses guild uuid from the player and member names from the guild`() {
        assertEquals(UUID.fromString("18d19092-684b-427b-aa58-574230befe79"), WynnApi.parsePlayer(inGuild).guild)
        assertNull(WynnApi.parsePlayer(playerJson("null")).guild)
        assertEquals(mapOf("Salted" to "owner", "Eilaa" to "chief", "Grian" to "recruit"), WynnApi.parseMembers(guildJson))
    }

    @Test
    fun `parses online status, server and the online status restriction`() {
        val online = WynnApi.parsePlayer(playerJson("null", online = true, server = "WC12", restricted = true))
        assertEquals(true, online.online)
        assertEquals("WC12", online.server)
        assertTrue(online.restricted)
        val offline = WynnApi.parsePlayer(playerJson("null"))
        assertEquals(false, offline.online)
        assertNull(offline.server)
        assertFalse(offline.restricted)
        val bare = WynnApi.parsePlayer("""{"username":"Salted"}""")
        assertNull(bare.online)
        assertNull(bare.server)
        assertFalse(bare.restricted)
    }

    private fun worldCheck(json: () -> String?): WynnApi.WorldCheck {
        val uuid = UUID.randomUUID()
        serve("/v3/player/$uuid" to json)
        return resolver.worldCheck(uuid, "WC12").join()
    }

    @Test
    fun `world check refuses only a clear contradiction`() {
        val before = WynnApi.WorldCheck.entries.associateWith { sample("voice_world_checks_total{result=\"${it.name.lowercase()}\"}") }
        assertEquals(WynnApi.WorldCheck.OK, worldCheck { playerJson("null", online = true, server = "WC12") })
        assertEquals(WynnApi.WorldCheck.MISMATCH, worldCheck { playerJson("null", online = true, server = "WC3") })
        assertEquals(WynnApi.WorldCheck.OFFLINE, worldCheck { playerJson("null") })
        assertEquals(WynnApi.WorldCheck.RESTRICTED, worldCheck { playerJson("null", online = false, server = null, restricted = true) })
        assertEquals(WynnApi.WorldCheck.UNKNOWN, worldCheck { playerJson("null", online = true, server = null) })
        assertEquals(WynnApi.WorldCheck.UNKNOWN, worldCheck { null })
        assertEquals(WynnApi.WorldCheck.UNKNOWN, worldCheck { throw IllegalStateException("api down") })
        assertEquals(WynnApi.WorldCheck.UNKNOWN, worldCheck { "not json" })
        assertEquals(listOf(false, true, true, false, false), WynnApi.WorldCheck.entries.map { it.refuses })
        assertEquals(mapOf(WynnApi.WorldCheck.OK to 1.0, WynnApi.WorldCheck.MISMATCH to 1.0, WynnApi.WorldCheck.OFFLINE to 1.0, WynnApi.WorldCheck.RESTRICTED to 1.0, WynnApi.WorldCheck.UNKNOWN to 4.0),
            before.mapValues { (result, n) -> sample("voice_world_checks_total{result=\"${result.name.lowercase()}\"}") - n })
    }

    @Test
    fun `world check and guild lookup share one cached player response`() {
        serve("/v3/player/$player" to { playerJson("""{"uuid":"18d19092-684b-427b-aa58-574230befe79"}""", online = true, server = "WC12") }, "/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79" to { guildJson })
        assertEquals(WynnApi.WorldCheck.OK, resolver.worldCheck(player, "WC12").join())
        assertEquals(setOf("Salted", "Eilaa", "Grian"), membersOf(player))
        assertEquals(WynnApi.WorldCheck.MISMATCH, resolver.worldCheck(player, "WC1").join())
        assertEquals(2, requests.size, "one player request serves both checks")
        now += WynnApi.CACHE_MS
        resolver.worldCheck(player, "WC12").join()
        assertEquals(3, requests.size)
    }

    @Test
    fun `resolves members through both endpoints and caches per guild`() {
        val hits = sample("voice_guild_lookups_total{result=\"hit\"}")
        val misses = sample("voice_guild_lookups_total{result=\"miss\"}")
        serve("/v3/player/$player" to { inGuild }, "/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79" to { guildJson })
        val guild = resolver.guildOf(player).join()!!
        assertEquals(UUID.fromString("18d19092-684b-427b-aa58-574230befe79"), guild.uuid)
        assertEquals("WYNN", guild.prefix)
        assertEquals(setOf("Salted", "Eilaa", "Grian"), guild.members.keys)
        assertEquals(listOf(
            "https://api.wynncraft.com/v3/player/$player",
            "https://api.wynncraft.com/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79",
        ), requests)

        val mate = UUID.randomUUID()
        serve("/v3/player/$mate" to { inGuild })
        assertEquals(setOf("Salted", "Eilaa", "Grian"), membersOf(mate))
        assertEquals(setOf("Salted", "Eilaa", "Grian"), membersOf(player))
        assertEquals(3, requests.size, "guild members and the player's guild are cached")
        assertEquals(hits + 1, sample("voice_guild_lookups_total{result=\"hit\"}"))
        assertEquals(misses + 2, sample("voice_guild_lookups_total{result=\"miss\"}"))

        now += WynnApi.CACHE_MS
        serve("/v3/player/$player" to { inGuild }, "/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79" to { guildJson })
        membersOf(player)
        assertEquals(5, requests.size, "both lookups expire after ten minutes")
    }

    @Test
    fun `no guild, unknown player or api failure is empty until the cache expires`() {
        serve("/v3/player/$player" to { playerJson("null") })
        assertEquals(emptySet(), membersOf(player))
        val unknown = UUID.randomUUID()
        serve("/v3/player/$unknown" to { null })
        assertEquals(emptySet(), membersOf(unknown))
        val unlucky = UUID.randomUUID()
        serve("/v3/player/$unlucky" to { throw IllegalStateException("api down") })
        assertEquals(emptySet(), membersOf(unlucky))
        assertEquals(3, requests.size)

        serve("/v3/player/$unlucky" to { inGuild }, "/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79" to { throw IllegalStateException("api down") })
        assertEquals(emptySet(), membersOf(unlucky), "failure is cached, not retried immediately")
        assertEquals(3, requests.size)

        now += WynnApi.CACHE_MS
        assertEquals(emptySet(), membersOf(unlucky))
        assertEquals(5, requests.size)
        now += WynnApi.CACHE_MS
        serve("/v3/player/$unlucky" to { inGuild }, "/v3/guild/uuid/18d19092-684b-427b-aa58-574230befe79" to { guildJson })
        assertEquals(setOf("Salted", "Eilaa", "Grian"), membersOf(unlucky))
    }
}
