package wynnvoice.server.voice

import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import wynnvoice.protocol.EndReason
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Peer
import wynnvoice.protocol.ResultKind
import wynnvoice.protocol.VoiceTier
import wynnvoice.server.ApiFetcher
import wynnvoice.server.MojangSessionFetcher
import wynnvoice.server.voice.svc.SvcCodec
import wynnvoice.server.voice.svc.SvcCrypto
import wynnvoice.server.voice.svc.SvcPacket

fun interface VoiceTransport {
    fun send(address: InetSocketAddress, bytes: ByteArray)
}

class FiledReport(
    val id: Int,
    val reporterName: String,
    val targetName: String,
    val report: NewReport,
    val reporterAudio: File?,
    val targetAudio: File?,
)

/**
 * Relay brain: SVC session protocol over UDP, routing, ring buffers, peers sync, blocks and reports.
 * Thread-safe: called from the UDP event loop, the control TCP event loops and its own scheduler.
 */
class VoiceManager(
    val config: VoiceConfig,
    val moderation: VoiceModeration,
    private val transport: VoiceTransport,
    private val mojang: ApiFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(VoiceManager::class.java)

    private val sessions = ConcurrentHashMap<UUID, VoiceSession>()
    private val router = VoiceRouter(config.range, moderation::isBlocked)

    private val reportMinuteLimiters = ConcurrentHashMap<UUID, RateLimiter>()
    private val reportDayLimiters = ConcurrentHashMap<UUID, RateLimiter>()

    private val unauthFailures = ConcurrentHashMap<String, RateLimiter>()
    private val ignoredIpsUntil = ConcurrentHashMap<String, Long>()
    /** uuid → until: an SVC client keeps sending for a while after its session is gone; that is not abuse */
    private val recentlyLeft = ConcurrentHashMap<UUID, Long>()

    private var scheduler: ScheduledExecutorService? = null

    /** Called after a report and its evidence are stored; must not throw or block. */
    @Volatile var onReport: (FiledReport) -> Unit = {}

    companion object {
        const val REPORT_WINDOW_MS = 120_000L
        private const val MAX_REASON_LENGTH = 500
        private const val PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/"
        private val USERNAME = Regex("[A-Za-z0-9_]{1,16}")
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

    fun block(player: Player, targetName: String, blocked: Boolean) {
        val kind = if (blocked) ResultKind.BLOCK else ResultKind.UNBLOCK
        resolveUuid(targetName).whenComplete { target, error ->
            when {
                error != null -> {
                    logger.warn("Profile lookup for {} failed: {}", targetName, error.message)
                    player.send(Packet.Result(kind, false, "Could not look up $targetName, try again later"))
                }
                target == null -> player.send(Packet.Result(kind, false, "Unknown player $targetName"))
                target == player.uuid -> player.send(Packet.Result(kind, false, "You cannot block yourself"))
                else -> {
                    if (blocked) moderation.block(player.uuid, target) else moderation.unblock(player.uuid, target)
                    player.send(Packet.Result(kind, true, (if (blocked) "Blocked " else "Unblocked ") + targetName))
                }
            }
        }
    }

    fun report(player: Player, targetName: String, reason: String) {
        val now = clock()
        fun fail(message: String) = player.send(Packet.Result(ResultKind.REPORT, false, message))

        val reporter = sessions[player.uuid]?.takeIf { it.player === player } ?: return fail("You are not on voice chat")
        val target = sessionNamed(targetName) ?: return fail("$targetName is not on voice chat")
        val heardAt = reporter.recentlyHeard[target.player.uuid]
        if (heardAt == null || now - heardAt > REPORT_WINDOW_MS) return fail("You have not heard $targetName in the last 2 minutes")

        val minute = reportMinuteLimiters.computeIfAbsent(player.uuid) { RateLimiter(1, 60_000) }
        val day = reportDayLimiters.computeIfAbsent(player.uuid) { RateLimiter(10, 24 * 60 * 60_000L) }
        if (!minute.tryAcquire(now) || !day.tryAcquire(now)) return fail("Too many reports, try again later")

        val witnesses = reporter.recentlyHeard.filter { now - it.value <= REPORT_WINDOW_MS }.keys.toList()
        val targetFrames = target.speech.snapshot()
        val reporterFrames = reporter.speech.snapshot()

        val newReport = NewReport(
            reporterId = player.uuid,
            targetId = target.player.uuid,
            reason = reason.take(MAX_REASON_LENGTH),
            world = player.world ?: "",
            instance = reporter.instance,
            reporterPos = player.position,
            targetPos = target.player.position,
            witnesses = witnesses,
            audioFrom = targetFrames.firstOrNull()?.timestampMs,
            audioTo = targetFrames.lastOrNull()?.timestampMs,
        )
        val id = moderation.createReport(newReport)
        // ponytail: file I/O on the caller's TCP event loop; reports are rare (≤10/day/user)
        val dir = File(config.reportDir, id.toString()).apply { mkdirs() }
        val targetAudio = writeAudio(File(dir, "target.opus"), targetFrames)
        val reporterAudio = writeAudio(File(dir, "reporter.opus"), reporterFrames)
        moderation.attachAudio(id, reporterAudio?.absolutePath, targetAudio?.absolutePath)
        logger.info("Voice report #{}: {} -> {} ({} witnesses)", id, player.name, target.player.name, witnesses.size)
        player.send(Packet.Result(ResultKind.REPORT, true, "Report #$id filed"))
        onReport(FiledReport(id, player.name, target.player.name, newReport, reporterAudio, targetAudio))
    }

    private fun writeAudio(file: File, frames: List<SpeechRingBuffer.Frame>): File? {
        if (frames.isEmpty()) return null
        file.outputStream().use { OggOpusWriter.write(frames, it) }
        return file
    }

    private fun sessionNamed(name: String) = sessions.values.firstOrNull { it.player.name.equals(name, ignoreCase = true) }

    /** Live voice sessions first, then Mojang's profile API. */
    fun resolveUuid(name: String): CompletableFuture<UUID?> {
        if (!USERNAME.matches(name)) return CompletableFuture.completedFuture(null)
        sessionNamed(name)?.let { return CompletableFuture.completedFuture(it.player.uuid) }
        return mojang.get(URI(PROFILE_URL + name)).thenApply { it?.let(MojangSessionFetcher::parseProfileId) }
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
        for (uuid in moderation.activeBans(sessions.keys.toList())) {
            val session = sessions.remove(uuid) ?: continue
            recentlyLeft[uuid] = now + LEFT_GRACE_MS
            session.player.send(Packet.Ended(EndReason.BANNED, "You are banned from voice chat"))
        }
        var total = sessions.values.sumOf { it.speech.bytes }
        if (total <= config.ringBufferCapBytes) return
        for (session in sessions.values.sortedBy { it.lastActivity }) {
            if (total <= config.ringBufferCapBytes) break
            total -= session.speech.bytes
            session.speech.clear()
        }
    }
}
