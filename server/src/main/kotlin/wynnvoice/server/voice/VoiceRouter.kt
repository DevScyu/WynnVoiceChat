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
    val guildId: UUID?,
    val guildChannel: Boolean,
    /** The friend this player is in a call with; both sides point at each other while the call is up. */
    val callPeer: UUID? = null,
)

/** Why a candidate did or did not get the speaker's audio, in the order the checks run. */
enum class RouteOutcome { SELF, BLOCKED, GROUP, CALL, GUILD, TIER_DENIED, WORLD_MISMATCH, INSTANCE_MISMATCH, POSITION_UNKNOWN, OUT_OF_RANGE, PROXIMITY }

/** [outcomes] is indexed by [RouteOutcome.ordinal]: how many candidates ended up in each bucket. */
class VoiceRecipients(val group: List<VoiceParticipant>, val call: List<VoiceParticipant>, val guild: List<VoiceParticipant>, val proximity: List<VoiceParticipant>, val outcomes: IntArray)

/**
 * Decides who hears a speaker. Pure: no I/O, no session state.
 */
class VoiceRouter(
    private val range: Double,
    private val isBlocked: (UUID, UUID) -> Boolean,
    private val isGuildMuted: (guild: UUID, member: UUID) -> Boolean = { _, _ -> false },
) {

    fun route(speaker: VoiceParticipant, candidates: Collection<VoiceParticipant>, whispering: Boolean): VoiceRecipients {
        val distance = if (whispering) range / 2 else range
        val group = ArrayList<VoiceParticipant>()
        val call = ArrayList<VoiceParticipant>()
        val guild = ArrayList<VoiceParticipant>()
        val proximity = ArrayList<VoiceParticipant>()
        val outcomes = IntArray(RouteOutcome.entries.size)
        val muted = isMuted(speaker)

        for (candidate in candidates) {
            val outcome = outcomeOf(speaker, candidate, distance, muted)
            outcomes[outcome.ordinal]++
            when (outcome) {
                RouteOutcome.GROUP -> group.add(candidate)
                RouteOutcome.CALL -> call.add(candidate)
                RouteOutcome.GUILD -> guild.add(candidate)
                RouteOutcome.PROXIMITY -> proximity.add(candidate)
                else -> Unit
            }
        }
        return VoiceRecipients(group, call, guild, proximity, outcomes)
    }

    /** A muted speaker skips the guild channel but is still heard nearby like anyone else. */
    private fun outcomeOf(speaker: VoiceParticipant, candidate: VoiceParticipant, distance: Double, muted: Boolean): RouteOutcome = when {
        candidate.uuid == speaker.uuid -> RouteOutcome.SELF
        isBlocked(speaker.uuid, candidate.uuid) -> RouteOutcome.BLOCKED
        isMutualParty(speaker, candidate) -> RouteOutcome.GROUP
        isCall(speaker, candidate) -> RouteOutcome.CALL
        !muted && isGuildChannel(speaker, candidate) -> RouteOutcome.GUILD
        !admits(speaker, candidate) || !admits(candidate, speaker) -> RouteOutcome.TIER_DENIED
        speaker.world == null || speaker.world != candidate.world -> RouteOutcome.WORLD_MISMATCH
        speaker.instance != candidate.instance -> RouteOutcome.INSTANCE_MISMATCH
        speaker.position == null || candidate.position == null -> RouteOutcome.POSITION_UNKNOWN
        !withinRange(speaker, candidate, distance) -> RouteOutcome.OUT_OF_RANGE
        else -> RouteOutcome.PROXIMITY
    }

    /** Whether [listener] could ever hear [speaker], ignoring distance: mutual party, call, guild channel, or mutual admission. */
    fun canTalk(speaker: VoiceParticipant, listener: VoiceParticipant): Boolean =
        !isBlocked(speaker.uuid, listener.uuid) &&
            (isMutualParty(speaker, listener) || isCall(speaker, listener) || (!isMuted(speaker) && isGuildChannel(speaker, listener)) || (admits(speaker, listener) && admits(listener, speaker)))

    fun relation(a: VoiceParticipant, b: VoiceParticipant): Relation = when {
        isMutualParty(a, b) -> Relation.PARTY
        a.friends.contains(b.name) && b.friends.contains(a.name) -> Relation.FRIEND
        a.guildMembers.contains(b.name) && b.guildMembers.contains(a.name) -> Relation.GUILD
        else -> Relation.NONE
    }

    private fun isMutualParty(a: VoiceParticipant, b: VoiceParticipant) =
        a.party.contains(b.name) && b.party.contains(a.name)

    fun isCall(a: VoiceParticipant, b: VoiceParticipant) = a.callPeer == b.uuid && b.callPeer == a.uuid

    /** Both opted into the guild channel and share a guild; a mute does not change membership, only who is heard. */
    fun isGuildChannel(a: VoiceParticipant, b: VoiceParticipant) =
        a.guildChannel && b.guildChannel && a.guildMembers.contains(b.name) && b.guildMembers.contains(a.name)

    private fun isMuted(p: VoiceParticipant) = p.guildId?.let { isGuildMuted(it, p.uuid) } == true

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
