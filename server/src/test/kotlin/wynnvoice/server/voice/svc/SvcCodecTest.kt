package wynnvoice.server.voice.svc

import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SvcCodecTest {
    private val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    private fun uuidBytes(u: UUID): ByteArray = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    private fun long(v: Long): ByteArray = ByteBuffer.allocate(8).putLong(v).array()
    private fun float(v: Float): ByteArray = ByteBuffer.allocate(4).putFloat(v).array()
    private fun double(v: Double): ByteArray = ByteBuffer.allocate(8).putDouble(v).array()

    @Test
    fun `read client datagram`() {
        val payload = byteArrayOf(10, 20, 30)
        val parsed = SvcCodec.readClientDatagram(byteArrayOf(0xFF.toByte()) + uuidBytes(uuid) + byteArrayOf(3) + payload)!!
        assertEquals(uuid, parsed.playerUuid)
        assertContentEquals(payload, parsed.encryptedPayload)
    }

    @Test
    fun `client datagram with bad magic or truncation is null`() {
        assertNull(SvcCodec.readClientDatagram(byteArrayOf(0x00) + uuidBytes(uuid) + byteArrayOf(0)))
        assertNull(SvcCodec.readClientDatagram(byteArrayOf(0xFF.toByte(), 1, 2)))
        assertNull(SvcCodec.readClientDatagram(byteArrayOf(0xFF.toByte()) + uuidBytes(uuid) + byteArrayOf(5, 1)))
    }

    @Test
    fun `decode mic`() {
        val mic = SvcCodec.decode(byteArrayOf(0x1, 3, 7, 8, 9) + long(42) + byteArrayOf(1)) as SvcPacket.Mic
        assertContentEquals(byteArrayOf(7, 8, 9), mic.data)
        assertEquals(42L, mic.sequence)
        assertTrue(mic.whispering)
    }

    @Test
    fun `decode authenticate`() {
        val secret = ByteArray(16) { it.toByte() }
        val auth = SvcCodec.decode(byteArrayOf(0x5) + uuidBytes(uuid) + secret) as SvcPacket.Authenticate
        assertEquals(uuid, auth.playerUuid)
        assertContentEquals(secret, auth.secret)
    }

    @Test
    fun `decode ping keep alive and connection check`() {
        val ping = SvcCodec.decode(byteArrayOf(0x7) + uuidBytes(uuid) + long(99)) as SvcPacket.Ping
        assertEquals(uuid, ping.id)
        assertEquals(99L, ping.timestamp)
        assertSame(SvcPacket.KeepAlive, SvcCodec.decode(byteArrayOf(0x8)))
        assertSame(SvcPacket.ConnectionCheck, SvcCodec.decode(byteArrayOf(0x9)))
    }

    @Test
    fun `decode unknown or truncated is null`() {
        assertNull(SvcCodec.decode(byteArrayOf(0x2)))
        assertNull(SvcCodec.decode(byteArrayOf(0x7F)))
        assertNull(SvcCodec.decode(byteArrayOf(0x1, 5, 1)))
        assertNull(SvcCodec.decode(ByteArray(0)))
    }

    @Test
    fun `encode group sound`() {
        val expected = byteArrayOf(0x3) + uuidBytes(uuid) + uuidBytes(uuid) + byteArrayOf(1, 9) + long(1) + byteArrayOf(0x0)
        assertContentEquals(expected, SvcCodec.encode(SvcPacket.GroupSound(uuid, byteArrayOf(9), 1)))
    }

    @Test
    fun `encode location sound`() {
        val expected = byteArrayOf(0x4) + uuidBytes(uuid) + uuidBytes(uuid) + double(1.5) + double(64.0) + double(-2.25) +
            byteArrayOf(2, 4, 5) + long(3) + float(16f) + byteArrayOf(0x0)
        assertContentEquals(expected, SvcCodec.encode(SvcPacket.LocationSound(uuid, 1.5, 64.0, -2.25, byteArrayOf(4, 5), 3, 16f)))
    }

    @Test
    fun `encode control packets`() {
        assertContentEquals(byteArrayOf(0x6), SvcCodec.encode(SvcPacket.AuthenticateAck))
        assertContentEquals(byteArrayOf(0x8), SvcCodec.encode(SvcPacket.KeepAlive))
        assertContentEquals(byteArrayOf(0xA), SvcCodec.encode(SvcPacket.ConnectionCheckAck))
        assertContentEquals(byteArrayOf(0x7) + uuidBytes(uuid) + long(5), SvcCodec.encode(SvcPacket.Ping(uuid, 5)))
    }

    @Test
    fun `encode rejects client only packets`() {
        assertFailsWith<IllegalArgumentException> { SvcCodec.encode(SvcPacket.ConnectionCheck) }
    }

    @Test
    fun `write server datagram`() {
        assertContentEquals(byteArrayOf(0xFF.toByte(), 2, 8, 9), SvcCodec.writeServerDatagram(byteArrayOf(8, 9)))
    }
}
