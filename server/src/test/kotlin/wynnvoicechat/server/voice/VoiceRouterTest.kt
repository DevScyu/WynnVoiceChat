package wynnvoicechat.server.voice

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wynnvoicechat.protocol.Packet.Position
import wynnvoicechat.protocol.Relation
import wynnvoicechat.protocol.VoiceTier

class VoiceRouterTest {
    private val blocked = HashSet<Pair<UUID, UUID>>()
    private val muted = HashSet<Pair<UUID, UUID>>()
    private val router = VoiceRouter(range = 32.0, { a, b -> (a to b) in blocked || (b to a) in blocked }) { guild, member -> (guild to member) in muted }
    private val guildId = UUID.fromString("18d19092-684b-427b-aa58-574230befe79")

    private fun participant(
        name: String,
        tier: VoiceTier = VoiceTier.EVERYONE,
        world: String? = "WC1",
        instance: String = "",
        position: Position? = Position(0f, 100f, 0f),
        party: Set<String> = emptySet(),
        friends: Set<String> = emptySet(),
        guild: Set<String> = emptySet(),
        disabled: Boolean = false,
        guildChannel: Boolean = false,
        callPeer: String? = null,
    ) = VoiceParticipant(UUID.nameUUIDFromBytes(name.toByteArray()), name, world, instance, position, tier, disabled, party, friends, guild, guildId.takeIf { guild.isNotEmpty() }, guildChannel, callPeer?.let { UUID.nameUUIDFromBytes(it.toByteArray()) })

    private fun guildmate(name: String, guildChannel: Boolean, tier: VoiceTier = VoiceTier.PARTY, world: String? = "WC7", position: Position? = Position(9999f, 0f, 9999f)) =
        participant(name, tier = tier, world = world, position = position, guild = setOf("Me", "Mate", "Other"), guildChannel = guildChannel)

    private fun names(list: List<VoiceParticipant>) = list.map { it.name }

    @Test
    fun `mutual party goes to group only`() {
        val me = participant("Me", tier = VoiceTier.PARTY, party = setOf("Pal"))
        val pal = participant("Pal", tier = VoiceTier.PARTY, party = setOf("Me"), world = "WC7", position = Position(9999f, 0f, 9999f))
        val r = router.route(me, listOf(me, pal), whispering = false)
        assertEquals(listOf("Pal"), names(r.group))
        assertTrue(r.proximity.isEmpty())
    }

    @Test
    fun `one sided party is not party`() {
        val me = participant("Me", tier = VoiceTier.PARTY, party = setOf("Pal"))
        val r = router.route(me, listOf(participant("Pal", tier = VoiceTier.PARTY)), whispering = false)
        assertTrue(r.group.isEmpty())
        assertTrue(r.proximity.isEmpty())
    }

    @Test
    fun `everyone tier hears strangers in range`() {
        val me = participant("Me")
        val near = participant("Near", position = Position(30f, 100f, 0f))
        val far = participant("Far", position = Position(33f, 100f, 0f))
        assertEquals(listOf("Near"), names(router.route(me, listOf(near, far), whispering = false).proximity))
    }

    @Test
    fun `whisper halves range`() {
        val me = participant("Me")
        val near = participant("Near", position = Position(16f, 100f, 0f))
        val far = participant("Far", position = Position(17f, 100f, 0f))
        assertEquals(listOf("Near"), names(router.route(me, listOf(near, far), whispering = true).proximity))
    }

    @Test
    fun `admission must be mutual`() {
        val open = participant("Open", tier = VoiceTier.EVERYONE)
        val closed = participant("Closed", tier = VoiceTier.PARTY)
        assertTrue(router.route(open, listOf(closed), whispering = false).proximity.isEmpty())
        assertTrue(router.route(closed, listOf(open), whispering = false).proximity.isEmpty())
    }

    @Test
    fun `friends and guild tier admits mutual friends or guild`() {
        val me = participant("Me", tier = VoiceTier.FRIENDS_AND_GUILD, friends = setOf("Friend"), guild = setOf("Mate"))
        val friend = participant("Friend", tier = VoiceTier.FRIENDS_AND_GUILD, friends = setOf("Me"))
        val mate = participant("Mate", tier = VoiceTier.FRIENDS_AND_GUILD, guild = setOf("Me"))
        val stranger = participant("Stranger", tier = VoiceTier.EVERYONE)
        val oneSided = participant("OneSided", tier = VoiceTier.EVERYONE, friends = setOf("Me"))
        val r = router.route(me, listOf(friend, mate, stranger, oneSided), whispering = false)
        assertEquals(setOf("Friend", "Mate"), names(r.proximity).toSet())
    }

    @Test
    fun `different world or instance is silent`() {
        val me = participant("Me", instance = "housing:Me")
        val sameWorldOtherHouse = participant("Other", instance = "housing:Other")
        val otherWorld = participant("Away", world = "WC2", instance = "housing:Me")
        val noWorld = participant("Limbo", world = null, instance = "housing:Me")
        assertTrue(router.route(me, listOf(sameWorldOtherHouse, otherWorld, noWorld), whispering = false).proximity.isEmpty())
    }

    @Test
    fun `unknown positions are silent`() {
        val me = participant("Me")
        assertTrue(router.route(me, listOf(participant("Unknown", position = null)), whispering = false).proximity.isEmpty())
        assertTrue(router.route(participant("Me", position = null), listOf(participant("Near")), whispering = false).proximity.isEmpty())
    }

    @Test
    fun `blocked pairs are excluded everywhere`() {
        val me = participant("Me", party = setOf("Pal"))
        val pal = participant("Pal", party = setOf("Me"))
        val near = participant("Near")
        blocked.add(pal.uuid to me.uuid)
        blocked.add(me.uuid to near.uuid)
        val r = router.route(me, listOf(pal, near), whispering = false)
        assertTrue(r.group.isEmpty())
        assertTrue(r.proximity.isEmpty())
        assertFalse(router.canTalk(me, near))
    }

    @Test
    fun `speaker is never a recipient and disabled is not filtered here`() {
        val me = participant("Me")
        val r = router.route(me, listOf(me, participant("Quiet", disabled = true)), whispering = false)
        assertEquals(listOf("Quiet"), names(r.proximity))
    }

    @Test
    fun `guild channel is group audio anywhere only when both opted in`() {
        val me = guildmate("Me", guildChannel = true)
        val on = guildmate("Mate", guildChannel = true)
        val off = guildmate("Other", guildChannel = false)
        val r = router.route(me, listOf(on, off), whispering = false)
        assertEquals(listOf("Mate"), names(r.guild))
        assertTrue(r.group.isEmpty())
        assertTrue(r.proximity.isEmpty())
        assertEquals(1, r.outcomes[RouteOutcome.GUILD.ordinal])
        assertEquals(1, r.outcomes[RouteOutcome.TIER_DENIED.ordinal])
        assertTrue(router.canTalk(me, on))
        assertFalse(router.canTalk(me, off))
        assertTrue(router.route(guildmate("Me", guildChannel = false), listOf(on), whispering = false).guild.isEmpty())
    }

    @Test
    fun `party wins over guild channel and blocks win over both`() {
        val me = guildmate("Me", guildChannel = true).copy(party = setOf("Mate"))
        val mate = guildmate("Mate", guildChannel = true).copy(party = setOf("Me"))
        val r = router.route(me, listOf(mate), whispering = false)
        assertEquals(listOf("Mate"), names(r.group))
        assertTrue(r.guild.isEmpty())

        blocked.add(me.uuid to mate.uuid)
        val b = router.route(me, listOf(mate), whispering = false)
        assertTrue(b.group.isEmpty())
        assertTrue(b.guild.isEmpty())
        assertTrue(router.route(me, listOf(guildmate("Other", guildChannel = true).also { blocked.add(it.uuid to me.uuid) }), whispering = false).guild.isEmpty())
    }

    @Test
    fun `a muted speaker hears the guild channel but is only heard nearby`() {
        val me = guildmate("Me", guildChannel = true, tier = VoiceTier.EVERYONE, world = "WC1", position = Position(0f, 100f, 0f))
        val far = guildmate("Mate", guildChannel = true)
        val near = guildmate("Other", guildChannel = true, tier = VoiceTier.EVERYONE, world = "WC1", position = Position(10f, 100f, 0f))
        muted.add(guildId to me.uuid)

        val r = router.route(me, listOf(far, near), whispering = false)
        assertTrue(r.guild.isEmpty())
        assertEquals(listOf("Other"), names(r.proximity))
        assertFalse(router.canTalk(me, far))
        assertEquals(listOf("Me"), names(router.route(far, listOf(me), whispering = false).guild), "the muted member still hears the channel")

        muted.clear()
        assertEquals(setOf("Mate", "Other"), names(router.route(me, listOf(far, near), whispering = false).guild).toSet())
    }

    @Test
    fun `a call is group audio anywhere for both sides only, party first, blocks win`() {
        val me = participant("Me", tier = VoiceTier.PARTY, callPeer = "Pal")
        val pal = participant("Pal", tier = VoiceTier.PARTY, world = "WC7", position = Position(9999f, 0f, 9999f), callPeer = "Me")
        val oneSided = participant("Other", tier = VoiceTier.PARTY, callPeer = "Me")
        val r = router.route(me, listOf(pal, oneSided), whispering = false)
        assertEquals(listOf("Pal"), names(r.call))
        assertTrue(r.group.isEmpty() && r.guild.isEmpty() && r.proximity.isEmpty())
        assertEquals(1, r.outcomes[RouteOutcome.CALL.ordinal])
        assertEquals(1, r.outcomes[RouteOutcome.TIER_DENIED.ordinal])
        assertTrue(router.canTalk(me, pal))
        assertFalse(router.canTalk(me, oneSided))
        assertEquals(listOf("Me"), names(router.route(pal, listOf(me), whispering = false).call))

        val partied = router.route(me.copy(party = setOf("Pal")), listOf(pal.copy(party = setOf("Me"))), whispering = false)
        assertEquals(listOf("Pal"), names(partied.group))
        assertTrue(partied.call.isEmpty())

        blocked.add(pal.uuid to me.uuid)
        assertTrue(router.route(me, listOf(pal), whispering = false).call.isEmpty())
    }

    @Test
    fun `relation prefers party then friend then guild`() {
        val me = participant("Me", party = setOf("Pal"), friends = setOf("Pal", "Friend"), guild = setOf("Mate"))
        assertEquals(Relation.PARTY, router.relation(me, participant("Pal", party = setOf("Me"), friends = setOf("Me"))))
        assertEquals(Relation.FRIEND, router.relation(me, participant("Friend", friends = setOf("Me"))))
        assertEquals(Relation.GUILD, router.relation(me, participant("Mate", guild = setOf("Me"))))
        assertEquals(Relation.NONE, router.relation(me, participant("Stranger", friends = setOf("Me"))))
    }
}
