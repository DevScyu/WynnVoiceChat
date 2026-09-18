package wynnvoice.server.voice

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * Minimal Ogg Opus muxer (RFC 3533 + RFC 7845) for 48 kHz mono 20 ms frames.
 * ponytail: gaps between frames are dropped, speech is concatenated; report rows keep the first/last timestamps.
 */
object OggOpusWriter {
    private const val SAMPLES_PER_FRAME = 960L
    private const val FRAMES_PER_PAGE = 40 // ≤ 40 × 6 lacing values = 240 < 255 segments
    private const val FLAG_BOS = 0x02
    private const val FLAG_EOS = 0x04

    fun write(frames: List<SpeechRingBuffer.Frame>, out: OutputStream) {
        val serial = Random.nextInt()
        var sequence = 0
        out.write(page(serial, sequence++, 0, FLAG_BOS, listOf(opusHead())))

        val chunks = frames.chunked(FRAMES_PER_PAGE)
        out.write(page(serial, sequence++, 0, if (chunks.isEmpty()) FLAG_EOS else 0, listOf(opusTags())))

        var granule = 0L
        chunks.forEachIndexed { index, chunk ->
            granule += chunk.size * SAMPLES_PER_FRAME
            val flags = if (index == chunks.lastIndex) FLAG_EOS else 0
            out.write(page(serial, sequence++, granule, flags, chunk.map { it.opus }))
        }
    }

    private fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray(StandardCharsets.US_ASCII))
        .put(1)          // version
        .put(1)          // channel count
        .putShort(0)     // pre-skip
        .putInt(48000)   // input sample rate
        .putShort(0)     // output gain
        .put(0)          // channel mapping family
        .array()

    private fun opusTags(): ByteArray {
        val vendor = "wynnvoice".toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(8 + 4 + vendor.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".toByteArray(StandardCharsets.US_ASCII))
            .putInt(vendor.size).put(vendor)
            .putInt(0) // user comment count
            .array()
    }

    private fun page(serial: Int, sequence: Int, granule: Long, flags: Int, packets: List<ByteArray>): ByteArray {
        val lacing = ArrayList<Int>()
        for (packet in packets) {
            var remaining = packet.size
            while (remaining >= 255) {
                lacing.add(255)
                remaining -= 255
            }
            lacing.add(remaining)
        }
        require(lacing.size <= 255) { "too many segments for one page: ${lacing.size}" }

        val bodySize = packets.sumOf { it.size }
        val page = ByteBuffer.allocate(27 + lacing.size + bodySize).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray(StandardCharsets.US_ASCII))
            .put(0)                 // stream structure version
            .put(flags.toByte())
            .putLong(granule)
            .putInt(serial)
            .putInt(sequence)
            .putInt(0)              // CRC placeholder
            .put(lacing.size.toByte())
        lacing.forEach { page.put(it.toByte()) }
        packets.forEach { page.put(it) }

        val bytes = page.array()
        val crc = crc32(bytes)
        bytes[22] = crc.toByte()
        bytes[23] = (crc ushr 8).toByte()
        bytes[24] = (crc ushr 16).toByte()
        bytes[25] = (crc ushr 24).toByte()
        return bytes
    }

    private fun crc32(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return crc
    }
}
