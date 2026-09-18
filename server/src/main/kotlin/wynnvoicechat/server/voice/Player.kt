package wynnvoicechat.server.voice

import java.util.UUID
import wynnvoicechat.protocol.Packet
import wynnvoicechat.protocol.Packet.Position
import wynnvoicechat.protocol.SocialAction
import wynnvoicechat.protocol.SocialKind

/**
 * One authenticated control connection's game state, written by the TCP handler and read by routing.
 */
class Player(val uuid: UUID, val name: String, val modVersion: String = "other", val send: (Packet) -> Unit) {
    @Volatile var world: String? = null
    /** Set when Wynncraft contradicted [world]; refuses the next join until a new world is claimed. */
    @Volatile var worldRefusal: String? = null
    @Volatile var position: Position? = null
    @Volatile var party: Set<String> = emptySet()
    @Volatile var friends: Set<String> = emptySet()
    /** Guild uuid and every member's rank by name, from the Wynncraft API after auth; never from the client. */
    @Volatile var guildId: UUID? = null
    @Volatile var guildRanks: Map<String, String> = emptyMap()
    val guildMembers: Set<String> get() = guildRanks.keys
    val guildRank: String? get() = guildRanks[name]

    fun apply(social: Packet.Social) {
        val current = when (social.kind) {
            SocialKind.PARTY -> party
            SocialKind.FRIENDS -> friends
        }
        val next = when (social.action) {
            SocialAction.SET -> social.names.toSet()
            SocialAction.ADD -> current + social.names
            SocialAction.REMOVE -> current - social.names.toSet()
        }
        when (social.kind) {
            SocialKind.PARTY -> party = next
            SocialKind.FRIENDS -> friends = next
        }
    }
}
