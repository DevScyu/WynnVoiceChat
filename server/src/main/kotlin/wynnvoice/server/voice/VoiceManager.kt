package wynnvoice.server.voice

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import wynnvoice.protocol.EndReason
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Peer
import wynnvoice.protocol.VoiceTier
import wynnvoice.server.voice.svc.SvcCodec
import wynnvoice.server.voice.svc.SvcCrypto
import wynnvoice.server.voice.svc.SvcPacket

fun interface VoiceTransport {
    fun send(address: InetSocketAddress, bytes: ByteArray)
}

/**
 * Relay brain: SVC session protocol over UDP, routing, ring buffers and peers sync.
 * Thread-safe: called from the UDP event loop, the control TCP event loops and its own scheduler.
 */
class VoiceManager(
    val config: VoiceConfig,
    private val transport: VoiceTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(VoiceManager::class.java)

    private val sessions = ConcurrentHashMap<UUID, VoiceSession>()
    // ponytail: blocks arrive with the moderation ticket; until then nobody is blocked
    private val router = VoiceRouter(config.range) { _, _ -> false }

    private val unauthFailures = ConcurrentHashMap<String, RateLimiter>()
    private val ignoredIpsUntil = ConcurrentHashMap<String, Long>()
    /** uuid → until: an SVC client keeps sending for a while after its session is gone; that is not abuse */
    private val recentlyLeft = ConcurrentHashMap<UUID, Long>()

    private var scheduler: ScheduledExecutorService? = null

    companion object {
        private const val UNCONNECTED_TIMEOUT_MS = 60_000L
        private const val PEERS_SYNC_MS = 2_000L
        private const val MAINTAIN_MS = 60_000L
        private const val UNAUTH_MAX_PER_MINUTE = 100
        private const val UNAUTH_IGNORE_MS = 10 * 60_000L
        private const val LEFT_GRACE_MS = 60_000L
    }

    fun start() {
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-scheduler").apply { isDaemon = true } }.also {
            it.scheduleAtFixedRate({ guarded { tick() } }, config.keepAliveMs.toLong(), config.keepAliveMs.toLong(), TimeUnit.MILLISECONDS)
            it.scheduleAtFixedRate({ guarded { syncPeers() } }, PEERS_SYNC_MS, PEERS_SYNC_MS, TimeUnit.MILLISECONDS)
            it.scheduleAtFixedRate({ guarded { maintain() } }, MAINTAIN_MS, MAINTAIN_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        scheduler?.shutdownNow()
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.error("Voice scheduler task failed", e)
        }
    }

    fun sessionOf(uuid: UUID): VoiceSession? = sessions[uuid]

    // --- control TCP side ---

    private fun clamp(tier: VoiceTier) = minOf(tier, config.maxTier)

    fun join(player: Player, svcCompatVersion: Int, tier: VoiceTier, instance: String) {
        if (svcCompatVersion != VoiceConfig.SUPPORTED_SVC_COMPAT) {
            player.send(Packet.Ended(EndReason.UNSUPPORTED_SVC_VERSION, "Update Simple Voice Chat to 2.6.x"))
            return
        }
        val session = VoiceSession(player, SvcCrypto.newSecret(), clamp(tier), instance, clock())
        if (sessions.put(player.uuid, session) != null) recentlyLeft[player.uuid] = clock() + LEFT_GRACE_MS
        player.send(Packet.Secret(session.secret, config.host, config.port, config.range, config.keepAliveMs))
    }

    fun update(player: Player, tier: VoiceTier, instance: String, disabled: Boolean) {
        sessions[player.uuid]?.let {
            it.tier = clamp(tier)
            it.instance = instance
            it.disabled = disabled
        }
    }

    fun leave(player: Player) {
        val session = sessions[player.uuid] ?: return
        if (session.player === player && sessions.remove(player.uuid, session)) recentlyLeft[player.uuid] = clock() + LEFT_GRACE_MS
    }

    // --- UDP side ---

    fun onDatagram(address: InetSocketAddress, bytes: ByteArray) {
        val now = clock()
        val ip = address.address.hostAddress
        ignoredIpsUntil[ip]?.let { until ->
            if (now < until) return
            ignoredIpsUntil.remove(ip)
        }

        val datagram = SvcCodec.readClientDatagram(bytes) ?: return
        val session = sessions[datagram.playerUuid]
        val stale = (recentlyLeft[datagram.playerUuid] ?: Long.MIN_VALUE) > now
        if (session == null) return if (stale) Unit else recordUnauthenticated(ip, now)
        val plain = SvcCrypto.decrypt(session.secret, datagram.encryptedPayload)
            ?: return if (stale) Unit else recordUnauthenticated(ip, now)
        val packet = SvcCodec.decode(plain) ?: return

        when (packet) {
            is SvcPacket.Authenticate -> if (packet.secret.contentEquals(session.secret)) {
                session.address = address
                send(session, SvcPacket.AuthenticateAck)
            }
            is SvcPacket.ConnectionCheck -> if (session.address == address) {
                session.connected = true
                session.lastKeepAliveResponse = now
                send(session, SvcPacket.ConnectionCheckAck)
            }
            is SvcPacket.KeepAlive -> if (session.address == address) session.lastKeepAliveResponse = now
            is SvcPacket.Ping -> if (session.address == address) send(session, packet)
            is SvcPacket.Mic -> if (session.connected && session.address == address) handleMic(session, packet, now)
            else -> Unit
        }
    }

    private fun recordUnauthenticated(ip: String, now: Long) {
        val limiter = unauthFailures.computeIfAbsent(ip) { RateLimiter(UNAUTH_MAX_PER_MINUTE, 60_000) }
        if (!limiter.tryAcquire(now)) {
            ignoredIpsUntil[ip] = now + UNAUTH_IGNORE_MS
            logger.warn("Ignoring voice datagrams from {} for {} minutes", ip, UNAUTH_IGNORE_MS / 60_000)
        }
    }

    private fun handleMic(session: VoiceSession, mic: SvcPacket.Mic, now: Long) {
        if (!session.micLimiter.tryAcquire(now)) return
        session.lastActivity = now
        session.speech.push(now, mic.data)

        val speaker = session.participant()
        // ponytail: O(voice sessions) per frame; index by world when voice users exceed ~1000
        val recipients = router.route(speaker, sessions.values.map { it.participant() }, mic.whispering)

        if (recipients.group.isNotEmpty()) {
            val plain = SvcCodec.encode(SvcPacket.GroupSound(speaker.uuid, mic.data, mic.sequence))
            recipients.group.forEach { deliver(it.uuid, plain, speaker.uuid, now) }
        }
        val position = speaker.position
        if (recipients.proximity.isNotEmpty() && position != null) {
            val distance = (if (mic.whispering) config.range / 2 else config.range).toFloat()
            val plain = SvcCodec.encode(
                SvcPacket.LocationSound(
                    speaker.uuid, position.x.toDouble(), position.y.toDouble(), position.z.toDouble(),
                    mic.data, mic.sequence, distance,
                )
            )
            recipients.proximity.forEach { deliver(it.uuid, plain, speaker.uuid, now) }
        }
    }

    private fun deliver(receiverUuid: UUID, plain: ByteArray, speakerUuid: UUID, now: Long) {
        val receiver = sessions[receiverUuid] ?: return
        if (receiver.disabled || !receiver.connected) return
        receiver.recentlyHeard[speakerUuid] = now
        sendPlain(receiver, plain)
    }

    private fun send(session: VoiceSession, packet: SvcPacket) = sendPlain(session, SvcCodec.encode(packet))

    private fun sendPlain(session: VoiceSession, plain: ByteArray) {
        val address = session.address ?: return
        transport.send(address, SvcCodec.writeServerDatagram(SvcCrypto.encrypt(session.secret, plain)))
    }

    // --- scheduled ---

    fun tick() {
        val now = clock()
        for (session in sessions.values) {
            val idle = now - session.lastKeepAliveResponse
            val timedOut = if (session.connected) idle >= config.keepAliveMs * 10L else idle >= UNCONNECTED_TIMEOUT_MS
            if (timedOut) {
                if (sessions.remove(session.player.uuid, session)) {
                    session.player.send(Packet.Ended(EndReason.TIMED_OUT, "Voice connection timed out"))
                }
            } else if (session.connected) {
                send(session, SvcPacket.KeepAlive)
            }
        }
    }

    fun syncPeers() {
        val participants = sessions.values.map { it.participant() }
        for (session in sessions.values) {
            if (!session.connected) continue
            val self = session.participant()
            val group = router.route(self, participants, whispering = false).group.mapTo(HashSet()) { it.uuid }
            // Every voice user on the world gets an entry so SVC can show who has voice; audio stays tier-gated.
            val peers = participants
                .filter { it.uuid != self.uuid && (it.uuid in group || (it.world != null && it.world == self.world)) }
                .map { Peer(it.uuid, it.name, it.disabled, router.relation(self, it), router.canTalk(self, it)) }
                .sortedBy { it.uuid }
            if (peers != session.lastPeers) {
                session.lastPeers = peers
                session.player.send(Packet.Peers(peers))
            }
        }
    }

    fun maintain() {
        val now = clock()
        // ponytail: the failure window is one minute, so a wholesale clear every maintain loses nothing
        unauthFailures.clear()
        ignoredIpsUntil.values.removeIf { it <= now }
        recentlyLeft.values.removeIf { it <= now }
        var total = sessions.values.sumOf { it.speech.bytes }
        if (total <= config.ringBufferCapBytes) return
        for (session in sessions.values.sortedBy { it.lastActivity }) {
            if (total <= config.ringBufferCapBytes) break
            total -= session.speech.bytes
            session.speech.clear()
        }
    }
}
