package wynnvoice.server.discord

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import wynnvoice.server.Metrics
import wynnvoice.server.Metrics.counters
import wynnvoice.server.voice.FiledReport
import wynnvoice.server.voice.Verdict
import wynnvoice.server.voice.VoiceManager

data class DiscordConfig(
    val botToken: String,
    val publicKey: String,
    val modRoleId: String,
    val reportChannelId: String,
    val applicationId: String,
    val guildId: String,
) {
    companion object {
        val VARIABLES = listOf(
            "DISCORD_BOT_TOKEN", "DISCORD_PUBLIC_KEY", "DISCORD_MOD_ROLE_ID",
            "DISCORD_REPORT_CHANNEL_ID", "DISCORD_APPLICATION_ID", "DISCORD_GUILD_ID",
        )

        /** null unless every variable is set. */
        fun fromEnv(): DiscordConfig? {
            val values = VARIABLES.map { System.getenv(it)?.takeIf { v -> v.isNotBlank() } ?: return null }
            return DiscordConfig(values[0], values[1], values[2], values[3], values[4], values[5])
        }
    }
}

/** Discord signs `timestamp + body` with the application's Ed25519 key; the public key is the raw 32 bytes in hex. */
class InteractionVerifier(publicKeyHex: String) {
    private val key: PublicKey = KeyFactory.getInstance("Ed25519")
        .generatePublic(X509EncodedKeySpec(HexFormat.of().parseHex(ED25519_SPKI_PREFIX + publicKeyHex)))

    fun verify(signatureHex: String?, timestamp: String?, body: ByteArray): Boolean {
        if (signatureHex == null || timestamp == null) return false
        return runCatching {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(timestamp.toByteArray())
                update(body)
                verify(HexFormat.of().parseHex(signatureHex))
            }
        }.getOrDefault(false)
    }

    companion object {
        const val ED25519_SPKI_PREFIX = "302a300506032b6570032100"
    }
}

/**
 * Report embeds with ban buttons, and `/voice` slash commands, served from `POST /discord`.
 * Every interaction is signature-checked, then role-checked, before anything else is read.
 */
class DiscordModeration(
    private val config: DiscordConfig,
    private val voice: VoiceManager,
    private val rest: DiscordRest,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(DiscordModeration::class.java)
    private val moderation get() = voice.moderation
    private val gson = Gson()
    private val verifier = InteractionVerifier(config.publicKey)
    private var server: HttpServer? = null

    private class Moderator(val id: String, val name: String)

    enum class Kind { PING, BAN_BUTTON, DISMISS_BUTTON, CMD_BAN, CMD_UNBAN, CMD_BANS, CMD_BLOCKS, UNKNOWN }
    enum class Outcome { OK, BAD_SIGNATURE, UNAUTHORIZED, ERROR }
    enum class BanAction { BAN, UNBAN, DISMISS }
    enum class BanSource { BUTTON, COMMAND }
    enum class Operation { POST_REPORT, REGISTER_COMMANDS }
    enum class RequestOutcome { OK, ERROR }

    companion object {
        private val interactions = Metrics.registry.counters<Kind, Outcome>("voice_discord_interactions_total", "kind", "outcome")
        private val requests = Metrics.registry.counters<Operation, RequestOutcome>("voice_discord_requests_total", "operation", "outcome")
        private val bans = Metrics.registry.counters<BanAction, BanSource>("voice_bans_total", "action", "source")

        private const val PING = 1
        private const val APPLICATION_COMMAND = 2
        private const val MESSAGE_COMPONENT = 3
        private const val PONG = 1
        private const val CHANNEL_MESSAGE = 4
        private const val UPDATE_MESSAGE = 7
        private const val EPHEMERAL = 64
        private const val ACTION_ROW = 1
        private const val BUTTON = 2
        private const val DANGER = 4
        private const val SECONDARY = 2
        private const val SUB_COMMAND = 1
        private const val STRING = 3
        private const val INTEGER = 4
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val LOOKUP_TIMEOUT_MS = 2_500L
    }

    val boundPort: Int get() = server!!.address.port

    fun start(port: Int) {
        registerCommands()
        server = HttpServer.create(InetSocketAddress(port), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "discord-http").apply { isDaemon = true } }
            createContext("/discord", ::serve)
            start()
        }
        voice.onReport = { postReport(it) }
        logger.info("Discord moderation enabled on port {}", boundPort)
    }

    fun stop() {
        server?.stop(0)
    }

    private fun serve(exchange: HttpExchange) = exchange.use {
        val body = it.requestBody.readAllBytes()
        val headers = it.requestHeaders
        val (status, response) = when {
            it.requestMethod != "POST" -> 405 to ""
            !verifier.verify(headers.getFirst("X-Signature-Ed25519"), headers.getFirst("X-Signature-Timestamp"), body) -> {
                interactions.getValue(Kind.UNKNOWN).getValue(Outcome.BAD_SIGNATURE).increment()
                401 to ""
            }
            else -> 200 to handle(String(body))
        }
        val bytes = response.toByteArray()
        it.responseHeaders.add("Content-Type", "application/json")
        it.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        it.responseBody.write(bytes)
    }

    /** Interaction JSON in, interaction response JSON out. Only call after the signature has been verified. */
    fun handle(interactionJson: String): String {
        var kind = Kind.UNKNOWN
        val response = try {
            val interaction = JsonParser.parseString(interactionJson).asJsonObject
            kind = kindOf(interaction)
            respond(interaction, kind)
        } catch (e: Exception) {
            logger.error("Discord interaction failed", e)
            counted(kind, Outcome.ERROR, ephemeral("Something went wrong, check the relay log."))
        }
        return gson.toJson(response)
    }

    private fun counted(kind: Kind, outcome: Outcome, response: Map<String, Any?>): Map<String, Any?> =
        response.also { interactions.getValue(kind).getValue(outcome).increment() }

    private fun kindOf(interaction: JsonObject): Kind {
        val data = interaction.getAsJsonObject("data")
        return when (interaction["type"].asInt) {
            PING -> Kind.PING
            MESSAGE_COMPONENT -> when (data?.get("custom_id")?.asString?.substringBefore(':')) {
                "ban" -> Kind.BAN_BUTTON
                "dismiss" -> Kind.DISMISS_BUTTON
                else -> Kind.UNKNOWN
            }
            APPLICATION_COMMAND -> when (data?.getAsJsonArray("options")?.firstOrNull()?.asJsonObject?.get("name")?.asString) {
                "ban" -> Kind.CMD_BAN
                "unban" -> Kind.CMD_UNBAN
                "bans" -> Kind.CMD_BANS
                "blocks" -> Kind.CMD_BLOCKS
                else -> Kind.UNKNOWN
            }
            else -> Kind.UNKNOWN
        }
    }

    private fun respond(interaction: JsonObject, kind: Kind): Map<String, Any?> {
        val type = interaction["type"].asInt
        if (type == PING) return counted(kind, Outcome.OK, mapOf("type" to PONG))
        val member = interaction.getAsJsonObject("member") ?: return counted(kind, Outcome.UNAUTHORIZED, ephemeral("Use this in the server."))
        val roles = member.getAsJsonArray("roles")?.map { it.asString } ?: emptyList()
        if (config.modRoleId !in roles) {
            logger.info("Discord interaction {} refused: user {} lacks the moderator role", kind, member.getAsJsonObject("user")?.get("id")?.asString)
            return counted(kind, Outcome.UNAUTHORIZED, ephemeral("You need the moderator role to do that."))
        }
        val user = member.getAsJsonObject("user")
        val moderator = Moderator(user["id"].asString, user["username"].asString)
        val data = interaction.getAsJsonObject("data")
        return counted(kind, Outcome.OK, when (type) {
            APPLICATION_COMMAND -> command(data, moderator)
            MESSAGE_COMPONENT -> button(data["custom_id"].asString, moderator)
            else -> ephemeral("Unsupported interaction.")
        })
    }

    private fun button(customId: String, moderator: Moderator): Map<String, Any?> {
        val parts = customId.split(':')
        val reportId = parts.last().toIntOrNull() ?: return ephemeral("Unknown button.")
        val (_, target) = moderation.reportParties(reportId) ?: return ephemeral("Report #$reportId no longer exists.")
        val (outcome, verdict) = when (parts[0]) {
            "ban" -> {
                val days = parts[1].toInt()
                ban(target, days, "Report #$reportId", moderator)
                bans.getValue(BanAction.BAN).getValue(BanSource.BUTTON).increment()
                "Banned ${duration(days)}" to Verdict.ACTIONED
            }
            "dismiss" -> {
                bans.getValue(BanAction.DISMISS).getValue(BanSource.BUTTON).increment()
                "Dismissed" to Verdict.DISMISSED
            }
            else -> return ephemeral("Unknown button.")
        }
        moderation.markHandled(reportId, moderator.name, verdict)
        voice.reportHandled(reportId)
        logger.info("Report #{} {} by {}", reportId, outcome.lowercase(), moderator.name)
        return mapOf("type" to UPDATE_MESSAGE, "data" to mapOf("content" to "$outcome by <@${moderator.id}>", "components" to emptyList<Any>()))
    }

    private fun command(data: JsonObject, moderator: Moderator): Map<String, Any?> {
        val sub = data.getAsJsonArray("options")?.firstOrNull()?.asJsonObject ?: return ephemeral("Unknown command.")
        val args = sub.getAsJsonArray("options")?.associate { it.asJsonObject.let { o -> o["name"].asString to o["value"] } } ?: emptyMap()
        val player = args["player"]?.asString ?: ""
        return when (sub["name"].asString) {
            "ban" -> resolving(player) { uuid ->
                val days = args["days"]?.asInt ?: 0
                ban(uuid, days, args["reason"]?.asString ?: "", moderator)
                bans.getValue(BanAction.BAN).getValue(BanSource.COMMAND).increment()
                logger.info("{} banned {} {}", moderator.name, player, duration(days))
                ephemeral("Banned $player ${duration(days)}.")
            }
            "unban" -> resolving(player) { uuid ->
                val lifted = moderation.unban(uuid) > 0
                if (lifted) {
                    bans.getValue(BanAction.UNBAN).getValue(BanSource.COMMAND).increment()
                    logger.info("{} unbanned {}", moderator.name, player)
                }
                ephemeral(if (lifted) "Unbanned $player." else "$player is not banned.")
            }
            "bans" -> ephemeral(listing("No active bans.", moderation.activeBanRows().map { ban ->
                val until = ban.expiresAt?.let { "until <t:${it / 1000}:f>" } ?: "permanent"
                "${nameOf(ban.userId)} — $until — by ${ban.bannedBy}" + ban.reason.takeIf(String::isNotBlank)?.let { " — $it" }.orEmpty()
            }))
            "blocks" -> resolving(player) { uuid ->
                ephemeral(listing("$player has blocked nobody.", moderation.blocksOf(uuid).map(::nameOf)))
            }
            else -> ephemeral("Unknown command.")
        }
    }

    private fun resolving(name: String, then: (UUID) -> Map<String, Any?>): Map<String, Any?> {
        // ponytail: blocks a discord-http thread for up to 2.5 s of Discord's 3 s budget; defer (type 5) and follow up if Mojang gets slow
        val uuid = try {
            voice.resolveUuid(name).get(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logger.warn("Profile lookup for {} failed: {}", name, e.message)
            return ephemeral("Could not look up $name, try again later.")
        }
        return if (uuid == null) ephemeral("Unknown player $name.") else then(uuid)
    }

    private fun ban(target: UUID, days: Int, reason: String, moderator: Moderator) {
        moderation.ban(target, reason, moderator.name, if (days > 0) clock() + days * DAY_MS else null)
    }

    private fun duration(days: Int) = if (days > 0) "for $days days" else "permanently"

    private fun nameOf(uuid: UUID) = voice.sessionOf(uuid)?.player?.name?.let { "$it (`$uuid`)" } ?: "`$uuid`"

    // ponytail: Discord caps content at 2000 chars; paginate if a list ever gets that long
    private fun listing(empty: String, lines: List<String>) = if (lines.isEmpty()) empty else lines.joinToString("\n").take(1990)

    private fun ephemeral(content: String) = mapOf("type" to CHANNEL_MESSAGE, "data" to mapOf("content" to content, "flags" to EPHEMERAL))

    // --- outbound ---

    fun postReport(report: FiledReport): CompletableFuture<String> {
        val r = report.report
        val files = listOfNotNull(report.targetAudio, report.reporterAudio)
        fun field(name: String, value: String, inline: Boolean = false) = mapOf("name" to name, "value" to value, "inline" to inline)
        fun button(label: String, customId: String, style: Int) = mapOf("type" to BUTTON, "style" to style, "label" to label, "custom_id" to customId)
        val window = if (r.audioFrom != null && r.audioTo != null) "<t:${r.audioFrom / 1000}:T> to <t:${r.audioTo / 1000}:T>" else "no audio"
        val payload = mapOf(
            "embeds" to listOf(
                mapOf(
                    "title" to "Voice report #${report.id}",
                    "fields" to listOf(
                        field("Reporter", "${report.reporterName}\n`${r.reporterId}`", inline = true),
                        field("Target", "${report.targetName}\n`${r.targetId}`", inline = true),
                        field("Reason", r.reason.ifBlank { "—" }),
                        field("World", r.world.ifBlank { "—" }, inline = true),
                        field("Instance", r.instance.ifBlank { "—" }, inline = true),
                        field("Witnesses", r.witnesses.size.toString(), inline = true),
                        field("Audio window", window),
                    ),
                )
            ),
            "components" to listOf(
                mapOf(
                    "type" to ACTION_ROW,
                    "components" to listOf(
                        button("Ban 7d", "ban:7:${report.id}", DANGER),
                        button("Ban 30d", "ban:30:${report.id}", DANGER),
                        button("Ban permanent", "ban:0:${report.id}", DANGER),
                        button("Dismiss", "dismiss:${report.id}", SECONDARY),
                    ),
                )
            ),
            "attachments" to files.mapIndexed { i, file -> mapOf("id" to i, "filename" to file.name) },
        )
        val multipart = Multipart(gson.toJson(payload), files)
        return CompletableFuture.supplyAsync(multipart::encode)
            .thenCompose { rest.send("POST", "/channels/${config.reportChannelId}/messages", multipart.contentType, it) }
            .whenComplete { _, error ->
                requests.getValue(Operation.POST_REPORT).getValue(if (error == null) RequestOutcome.OK else RequestOutcome.ERROR).increment()
                if (error != null) logger.warn("Posting report #{} to Discord failed: {}", report.id, error.message)
            }
    }

    private fun registerCommands() {
        val player = mapOf("type" to STRING, "name" to "player", "description" to "Minecraft name", "required" to true)
        fun sub(name: String, description: String, vararg options: Map<String, Any>) =
            mapOf("type" to SUB_COMMAND, "name" to name, "description" to description, "options" to options.toList())
        val commands = listOf(
            mapOf(
                "name" to "voice",
                "description" to "WynnVoice moderation",
                "default_member_permissions" to "0",
                "options" to listOf(
                    sub(
                        "ban", "Ban a player from voice chat", player,
                        mapOf("type" to INTEGER, "name" to "days", "description" to "Length in days, omit for permanent", "min_value" to 1),
                        mapOf("type" to STRING, "name" to "reason", "description" to "Reason"),
                    ),
                    sub("unban", "Lift a player's voice ban", player),
                    sub("bans", "List active voice bans"),
                    sub("blocks", "List who a player has blocked", player),
                ),
            )
        )
        rest.send("PUT", "/applications/${config.applicationId}/guilds/${config.guildId}/commands", "application/json", gson.toJson(commands).toByteArray())
            .whenComplete { _, error ->
                requests.getValue(Operation.REGISTER_COMMANDS).getValue(if (error == null) RequestOutcome.OK else RequestOutcome.ERROR).increment()
                if (error != null) logger.warn("Registering Discord commands failed: {}", error.message)
                else logger.info("Registered /voice commands in guild {}", config.guildId)
            }
    }
}
