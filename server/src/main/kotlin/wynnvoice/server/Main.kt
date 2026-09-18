package wynnvoice.server

import org.slf4j.LoggerFactory
import wynnvoice.server.discord.DiscordConfig
import wynnvoice.server.discord.DiscordModeration
import wynnvoice.server.discord.HttpDiscordRest
import wynnvoice.server.voice.VoiceConfig
import wynnvoice.server.voice.VoiceManager
import wynnvoice.server.voice.VoiceModeration
import wynnvoice.server.voice.VoiceUdpServer

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

fun main() {
    val log = LoggerFactory.getLogger("wynnvoice")
    val voiceConfig = VoiceConfig.fromEnv()
    val moderation = VoiceModeration(VoiceModeration.openSqlite(env("DB_PATH", "voice.db"))).also { it.init() }
    val api = HttpApiFetcher()
    lateinit var voice: VoiceManager
    val udp = VoiceUdpServer(voiceConfig.bindAddress, voiceConfig.port) { address, bytes -> voice.onDatagram(address, bytes) }
    voice = VoiceManager(voiceConfig, moderation, udp, api)
    val discord = DiscordConfig.fromEnv()?.let { DiscordModeration(it, voice, HttpDiscordRest(it.botToken)) }
    val server = ControlServer(
        host = env("CONTROL_HOST", "0.0.0.0"),
        port = env("CONTROL_PORT", "9100").toInt(),
        sessions = MojangSessionFetcher(),
        guilds = GuildResolver(api),
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
    if (discord != null) discord.start(env("HTTP_PORT", "9101").toInt())
    else log.warn("Discord moderation disabled: set {} to enable it", DiscordConfig.VARIABLES.joinToString(", "))
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        discord?.stop()
        voice.stop()
        udp.stop()
    })
    server.awaitClose()
}
