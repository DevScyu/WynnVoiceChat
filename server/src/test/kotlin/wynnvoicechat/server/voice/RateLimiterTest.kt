package wynnvoicechat.server.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    @Test
    fun `allows max per window then refills on the next window`() {
        val limiter = RateLimiter(max = 2, windowMs = 1000)
        assertTrue(limiter.tryAcquire(0))
        assertTrue(limiter.tryAcquire(500))
        assertFalse(limiter.tryAcquire(999))
        assertTrue(limiter.tryAcquire(1000))
    }
}
