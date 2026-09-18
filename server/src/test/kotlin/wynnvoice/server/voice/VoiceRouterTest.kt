package wynnvoice.server.voice

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import wynnvoice.protocol.Packet.Position
import wynnvoice.protocol.Relation
import wynnvoice.protocol.VoiceTier

class VoiceRouterTest {
    private val blocked = HashSet<Pair<UUID, UUID>>()
    private val router = VoiceRouter(range = 32.0) { a, b -> (a to b) in blocked || (b to a) in blocked }

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
    ) = VoiceParticipant(UUID.nameUUIDFromBytes(name.toByteArray()), name, world, instance, position, tier, disabled, party, friends, guild)

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
    fun `relation prefers party then friend then guild`() {
        val me = participant("Me", party = setOf("Pal"), friends = setOf("Pal", "Friend"), guild = setOf("Mate"))
        assertEquals(Relation.PARTY, router.relation(me, participant("Pal", party = setOf("Me"), friends = setOf("Me"))))
        assertEquals(Relation.FRIEND, router.relation(me, participant("Friend", friends = setOf("Me"))))
        assertEquals(Relation.GUILD, router.relation(me, participant("Mate", guild = setOf("Me"))))
        assertEquals(Relation.NONE, router.relation(me, participant("Stranger", friends = setOf("Me"))))
    }
}
