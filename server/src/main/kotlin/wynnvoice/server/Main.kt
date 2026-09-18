package wynnvoice.server

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

fun main() {
    val server = ControlServer(
        host = env("CONTROL_HOST", "0.0.0.0"),
        port = env("CONTROL_PORT", "9100").toInt(),
        sessions = MojangSessionFetcher(),
        rateLimiter = ConnectionRateLimiter(
            maxConcurrentPerIp = env("RATE_LIMIT_MAX_CONCURRENT_PER_IP", "5").toInt(),
            maxPerMinutePerIp = env("RATE_LIMIT_MAX_PER_MINUTE_PER_IP", "15").toInt(),
            maxHandshaking = env("RATE_LIMIT_MAX_HANDSHAKING", "500").toInt(),
        ),
    )
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread(server::stop))
    server.awaitClose()
}
