package wynnvoicechat.server

import org.slf4j.LoggerFactory
import wynnvoicechat.server.discord.DiscordConfig
import wynnvoicechat.server.discord.DiscordModeration
import wynnvoicechat.server.discord.HttpDiscordRest
import wynnvoicechat.server.voice.VoiceConfig
import wynnvoicechat.server.voice.VoiceManager
import wynnvoicechat.server.voice.VoiceModeration
import wynnvoicechat.server.voice.VoiceUdpServer

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

fun main() {
    val log = LoggerFactory.getLogger("wynnvoicechat")
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
        wynn = WynnApi(api),
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
    System.getenv("METRICS_PUSH_URL")?.takeIf { it.isNotBlank() }?.let { Metrics.push(it, env("METRICS_PUSH_TOKEN", ""), env("METRICS_PUSH_STEP", "30").toLong()) }
    val metrics = env("METRICS_PORT", "9102").toInt().takeIf { it != 0 }?.let { Metrics.start(env("METRICS_BIND", "127.0.0.1"), it) }
    server.start()
    // ponytail: Pterodactyl stops Minecraft-style eggs by typing "stop" on stdin; exiting runs the shutdown hook
    Thread { generateSequence(::readlnOrNull).firstOrNull { it.trim() == "stop" }?.let { System.exit(0) } }.apply { isDaemon = true }.start()
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        discord?.stop()
        metrics?.stop(0)
        voice.stop()
        udp.stop()
    })
    server.awaitClose()
}
