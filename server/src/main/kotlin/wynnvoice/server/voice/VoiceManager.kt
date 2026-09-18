package wynnvoice.server.voice

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import wynnvoice.protocol.EndReason
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Peer
import wynnvoice.protocol.ResultKind
import wynnvoice.protocol.VoiceTier
import wynnvoice.server.ApiFetcher
import wynnvoice.server.Metrics
import wynnvoice.server.Metrics.counters
import wynnvoice.server.Metrics.sloTimer
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

enum class SessionEnd { LEFT, TIMED_OUT, BANNED, REPLACED, UNSUPPORTED_SVC_VERSION }
enum class UdpType { AUTHENTICATE, CONNECTION_CHECK, KEEP_ALIVE, PING, MIC, OTHER }
enum class UdpDrop { IGNORED_IP, UNDECODABLE, NO_SESSION, DECRYPT_FAILED, UNKNOWN_PACKET, BAD_SECRET, WRONG_ADDRESS, NOT_CONNECTED, MIC_RATE_LIMITED }
enum class DeliverySkip { DISABLED, NOT_CONNECTED, NO_SESSION }
enum class ReportOutcome { FILED, NOT_ON_VOICE, TARGET_NOT_ON_VOICE, NOT_HEARD, RATE_LIMITED }
enum class BlockAction { BLOCK, UNBLOCK }

/**
 * Relay brain: SVC session protocol over UDP, routing, ring buffers, peers sync, blocks and reports.
 * Thread-safe: called from the UDP event loop, the control TCP event loops and its own scheduler.
 */
class VoiceManager(
    val config: VoiceConfig,
    val moderation: VoiceModeration,
    private val transport: VoiceTransport,
    private val mojang: ApiFetcher,
    registry: MeterRegistry = Metrics.registry,
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

    private val sessionsStarted = registry.counter("voice_sessions_started_total")
    private val sessionsEnded = registry.counters<SessionEnd>("voice_sessions_ended_total", "reason")
    private val sessionDuration = registry.sloTimer("voice_session_duration_seconds", Metrics.SESSION_SLO)
    private val sessionsByWorld = MultiGauge.builder("voice_sessions_by_world").register(registry)
    private val sessionsByVersion = MultiGauge.builder("voice_sessions_by_version").register(registry)
    private val datagramsIn = registry.counter("voice_udp_datagrams_total", "direction", "in")
    private val datagramsOut = registry.counter("voice_udp_datagrams_total", "direction", "out")
    private val bytesIn = registry.counter("voice_udp_bytes_total", "direction", "in")
    private val bytesOut = registry.counter("voice_udp_bytes_total", "direction", "out")
    private val udpPackets = registry.counters<UdpType>("voice_udp_packets_total", "type")
    private val udpDropped = registry.counters<UdpDrop>("voice_udp_dropped_total", "reason")
    private val ignoredIps = registry.counter("voice_udp_ignored_ips_total")
    private val micFrames = registry.counter("voice_mic_frames_total")
    private val micSequenceGaps = registry.counter("voice_mic_sequence_gaps_total")
    private val fanout = DistributionSummary.builder("voice_fanout_recipients").serviceLevelObjectives(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0).register(registry)
    private val deliverySkipped = registry.counters<DeliverySkip>("voice_delivery_skipped_total", "reason")
    private val relayTimer = registry.sloTimer("voice_relay_seconds", Metrics.RELAY_SLO)
    private val ringEvictions = registry.counter("voice_ring_evictions_total")
    private val routeOutcomes = registry.counters<RouteOutcome>("voice_route_candidates_total", "outcome").values.toTypedArray()
    private val reports = registry.counters<ReportOutcome>("voice_reports_total", "outcome")
    private val reportAudioBytes = DistributionSummary.builder("voice_report_audio_bytes").register(registry)
    private val blocks = registry.counters<BlockAction>("voice_blocks_total", "action")
    private val bansActive = AtomicInteger()
    // ponytail: names cached for the process lifetime; Mojang rate-limits repeat profile lookups per uuid
    private val profileNames = ConcurrentHashMap<UUID, String>()

    init {
        for (tier in VoiceTier.entries) Gauge.builder("voice_sessions") { sessions.values.count { it.tier == tier } }.tag("tier", tier.name).register(registry)
        Gauge.builder("voice_sessions_connected") { sessions.values.count { it.connected } }.register(registry)
        Gauge.builder("voice_sessions_svc_disabled") { sessions.values.count { it.disabled } }.register(registry)
        Gauge.builder("voice_ring_bytes") { sessions.values.sumOf { it.speech.bytes } }.register(registry)
        Gauge.builder("voice_blocks_active") { moderation.blockCount }.register(registry)
        Gauge.builder("voice_bans_active", bansActive) { it.get().toDouble() }.register(registry)
    }

    companion object {
        const val REPORT_WINDOW_MS = 120_000L
        private const val MAX_REASON_LENGTH = 500
        private const val PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/"
        private const val SESSION_PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/"
        private val USERNAME = Regex("[A-Za-z0-9_]{1,16}")
        /** World is a client-supplied string; only real Wynncraft worlds become label values. */
        private val WORLD = Regex("WC\\d{1,3}")
        private const val UNCONNECTED_TIMEOUT_MS = 60_000L
        private const val PEERS_SYNC_MS = 2_000L
        private const val MAINTAIN_MS = 60_000L
        private const val UNAUTH_MAX_PER_MINUTE = 100
        private const val UNAUTH_IGNORE_MS = 10 * 60_000L
        private const val LEFT_GRACE_MS = 60_000L
        private const val SESSION_RETENTION_MS = 90L * 24 * 60 * 60_000
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
            sessionsEnded.getValue(SessionEnd.UNSUPPORTED_SVC_VERSION).increment()
            player.send(Packet.Ended(EndReason.UNSUPPORTED_SVC_VERSION, "Update Simple Voice Chat to 2.6.x"))
            return
        }
        val now = clock()
        val effectiveTier = clamp(tier)
        // ponytail: one SQLite insert per join on the TCP event loop, as the report path does; offload if joins pile up
        val recordId = moderation.recordSession(player.uuid, player.name, now, effectiveTier, player.world)
        val session = VoiceSession(player, SvcCrypto.newSecret(), effectiveTier, instance, now, recordId)
        sessions.put(player.uuid, session)?.let { end(it, SessionEnd.REPLACED, now) }
        sessionsStarted.increment()
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
        if (session.player === player && sessions.remove(player.uuid, session)) end(session, SessionEnd.LEFT, clock())
    }

    /** Bookkeeping for a session already removed from [sessions]. */
    private fun end(session: VoiceSession, reason: SessionEnd, now: Long) {
        recentlyLeft[session.player.uuid] = now + LEFT_GRACE_MS
        sessionsEnded.getValue(reason).increment()
        sessionDuration.record(now - session.createdAt, TimeUnit.MILLISECONDS)
        moderation.endSession(session.recordId, now, reason.name.lowercase())
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
                    blocks.getValue(if (blocked) BlockAction.BLOCK else BlockAction.UNBLOCK).increment()
                    player.send(Packet.Result(kind, true, (if (blocked) "Blocked " else "Unblocked ") + targetName))
                }
            }
        }
    }

    fun blockList(player: Player) {
        val names = moderation.blocksOf(player.uuid).map(::resolveName)
        CompletableFuture.allOf(*names.toTypedArray()).whenComplete { _, _ ->
            player.send(Packet.BlockListResult(names.map { it.join() }.sortedBy { it.lowercase() }))
        }
    }

    /** Live voice sessions first, then Mojang's session profile; the uuid itself when neither knows the name. */
    private fun resolveName(uuid: UUID): CompletableFuture<String> {
        sessions[uuid]?.let { return CompletableFuture.completedFuture(it.player.name) }
        profileNames[uuid]?.let { return CompletableFuture.completedFuture(it) }
        return mojang.get(URI(SESSION_PROFILE_URL + uuid.toString().replace("-", "")))
            .thenApply { json -> json?.let(MojangSessionFetcher::parseProfileName)?.also { profileNames[uuid] = it } }
            .exceptionally { error ->
                logger.warn("Profile lookup for {} failed: {}", uuid, error.message)
                null
            }
            .thenApply { it ?: uuid.toString() }
    }

    fun report(player: Player, targetName: String, reason: String) {
        val now = clock()
        fun fail(outcome: ReportOutcome, message: String) {
            reports.getValue(outcome).increment()
            player.send(Packet.Result(ResultKind.REPORT, false, message))
        }

        val reporter = sessions[player.uuid]?.takeIf { it.player === player } ?: return fail(ReportOutcome.NOT_ON_VOICE, "You are not on voice chat")
        val target = sessionNamed(targetName) ?: return fail(ReportOutcome.TARGET_NOT_ON_VOICE, "$targetName is not on voice chat")
        val heardAt = reporter.recentlyHeard[target.player.uuid]
        if (heardAt == null || now - heardAt > REPORT_WINDOW_MS) return fail(ReportOutcome.NOT_HEARD, "You have not heard $targetName in the last 2 minutes")

        val minute = reportMinuteLimiters.computeIfAbsent(player.uuid) { RateLimiter(1, 60_000) }
        val day = reportDayLimiters.computeIfAbsent(player.uuid) { RateLimiter(10, 24 * 60 * 60_000L) }
        if (!minute.tryAcquire(now) || !day.tryAcquire(now)) return fail(ReportOutcome.RATE_LIMITED, "Too many reports, try again later")

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
        reports.getValue(ReportOutcome.FILED).increment()
        reportAudioBytes.record(((targetAudio?.length() ?: 0L) + (reporterAudio?.length() ?: 0L)).toDouble())
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
        val start = System.nanoTime()
        datagramsIn.increment()
        bytesIn.increment(bytes.size.toDouble())
        try {
            handle(address, bytes)
        } finally {
            relayTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    private fun drop(reason: UdpDrop) = udpDropped.getValue(reason).increment()

    private fun handle(address: InetSocketAddress, bytes: ByteArray) {
        val now = clock()
        val ip = address.address.hostAddress
        ignoredIpsUntil[ip]?.let { until ->
            if (now < until) return drop(UdpDrop.IGNORED_IP)
            ignoredIpsUntil.remove(ip)
        }

        val datagram = SvcCodec.readClientDatagram(bytes) ?: return drop(UdpDrop.UNDECODABLE)
        val session = sessions[datagram.playerUuid]
        val stale = (recentlyLeft[datagram.playerUuid] ?: Long.MIN_VALUE) > now
        if (session == null) {
            drop(UdpDrop.NO_SESSION)
            return if (stale) Unit else recordUnauthenticated(ip, now)
        }
        val plain = SvcCrypto.decrypt(session.secret, datagram.encryptedPayload)
        if (plain == null) {
            drop(UdpDrop.DECRYPT_FAILED)
            return if (stale) Unit else recordUnauthenticated(ip, now)
        }
        val packet = SvcCodec.decode(plain) ?: return drop(UdpDrop.UNKNOWN_PACKET)

        when (packet) {
            is SvcPacket.Authenticate -> {
                udpPackets.getValue(UdpType.AUTHENTICATE).increment()
                if (!packet.secret.contentEquals(session.secret)) return drop(UdpDrop.BAD_SECRET)
                session.address = address
                send(session, SvcPacket.AuthenticateAck)
            }
            is SvcPacket.ConnectionCheck -> {
                udpPackets.getValue(UdpType.CONNECTION_CHECK).increment()
                if (session.address != address) return drop(UdpDrop.WRONG_ADDRESS)
                session.connected = true
                session.lastKeepAliveResponse = now
                send(session, SvcPacket.ConnectionCheckAck)
            }
            is SvcPacket.KeepAlive -> {
                udpPackets.getValue(UdpType.KEEP_ALIVE).increment()
                if (session.address != address) return drop(UdpDrop.WRONG_ADDRESS)
                session.lastKeepAliveResponse = now
            }
            is SvcPacket.Ping -> {
                udpPackets.getValue(UdpType.PING).increment()
                if (session.address != address) return drop(UdpDrop.WRONG_ADDRESS)
                send(session, packet)
            }
            is SvcPacket.Mic -> {
                udpPackets.getValue(UdpType.MIC).increment()
                if (session.address != address) return drop(UdpDrop.WRONG_ADDRESS)
                if (!session.connected) return drop(UdpDrop.NOT_CONNECTED)
                handleMic(session, packet, now)
            }
            else -> {
                udpPackets.getValue(UdpType.OTHER).increment()
                drop(UdpDrop.UNKNOWN_PACKET)
            }
        }
    }

    private fun recordUnauthenticated(ip: String, now: Long) {
        val limiter = unauthFailures.computeIfAbsent(ip) { RateLimiter(UNAUTH_MAX_PER_MINUTE, 60_000) }
        if (!limiter.tryAcquire(now)) {
            ignoredIpsUntil[ip] = now + UNAUTH_IGNORE_MS
            ignoredIps.increment()
            logger.warn("Ignoring voice datagrams from {} for {} minutes", ip, UNAUTH_IGNORE_MS / 60_000)
        }
    }

    private fun handleMic(session: VoiceSession, mic: SvcPacket.Mic, now: Long) {
        if (session.lastSequence >= 0 && mic.sequence > session.lastSequence + 1) micSequenceGaps.increment((mic.sequence - session.lastSequence - 1).toDouble())
        session.lastSequence = mic.sequence
        if (!session.micLimiter.tryAcquire(now)) return drop(UdpDrop.MIC_RATE_LIMITED)
        micFrames.increment()
        session.lastActivity = now
        session.speech.push(now, mic.data)

        val speaker = session.participant()
        // ponytail: O(voice sessions) per frame; index by world when voice users exceed ~1000
        val recipients = router.route(speaker, sessions.values.map { it.participant() }, mic.whispering)
        for (i in routeOutcomes.indices) routeOutcomes[i].increment(recipients.outcomes[i].toDouble())
        fanout.record((recipients.group.size + recipients.proximity.size).toDouble())

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
        val receiver = sessions[receiverUuid] ?: return deliverySkipped.getValue(DeliverySkip.NO_SESSION).increment()
        if (receiver.disabled) return deliverySkipped.getValue(DeliverySkip.DISABLED).increment()
        if (!receiver.connected) return deliverySkipped.getValue(DeliverySkip.NOT_CONNECTED).increment()
        receiver.recentlyHeard[speakerUuid] = now
        sendPlain(receiver, plain)
    }

    private fun send(session: VoiceSession, packet: SvcPacket) = sendPlain(session, SvcCodec.encode(packet))

    private fun sendPlain(session: VoiceSession, plain: ByteArray) {
        val address = session.address ?: return
        val datagram = SvcCodec.writeServerDatagram(SvcCrypto.encrypt(session.secret, plain))
        datagramsOut.increment()
        bytesOut.increment(datagram.size.toDouble())
        transport.send(address, datagram)
    }

    // --- scheduled ---

    fun tick() {
        val now = clock()
        for (session in sessions.values) {
            val idle = now - session.lastKeepAliveResponse
            val timedOut = if (session.connected) idle >= config.keepAliveMs * 10L else idle >= UNCONNECTED_TIMEOUT_MS
            if (timedOut) {
                if (sessions.remove(session.player.uuid, session)) {
                    end(session, SessionEnd.TIMED_OUT, now)
                    session.player.send(Packet.Ended(EndReason.TIMED_OUT, "Voice connection timed out"))
                }
            } else if (session.connected) {
                send(session, SvcPacket.KeepAlive)
            }
        }
    }

    fun syncPeers() {
        val participants = sessions.values.map { it.participant() }
        refreshDistributions()
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

    private fun refreshDistributions() {
        sessionsByWorld.register(sessions.values.groupingBy { it.player.world?.takeIf(WORLD::matches) ?: "other" }.eachCount().map { (world, n) ->
            MultiGauge.Row.of(Tags.of("world", world), n)
        }, true)
        sessionsByVersion.register(sessions.values.groupingBy { it.player.modVersion }.eachCount().map { (version, n) ->
            MultiGauge.Row.of(Tags.of("version", version), n)
        }, true)
    }

    fun maintain() {
        val now = clock()
        // ponytail: the failure window is one minute, so a wholesale clear every maintain loses nothing
        unauthFailures.clear()
        ignoredIpsUntil.values.removeIf { it <= now }
        recentlyLeft.values.removeIf { it <= now }
        val bans = moderation.activeBanRows()
        bansActive.set(bans.size)
        for (ban in bans) {
            val session = sessions.remove(ban.userId) ?: continue
            end(session, SessionEnd.BANNED, now)
            session.player.send(Packet.Ended(EndReason.BANNED, "You are banned from voice chat"))
        }
        moderation.pruneSessions(now - SESSION_RETENTION_MS)
        var total = sessions.values.sumOf { it.speech.bytes }
        if (total <= config.ringBufferCapBytes) return
        for (session in sessions.values.sortedBy { it.lastActivity }) {
            if (total <= config.ringBufferCapBytes) break
            total -= session.speech.bytes
            session.speech.clear()
            ringEvictions.increment()
        }
    }
}
