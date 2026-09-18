package wynnvoicechat.server.voice

/**
 * Fixed-window counter: at most [max] acquisitions per [windowMs].
 */
class RateLimiter(private val max: Int, private val windowMs: Long) {
    private var windowStart = Long.MIN_VALUE
    private var count = 0

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        if (now >= windowStart + windowMs) {
            windowStart = now
            count = 0
        }
        if (count >= max) return false
        count++
        return true
    }
}
