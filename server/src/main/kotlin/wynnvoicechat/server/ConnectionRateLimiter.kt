package wynnvoicechat.server

import java.util.concurrent.ConcurrentHashMap

class ConnectionRateLimiter(
    private val maxConcurrentPerIp: Int = 5,
    private val maxPerMinutePerIp: Int = 15,
    private val maxHandshaking: Int = 500,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Limit { CONCURRENT_PER_IP, PER_MINUTE_PER_IP, HANDSHAKING }

    private class IpState(var concurrent: Int, var tokens: Int, var lastRefillMs: Long)

    private val ipStates = ConcurrentHashMap<String, IpState>()

    fun tryAcquire(ip: String, currentHandshaking: Int): Boolean = refusal(ip, currentHandshaking) == null

    /** Takes a slot and returns null, or names the limit that refused it. All state changes run inside the map's own per-key lock. */
    fun refusal(ip: String, currentHandshaking: Int): Limit? {
        if (currentHandshaking >= maxHandshaking) return Limit.HANDSHAKING
        var limit: Limit? = null
        ipStates.compute(ip) { _, existing ->
            val now = clock()
            val state = existing ?: IpState(0, maxPerMinutePerIp, now)
            val refill = ((now - state.lastRefillMs) * maxPerMinutePerIp / 60_000.0).toInt()
            if (refill > 0) {
                state.tokens = minOf(maxPerMinutePerIp, state.tokens + refill)
                state.lastRefillMs = now
            }
            limit = when {
                state.concurrent >= maxConcurrentPerIp -> Limit.CONCURRENT_PER_IP
                state.tokens <= 0 -> Limit.PER_MINUTE_PER_IP
                else -> {
                    state.tokens--
                    state.concurrent++
                    null
                }
            }
            state
        }
        return limit
    }

    fun release(ip: String) {
        ipStates.computeIfPresent(ip) { _, state -> state.also { it.concurrent = maxOf(0, it.concurrent - 1) } }
    }

    fun cleanup() {
        val now = clock()
        for (ip in ipStates.keys) ipStates.computeIfPresent(ip) { _, state -> state.takeUnless { it.concurrent == 0 && now - it.lastRefillMs >= 60_000 } }
    }
}
