package wynnvoice.server.voice

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wynnvoice.protocol.Packet
import wynnvoice.protocol.VoiceTier
import wynnvoice.server.voice.svc.SvcCrypto

class VoiceUdpServerTest {
    private var server: VoiceUdpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
    }

    private fun uuidBytes(u: UUID) = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()

    private fun clientDatagram(uuid: UUID, secret: ByteArray, plain: ByteArray): ByteArray {
        val enc = SvcCrypto.encrypt(secret, plain)
        return byteArrayOf(0xFF.toByte()) + uuidBytes(uuid) + byteArrayOf(enc.size.toByte()) + enc
    }

    @Test
    fun `handshake over a real socket`() {
        val config = VoiceConfig(true, true, "127.0.0.1", 0, "127.0.0.1", 32.0, 1000, "build/tmp/udp-reports", 1_000_000)
        val tcp = ArrayList<Packet>()
        lateinit var manager: VoiceManager
        val udp = VoiceUdpServer(config.bindAddress, config.port) { address, bytes -> manager.onDatagram(address, bytes) }
        manager = VoiceManager(config, testModeration("udp-server-test"), udp, { CompletableFuture.completedFuture(null) })
        udp.start()
        server = udp

        val uuid = UUID.randomUUID()
        manager.join(Player(uuid, "A") { tcp.add(it) }, 20, VoiceTier.EVERYONE, "")
        val secret = (tcp.single() as Packet.Secret).secret

        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            val target = InetSocketAddress(InetAddress.getByName("127.0.0.1"), udp.boundPort)

            fun exchange(plain: ByteArray): Int {
                val out = clientDatagram(uuid, secret, plain)
                socket.send(DatagramPacket(out, out.size, target))
                val buf = ByteArray(2048)
                val reply = DatagramPacket(buf, buf.size)
                socket.receive(reply)
                val bytes = buf.copyOf(reply.length)
                assertEquals(0xFF.toByte(), bytes[0])
                return SvcCrypto.decrypt(secret, bytes.copyOfRange(2, bytes.size))!![0].toInt()
            }

            assertEquals(0x6, exchange(byteArrayOf(0x5) + uuidBytes(uuid) + secret), "AuthenticateAck")
            assertEquals(0xA, exchange(byteArrayOf(0x9)), "ConnectionCheckAck")
        }
        assertTrue(manager.sessionOf(uuid)!!.connected)
    }
}
