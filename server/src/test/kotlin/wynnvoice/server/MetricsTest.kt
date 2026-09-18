package wynnvoice.server

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wynnvoice.protocol.AuthStatus
import wynnvoice.server.Metrics.counters
import wynnvoice.server.Metrics.sloTimer

class MetricsTest {
    private enum class Side { LEFT, RIGHT }

    @Test
    fun `labelled counters exist for every enum value before any event`() {
        val auth = Metrics.registry.counters<AuthStatus>("voice_test_auth_total", "result")
        auth.getValue(AuthStatus.BAD_SESSION).increment()
        val both = Metrics.registry.counters<AuthStatus, Side>("voice_test_pair_total", "result", "side")
        both.getValue(AuthStatus.OK).getValue(Side.RIGHT).increment(2.0)

        val scrape = Metrics.scrape()
        assertEquals("1.0", sample(scrape, "voice_test_auth_total{result=\"bad_session\"}"), scrape)
        assertEquals("0.0", sample(scrape, "voice_test_auth_total{result=\"session_unavailable\"}"), scrape)
        assertEquals("2.0", sample(scrape, "voice_test_pair_total{result=\"ok\",side=\"right\"}"), scrape)
        assertEquals("0.0", sample(scrape, "voice_test_pair_total{result=\"banned\",side=\"left\"}"), scrape)
    }

    @Test
    fun `slo timers expose exactly their buckets`() {
        Metrics.registry.sloTimer("voice_test_seconds", Metrics.DB_SLO).record(java.time.Duration.ofMillis(3))

        val scrape = Metrics.scrape()
        assertEquals("1", sample(scrape, "voice_test_seconds_bucket{le=\"0.005\"}"), scrape)
        assertEquals("0", sample(scrape, "voice_test_seconds_bucket{le=\"0.001\"}"), scrape)
        assertEquals("1", sample(scrape, "voice_test_seconds_bucket{le=\"+Inf\"}"), scrape)
        assertEquals(Metrics.DB_SLO.size + 1, Regex("^voice_test_seconds_bucket", RegexOption.MULTILINE).findAll(scrape).count())
    }

    @Test
    fun `runtime binders and build info are present`() {
        val scrape = Metrics.scrape()
        for (series in listOf("jvm_memory_used_bytes", "jvm_gc_", "jvm_threads_live_threads", "jvm_classes_loaded_classes",
            "process_cpu_usage", "process_uptime_seconds", "process_files_open_files", "jvm_info", "netty_allocator_memory_used")) {
            assertTrue(scrape.contains(series), series)
        }
        assertTrue(Regex("^voice_build_info\\{.*protocol_version=\"1\".*svc_compat=\"20\".*version=\"dev\".*} 1$", RegexOption.MULTILINE).containsMatchIn(scrape), scrape)
    }

    @Test
    fun `metrics endpoint serves the scrape on loopback`() {
        val server = Metrics.start("127.0.0.1", 0)
        try {
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertEquals("text/plain; version=0.0.4; charset=utf-8", response.headers().firstValue("Content-Type").get())
            assertTrue(response.body().contains("jvm_memory_used_bytes"))
        } finally {
            server.stop(0)
        }
    }

    companion object {
        fun sample(scrape: String, series: String): String? =
            Regex("^" + Regex.escape(series) + " (\\S+)$", RegexOption.MULTILINE).find(scrape)?.groupValues?.get(1)
    }
}
