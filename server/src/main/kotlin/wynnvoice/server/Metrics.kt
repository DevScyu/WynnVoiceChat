package wynnvoice.server

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmInfoMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.netty4.NettyAllocatorMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.netty.buffer.PooledByteBufAllocator
import java.net.InetSocketAddress
import java.time.Duration
import java.util.EnumMap
import org.slf4j.LoggerFactory
import wynnvoice.protocol.Protocol
import wynnvoice.server.voice.VoiceConfig

/**
 * Process-wide Prometheus registry, JVM/Netty binders and the loopback `/metrics` endpoint.
 * Hot paths cache their counters as fields; nothing here carries player identity.
 */
object Metrics {
    private val log = LoggerFactory.getLogger(Metrics::class.java)

    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val HTTP_SLO = doubleArrayOf(0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
    val DB_SLO = doubleArrayOf(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0)
    val RELAY_SLO = doubleArrayOf(0.0001, 0.00025, 0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025)
    val SESSION_SLO = doubleArrayOf(30.0, 60.0, 300.0, 900.0, 1800.0, 3600.0, 7200.0, 14400.0)

    val version: String = Metrics::class.java.`package`.implementationVersion ?: "dev"

    init {
        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ClassLoaderMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)
        FileDescriptorMetrics().bindTo(registry)
        JvmInfoMetrics().bindTo(registry)
        NettyAllocatorMetrics(PooledByteBufAllocator.DEFAULT).bindTo(registry)
        Gauge.builder("voice.build.info") { 1 }
            .tags("version", version, "protocol_version", Protocol.VERSION.toString(), "svc_compat", VoiceConfig.SUPPORTED_SVC_COMPAT.toString())
            .register(registry)
    }

    /** One counter per enum value, created up front so `rate()` works from the first event. */
    inline fun <reified E : Enum<E>> MeterRegistry.counters(name: String, tag: String, vararg tags: String): EnumMap<E, Counter> =
        enumValues<E>().associateWithTo(EnumMap(E::class.java)) { counter(name, tag, it.name.lowercase(), *tags) }

    inline fun <reified A : Enum<A>, reified B : Enum<B>> MeterRegistry.counters(name: String, tagA: String, tagB: String): EnumMap<A, EnumMap<B, Counter>> =
        enumValues<A>().associateWithTo(EnumMap(A::class.java)) { counters<B>(name, tagB, tagA, it.name.lowercase()) }

    fun MeterRegistry.sloTimer(name: String, sloSeconds: DoubleArray, vararg tags: String): Timer =
        Timer.builder(name)
            .serviceLevelObjectives(*sloSeconds.map { Duration.ofNanos((it * 1e9).toLong()) }.toTypedArray())
            .tags(*tags)
            .register(this)

    enum class HttpOutcome { OK, NOT_FOUND, RATE_LIMITED, ERROR }

    /** `<name>{endpoint,outcome}` timers with the HTTP buckets, one per endpoint × outcome. */
    fun httpTimers(name: String, vararg endpoints: String): Map<String, EnumMap<HttpOutcome, Timer>> = endpoints.associateWith { endpoint ->
        HttpOutcome.entries.associateWithTo(EnumMap(HttpOutcome::class.java)) {
            registry.sloTimer(name, HTTP_SLO, "endpoint", endpoint, "outcome", it.name.lowercase())
        }
    }

    fun httpOutcome(status: Int): HttpOutcome = when (status) {
        in 200..299 -> HttpOutcome.OK
        404 -> HttpOutcome.NOT_FOUND
        429 -> HttpOutcome.RATE_LIMITED
        else -> HttpOutcome.ERROR
    }

    fun scrape(): String = registry.scrape()

    fun start(bind: String, port: Int): HttpServer = HttpServer.create(InetSocketAddress(bind, port), 0).apply {
        createContext("/metrics") { exchange ->
            exchange.use {
                val body = registry.scrape().toByteArray()
                it.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        start()
        log.info("Metrics on http://{}:{}/metrics", bind, address.port)
    }
}
