package wynnvoicechat.server.voice

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import wynnvoicechat.protocol.Peer
import wynnvoicechat.protocol.VoiceTier

class VoiceSession(
    val player: Player,
    val secret: ByteArray,
    @Volatile var tier: VoiceTier,
    @Volatile var instance: String,
    val createdAt: Long,
    /** Row id in `voice_sessions`. */
    val recordId: Int,
) {
    @Volatile var address: InetSocketAddress? = null
    @Volatile var connected = false
    @Volatile var disabled = false
    @Volatile var guildChannel = false
    @Volatile var dnd = false
    @Volatile var callPeer: Player? = null
    @Volatile var lastKeepAliveResponse = createdAt
    @Volatile var lastActivity = createdAt
    @Volatile var lastPeers: List<Peer>? = null
    /** Last SVC mic sequence number seen, for upstream-loss accounting. */
    var lastSequence = -1L

    val speech = SpeechRingBuffer()
    val micLimiter = RateLimiter(max = 60, windowMs = 1000)

    /** sender uuid → last time audio from them was forwarded to this session */
    val recentlyHeard = ConcurrentHashMap<UUID, Long>()

    fun participant() = VoiceParticipant(
        player.uuid, player.name, player.world, instance, player.position, tier, disabled,
        player.party, player.friends, player.guildMembers, player.guildId, guildChannel, callPeer?.uuid,
    )
}
