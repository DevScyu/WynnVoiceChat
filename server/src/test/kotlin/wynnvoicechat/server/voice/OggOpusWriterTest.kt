package wynnvoicechat.server.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OggOpusWriterTest {
    private class Page(val headerType: Int, val granule: Long, val crc: Int, val packets: List<ByteArray>, val raw: ByteArray)

    private fun parsePages(bytes: ByteArray): List<Page> {
        val pages = ArrayList<Page>()
        var pos = 0
        while (pos < bytes.size) {
            assertEquals("OggS", String(bytes, pos, 4, Charsets.US_ASCII), "page magic at $pos")
            val h = ByteBuffer.wrap(bytes, pos, 27).order(ByteOrder.LITTLE_ENDIAN)
            h.position(pos + 5)
            val headerType = h.get().toInt()
            val granule = h.long
            h.int // serial
            h.int // sequence
            val crc = h.int
            val segments = h.get().toInt() and 0xFF
            val lacing = bytes.copyOfRange(pos + 27, pos + 27 + segments).map { it.toInt() and 0xFF }
            var bodyPos = pos + 27 + segments
            val packets = ArrayList<ByteArray>()
            var current = ByteArrayOutputStream()
            for (l in lacing) {
                current.write(bytes, bodyPos, l)
                bodyPos += l
                if (l < 255) {
                    packets.add(current.toByteArray())
                    current = ByteArrayOutputStream()
                }
            }
            pages.add(Page(headerType, granule, crc, packets, bytes.copyOfRange(pos, bodyPos)))
            pos = bodyPos
        }
        return pages
    }

    private val crcTable = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    private fun referenceCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor crcTable[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }

    private fun frames(n: Int) = List(n) { SpeechRingBuffer.Frame(it * 20L, byteArrayOf(0x08)) }

    private fun write(n: Int) = ByteArrayOutputStream().also { OggOpusWriter.write(frames(n), it) }.toByteArray()

    @Test
    fun `headers and crc`() {
        val pages = parsePages(write(3))

        assertEquals(0x02, pages[0].headerType and 0x02, "first page is BOS")
        val head = pages[0].packets.single()
        assertEquals(19, head.size)
        assertEquals("OpusHead", String(head, 0, 8, Charsets.US_ASCII))
        assertEquals(1, head[8].toInt())
        assertEquals(1, head[9].toInt())
        assertEquals(48000, ByteBuffer.wrap(head, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals("OpusTags", String(pages[1].packets.single(), 0, 8, Charsets.US_ASCII))

        for (page in pages) {
            val zeroed = page.raw.copyOf()
            for (i in 22..25) zeroed[i] = 0
            assertEquals(referenceCrc(zeroed), page.crc)
        }
    }

    @Test
    fun `audio pages carry all frames and granule`() {
        val pages = parsePages(write(100))
        val audioPages = pages.drop(2)
        assertEquals(100, audioPages.sumOf { it.packets.size })
        assertTrue(audioPages.all { it.packets.size <= 40 })
        assertEquals(0x04, pages.last().headerType and 0x04, "last page is EOS")
        assertEquals(100L * 960, pages.last().granule)
        assertEquals(0L, pages[0].granule)
    }

    @Test
    fun `empty input still produces a valid stream`() {
        val pages = parsePages(write(0))
        assertEquals(2, pages.size)
        assertEquals(0x04, pages.last().headerType and 0x04)
    }
}
