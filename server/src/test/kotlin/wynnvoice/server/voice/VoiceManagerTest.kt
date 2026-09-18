package wynnvoice.server.voice

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wynnvoice.protocol.EndReason
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Packet.Position
import wynnvoice.protocol.Relation
import wynnvoice.protocol.VoiceTier
import wynnvoice.server.voice.svc.SvcCodec
import wynnvoice.server.voice.svc.SvcCrypto
import wynnvoice.server.voice.svc.SvcPacket

class VoiceManagerTest {
    private class FakeTransport : VoiceTransport {
        val sent = ArrayList<Pair<InetSocketAddress, ByteArray>>()
        override fun send(address: InetSocketAddress, bytes: ByteArray) { sent.add(address to bytes) }
        fun clear() = sent.clear()
    }

    private val transport = FakeTransport()
    private val tcp = HashMap<UUID, ArrayList<Packet>>()
    private var now = 1_000_000L
    private val config = VoiceConfig(
        enabled = true, everyoneEnabled = true, host = "voice.test", port = 24454, bindAddress = "127.0.0.1",
        range = 32.0, keepAliveMs = 1000, reportDir = "build/tmp/voice-reports-test", ringBufferCapBytes = 1_000_000,
    )
    private lateinit var manager: VoiceManager

    private fun manager(config: VoiceConfig = this.config) = VoiceManager(config, transport) { now }

    private fun player(name: String, position: Position? = Position(0f, 100f, 0f), world: String? = "WC1"): Player {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        tcp[uuid] = ArrayList()
        return Player(uuid, name) { tcp[uuid]!!.add(it) }.also {
            it.world = world
            it.position = position
        }
    }

    private fun sentTo(player: Player) = tcp[player.uuid]!!
    private inline fun <reified P> last(player: Player): P = sentTo(player).last() as P

    @BeforeTest
    fun setUp() {
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
        val a = player("A").also { it.friends = setOf("F", "OneSided"); it.guildMembers = setOf("A", "G") }
        val f = player("F", Position(10f, 100f, 0f)).also { it.friends = setOf("A") }
        val g = player("G", Position(10f, 100f, 0f)).also { it.guildMembers = setOf("A", "G") }
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
    fun `leave from a stale connection keeps the newer session`() {
        val old = player("A")
        connect(old, addrA)
        val fresh = player("A")
        manager.join(fresh, 20, VoiceTier.EVERYONE, "")
        manager.leave(old)
        assertEquals(fresh, manager.sessionOf(fresh.uuid)!!.player)
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
}
