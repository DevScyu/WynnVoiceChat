package wynnvoicechat.server.voice

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import wynnvoicechat.protocol.CallAction
import wynnvoicechat.protocol.CallStateKind
import wynnvoicechat.protocol.EndReason
import wynnvoicechat.protocol.Packet
import wynnvoicechat.protocol.Packet.CallState
import wynnvoicechat.protocol.Packet.Position
import wynnvoicechat.protocol.Relation
import wynnvoicechat.protocol.ResultKind
import wynnvoicechat.protocol.SocialAction
import wynnvoicechat.protocol.SocialKind
import wynnvoicechat.protocol.VoiceTier
import wynnvoicechat.server.ApiFetcher
import wynnvoicechat.server.MetricsTest
import wynnvoicechat.server.voice.svc.SvcCodec
import wynnvoicechat.server.voice.svc.SvcCrypto
import wynnvoicechat.server.voice.svc.SvcPacket

class VoiceManagerTest {
    private class FakeTransport : VoiceTransport {
        val sent = ArrayList<Pair<InetSocketAddress, ByteArray>>()
        override fun send(address: InetSocketAddress, bytes: ByteArray) { sent.add(address to bytes) }
        fun clear() = sent.clear()
    }

    private val transport = FakeTransport()
    private val tcp = HashMap<UUID, ArrayList<Packet>>()
    private var now = 1_000_000L
    private val reportDir = File("build/tmp/voice-reports-test").apply { deleteRecursively() }
    private val config = VoiceConfig(
        enabled = true, everyoneEnabled = true, host = "voice.test", port = 24454, bindAddress = "127.0.0.1",
        range = 32.0, keepAliveMs = 1000, reportDir = reportDir.path, ringBufferCapBytes = 1_000_000,
    )
    private lateinit var moderation: VoiceModeration
    private lateinit var manager: VoiceManager
    /** Names Mojang knows that are not on voice. */
    private val profiles = HashMap<String, UUID>()
    private val mojang = ApiFetcher { uri ->
        val key = uri.path.substringAfterLast('/')
        if (key == "boom") return@ApiFetcher CompletableFuture.failedFuture(IOException("HTTP 429"))
        val profile = if (uri.host == "sessionserver.mojang.com") profiles.entries.firstOrNull { it.value.toString().replace("-", "") == key }
        else profiles.entries.firstOrNull { it.key == key }
        CompletableFuture.completedFuture(profile?.let { "{\"id\":\"${it.value.toString().replace("-", "")}\",\"name\":\"${it.key}\"}" })
    }

    private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    private fun manager(config: VoiceConfig = this.config) = VoiceManager(config, moderation, transport, mojang, registry) { now }

    private fun sample(series: String): Double? = MetricsTest.sample(registry.scrape(), series)?.toDouble()

    private fun player(name: String, position: Position? = Position(0f, 100f, 0f), world: String? = "WC1"): Player {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        tcp[uuid] = ArrayList()
        return Player(uuid, name) { tcp[uuid]!!.add(it) }.also {
            it.world = world
            it.position = position
        }
    }

    private val guildId = UUID.fromString("18d19092-684b-427b-aa58-574230befe79")

    private fun guildmate(name: String, rank: String = "recruit", world: String? = "WC1", position: Position? = Position(0f, 100f, 0f)) = player(name, position, world).also {
        it.guildRanks = mapOf("A" to "recruit", "G" to "recruit", "H" to "recruit", name to rank)
        it.guildId = guildId
    }

    private fun mic(player: Player, address: InetSocketAddress, secret: ByteArray, sequence: Long = 1) {
        transport.clear()
        manager.onDatagram(address, clientDatagram(player, secret, SvcPacket.Mic(byteArrayOf(1), sequence, whispering = false)))
    }

    private fun sentTo(player: Player) = tcp[player.uuid]!!
    private inline fun <reified P> last(player: Player): P = sentTo(player).last() as P

    @BeforeTest
    fun setUp() {
        moderation = testModeration("voice-manager-test") { now }
        manager = manager()
    }

    // --- helpers that act as an SVC client ---

    private fun secretOf(player: Player) = last<Packet.Secret>(player).secret

    private fun clientDatagram(player: Player, secret: ByteArray, packet: SvcPacket): ByteArray {
        val plain = when (packet) {
            is SvcPacket.Authenticate -> byteArrayOf(0x5) + uuidBytes(packet.playerUuid) + packet.secret
            SvcPacket.ConnectionCheck -> byteArrayOf(0x9)
            SvcPacket.KeepAlive -> byteArrayOf(0x8)
            is SvcPacket.Ping -> SvcCodec.encode(packet)
            is SvcPacket.Mic -> byteArrayOf(0x1) + varintAndBytes(packet.data) + longBytes(packet.sequence) + byteArrayOf(if (packet.whispering) 1 else 0)
            else -> error("not a client packet")
        }
        return byteArrayOf(0xFF.toByte()) + uuidBytes(player.uuid) + varintAndBytes(SvcCrypto.encrypt(secret, plain))
    }

    private fun uuidBytes(u: UUID) = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    private fun longBytes(v: Long) = ByteBuffer.allocate(8).putLong(v).array()
    private fun varintAndBytes(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var v = b.size
        while (v and -128 != 0) { out.write(v and 127 or 128); v = v ushr 7 }
        out.write(v)
        out.write(b)
        return out.toByteArray()
    }

    /** Decrypted server datagram payload; the first byte is the SVC packet type. */
    private fun decodeServer(secret: ByteArray, datagram: ByteArray): ByteArray {
        assertEquals(0xFF.toByte(), datagram[0])
        val headerLen = if (datagram[1].toInt() and 0x80 == 0) 2 else 3
        return SvcCrypto.decrypt(secret, datagram.copyOfRange(headerLen, datagram.size))!!
    }

    private fun typeOf(secret: ByteArray, datagram: ByteArray) = decodeServer(secret, datagram)[0].toInt()

    private fun connect(player: Player, address: InetSocketAddress, tier: VoiceTier = VoiceTier.EVERYONE, instance: String = "", m: VoiceManager = manager): ByteArray {
        m.join(player, 20, tier, instance)
        val secret = secretOf(player)
        m.onDatagram(address, clientDatagram(player, secret, SvcPacket.Authenticate(player.uuid, secret)))
        m.onDatagram(address, clientDatagram(player, secret, SvcPacket.ConnectionCheck))
        return secret
    }

    private val addrA = InetSocketAddress("10.0.0.1", 5000)
    private val addrB = InetSocketAddress("10.0.0.2", 5000)
    private val addrC = InetSocketAddress("10.0.0.3", 5000)

    // --- join ---

    @Test
    fun `unsupported svc version is refused`() {
        val u = player("A")
        manager.join(u, 18, VoiceTier.PARTY, "")
        assertEquals(EndReason.UNSUPPORTED_SVC_VERSION, last<Packet.Ended>(u).reason)
        assertNull(manager.sessionOf(u.uuid))
    }

    @Test
    fun `tier is clamped to the relay maximum`() {
        val u = player("A")
        val closed = manager(config.copy(everyoneEnabled = false))
        closed.join(u, 20, VoiceTier.EVERYONE, "")
        assertEquals(VoiceTier.FRIENDS_AND_GUILD, closed.sessionOf(u.uuid)!!.tier)
        closed.update(u, VoiceTier.EVERYONE, "", disabled = false)
        assertEquals(VoiceTier.FRIENDS_AND_GUILD, closed.sessionOf(u.uuid)!!.tier)

        manager.join(u, 20, VoiceTier.EVERYONE, "")
        assertEquals(VoiceTier.EVERYONE, manager.sessionOf(u.uuid)!!.tier)
    }

    @Test
    fun `join issues a secret`() {
        val u = player("A")
        manager.join(u, 20, VoiceTier.PARTY, "")
        val secret = last<Packet.Secret>(u)
        assertEquals(16, secret.secret.size)
        assertEquals("voice.test", secret.host)
        assertEquals(24454, secret.port)
        assertEquals(32.0, secret.range)
        assertEquals(1000, secret.keepAliveMs)
    }

    @Test
    fun `leave drops the session`() {
        val u = player("A")
        connect(u, addrA)
        manager.leave(u)
        assertNull(manager.sessionOf(u.uuid))
    }

    // --- UDP session protocol ---

    @Test
    fun `authenticate connection check and ping`() {
        val u = player("A")
        manager.join(u, 20, VoiceTier.EVERYONE, "")
        val secret = secretOf(u)

        manager.onDatagram(addrA, clientDatagram(u, secret, SvcPacket.Authenticate(u.uuid, SvcCrypto.newSecret())))
        assertTrue(transport.sent.isEmpty(), "wrong secret gets no reply")

        manager.onDatagram(addrA, clientDatagram(u, secret, SvcPacket.Authenticate(u.uuid, secret)))
        assertEquals(addrA, transport.sent.single().first)
        assertEquals(0x6, typeOf(secret, transport.sent.single().second))

        transport.clear()
        manager.onDatagram(addrA, clientDatagram(u, secret, SvcPacket.ConnectionCheck))
        assertEquals(0xA, typeOf(secret, transport.sent.single().second))

        transport.clear()
        manager.onDatagram(addrA, clientDatagram(u, secret, SvcPacket.Ping(UUID.randomUUID(), 5)))
        assertEquals(0x7, typeOf(secret, transport.sent.single().second))
    }

    @Test
    fun `keep alive and timeout after ten seconds`() {
        val a = player("A")
        val secret = connect(a, addrA)
        transport.clear()

        now += 1000
        manager.tick()
        assertEquals(0x8, typeOf(secret, transport.sent.single().second))

        manager.onDatagram(addrA, clientDatagram(a, secret, SvcPacket.KeepAlive))
        now += 9_999
        manager.tick()
        assertTrue(sentTo(a).none { it is Packet.Ended }, "refreshed keepalive keeps session alive")

        now += 10_000
        manager.tick()
        assertEquals(EndReason.TIMED_OUT, last<Packet.Ended>(a).reason)
        assertNull(manager.sessionOf(a.uuid))
    }

    @Test
    fun `never connected session times out after a minute`() {
        val a = player("A")
        manager.join(a, 20, VoiceTier.PARTY, "")
        now += 59_000
        manager.tick()
        assertTrue(sentTo(a).none { it is Packet.Ended })
        now += 2_000
        manager.tick()
        assertEquals(EndReason.TIMED_OUT, last<Packet.Ended>(a).reason)
    }

    // --- routing ---

    @Test
    fun `mic goes to party as group and strangers in range as location`() {
        val a = player("A").also { it.party = setOf("P") }
        val p = player("P", world = "WC9").also { it.party = setOf("A") }
        val s = player("S", Position(10f, 100f, 0f))
        val far = player("F", Position(100f, 100f, 0f))
        val q = player("Q", Position(5f, 100f, 0f))
        val secretA = connect(a, addrA)
        val secretP = connect(p, InetSocketAddress("10.0.0.3", 1))
        val secretS = connect(s, InetSocketAddress("10.0.0.4", 1))
        connect(far, InetSocketAddress("10.0.0.5", 1))
        connect(q, InetSocketAddress("10.0.0.6", 1))
        manager.update(q, VoiceTier.EVERYONE, "", disabled = true)
        transport.clear()

        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1, 2, 3), 7, whispering = false)))

        assertEquals(2, transport.sent.size)
        val toP = transport.sent.single { it.first.address.hostAddress == "10.0.0.3" }
        val toS = transport.sent.single { it.first.address.hostAddress == "10.0.0.4" }
        assertEquals(0x3, typeOf(secretP, toP.second))
        val expected = SvcCodec.encode(SvcPacket.LocationSound(a.uuid, 0.0, 100.0, 0.0, byteArrayOf(1, 2, 3), 7, 32f))
        assertContentEquals(expected, decodeServer(secretS, toS.second))
    }

    @Test
    fun `friends and guild tier reaches mutual friends and guild mates in range only`() {
        val guild = mapOf("A" to "recruit", "G" to "recruit")
        val a = player("A").also { it.friends = setOf("F", "OneSided"); it.guildRanks = guild }
        val f = player("F", Position(10f, 100f, 0f)).also { it.friends = setOf("A") }
        val g = player("G", Position(10f, 100f, 0f)).also { it.guildRanks = guild }
        val oneSided = player("OneSided", Position(10f, 100f, 0f))
        val stranger = player("S", Position(10f, 100f, 0f))
        val secretA = connect(a, addrA, tier = VoiceTier.FRIENDS_AND_GUILD)
        val secretF = connect(f, addrB, tier = VoiceTier.FRIENDS_AND_GUILD)
        val secretG = connect(g, addrC, tier = VoiceTier.EVERYONE)
        connect(oneSided, InetSocketAddress("10.0.0.4", 1))
        connect(stranger, InetSocketAddress("10.0.0.5", 1))
        transport.clear()

        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1), 1, whispering = false)))

        assertEquals(setOf(addrB, addrC), transport.sent.map { it.first }.toSet())
        val expected = SvcCodec.encode(SvcPacket.LocationSound(a.uuid, 0.0, 100.0, 0.0, byteArrayOf(1), 1, 32f))
        assertContentEquals(expected, decodeServer(secretF, transport.sent.single { it.first == addrB }.second))
        assertContentEquals(expected, decodeServer(secretG, transport.sent.single { it.first == addrC }.second))
    }

    @Test
    fun `guild channel is group audio across worlds for members who opted in, party first`() {
        val a = guildmate("A")
        val g = guildmate("G", world = "WC9")
        val h = guildmate("H", world = "WC9")
        val secretA = connect(a, addrA, tier = VoiceTier.PARTY)
        val secretG = connect(g, addrB, tier = VoiceTier.PARTY)
        connect(h, addrC, tier = VoiceTier.PARTY)
        manager.update(a, VoiceTier.PARTY, "", disabled = false, guildChannel = true)
        manager.update(g, VoiceTier.PARTY, "", disabled = false, guildChannel = true)

        mic(a, addrA, secretA)
        assertEquals(listOf(addrB), transport.sent.map { it.first })
        assertContentEquals(SvcCodec.encode(SvcPacket.GroupSound(a.uuid, byteArrayOf(1), 1)), decodeServer(secretG, transport.sent.single().second))
        assertEquals(1.0, sample("voice_route_candidates_total{outcome=\"guild\"}"))

        manager.syncPeers()
        val peers = last<Packet.Peers>(a).peers
        assertEquals(listOf(g.uuid), peers.map { it.uuid }, "opted-in guild mates are listed across worlds, others are not")
        assertEquals(Relation.GUILD, peers.single().relation)
        assertTrue(peers.single().reachable)

        a.party = setOf("G")
        g.party = setOf("A")
        mic(a, addrA, secretA, sequence = 2)
        assertEquals(listOf(addrB), transport.sent.map { it.first })
        assertEquals(1.0, sample("voice_route_candidates_total{outcome=\"group\"}"))
        assertEquals(1.0, sample("voice_route_candidates_total{outcome=\"guild\"}"))
    }

    @Test
    fun `guild mute needs owner or chief rank and a fellow member, silences the channel only and expires`() {
        val a = guildmate("A")
        val g = guildmate("G", world = "WC9")
        val secretA = connect(a, addrA, tier = VoiceTier.PARTY)
        val secretG = connect(g, addrB, tier = VoiceTier.PARTY)
        manager.update(a, VoiceTier.PARTY, "", disabled = false, guildChannel = true)
        manager.update(g, VoiceTier.PARTY, "", disabled = false, guildChannel = true)

        manager.guildMute(a, "G", muted = true, hours = 1)
        assertEquals(Packet.Result(ResultKind.GUILD_MUTE, false, "Only the guild owner and chiefs can mute"), last<Packet.Result>(a))
        a.guildRanks = a.guildRanks + ("A" to "chief")
        manager.guildMute(a, "Nobody", muted = true, hours = 1)
        assertEquals(Packet.Result(ResultKind.GUILD_MUTE, false, "Nobody is not in your guild"), last<Packet.Result>(a))
        val guildless = player("X")
        manager.guildMute(guildless, "G", muted = true, hours = 1)
        assertEquals(Packet.Result(ResultKind.GUILD_MUTE, false, "You are not in a guild"), last<Packet.Result>(guildless))

        manager.guildMute(a, "g", muted = true, hours = 1)
        assertEquals(Packet.Result(ResultKind.GUILD_MUTE, true, "Muted G in the guild channel for 1 h"), last<Packet.Result>(a))
        assertEquals(1.0, sample("voice_guild_mutes_total{action=\"mute\"}"))

        mic(g, addrB, secretG)
        assertTrue(transport.sent.isEmpty(), "muted member is not heard in the channel")
        mic(a, addrA, secretA)
        assertEquals(listOf(addrB), transport.sent.map { it.first }, "muted member still hears the channel")
        manager.syncPeers()
        assertFalse(last<Packet.Peers>(g).peers.single().reachable)

        now += 3_600_001
        mic(g, addrB, secretG, sequence = 2)
        assertEquals(listOf(addrA), transport.sent.map { it.first }, "mute expired")

        manager.guildMute(a, "G", muted = true, hours = 0)
        mic(g, addrB, secretG, sequence = 3)
        assertTrue(transport.sent.isEmpty())
        manager.guildMute(a, "G", muted = false, hours = 0)
        assertEquals(Packet.Result(ResultKind.GUILD_MUTE, true, "Unmuted G in the guild channel"), last<Packet.Result>(a))
        mic(g, addrB, secretG, sequence = 4)
        assertEquals(listOf(addrA), transport.sent.map { it.first })
    }

    @Test
    fun `chiefs cannot mute the owner or another chief but the owner mutes anyone`() {
        val chief = guildmate("A", rank = "chief").also { it.guildRanks = it.guildRanks + mapOf("G" to "chief", "H" to "owner") }
        val other = guildmate("G", rank = "chief")
        val owner = guildmate("H", rank = "owner").also { it.guildRanks = it.guildRanks + mapOf("A" to "chief", "G" to "chief") }
        connect(chief, addrA)
        connect(other, addrB)
        connect(owner, addrC)

        manager.guildMute(chief, "G", muted = true, hours = 0)
        assertEquals(Packet.Result(ResultKind.GUILD_MUTE, false, "Only the guild owner can mute a chief"), last<Packet.Result>(chief))
        manager.guildMute(chief, "H", muted = true, hours = 0)
        assertEquals(Packet.Result(ResultKind.GUILD_MUTE, false, "Only the guild owner can mute a chief"), last<Packet.Result>(chief))
        assertFalse(moderation.isGuildMuted(guildId, other.uuid))
        assertFalse(moderation.isGuildMuted(guildId, owner.uuid))

        manager.guildMute(owner, "G", muted = true, hours = 0)
        assertEquals(Packet.Result(ResultKind.GUILD_MUTE, true, "Muted G in the guild channel"), last<Packet.Result>(owner))
        assertTrue(moderation.isGuildMuted(guildId, other.uuid))
        manager.guildMute(chief, "G", muted = false, hours = 0)
        assertTrue(moderation.isGuildMuted(guildId, other.uuid), "a chief cannot undo the owner's mute of a chief")
        manager.guildMute(owner, "A", muted = true, hours = 0)
        assertTrue(moderation.isGuildMuted(guildId, chief.uuid))
    }

    @Test
    fun `whisper delivers half range`() {
        val a = player("A")
        val s = player("S", Position(10f, 100f, 0f))
        val secretA = connect(a, addrA)
        val secretS = connect(s, addrB)
        transport.clear()
        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1), 1, whispering = true)))
        val expected = SvcCodec.encode(SvcPacket.LocationSound(a.uuid, 0.0, 100.0, 0.0, byteArrayOf(1), 1, 16f))
        assertContentEquals(expected, decodeServer(secretS, transport.sent.single().second))
    }

    @Test
    fun `mic is rate limited and buffered`() {
        val a = player("A")
        val secretA = connect(a, addrA)
        repeat(70) { manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1), it.toLong(), false))) }
        assertEquals(60, manager.sessionOf(a.uuid)!!.speech.snapshot().size)
        now += 1000
        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1), 99, false)))
        assertEquals(61, manager.sessionOf(a.uuid)!!.speech.snapshot().size)
    }

    @Test
    fun `mic from unconnected or wrong address is ignored`() {
        val a = player("A")
        val s = player("S", Position(10f, 100f, 0f))
        manager.join(a, 20, VoiceTier.EVERYONE, "")
        val secretA = secretOf(a)
        connect(s, addrB)
        transport.clear()
        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1), 1, false)))
        assertTrue(transport.sent.isEmpty())

        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Authenticate(a.uuid, secretA)))
        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.ConnectionCheck))
        transport.clear()
        manager.onDatagram(InetSocketAddress("10.9.9.9", 1), clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1), 1, false)))
        assertTrue(transport.sent.isEmpty())
    }

    // --- peers ---

    @Test
    fun `peers are sent only on change`() {
        val a = player("A")
        val s = player("S", Position(10f, 100f, 0f))
        connect(a, addrA)
        connect(s, addrB)

        manager.syncPeers()
        val peers = last<Packet.Peers>(a).peers
        assertEquals(listOf(s.uuid), peers.map { it.uuid })
        assertEquals(Relation.NONE, peers[0].relation)
        assertTrue(peers[0].reachable)
        val count = sentTo(a).size

        manager.syncPeers()
        assertEquals(count, sentTo(a).size)

        manager.update(s, VoiceTier.EVERYONE, "", disabled = true)
        manager.syncPeers()
        assertTrue(last<Packet.Peers>(a).peers[0].disabled)
    }

    @Test
    fun `peers cover the whole world not just the audience`() {
        val a = player("A")
        val partyOnly = player("P")
        val elsewhere = player("E", world = "WC2")
        connect(a, addrA)
        connect(partyOnly, addrB, tier = VoiceTier.PARTY)
        connect(elsewhere, addrC)

        manager.syncPeers()
        val peers = last<Packet.Peers>(a).peers
        assertEquals(listOf(partyOnly.uuid), peers.map { it.uuid })
        assertEquals(Relation.NONE, peers[0].relation)
        assertFalse(peers[0].reachable, "audience mismatch reads as unreachable")
        assertFalse(peers[0].disabled)
    }

    @Test
    fun `party peers carry the party relation across worlds`() {
        val a = player("A").also { it.party = setOf("P") }
        val p = player("P", world = "WC9").also { it.party = setOf("A") }
        connect(a, addrA)
        connect(p, addrB, tier = VoiceTier.PARTY)
        manager.syncPeers()
        val peer = last<Packet.Peers>(a).peers.single()
        assertEquals(Relation.PARTY, peer.relation)
        assertTrue(peer.reachable)
    }

    // --- maintenance ---

    @Test
    fun `ring cap clears oldest activity first`() {
        val a = player("A")
        val b = player("B")
        val small = manager(config.copy(ringBufferCapBytes = 60_000))
        val sA = connect(a, addrA, m = small)
        val sB = connect(b, addrB, m = small)
        repeat(50) { small.onDatagram(addrA, clientDatagram(a, sA, SvcPacket.Mic(ByteArray(1000), it.toLong(), false))) }
        now += 1000
        repeat(50) { small.onDatagram(addrB, clientDatagram(b, sB, SvcPacket.Mic(ByteArray(1000), it.toLong(), false))) }

        small.maintain()

        assertEquals(0L, small.sessionOf(a.uuid)!!.speech.bytes)
        assertEquals(50_000L, small.sessionOf(b.uuid)!!.speech.bytes)
    }

    @Test
    fun `maintain drops banned sessions`() {
        val a = player("A")
        val b = player("B")
        connect(a, addrA)
        val secretB = connect(b, addrB)
        transaction(moderation.db) { VoiceBansTable.insert { it[userId] = b.uuid; it[reason] = "r"; it[bannedBy] = "s"; it[bannedAt] = now } }

        manager.maintain()

        assertEquals(EndReason.BANNED, last<Packet.Ended>(b).reason)
        assertNull(manager.sessionOf(b.uuid))
        assertTrue(sentTo(a).none { it is Packet.Ended })
        repeat(101) { manager.onDatagram(addrB, clientDatagram(b, secretB, SvcPacket.KeepAlive)) }
        assertEquals(0.0, sample("voice_udp_ignored_ips_total"), "a banned client's trailing datagrams are not abuse")
    }

    @Test
    fun `join while banned is refused without a session`() {
        val b = player("B")
        transaction(moderation.db) { VoiceBansTable.insert { it[userId] = b.uuid; it[reason] = "slurs"; it[bannedBy] = "s"; it[bannedAt] = now } }

        manager.join(b, 20, VoiceTier.EVERYONE, "")

        assertEquals(EndReason.BANNED, last<Packet.Ended>(b).reason)
        assertTrue(sentTo(b).none { it is Packet.Secret })
        assertNull(manager.sessionOf(b.uuid))
    }

    @Test
    fun `leave from a stale connection keeps the newer session`() {
        val old = player("A")
        connect(old, addrA)
        val fresh = player("A")
        manager.join(fresh, 20, VoiceTier.EVERYONE, "")
        manager.leave(old)
        assertEquals(fresh, manager.sessionOf(fresh.uuid)!!.player)
    }

    // --- block / report ---

    @Test
    fun `block and unblock resolve live sessions then mojang`() {
        val a = player("A")
        val b = player("B")
        connect(a, addrA)
        connect(b, addrB)
        profiles["Offline"] = UUID.randomUUID()

        manager.block(a, "b", blocked = true)
        val blocked = last<Packet.Result>(a)
        assertEquals(ResultKind.BLOCK, blocked.kind)
        assertTrue(blocked.ok, blocked.message)
        assertTrue(moderation.isBlocked(b.uuid, a.uuid))

        manager.block(a, "B", blocked = false)
        assertEquals(ResultKind.UNBLOCK, last<Packet.Result>(a).kind)
        assertTrue(last<Packet.Result>(a).ok)
        assertFalse(moderation.isBlocked(a.uuid, b.uuid))

        manager.block(a, "Offline", blocked = true)
        assertTrue(last<Packet.Result>(a).ok)
        assertTrue(moderation.isBlocked(a.uuid, profiles["Offline"]!!))

        manager.block(a, "Nobody", blocked = true)
        assertFalse(last<Packet.Result>(a).ok)
        manager.block(a, "A", blocked = true)
        assertFalse(last<Packet.Result>(a).ok)
        manager.block(a, "bad name!", blocked = true)
        assertFalse(last<Packet.Result>(a).ok)
    }

    @Test
    fun `mojang lookups for blocks are capped per player while live names stay free`() {
        val a = player("A")
        val b = player("B")
        connect(a, addrA)
        connect(b, addrB)
        repeat(VoiceManager.LOOKUPS_PER_MINUTE) { manager.block(a, "Nobody$it", blocked = true) }
        assertEquals("Unknown player Nobody0", sentTo(a).filterIsInstance<Packet.Result>().first().message)

        manager.block(a, "NobodyMore", blocked = true)
        assertEquals("Too many lookups, try again later", last<Packet.Result>(a).message)
        manager.block(a, "B", blocked = true)
        assertTrue(last<Packet.Result>(a).ok, "a name on voice needs no lookup")
        manager.block(b, "NobodyElse", blocked = true)
        assertEquals("Unknown player NobodyElse", last<Packet.Result>(b).message, "the cap is per player")
        now += 60_000
        manager.block(a, "NobodyLater", blocked = true)
        assertEquals("Unknown player NobodyLater", last<Packet.Result>(a).message)
    }

    @Test
    fun `party and friend lists are capped so a client cannot grow them without bound`() {
        val a = player("A")
        val names = (1..Player.MAX_NAMES).map { "F$it" }
        manager.social(a, Packet.Social(SocialKind.FRIENDS, SocialAction.SET, names))
        assertEquals(Player.MAX_NAMES, a.friends.size)
        manager.social(a, Packet.Social(SocialKind.FRIENDS, SocialAction.ADD, listOf("OneMore")))
        assertEquals(Player.MAX_NAMES, a.friends.size)
        assertFalse("OneMore" in a.friends)
        manager.social(a, Packet.Social(SocialKind.FRIENDS, SocialAction.SET, names + "OneMore"))
        assertEquals(Player.MAX_NAMES, a.friends.size, "an oversized SET keeps the first names")
    }

    @Test
    fun `block list resolves names via live sessions then mojang and falls back to the uuid`() {
        val a = player("A")
        val b = player("B")
        connect(a, addrA)
        connect(b, addrB)
        profiles["Carl"] = UUID.randomUUID()
        val unknown = UUID.fromString("ffffffff-0000-4000-8000-000000000000")
        moderation.block(a.uuid, b.uuid)
        moderation.block(a.uuid, profiles["Carl"]!!)
        moderation.block(a.uuid, unknown)

        manager.blockList(a)
        assertEquals(Packet.BlockListResult(listOf("B", "Carl", unknown.toString())), last<Packet.BlockListResult>(a))

        manager.blockList(b)
        assertEquals(Packet.BlockListResult(emptyList()), last<Packet.BlockListResult>(b))
    }

    @Test
    fun `blocked players hear nothing from each other and see each other as unreachable`() {
        val a = player("A")
        val s = player("S", Position(10f, 100f, 0f))
        val secretA = connect(a, addrA)
        val secretS = connect(s, addrB)
        manager.block(s, "A", blocked = true)
        transport.clear()

        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1), 1, false)))
        manager.onDatagram(addrB, clientDatagram(s, secretS, SvcPacket.Mic(byteArrayOf(1), 1, false)))
        assertTrue(transport.sent.isEmpty())

        manager.syncPeers()
        assertFalse(last<Packet.Peers>(a).peers.single().reachable)

        manager.block(s, "A", blocked = false)
        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1), 2, false)))
        assertEquals(listOf(addrB), transport.sent.map { it.first })
    }

    @Test
    fun `report requires recent audio and writes evidence`() {
        val a = player("A")
        val s = player("S", Position(10f, 100f, 0f))
        val secretA = connect(a, addrA)
        val secretS = connect(s, addrB)

        manager.report(a, "S", "spam")
        assertFalse(last<Packet.Result>(a).ok, "nothing heard yet")

        manager.onDatagram(addrB, clientDatagram(s, secretS, SvcPacket.Mic(byteArrayOf(0x08), 1, false)))
        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(0x08), 1, false)))
        now += 30_000

        var filed: FiledReport? = null
        manager.onReport = { filed = it }
        manager.report(a, "s", "x".repeat(600))
        val result = last<Packet.Result>(a)
        assertEquals(ResultKind.REPORT, result.kind)
        assertTrue(result.ok, result.message)
        val id = result.message.removePrefix("Report #").substringBefore(' ').toInt()
        assertTrue(File(reportDir, "$id/target.opus").length() > 0)
        assertTrue(File(reportDir, "$id/reporter.opus").length() > 0)
        assertEquals(id, filed!!.id)
        assertEquals("S", filed!!.targetName)
        assertEquals(500, filed!!.report.reason.length)
        assertEquals(File(reportDir, "$id/target.opus"), filed!!.targetAudio)

        manager.report(a, "S", "again")
        assertFalse(last<Packet.Result>(a).ok, "1 per minute")

        now += 121_000
        manager.report(a, "S", "stale")
        assertFalse(last<Packet.Result>(a).ok, "not heard in the last 2 minutes")

        manager.leave(a)
        manager.report(a, "S", "gone")
        assertFalse(last<Packet.Result>(a).ok, "reporter must be on voice")
    }

    // --- calls ---

    private fun friends(vararg names: String): List<Player> {
        val all = names.map { player(it, world = "WC${it.hashCode() % 7 + 1}") }
        all.forEach { p -> p.friends = names.filter { it != p.name }.toSet() }
        return all
    }

    private fun call(from: Player, action: CallAction, target: String = "") = manager.call(from, target, action)

    private fun refusal(from: Player, message: String) = assertEquals(Packet.Result(ResultKind.CALL, false, message), last<Packet.Result>(from))

    @Test
    fun `invite needs both on voice, mutual friends, no party, no block, both free and the target not in dnd`() {
        val (a, b, c) = friends("A", "B", "C")
        call(a, CallAction.INVITE, "B")
        refusal(a, "You are not on voice chat")
        val secretA = connect(a, addrA, tier = VoiceTier.PARTY)
        call(a, CallAction.INVITE, "B")
        refusal(a, "B is not on voice chat")
        call(a, CallAction.INVITE, "a")
        refusal(a, "You cannot call yourself")
        val secretB = connect(b, addrB, tier = VoiceTier.PARTY)
        connect(c, addrC, tier = VoiceTier.PARTY)

        b.friends = setOf("C")
        call(a, CallAction.INVITE, "B")
        refusal(a, "You can only call mutual friends")
        b.friends = setOf("A", "C")

        a.party = setOf("X")
        call(a, CallAction.INVITE, "B")
        refusal(a, "Leave your party first")
        a.party = emptySet()
        b.party = setOf("X")
        call(a, CallAction.INVITE, "B")
        refusal(a, "B is in a party")
        b.party = emptySet()

        moderation.block(b.uuid, a.uuid)
        call(a, CallAction.INVITE, "B")
        refusal(a, "You cannot call B")
        moderation.unblock(b.uuid, a.uuid)

        manager.update(b, VoiceTier.PARTY, "", disabled = false, dnd = true)
        call(a, CallAction.INVITE, "B")
        assertEquals(CallState("B", b.uuid, CallStateKind.DND), last<CallState>(a))
        assertTrue(sentTo(b).none { it is CallState }, "the callee is not told about calls dnd suppressed")
        manager.update(b, VoiceTier.PARTY, "", disabled = false, dnd = false)

        call(a, CallAction.INVITE, "b")
        assertEquals(CallState("B", b.uuid, CallStateKind.RINGING), last<CallState>(a))
        assertEquals(CallState("A", a.uuid, CallStateKind.INCOMING), last<CallState>(b))
        call(a, CallAction.INVITE, "C")
        refusal(a, "Hang up or answer your current call first")
        call(b, CallAction.INVITE, "C")
        refusal(b, "Hang up or answer your current call first")
        call(c, CallAction.INVITE, "B")
        assertEquals(CallState("B", b.uuid, CallStateKind.BUSY), last<CallState>(c))
        call(c, CallAction.INVITE, "A")
        assertEquals(CallState("A", a.uuid, CallStateKind.BUSY), last<CallState>(c))
        assertEquals(1.0, sample("voice_calls_total{outcome=\"invited\"}"))
        assertEquals(2.0, sample("voice_calls_total{outcome=\"busy\"}"))
        assertEquals(1.0, sample("voice_calls_total{outcome=\"dnd\"}"))
        assertEquals(9.0, sample("voice_calls_total{outcome=\"refused\"}"))

        call(b, CallAction.ACCEPT)
        assertEquals(CallState("B", b.uuid, CallStateKind.ACTIVE), last<CallState>(a))
        assertEquals(CallState("A", a.uuid, CallStateKind.ACTIVE), last<CallState>(b))
        assertEquals(b, manager.sessionOf(a.uuid)!!.callPeer)
        assertEquals(a, manager.sessionOf(b.uuid)!!.callPeer)
        call(c, CallAction.INVITE, "A")
        assertEquals(CallState("A", a.uuid, CallStateKind.BUSY), last<CallState>(c))

        mic(a, addrA, secretA)
        assertEquals(listOf(addrB), transport.sent.map { it.first }, "call audio crosses worlds as group sound")
        assertEquals(0x3, typeOf(secretB, transport.sent.single().second))
        assertEquals(1.0, sample("voice_route_candidates_total{outcome=\"call\"}"))
        manager.syncPeers()
        val peer = last<Packet.Peers>(a).peers.single { it.uuid == b.uuid }
        assertEquals(Relation.FRIEND, peer.relation)
        assertTrue(peer.reachable)

        call(a, CallAction.HANGUP)
        assertEquals(CallState("B", b.uuid, CallStateKind.ENDED), last<CallState>(a))
        assertEquals(CallState("A", a.uuid, CallStateKind.ENDED), last<CallState>(b))
        assertNull(manager.sessionOf(a.uuid)!!.callPeer)
        assertNull(manager.sessionOf(b.uuid)!!.callPeer)
        call(a, CallAction.HANGUP)
        refusal(a, "You are not in a call")
        call(b, CallAction.ACCEPT)
        refusal(b, "Nobody is calling you")
        assertEquals(1.0, sample("voice_calls_total{outcome=\"ended\"}"))
    }

    @Test
    fun `decline, expiry and a hangup while ringing end the invite`() {
        val (a, b) = friends("A", "B")
        val secretA = connect(a, addrA, tier = VoiceTier.PARTY)
        val secretB = connect(b, addrB, tier = VoiceTier.PARTY)
        fun keepAlive() {
            manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.KeepAlive))
            manager.onDatagram(addrB, clientDatagram(b, secretB, SvcPacket.KeepAlive))
        }

        call(a, CallAction.INVITE, "B")
        call(b, CallAction.DECLINE)
        assertEquals(CallState("B", b.uuid, CallStateKind.DECLINED), last<CallState>(a))
        call(b, CallAction.DECLINE)
        refusal(b, "Nobody is calling you")

        call(a, CallAction.INVITE, "B")
        now += 29_999
        keepAlive()
        manager.tick()
        assertEquals(CallState("B", b.uuid, CallStateKind.RINGING), last<CallState>(a))
        now += 1
        keepAlive()
        manager.tick()
        assertEquals(CallState("B", b.uuid, CallStateKind.NO_ANSWER), last<CallState>(a))
        assertEquals(CallState("A", a.uuid, CallStateKind.EXPIRED), last<CallState>(b))
        call(b, CallAction.ACCEPT)
        refusal(b, "Nobody is calling you")

        call(a, CallAction.INVITE, "B")
        call(a, CallAction.HANGUP)
        assertEquals(CallState("B", b.uuid, CallStateKind.ENDED), last<CallState>(a))
        assertEquals(CallState("A", a.uuid, CallStateKind.ENDED), last<CallState>(b))
        call(b, CallAction.ACCEPT)
        refusal(b, "Nobody is calling you")
        assertEquals(1.0, sample("voice_calls_total{outcome=\"declined\"}"))
        assertEquals(1.0, sample("voice_calls_total{outcome=\"no_answer\"}"))
    }

    @Test
    fun `a call ends when either side leaves voice, joins a party or blocks the other`() {
        val (a, b) = friends("A", "B")
        fun activeCall() {
            connect(a, addrA, tier = VoiceTier.PARTY)
            connect(b, addrB, tier = VoiceTier.PARTY)
            call(a, CallAction.INVITE, "B")
            call(b, CallAction.ACCEPT)
            assertEquals(CallState("A", a.uuid, CallStateKind.ACTIVE), last<CallState>(b))
        }

        activeCall()
        manager.leave(a)
        assertEquals(CallState("A", a.uuid, CallStateKind.ENDED), last<CallState>(b))
        assertNull(manager.sessionOf(b.uuid)!!.callPeer)

        activeCall()
        manager.social(b, Packet.Social(SocialKind.PARTY, SocialAction.ADD, listOf("X")))
        assertEquals(CallState("B", b.uuid, CallStateKind.ENDED), last<CallState>(a))
        assertEquals(CallState("A", a.uuid, CallStateKind.ENDED), last<CallState>(b))
        assertEquals(setOf("X"), b.party)
        manager.social(b, Packet.Social(SocialKind.PARTY, SocialAction.SET, emptyList()))

        activeCall()
        manager.block(a, "B", blocked = true)
        assertEquals(CallState("B", b.uuid, CallStateKind.ENDED), sentTo(a).filterIsInstance<CallState>().last())
        assertEquals(CallState("A", a.uuid, CallStateKind.ENDED), last<CallState>(b))
        moderation.unblock(a.uuid, b.uuid)

        activeCall()
        now += 60_000
        manager.tick()
        assertNull(manager.sessionOf(a.uuid))
        assertTrue(sentTo(a).any { it == CallState("B", b.uuid, CallStateKind.ENDED) })
        assertTrue(sentTo(b).any { it == CallState("A", a.uuid, CallStateKind.ENDED) })

        connect(a, addrA, tier = VoiceTier.PARTY)
        connect(b, addrB, tier = VoiceTier.PARTY)
        call(a, CallAction.INVITE, "B")
        manager.social(a, Packet.Social(SocialKind.PARTY, SocialAction.SET, listOf("X")))
        assertEquals(CallState("A", a.uuid, CallStateKind.ENDED), last<CallState>(b), "a pending invite is withdrawn too")
        call(b, CallAction.ACCEPT)
        refusal(b, "Nobody is calling you")
        assertEquals(5.0, sample("voice_calls_total{outcome=\"ended\"}"))
    }

    // --- abuse ---

    @Test
    fun `datagrams from a client that just left are not counted as abuse`() {
        val a = player("A")
        val secret = connect(a, addrA)
        manager.leave(a)
        repeat(101) { manager.onDatagram(addrA, clientDatagram(a, secret, SvcPacket.KeepAlive)) }
        val again = connect(a, addrA)
        transport.clear()
        manager.onDatagram(addrA, clientDatagram(a, again, SvcPacket.Ping(UUID.randomUUID(), 1)))
        assertEquals(1, transport.sent.size, "IP is not ignored")
    }

    @Test
    fun `unauthenticated flood is ignored for ten minutes`() {
        val a = player("A")
        val secret = connect(a, addrA)
        val junk = byteArrayOf(0xFF.toByte()) + uuidBytes(UUID.randomUUID()) + varintAndBytes(ByteArray(40))
        repeat(101) { manager.onDatagram(addrB, junk) }
        transport.clear()

        manager.onDatagram(addrB, clientDatagram(a, secret, SvcPacket.Authenticate(a.uuid, secret)))
        assertTrue(transport.sent.isEmpty(), "IP is ignored even for a valid authenticate")

        now += 10 * 60_000 + 1
        manager.onDatagram(addrB, clientDatagram(a, secret, SvcPacket.Authenticate(a.uuid, secret)))
        assertEquals(1, transport.sent.size)
    }

    // --- metrics ---

    @Test
    fun `metrics follow sessions and datagrams`() {
        val a = player("A").also { it.party = setOf("P") }
        val p = player("P").also { it.party = setOf("A") }
        assertEquals(0.0, sample("voice_sessions_started_total"))
        assertEquals(0.0, sample("voice_udp_dropped_total{reason=\"no_session\"}"), "counters exist before any event")

        val secretA = connect(a, addrA, VoiceTier.PARTY)
        connect(p, addrB, VoiceTier.EVERYONE)
        assertEquals(2.0, sample("voice_sessions_started_total"))
        assertEquals(1.0, sample("voice_sessions{tier=\"PARTY\"}"))
        assertEquals(1.0, sample("voice_sessions{tier=\"EVERYONE\"}"))
        assertEquals(2.0, sample("voice_sessions_connected"))
        assertEquals(2.0, sample("voice_udp_packets_total{type=\"authenticate\"}"))

        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1, 2, 3), 7, whispering = false)))
        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1, 2, 3), 10, whispering = false)))
        assertEquals(6.0, sample("voice_udp_datagrams_total{direction=\"in\"}"))
        assertEquals(6.0, sample("voice_udp_datagrams_total{direction=\"out\"}"))
        assertTrue(sample("voice_udp_bytes_total{direction=\"out\"}")!! > 0.0)
        assertEquals(2.0, sample("voice_mic_frames_total"))
        assertEquals(2.0, sample("voice_mic_sequence_gaps_total"))
        assertEquals(2.0, sample("voice_route_candidates_total{outcome=\"self\"}"))
        assertEquals(2.0, sample("voice_route_candidates_total{outcome=\"group\"}"))
        assertEquals(2.0, sample("voice_fanout_recipients_bucket{le=\"1.0\"}"))
        assertEquals(6.0, sample("voice_relay_seconds_count"))
        assertTrue(sample("voice_ring_bytes")!! > 0.0)

        now += 40_000
        manager.leave(a)
        manager.leave(p)
        assertEquals(0.0, sample("voice_sessions{tier=\"PARTY\"}"))
        assertEquals(0.0, sample("voice_sessions_connected"))
        assertEquals(2.0, sample("voice_sessions_ended_total{reason=\"left\"}"))
        assertEquals(2.0, sample("voice_session_duration_seconds_bucket{le=\"60.0\"}"))
        assertEquals(0.0, sample("voice_session_duration_seconds_bucket{le=\"30.0\"}"))
    }

    @Test
    fun `party speaker against an everyone listener is tier denied`() {
        val a = player("A")
        val s = player("S", Position(10f, 100f, 0f))
        val secretA = connect(a, addrA, VoiceTier.PARTY)
        connect(s, addrB, VoiceTier.EVERYONE)

        manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(1), 1, whispering = false)))

        assertEquals(1.0, sample("voice_route_candidates_total{outcome=\"tier_denied\"}"))
        assertEquals(0.0, sample("voice_route_candidates_total{outcome=\"proximity\"}"))
        assertEquals(1.0, sample("voice_fanout_recipients_bucket{le=\"1.0\"}"))
        assertEquals(0.0, sample("voice_fanout_recipients_sum"))
    }

    @Test
    fun `world label only accepts real worlds`() {
        connect(player("A", world = "WC12"), addrA)
        connect(player("B", world = "EU7"), addrB)
        connect(player("C", world = "lobby-x\"} 9"), addrC)
        connect(player("D", world = null), InetSocketAddress("10.0.0.4", 1))

        manager.syncPeers()

        val scrape = registry.scrape()
        assertEquals("1.0", MetricsTest.sample(scrape, "voice_sessions_by_world{world=\"WC12\"}"), scrape)
        assertEquals("1.0", MetricsTest.sample(scrape, "voice_sessions_by_world{world=\"EU7\"}"), scrape)
        assertEquals("2.0", MetricsTest.sample(scrape, "voice_sessions_by_world{world=\"other\"}"), scrape)
        assertFalse(scrape.contains("lobby"), scrape)
        assertEquals("4.0", MetricsTest.sample(scrape, "voice_sessions_by_version{version=\"other\"}"), scrape)
    }

    @Test
    fun `dropped datagrams and refusals are counted by reason`() {
        val a = player("A")
        manager.join(a, 18, VoiceTier.PARTY, "")
        assertEquals(1.0, sample("voice_sessions_ended_total{reason=\"unsupported_svc_version\"}"))

        val secret = connect(a, addrA)
        manager.onDatagram(addrA, byteArrayOf(1, 2, 3))
        manager.onDatagram(addrA, clientDatagram(player("Nobody"), ByteArray(16), SvcPacket.KeepAlive))
        manager.onDatagram(addrA, clientDatagram(a, ByteArray(16), SvcPacket.KeepAlive))
        manager.onDatagram(addrB, clientDatagram(a, secret, SvcPacket.KeepAlive))
        manager.onDatagram(addrA, clientDatagram(a, secret, SvcPacket.Authenticate(a.uuid, ByteArray(16))))
        repeat(61) { manager.onDatagram(addrA, clientDatagram(a, secret, SvcPacket.Mic(byteArrayOf(1), it.toLong(), false))) }

        assertEquals(1.0, sample("voice_udp_dropped_total{reason=\"undecodable\"}"))
        assertEquals(1.0, sample("voice_udp_dropped_total{reason=\"no_session\"}"))
        assertEquals(1.0, sample("voice_udp_dropped_total{reason=\"decrypt_failed\"}"))
        assertEquals(1.0, sample("voice_udp_dropped_total{reason=\"wrong_address\"}"))
        assertEquals(1.0, sample("voice_udp_dropped_total{reason=\"bad_secret\"}"))
        assertEquals(1.0, sample("voice_udp_dropped_total{reason=\"mic_rate_limited\"}"))
        assertEquals(60.0, sample("voice_mic_frames_total"))
    }

    @Test
    fun `voice_sessions has one row after join and leave and old rows are pruned`() {
        val a = player("A", world = "WC3")
        connect(a, addrA, VoiceTier.FRIENDS_AND_GUILD)
        now += 5_000
        manager.leave(a)

        val row = transaction(moderation.db) { VoiceSessionsTable.selectAll().single() }
        assertEquals(a.uuid, row[VoiceSessionsTable.uuid])
        assertEquals("A", row[VoiceSessionsTable.name])
        assertEquals(1_000_000L, row[VoiceSessionsTable.startedAt])
        assertEquals(1_005_000L, row[VoiceSessionsTable.endedAt])
        assertEquals("FRIENDS_AND_GUILD", row[VoiceSessionsTable.tier])
        assertEquals("WC3", row[VoiceSessionsTable.world])
        assertEquals("left", row[VoiceSessionsTable.endReason])

        now += 91L * 24 * 60 * 60_000
        manager.maintain()
        assertEquals(0, transaction(moderation.db) { VoiceSessionsTable.selectAll().count() })
    }

    @Test
    fun `maintain removes report clips after 30 days and tolerates a missing directory`() {
        val day = 24 * 60 * 60_000L
        val a = player("A")
        val s = player("S", Position(10f, 100f, 0f))
        val secretA = connect(a, addrA)
        val secretS = connect(s, addrB)
        fun fileReport(): Int {
            manager.onDatagram(addrB, clientDatagram(s, secretS, SvcPacket.Mic(byteArrayOf(0x08), 1, false)))
            manager.onDatagram(addrA, clientDatagram(a, secretA, SvcPacket.Mic(byteArrayOf(0x08), 1, false)))
            manager.report(a, "S", "spam")
            val result = last<Packet.Result>(a)
            assertTrue(result.ok, result.message)
            return result.message.removePrefix("Report #").substringBefore(' ').toInt()
        }

        val id = fileReport()
        val dir = File(reportDir, id.toString())
        now += 29 * day
        manager.maintain()
        assertTrue(File(dir, "target.opus").exists(), "29 days: clips stay")

        now += 2 * day
        manager.maintain()
        assertFalse(dir.exists(), "31 days: clips gone")
        assertNotNull(moderation.reportParties(id), "row stays readable")

        val second = fileReport()
        File(reportDir, second.toString()).deleteRecursively()
        now += 31 * day
        manager.maintain()
        assertNotNull(moderation.reportParties(second))
    }
}
