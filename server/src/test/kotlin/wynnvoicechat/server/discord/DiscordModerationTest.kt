package wynnvoicechat.server.discord

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import wynnvoicechat.protocol.EndReason
import wynnvoicechat.protocol.Packet
import wynnvoicechat.protocol.Packet.Position
import wynnvoicechat.protocol.ResultKind
import wynnvoicechat.protocol.VoiceTier
import wynnvoicechat.server.ApiFetcher
import wynnvoicechat.server.Metrics
import wynnvoicechat.server.MetricsTest
import wynnvoicechat.server.voice.FiledReport
import wynnvoicechat.server.voice.NewReport
import wynnvoicechat.server.voice.Player
import wynnvoicechat.server.voice.VoiceBansTable
import wynnvoicechat.server.voice.VoiceConfig
import wynnvoicechat.server.voice.VoiceManager
import wynnvoicechat.server.voice.VoiceReportsTable
import wynnvoicechat.server.voice.testModeration

class DiscordModerationTest {
    private class Request(val method: String, val path: String, val contentType: String, val body: ByteArray)

    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKeyHex = HexFormat.of().formatHex(keys.public.encoded.takeLast(32).toByteArray())
    private val config = DiscordConfig("token", publicKeyHex, "mod-role", "report-channel", "app", "guild", "mod-log")

    private var now = 1_000_000L
    private val moderation = testModeration("discord-moderation-test") { now }
    private val profiles = HashMap<String, UUID>()
    private val mojang = ApiFetcher { uri ->
        val id = profiles[uri.path.substringAfterLast('/')]
        CompletableFuture.completedFuture(id?.let { "{\"id\":\"${it.toString().replace("-", "")}\",\"name\":\"x\"}" })
    }
    private val voiceConfig = VoiceConfig(true, true, "voice.test", 24454, "127.0.0.1", 32.0, 1000, "build/tmp/discord-reports", 1_000_000)
    private val voice = VoiceManager(voiceConfig, moderation, { _, _ -> }, mojang) { now }
    private val requests = ArrayList<Request>()
    private val discord = DiscordModeration(config, voice, { method, path, contentType, body ->
        requests.add(Request(method, path, contentType, body))
        CompletableFuture.completedFuture(when {
            path.endsWith("/threads") -> """{"id":"777"}"""
            path == "/channels/report-channel/messages" -> """{"id":"msg-1"}"""
            else -> "{}"
        })
    }) { now }
    private val sent = HashMap<UUID, ArrayList<Packet>>()

    @AfterTest
    fun stop() = discord.stop()

    private fun player(name: String): Player {
        val uuid = UUID.nameUUIDFromBytes(name.toByteArray())
        sent[uuid] = ArrayList()
        return Player(uuid, name) { sent[uuid]!!.add(it) }.also { it.world = "WC1"; it.position = Position(0f, 0f, 0f) }
    }

    private fun onVoice(name: String) = player(name).also { voice.join(it, 20, VoiceTier.EVERYONE, "") }

    private fun member(vararg roles: String) = """"member":{"roles":[${roles.joinToString(",") { "\"$it\"" }}],"user":{"id":"42","username":"modbob"}}"""

    private fun button(customId: String, vararg roles: String = arrayOf("mod-role")) =
        """{"type":3,${member(*roles)},"data":{"custom_id":"$customId","component_type":2}}"""

    private fun banModal(customId: String, reason: String, vararg roles: String = arrayOf("mod-role")) =
        """{"type":5,${member(*roles)},"data":{"custom_id":"$customId","components":[{"type":18,"component":{"type":3,"custom_id":"reason","values":["$reason"]}}]}}"""

    private fun slash(sub: String, vararg args: Pair<String, Any>, roles: Array<String> = arrayOf("mod-role")): String {
        val options = args.joinToString(",") { (name, value) ->
            val json = if (value is String) "\"$value\"" else value.toString()
            """{"name":"$name","type":${if (value is String) 3 else 4},"value":$json}"""
        }
        return """{"type":2,${member(*roles)},"data":{"name":"voice","options":[{"name":"$sub","type":1,"options":[$options]}]}}"""
    }

    private fun handle(json: String): JsonObject = JsonParser.parseString(discord.handle(json)).asJsonObject
    private fun sample(series: String) = MetricsTest.sample(Metrics.scrape(), series)!!.toDouble()
    private fun content(response: JsonObject) = response.getAsJsonObject("data")["content"].asString
    private fun assertEphemeral(response: JsonObject) {
        assertEquals(4, response["type"].asInt)
        assertEquals(64, response.getAsJsonObject("data")["flags"].asInt)
    }

    private fun sign(timestamp: String, body: ByteArray): String {
        val signature = Signature.getInstance("Ed25519").apply { initSign(keys.private); update(timestamp.toByteArray()); update(body) }
        return HexFormat.of().formatHex(signature.sign())
    }

    // --- signature and transport ---

    @Test
    fun `verifier accepts a valid signature and rejects everything else`() {
        val verifier = InteractionVerifier(publicKeyHex)
        val body = """{"type":1}""".toByteArray()
        val signature = sign("1700000000", body)
        assertTrue(verifier.verify(signature, "1700000000", body))
        assertFalse(verifier.verify(signature, "1700000001", body))
        assertFalse(verifier.verify(signature, "1700000000", """{"type":2}""".toByteArray()))
        assertFalse(verifier.verify(null, "1700000000", body))
        assertFalse(verifier.verify(signature, null, body))
        assertFalse(verifier.verify("zz", "1700000000", body))
        assertFalse(verifier.verify(signature.dropLast(2), "1700000000", body))
    }

    @Test
    fun `http endpoint answers ping with pong and unsigned requests with 401`() {
        discord.start(0)
        assertEquals("PUT", requests.single().method)
        assertEquals("/applications/app/guilds/guild/commands", requests.single().path)
        assertTrue(String(requests.single().body).contains("\"default_member_permissions\":\"0\""))

        val body = """{"type":1}"""
        val http = HttpClient.newHttpClient()
        fun post(vararg headers: String): HttpResponse<String> {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${discord.boundPort}/discord")).POST(HttpRequest.BodyPublishers.ofString(body))
            if (headers.isNotEmpty()) request.headers(*headers)
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

        val signed = post("X-Signature-Ed25519", sign("123", body.toByteArray()), "X-Signature-Timestamp", "123")
        assertEquals(200, signed.statusCode())
        assertEquals("""{"type":1}""", signed.body())

        assertEquals(401, post().statusCode())
        assertEquals(401, post("X-Signature-Ed25519", sign("124", body.toByteArray()), "X-Signature-Timestamp", "123").statusCode())
        val get = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${discord.boundPort}/discord")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(405, get.statusCode())
        val huge = HttpRequest.newBuilder(URI("http://127.0.0.1:${discord.boundPort}/discord")).POST(HttpRequest.BodyPublishers.ofByteArray(ByteArray(DiscordModeration.MAX_BODY_BYTES + 1))).build()
        assertEquals(413, http.send(huge, HttpResponse.BodyHandlers.ofString()).statusCode(), "refused before the body is read")
    }

    // --- authorisation ---

    @Test
    fun `interactions without the moderator role are refused`() {
        val dismissRefused = sample("voice_discord_interactions_total{kind=\"dismiss_button\",outcome=\"unauthorized\"}")
        val bansRefused = sample("voice_discord_interactions_total{kind=\"cmd_bans\",outcome=\"unauthorized\"}")
        val pings = sample("voice_discord_interactions_total{kind=\"ping\",outcome=\"ok\"}")
        val refused = handle(button("dismiss:1", "other-role"))
        assertEphemeral(refused)
        assertEquals("You need the moderator role to do that.", content(refused))
        assertEphemeral(handle(slash("bans", roles = arrayOf())))
        assertTrue(content(handle(slash("bans", roles = arrayOf()))).contains("moderator role"))
        assertEquals("""{"type":1}""", discord.handle("""{"type":1}"""))
        assertEquals(dismissRefused + 1, sample("voice_discord_interactions_total{kind=\"dismiss_button\",outcome=\"unauthorized\"}"))
        assertEquals(bansRefused + 2, sample("voice_discord_interactions_total{kind=\"cmd_bans\",outcome=\"unauthorized\"}"))
        assertEquals(pings + 1, sample("voice_discord_interactions_total{kind=\"ping\",outcome=\"ok\"}"))
    }

    @Test
    fun `the appeal button needs no role and opens a private thread for the clicker`() {
        val ok = sample("voice_discord_interactions_total{kind=\"appeal_button\",outcome=\"ok\"}")
        val response = handle("""{"type":3,"channel_id":"appeals",${member()},"data":{"custom_id":"appeal","component_type":2}}""")
        assertEphemeral(response)
        assertTrue(content(response).contains("<#777>"), content(response))

        val thread = requests.single { it.method == "POST" && it.path == "/channels/appeals/threads" }
        val body = JsonParser.parseString(String(thread.body)).asJsonObject
        assertEquals(12, body["type"].asInt, "private thread")
        assertFalse(body["invitable"].asBoolean)
        assertTrue(body["name"].asString.contains("modbob"))
        assertEquals(1, requests.count { it.method == "PUT" && it.path == "/channels/777/thread-members/42" })
        val first = JsonParser.parseString(String(requests.single { it.method == "POST" && it.path == "/channels/777/messages" }.body)).asJsonObject
        assertTrue(first["content"].asString.startsWith("<@42>"))
        assertTrue(first["content"].asString.contains("<@&mod-role>"), "the role mention is what adds and pings the moderators")
        assertEquals(listOf("mod-role"), first.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").map { it.asString })
        assertEquals(ok + 1, sample("voice_discord_interactions_total{kind=\"appeal_button\",outcome=\"ok\"}"))

        val again = handle("""{"type":3,"channel_id":"appeals",${member()},"data":{"custom_id":"appeal","component_type":2}}""")
        assertTrue(content(again).contains("<#777>"))
        assertEquals(1, requests.count { it.path == "/channels/appeals/threads" }, "one open appeal per user, however often they press the button")
    }

    // --- buttons ---

    private fun filedReport(reporter: Player, target: Player): Int = moderation.createReport(
        NewReport(reporter.uuid, target.uuid, "spam", "WC1", "", null, null, listOf(target.uuid), now - 5000, now)
    )

    @Test
    fun `ban button bans the target drops the session and edits the message`() {
        val reporter = onVoice("Alice")
        val target = onVoice("Bob")
        val id = filedReport(reporter, target)
        val buttonBans = sample("voice_bans_total{action=\"ban\",source=\"button\"}")
        val banButtons = sample("voice_discord_interactions_total{kind=\"ban_button\",outcome=\"ok\"}")

        val modal = handle(button("ban:7:$id"))
        assertEquals(9, modal["type"].asInt)
        assertEquals("ban:7:$id", modal.getAsJsonObject("data")["custom_id"].asString)
        assertEquals(banButtons + 1, sample("voice_discord_interactions_total{kind=\"ban_button\",outcome=\"ok\"}"))
        assertFalse(moderation.isBanned(target.uuid))

        val response = handle(banModal("ban:7:$id", "hate"))

        assertEquals(buttonBans + 1, sample("voice_bans_total{action=\"ban\",source=\"button\"}"))
        assertEquals(7, response["type"].asInt)
        assertEquals("Banned for 7 days by <@42> — Hate speech", content(response))
        assertEquals(0, response.getAsJsonObject("data").getAsJsonArray("components").size())
        assertTrue(moderation.isBanned(target.uuid))
        val ban = transaction(moderation.db) { VoiceBansTable.selectAll().single() }
        assertEquals(now + 7 * 24 * 60 * 60_000L, ban[VoiceBansTable.expiresAt])
        assertEquals("modbob", ban[VoiceBansTable.bannedBy])
        assertEquals("Hate speech", ban[VoiceBansTable.reason])
        val report = transaction(moderation.db) { VoiceReportsTable.selectAll().where { VoiceReportsTable.id eq id }.single() }
        assertTrue(report[VoiceReportsTable.handled])
        assertEquals("modbob", report[VoiceReportsTable.handledBy])

        assertEquals(EndReason.BANNED, (sent[target.uuid]!!.last() as Packet.Ended).reason, "kicked at once, not on the next maintenance pass")
        assertNull(voice.sessionOf(target.uuid))
        assertTrue(sent[reporter.uuid]!!.none { it is Packet.Ended })
    }

    @Test
    fun `permanent ban and dismiss buttons`() {
        val id = filedReport(player("Alice"), player("Bob"))
        assertEquals("Pick a rule from the list.", content(handle(banModal("ban:0:$id", "not-a-rule"))))
        assertFalse(moderation.isBanned(player("Bob").uuid))
        assertEquals("Banned permanently by <@42> — Ban evasion", content(handle(banModal("ban:0:$id", "ban_evasion"))))
        assertEquals("Ban evasion", transaction(moderation.db) { VoiceBansTable.selectAll().single()[VoiceBansTable.reason] })
        assertNull(transaction(moderation.db) { VoiceBansTable.selectAll().single()[VoiceBansTable.expiresAt] })

        val other = filedReport(player("Alice"), player("Carol"))
        assertEquals("Dismissed by <@42>", content(handle(button("dismiss:$other"))))
        assertFalse(moderation.isBanned(player("Carol").uuid))
        assertTrue(transaction(moderation.db) { VoiceReportsTable.selectAll().where { VoiceReportsTable.id eq other }.single()[VoiceReportsTable.handled] })

        assertEphemeral(handle(button("dismiss:999")))
        assertEphemeral(handle(button("nonsense")))
    }

    private fun closing(threadId: String): Pair<String?, JsonObject?> {
        val line = requests.lastOrNull { it.method == "POST" && it.path == "/channels/$threadId/messages" }?.let { JsonParser.parseString(String(it.body)).asJsonObject["content"].asString }
        val patch = requests.lastOrNull { it.method == "PATCH" && it.path == "/channels/$threadId" }?.let { JsonParser.parseString(String(it.body)).asJsonObject }
        return line to patch
    }

    @Test
    fun `actioning a report closes its thread with the outcome`() {
        val reporter = player("Alice")
        val bob = onVoice("Bob")
        val id = filedReport(reporter, bob)
        discord.postReport(FiledReport(id, "Alice", "Bob", NewReport(reporter.uuid, bob.uuid, "", "", "", null, null, emptyList(), null, null), null, null)).join()

        handle(button("dismiss:$id"))
        val (line, patch) = closing("msg-1")
        assertEquals("Dismissed by <@42>", line)
        assertTrue(patch!!["archived"].asBoolean && patch["locked"].asBoolean, "archived and locked so it stays a record")

        val carol = onVoice("Carol")
        profiles["Carol"] = carol.uuid
        val second = filedReport(reporter, carol)
        discord.postReport(FiledReport(second, "Alice", "Carol", NewReport(reporter.uuid, carol.uuid, "", "", "", null, null, emptyList(), null, null), null, null)).join()
        handle(slash("ban", "player" to "Carol", "days" to 7, "reason" to "hate"))
        val (slashLine, slashPatch) = closing("msg-1")
        assertEquals("Banned for 7 days by <@42> — Hate speech", slashLine, "a slash ban closes the threads of the reports it actions")
        assertTrue(slashPatch!!["archived"].asBoolean)

        val unposted = filedReport(reporter, player("Dave"))
        val before = requests.size
        handle(banModal("ban:0:$unposted", "hate"))
        assertTrue(requests.drop(before).none { it.method == "PATCH" }, "a report that never reached Discord has no thread to close")
    }

    @Test
    fun `an online reporter hears the outcome at once`() {
        val reporter = onVoice("Alice")
        voice.connected(reporter)
        val id = filedReport(reporter, onVoice("Bob"))

        handle(banModal("ban:7:$id", "disruption"))

        assertEquals(listOf(Packet.Result(ResultKind.REPORT_OUTCOME, true, "Report #$id was actioned")), sent[reporter.uuid]!!.filterIsInstance<Packet.Result>())
        val report = transaction(moderation.db) { VoiceReportsTable.selectAll().where { VoiceReportsTable.id eq id }.single() }
        assertEquals("ACTIONED", report[VoiceReportsTable.outcome])
        assertEquals(now, report[VoiceReportsTable.notifiedAt])
        voice.connected(reporter)
        assertEquals(1, sent[reporter.uuid]!!.count { it is Packet.Result })
    }

    @Test
    fun `an offline reporter hears the outcome on the next auth exactly once`() {
        val reporter = player("Alice")
        val id = filedReport(reporter, player("Bob"))

        handle(button("dismiss:$id"))
        assertTrue(sent[reporter.uuid]!!.isEmpty())
        assertNull(transaction(moderation.db) { VoiceReportsTable.selectAll().where { VoiceReportsTable.id eq id }.single()[VoiceReportsTable.notifiedAt] })

        voice.connected(reporter)
        assertEquals(listOf<Packet>(Packet.Result(ResultKind.REPORT_OUTCOME, true, "Report #$id was dismissed")), sent[reporter.uuid]!!.toList())
        voice.leave(reporter)
        voice.connected(reporter)
        assertEquals(1, sent[reporter.uuid]!!.size)
    }

    // --- slash commands ---

    @Test
    fun `slash ban actions every open report against the target and tells the reporters`() {
        val alice = onVoice("Alice")
        val carol = onVoice("Carol")
        val bob = onVoice("Bob")
        val dave = onVoice("Dave")
        voice.connected(alice)
        voice.connected(carol)
        val first = filedReport(alice, bob)
        val second = filedReport(carol, bob)
        val unrelated = filedReport(alice, dave)
        val dismissed = filedReport(carol, bob)
        handle(button("dismiss:$dismissed"))
        sent.values.forEach { it.clear() }

        assertEquals("Banned Bob permanently. Actioned 2 open report(s): #$first, #$second.", content(handle(slash("ban", "player" to "Bob", "reason" to "harassment"))))

        val rows = transaction(moderation.db) { VoiceReportsTable.selectAll().associate { it[VoiceReportsTable.id].value to Triple(it[VoiceReportsTable.outcome], it[VoiceReportsTable.handledBy], it[VoiceReportsTable.notifiedAt]) } }
        assertEquals(Triple("ACTIONED", "modbob", now), rows[first])
        assertEquals(Triple("ACTIONED", "modbob", now), rows[second])
        assertEquals(Triple(null, null, null), rows[unrelated])
        assertEquals("DISMISSED", rows[dismissed]!!.first)
        assertEquals(listOf(Packet.Result(ResultKind.REPORT_OUTCOME, true, "Report #$first was actioned")), sent[alice.uuid]!!.filterIsInstance<Packet.Result>())
        assertEquals(listOf(Packet.Result(ResultKind.REPORT_OUTCOME, true, "Report #$second was actioned")), sent[carol.uuid]!!.filterIsInstance<Packet.Result>())
    }

    @Test
    fun `ban and unban by name resolve live sessions then mojang`() {
        val bob = onVoice("Bob")
        profiles["Bob"] = bob.uuid // the ban drops Bob's session at once, so the unban resolves through Mojang
        val offline = UUID.randomUUID()
        profiles["Offline"] = offline

        val banned = handle(slash("ban", "player" to "bob", "days" to 30, "reason" to "hate"))
        assertEphemeral(banned)
        assertEquals("Banned bob for 30 days.", content(banned))
        assertTrue(moderation.isBanned(bob.uuid))
        val row = transaction(moderation.db) { VoiceBansTable.selectAll().single() }
        assertEquals("Hate speech", row[VoiceBansTable.reason])
        assertEquals(now + 30 * 24 * 60 * 60_000L, row[VoiceBansTable.expiresAt])

        assertEquals("Banned Offline permanently.", content(handle(slash("ban", "player" to "Offline", "reason" to "sexual"))))
        assertTrue(moderation.isBanned(offline))

        assertEquals("Unknown player Nobody.", content(handle(slash("ban", "player" to "Nobody", "reason" to "sexual"))))
        assertEquals("Unknown player bad name!.", content(handle(slash("ban", "player" to "bad name!", "reason" to "sexual"))))

        assertEquals("Unbanned Bob.", content(handle(slash("unban", "player" to "Bob"))))
        assertFalse(moderation.isBanned(bob.uuid))
        assertEquals("Bob is not banned.", content(handle(slash("unban", "player" to "Bob"))))
        assertTrue(moderation.isBanned(offline))
    }

    @Test
    fun `bans and blocks are listed`() {
        assertEquals("No active bans.", content(handle(slash("bans"))))
        val bob = onVoice("Bob")
        profiles["Bob"] = bob.uuid
        val carol = player("Carol")
        handle(slash("ban", "player" to "Bob", "days" to 1, "reason" to "disruption"))
        moderation.ban(carol.uuid, "", "modbob", null)
        moderation.block(bob.uuid, carol.uuid)

        val bans = content(handle(slash("bans"))).lines()
        assertEquals(2, bans.size)
        assertEquals("`${bob.uuid}` — until <t:${(now + 24 * 60 * 60_000L) / 1000}:f> — by modbob — Disruption (earrape, soundboards, spam)", bans[0])
        assertEquals("`${carol.uuid}` — permanent — by modbob", bans[1])

        assertEquals("`${carol.uuid}`", content(handle(slash("blocks", "player" to "Bob"))))
        profiles["Carol"] = carol.uuid
        assertEquals("Carol has blocked nobody.", content(handle(slash("blocks", "player" to "Carol"))))
        assertEphemeral(handle(slash("nonsense")))
    }

    // --- report posting ---

    @Test
    fun `report is posted as an embed with both audio files and buttons`() {
        val dir = File("build/tmp/discord-reports/7").apply { mkdirs() }
        val target = File(dir, "target.opus").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val reporter = File(dir, "reporter.opus").apply { writeBytes(byteArrayOf(4, 5)) }
        val report = NewReport(UUID.randomUUID(), UUID.randomUUID(), "spam", "WC1", "housing:x", null, null, listOf(UUID.randomUUID()), 1_000_000, 1_060_000)

        discord.postReport(FiledReport(7, "Alice", "Bob", report, reporter, target)).join()

        val request = requests.first()
        assertEquals("POST", request.method)
        assertEquals("/channels/report-channel/messages", request.path)
        val thread = requests.single { it.path == "/channels/report-channel/messages/msg-1/threads" }
        assertEquals("Report #7: Bob", JsonParser.parseString(String(thread.body)).asJsonObject["name"].asString, "a thread on the report keeps the discussion together")
        val boundary = request.contentType.removePrefix("multipart/form-data; boundary=")
        val body = String(request.body, Charsets.ISO_8859_1)
        val parts = body.split("--$boundary").filter { it.isNotBlank() && it != "--\r\n" }
        assertEquals(3, parts.size)
        assertTrue(parts[0].startsWith("\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"))
        val payload = JsonParser.parseString(parts[0].substringAfter("\r\n\r\n").trim()).asJsonObject
        assertEquals("<@&mod-role>", payload["content"].asString)
        assertEquals(listOf("mod-role"), payload.getAsJsonObject("allowed_mentions").getAsJsonArray("roles").map { it.asString })
        val embed = payload.getAsJsonArray("embeds").single().asJsonObject
        assertEquals("Voice report #7", embed["title"].asString)
        val fields = embed.getAsJsonArray("fields").associate { it.asJsonObject["name"].asString to it.asJsonObject["value"].asString }
        assertEquals("Alice\n`${report.reporterId}`", fields["Reporter"])
        assertEquals("Bob\n`${report.targetId}`", fields["Target"])
        assertEquals("spam", fields["Reason"])
        assertEquals("WC1", fields["World"])
        assertEquals("housing:x", fields["Instance"])
        assertEquals("1", fields["Witnesses"])
        assertEquals("<t:1000:T> to <t:1060:T>", fields["Audio window"])
        val buttons = payload.getAsJsonArray("components").single().asJsonObject.getAsJsonArray("components")
        assertEquals(listOf("ban:7:7", "ban:30:7", "ban:0:7", "dismiss:7"), buttons.map { it.asJsonObject["custom_id"].asString })
        assertEquals(listOf("Ban 7d", "Ban 30d", "Ban permanent", "Dismiss"), buttons.map { it.asJsonObject["label"].asString })
        assertEquals(listOf("target.opus", "reporter.opus"), payload.getAsJsonArray("attachments").map { it.asJsonObject["filename"].asString })
        assertTrue(parts[1].startsWith("\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"target.opus\"\r\nContent-Type: audio/ogg\r\n\r\n\r\n"))
        assertTrue(parts[2].startsWith("\r\nContent-Disposition: form-data; name=\"files[1]\"; filename=\"reporter.opus\"\r\nContent-Type: audio/ogg\r\n\r\n\r\n"))
        assertTrue(body.endsWith("--$boundary--\r\n"))
    }

    @Test
    fun `a failed post is logged and the future completes`() {
        val failing = DiscordModeration(config, voice, { _, _, _, _ -> CompletableFuture.failedFuture(IllegalStateException("down")) }) { now }
        val report = NewReport(UUID.randomUUID(), UUID.randomUUID(), "", "", "", null, null, emptyList(), null, null)
        val future = failing.postReport(FiledReport(1, "A", "B", report, null, null))
        assertTrue(runCatching { future.join() }.isFailure)
    }
    @Test
    fun `bans and unbans are audited in the table the history command and the mod log`() {
        val bob = onVoice("Bob")
        profiles["Bob"] = bob.uuid
        requests.clear()

        handle(slash("ban", "player" to "Bob", "days" to 7, "reason" to "doxxing"))
        now += 60_000
        handle(slash("unban", "player" to "Bob"))

        val row = transaction(moderation.db) { VoiceBansTable.selectAll().single() }
        assertEquals(now, row[VoiceBansTable.liftedAt])
        assertEquals("modbob", row[VoiceBansTable.liftedBy])

        val history = content(handle(slash("history", "player" to "Bob")))
        assertEquals("<t:${(now - 60_000) / 1000}:f> — Sharing personal information — until <t:${(now - 60_000 + 7 * 24 * 60 * 60_000L) / 1000}:f> — by modbob — lifted <t:${now / 1000}:f> by modbob", history)
        val carol = onVoice("Carol")
        assertEquals("Carol (`${carol.uuid}`) has never been banned.", content(handle(slash("history", "player" to "Carol"))))

        val log = requests.filter { it.path == "/channels/mod-log/messages" }.map { JsonParser.parseString(String(it.body)).asJsonObject["content"].asString }
        assertEquals(listOf("<@42> banned Bob (`${bob.uuid}`) for 7 days — Sharing personal information", "<@42> unbanned `${bob.uuid}`"), log)
    }
}
