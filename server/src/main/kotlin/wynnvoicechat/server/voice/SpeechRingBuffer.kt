package wynnvoicechat.server.voice

/**
 * The last [maxFrames] Opus frames a speaker sent (6000 × 20 ms = 120 s of speech).
 * Silence costs nothing: SVC clients send no frames while the mic is inactive.
 */
class SpeechRingBuffer(private val maxFrames: Int = 6000) {
    class Frame(val timestampMs: Long, val opus: ByteArray)

    private val frames = ArrayDeque<Frame>()

    @Volatile
    var bytes: Long = 0
        private set

    @Synchronized
    fun push(timestampMs: Long, opus: ByteArray) {
        if (opus.isEmpty()) return
        frames.addLast(Frame(timestampMs, opus))
        bytes += opus.size
        while (frames.size > maxFrames) {
            bytes -= frames.removeFirst().opus.size
        }
    }

    @Synchronized
    fun snapshot(): List<Frame> = frames.toList()

    @Synchronized
    fun oldestTimestamp(): Long? = frames.firstOrNull()?.timestampMs

    @Synchronized
    fun clear() {
        frames.clear()
        bytes = 0
    }
}
