package wynnvoice.server.voice

import wynnvoice.protocol.VoiceTier

data class VoiceConfig(
    val enabled: Boolean,
    val everyoneEnabled: Boolean,
    val host: String,
    val port: Int,
    val bindAddress: String,
    val range: Double,
    val keepAliveMs: Int,
    val reportDir: String,
    val ringBufferCapBytes: Long,
) {
    init {
        require(!enabled || host.isNotBlank()) { "VOICE_HOST must be set when VOICE_ENABLED=true" }
    }

    val maxTier: VoiceTier get() = if (everyoneEnabled) VoiceTier.EVERYONE else VoiceTier.FRIENDS_AND_GUILD

    companion object {
        const val SUPPORTED_SVC_COMPAT = 20

        fun fromEnv(): VoiceConfig = VoiceConfig(
            enabled = System.getenv("VOICE_ENABLED") == "true",
            everyoneEnabled = System.getenv("VOICE_EVERYONE_ENABLED") == "true",
            host = System.getenv("VOICE_HOST") ?: "",
            port = System.getenv("VOICE_PORT")?.toIntOrNull() ?: 24454,
            bindAddress = System.getenv("VOICE_BIND") ?: "0.0.0.0",
            range = System.getenv("VOICE_RANGE")?.toDoubleOrNull() ?: 32.0,
            keepAliveMs = 1000,
            reportDir = System.getenv("VOICE_REPORT_DIR") ?: "voice-reports",
            ringBufferCapBytes = (System.getenv("VOICE_RING_CAP_MB")?.toLongOrNull() ?: 512L) * 1024 * 1024,
        )
    }
}
