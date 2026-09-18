package wynnvoice.server.voice

import java.util.UUID
import wynnvoice.protocol.Packet.Position
import wynnvoice.protocol.Relation
import wynnvoice.protocol.VoiceTier

/**
 * Snapshot of everything routing needs to know about one voice user.
 */
data class VoiceParticipant(
    val uuid: UUID,
    val name: String,
    val world: String?,
    val instance: String,
    val position: Position?,
    val tier: VoiceTier,
    val disabled: Boolean,
    val party: Set<String>,
    val friends: Set<String>,
    val guildMembers: Set<String>,
)

/** Why a candidate did or did not get the speaker's audio, in the order the checks run. */
enum class RouteOutcome { SELF, BLOCKED, GROUP, TIER_DENIED, WORLD_MISMATCH, INSTANCE_MISMATCH, POSITION_UNKNOWN, OUT_OF_RANGE, PROXIMITY }

/** [outcomes] is indexed by [RouteOutcome.ordinal]: how many candidates ended up in each bucket. */
class VoiceRecipients(val group: List<VoiceParticipant>, val proximity: List<VoiceParticipant>, val outcomes: IntArray)

/**
 * Decides who hears a speaker. Pure: no I/O, no session state.
 */
class VoiceRouter(private val range: Double, private val isBlocked: (UUID, UUID) -> Boolean) {

    fun route(speaker: VoiceParticipant, candidates: Collection<VoiceParticipant>, whispering: Boolean): VoiceRecipients {
        val distance = if (whispering) range / 2 else range
        val group = ArrayList<VoiceParticipant>()
        val proximity = ArrayList<VoiceParticipant>()
        val outcomes = IntArray(RouteOutcome.entries.size)

        for (candidate in candidates) {
            val outcome = outcomeOf(speaker, candidate, distance)
            outcomes[outcome.ordinal]++
            when (outcome) {
                RouteOutcome.GROUP -> group.add(candidate)
                RouteOutcome.PROXIMITY -> proximity.add(candidate)
                else -> Unit
            }
        }
        return VoiceRecipients(group, proximity, outcomes)
    }

    private fun outcomeOf(speaker: VoiceParticipant, candidate: VoiceParticipant, distance: Double): RouteOutcome = when {
        candidate.uuid == speaker.uuid -> RouteOutcome.SELF
        isBlocked(speaker.uuid, candidate.uuid) -> RouteOutcome.BLOCKED
        isMutualParty(speaker, candidate) -> RouteOutcome.GROUP
        !admits(speaker, candidate) || !admits(candidate, speaker) -> RouteOutcome.TIER_DENIED
        speaker.world == null || speaker.world != candidate.world -> RouteOutcome.WORLD_MISMATCH
        speaker.instance != candidate.instance -> RouteOutcome.INSTANCE_MISMATCH
        speaker.position == null || candidate.position == null -> RouteOutcome.POSITION_UNKNOWN
        !withinRange(speaker, candidate, distance) -> RouteOutcome.OUT_OF_RANGE
        else -> RouteOutcome.PROXIMITY
    }

    /** Whether two users could ever hear each other, ignoring distance: mutual party, or mutual admission. */
    fun canTalk(a: VoiceParticipant, b: VoiceParticipant): Boolean =
        !isBlocked(a.uuid, b.uuid) && (isMutualParty(a, b) || (admits(a, b) && admits(b, a)))

    fun relation(a: VoiceParticipant, b: VoiceParticipant): Relation = when {
        isMutualParty(a, b) -> Relation.PARTY
        a.friends.contains(b.name) && b.friends.contains(a.name) -> Relation.FRIEND
        a.guildMembers.contains(b.name) && b.guildMembers.contains(a.name) -> Relation.GUILD
        else -> Relation.NONE
    }

    private fun isMutualParty(a: VoiceParticipant, b: VoiceParticipant) =
        a.party.contains(b.name) && b.party.contains(a.name)

    private fun admits(listener: VoiceParticipant, other: VoiceParticipant): Boolean = when (listener.tier) {
        VoiceTier.PARTY -> false
        VoiceTier.FRIENDS_AND_GUILD -> relation(listener, other) != Relation.NONE
        VoiceTier.EVERYONE -> true
    }

    private fun withinRange(a: VoiceParticipant, b: VoiceParticipant, distance: Double): Boolean {
        val pa = a.position ?: return false
        val pb = b.position ?: return false
        val dx = (pa.x - pb.x).toDouble()
        val dy = (pa.y - pb.y).toDouble()
        val dz = (pa.z - pb.z).toDouble()
        return dx * dx + dy * dy + dz * dz <= distance * distance
    }
}
