package wynnvoice.server.voice

import java.util.UUID
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Packet.Position

/**
 * One authenticated control connection's game state, written by the TCP handler and read by routing.
 */
class Player(val uuid: UUID, val name: String, val send: (Packet) -> Unit) {
    @Volatile var world: String? = null
    @Volatile var position: Position? = null
    @Volatile var party: Set<String> = emptySet()
    @Volatile var friends: Set<String> = emptySet()
    @Volatile var guildMembers: Set<String> = emptySet()
}
