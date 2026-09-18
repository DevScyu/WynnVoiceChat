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

class VoiceRecipients(val group: List<VoiceParticipant>, val proximity: List<VoiceParticipant>)

/**
 * Decides who hears a speaker. Pure: no I/O, no session state.
 */
class VoiceRouter(private val range: Double, private val isBlocked: (UUID, UUID) -> Boolean) {

    fun route(speaker: VoiceParticipant, candidates: Collection<VoiceParticipant>, whispering: Boolean): VoiceRecipients {
        val distance = if (whispering) range / 2 else range
        val group = ArrayList<VoiceParticipant>()
        val proximity = ArrayList<VoiceParticipant>()

        for (candidate in candidates) {
            if (candidate.uuid == speaker.uuid || isBlocked(speaker.uuid, candidate.uuid)) continue
            if (isMutualParty(speaker, candidate)) {
                group.add(candidate)
                continue
            }
            if (!admits(speaker, candidate) || !admits(candidate, speaker)) continue
            if (!sameInstance(speaker, candidate)) continue
            if (withinRange(speaker, candidate, distance)) proximity.add(candidate)
        }
        return VoiceRecipients(group, proximity)
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

    private fun sameInstance(a: VoiceParticipant, b: VoiceParticipant) =
        a.world != null && a.world == b.world && a.instance == b.instance

    private fun withinRange(a: VoiceParticipant, b: VoiceParticipant, distance: Double): Boolean {
        val pa = a.position ?: return false
        val pb = b.position ?: return false
        val dx = (pa.x - pb.x).toDouble()
        val dy = (pa.y - pb.y).toDouble()
        val dz = (pa.z - pb.z).toDouble()
        return dx * dx + dy * dy + dz * dz <= distance * distance
    }
}
