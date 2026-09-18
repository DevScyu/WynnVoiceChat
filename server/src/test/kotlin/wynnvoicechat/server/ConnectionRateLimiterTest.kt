package wynnvoicechat.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionRateLimiterTest {
    private var now = 0L
    private val limiter = ConnectionRateLimiter(maxConcurrentPerIp = 2, maxPerMinutePerIp = 3, maxHandshaking = 5) { now }

    @Test
    fun `concurrent connections per ip are capped and released`() {
        assertTrue(limiter.tryAcquire("1.1.1.1", 0))
        assertTrue(limiter.tryAcquire("1.1.1.1", 0))
        assertFalse(limiter.tryAcquire("1.1.1.1", 0))
        assertTrue(limiter.tryAcquire("2.2.2.2", 0))
        limiter.release("1.1.1.1")
        assertTrue(limiter.tryAcquire("1.1.1.1", 0))
    }

    @Test
    fun `per minute budget refills with time`() {
        repeat(3) {
            assertTrue(limiter.tryAcquire("1.1.1.1", 0))
            limiter.release("1.1.1.1")
        }
        assertFalse(limiter.tryAcquire("1.1.1.1", 0))
        now += 20_000
        assertTrue(limiter.tryAcquire("1.1.1.1", 0))
        assertFalse(limiter.tryAcquire("1.1.1.1", 0))
    }

    @Test
    fun `global handshaking cap refuses everyone`() {
        assertFalse(limiter.tryAcquire("1.1.1.1", 5))
        assertTrue(limiter.tryAcquire("1.1.1.1", 4))
    }
}
