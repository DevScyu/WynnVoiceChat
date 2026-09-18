package wynnvoicechat.server.voice.svc

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import java.util.UUID

/**
 * The subset of Simple Voice Chat's UDP packets a relay needs. Client→server types are decoded,
 * server→client types are encoded; channelId is always the sender (stock server behaviour).
 */
sealed class SvcPacket {
    class Mic(val data: ByteArray, val sequence: Long, val whispering: Boolean) : SvcPacket()
    class GroupSound(val sender: UUID, val data: ByteArray, val sequence: Long) : SvcPacket()
    class LocationSound(
        val sender: UUID, val x: Double, val y: Double, val z: Double,
        val data: ByteArray, val sequence: Long, val distance: Float,
    ) : SvcPacket()
    class Authenticate(val playerUuid: UUID, val secret: ByteArray) : SvcPacket()
    object AuthenticateAck : SvcPacket()
    class Ping(val id: UUID, val timestamp: Long) : SvcPacket()
    object KeepAlive : SvcPacket()
    object ConnectionCheck : SvcPacket()
    object ConnectionCheckAck : SvcPacket()
}

object SvcCodec {
    const val MAGIC: Byte = 0xFF.toByte()
    const val MAX_OPUS_PAYLOAD = 1275
    const val MAX_PACKET = 2048

    private const val TYPE_MIC: Byte = 0x1
    private const val TYPE_GROUP_SOUND: Byte = 0x3
    private const val TYPE_LOCATION_SOUND: Byte = 0x4
    private const val TYPE_AUTHENTICATE: Byte = 0x5
    private const val TYPE_AUTHENTICATE_ACK: Byte = 0x6
    private const val TYPE_PING: Byte = 0x7
    private const val TYPE_KEEP_ALIVE: Byte = 0x8
    private const val TYPE_CONNECTION_CHECK: Byte = 0x9
    private const val TYPE_CONNECTION_CHECK_ACK: Byte = 0xA

    class ClientDatagram(val playerUuid: UUID, val encryptedPayload: ByteArray)

    fun readClientDatagram(bytes: ByteArray): ClientDatagram? {
        if (bytes.size < 18 || bytes[0] != MAGIC) return null
        return try {
            val buf = Unpooled.wrappedBuffer(bytes)
            buf.readByte()
            ClientDatagram(buf.readUuid(), buf.readByteArray(MAX_PACKET))
        } catch (e: Exception) {
            null
        }
    }

    fun decode(plain: ByteArray): SvcPacket? {
        if (plain.isEmpty()) return null
        return try {
            val buf = Unpooled.wrappedBuffer(plain)
            when (buf.readByte()) {
                TYPE_MIC -> SvcPacket.Mic(buf.readByteArray(MAX_OPUS_PAYLOAD), buf.readLong(), buf.readBoolean())
                TYPE_AUTHENTICATE -> SvcPacket.Authenticate(buf.readUuid(), ByteArray(SvcCrypto.SECRET_SIZE).also(buf::readBytes))
                TYPE_PING -> SvcPacket.Ping(buf.readUuid(), buf.readLong())
                TYPE_KEEP_ALIVE -> SvcPacket.KeepAlive
                TYPE_CONNECTION_CHECK -> SvcPacket.ConnectionCheck
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun encode(packet: SvcPacket): ByteArray {
        val buf = Unpooled.buffer()
        when (packet) {
            is SvcPacket.GroupSound -> {
                buf.writeByte(TYPE_GROUP_SOUND.toInt())
                buf.writeUuid(packet.sender)
                buf.writeUuid(packet.sender)
                buf.writeByteArray(packet.data)
                buf.writeLong(packet.sequence)
                buf.writeByte(0x0)
            }
            is SvcPacket.LocationSound -> {
                buf.writeByte(TYPE_LOCATION_SOUND.toInt())
                buf.writeUuid(packet.sender)
                buf.writeUuid(packet.sender)
                buf.writeDouble(packet.x)
                buf.writeDouble(packet.y)
                buf.writeDouble(packet.z)
                buf.writeByteArray(packet.data)
                buf.writeLong(packet.sequence)
                buf.writeFloat(packet.distance)
                buf.writeByte(0x0)
            }
            is SvcPacket.Ping -> {
                buf.writeByte(TYPE_PING.toInt())
                buf.writeUuid(packet.id)
                buf.writeLong(packet.timestamp)
            }
            SvcPacket.AuthenticateAck -> buf.writeByte(TYPE_AUTHENTICATE_ACK.toInt())
            SvcPacket.KeepAlive -> buf.writeByte(TYPE_KEEP_ALIVE.toInt())
            SvcPacket.ConnectionCheckAck -> buf.writeByte(TYPE_CONNECTION_CHECK_ACK.toInt())
            else -> throw IllegalArgumentException("Relay never sends ${packet::class.simpleName}")
        }
        return ByteBufUtil.getBytes(buf)
    }

    fun writeServerDatagram(encryptedPayload: ByteArray): ByteArray {
        val buf = Unpooled.buffer()
        buf.writeByte(MAGIC.toInt())
        buf.writeByteArray(encryptedPayload)
        return ByteBufUtil.getBytes(buf)
    }

    private fun ByteBuf.readUuid() = UUID(readLong(), readLong())

    private fun ByteBuf.writeUuid(uuid: UUID) {
        writeLong(uuid.mostSignificantBits)
        writeLong(uuid.leastSignificantBits)
    }

    private fun ByteBuf.readByteArray(max: Int): ByteArray {
        val length = readVarInt()
        if (length < 0 || length > max || readableBytes() < length) throw DecoderException("Bad byte array length $length")
        return ByteArray(length).also(::readBytes)
    }

    private fun ByteBuf.writeByteArray(bytes: ByteArray) {
        writeVarInt(bytes.size)
        writeBytes(bytes)
    }

    private fun ByteBuf.readVarInt(): Int {
        var value = 0
        var shift = 0
        while (shift < 35) {
            val b = readByte().toInt()
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
        }
        throw DecoderException("VarInt too long")
    }

    private fun ByteBuf.writeVarInt(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        writeByte(v)
    }
}
