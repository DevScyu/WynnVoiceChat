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

    /** Takes a slot and returns null, or names the limit that refused it. */
    fun refusal(ip: String, currentHandshaking: Int): Limit? {
        if (currentHandshaking >= maxHandshaking) return Limit.HANDSHAKING
        val state = ipStates.computeIfAbsent(ip) { IpState(0, maxPerMinutePerIp, clock()) }
        synchronized(state) {
            val now = clock()
            val refill = ((now - state.lastRefillMs) * maxPerMinutePerIp / 60_000.0).toInt()
            if (refill > 0) {
                state.tokens = minOf(maxPerMinutePerIp, state.tokens + refill)
                state.lastRefillMs = now
            }
            if (state.concurrent >= maxConcurrentPerIp) return Limit.CONCURRENT_PER_IP
            if (state.tokens <= 0) return Limit.PER_MINUTE_PER_IP
            state.tokens--
            state.concurrent++
            return null
        }
    }

    fun release(ip: String) {
        val state = ipStates[ip] ?: return
        synchronized(state) { state.concurrent = maxOf(0, state.concurrent - 1) }
    }

    fun cleanup() {
        val now = clock()
        ipStates.entries.removeIf { (_, state) ->
            synchronized(state) { state.concurrent == 0 && now - state.lastRefillMs >= 60_000 }
        }
    }
}
