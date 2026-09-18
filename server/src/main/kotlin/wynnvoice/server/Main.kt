package wynnvoice.server

import org.slf4j.LoggerFactory
import wynnvoice.server.voice.VoiceConfig
import wynnvoice.server.voice.VoiceManager
import wynnvoice.server.voice.VoiceUdpServer

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

fun main() {
    val log = LoggerFactory.getLogger("wynnvoice")
    val voiceConfig = VoiceConfig.fromEnv()
    lateinit var voice: VoiceManager
    val udp = VoiceUdpServer(voiceConfig.bindAddress, voiceConfig.port) { address, bytes -> voice.onDatagram(address, bytes) }
    voice = VoiceManager(voiceConfig, udp)
    val server = ControlServer(
        host = env("CONTROL_HOST", "0.0.0.0"),
        port = env("CONTROL_PORT", "9100").toInt(),
        sessions = MojangSessionFetcher(),
        guilds = GuildResolver(HttpApiFetcher()),
        voice = voice,
        rateLimiter = ConnectionRateLimiter(
            maxConcurrentPerIp = env("RATE_LIMIT_MAX_CONCURRENT_PER_IP", "5").toInt(),
            maxPerMinutePerIp = env("RATE_LIMIT_MAX_PER_MINUTE_PER_IP", "15").toInt(),
            maxHandshaking = env("RATE_LIMIT_MAX_HANDSHAKING", "500").toInt(),
        ),
    )
    if (voiceConfig.enabled) {
        udp.start()
        voice.start()
        log.info("Voice relay enabled, advertising {}:{}, range {}", voiceConfig.host, voiceConfig.port, voiceConfig.range)
    } else {
        log.warn("VOICE_ENABLED is not true: every client is refused with DISABLED")
    }
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        voice.stop()
        udp.stop()
    })
    server.awaitClose()
}
