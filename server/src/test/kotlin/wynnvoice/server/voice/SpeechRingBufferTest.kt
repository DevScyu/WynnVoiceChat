package wynnvoice.server.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechRingBufferTest {
    @Test
    fun `keeps newest frames up to capacity`() {
        val buffer = SpeechRingBuffer(maxFrames = 3)
        for (i in 1..5) buffer.push(i * 20L, byteArrayOf(i.toByte()))
        assertEquals(listOf(60L, 80L, 100L), buffer.snapshot().map { it.timestampMs })
        assertEquals(3L, buffer.bytes)
    }

    @Test
    fun `ignores empty frames`() {
        val buffer = SpeechRingBuffer(maxFrames = 3)
        buffer.push(0, ByteArray(0))
        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0L, buffer.bytes)
    }

    @Test
    fun `bytes tracks eviction`() {
        val buffer = SpeechRingBuffer(maxFrames = 2)
        buffer.push(0, ByteArray(10))
        buffer.push(20, ByteArray(20))
        buffer.push(40, ByteArray(30))
        assertEquals(50L, buffer.bytes)
        assertEquals(20L, buffer.oldestTimestamp())
    }

    @Test
    fun `clear resets`() {
        val buffer = SpeechRingBuffer()
        buffer.push(0, ByteArray(10))
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
        assertEquals(0L, buffer.bytes)
        assertNull(buffer.oldestTimestamp())
    }
}
