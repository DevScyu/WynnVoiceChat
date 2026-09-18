package wynnvoice.server

import io.netty.channel.embedded.EmbeddedChannel
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import wynnvoice.protocol.AuthStatus
import wynnvoice.protocol.EndReason
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Packet.Position
import wynnvoice.protocol.Protocol
import wynnvoice.protocol.ResultKind
import wynnvoice.protocol.SocialAction
import wynnvoice.protocol.SocialKind
import wynnvoice.protocol.VoiceTier
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import wynnvoice.server.voice.VoiceBansTable
import wynnvoice.server.voice.VoiceConfig
import wynnvoice.server.voice.VoiceManager
import wynnvoice.server.voice.testModeration

class ControlHandlerTest {
    private val uuid = UUID.randomUUID()
    private var fetched: Pair<String, String>? = null
    private var authenticated: ControlHandler? = null
    private val config = VoiceConfig(true, true, "voice.test", 24454, "127.0.0.1", 32.0, 1000, "build/tmp/reports", 1_000_000)
    private val moderation = testModeration("control-handler-test")
    private fun manager(config: VoiceConfig) = VoiceManager(config, moderation, { _, _ -> }, { CompletableFuture.completedFuture(null) })
    private var voice = manager(config)
    private var guildMembers: Set<String> = emptySet()
    private val guilds = GuildResolver({ uri ->
        CompletableFuture.completedFuture(
            if (uri.path.startsWith("/v3/player/")) """{"guild":{"uuid":"18d19092-684b-427b-aa58-574230befe79","name":"G"}}"""
            else guildMembers.joinToString(",", "{\"members\":{\"owner\":{", "}}}") { "\"$it\":{\"uuid\":\"x\"}" })
    })

    private fun channel(session: () -> UUID?): EmbeddedChannel {
        val fetcher = SessionFetcher { username, serverId ->
            fetched = username to serverId
            runCatching { CompletableFuture.completedFuture(session()) }.getOrElse { CompletableFuture.failedFuture(it) }
        }
        return EmbeddedChannel(ControlHandler(fetcher, guilds, voice) { authenticated = it })
    }

    private fun EmbeddedChannel.hello(version: Int = Protocol.VERSION, modVersion: String = "1.0.0", svcVersion: Int = 20): Packet? {
        writeInbound(Packet.Hello(version, svcVersion, modVersion))
        return readOutbound()
    }

    private fun ready(svcVersion: Int = 20): EmbeddedChannel = channel { uuid }.also {
        it.hello(svcVersion = svcVersion)
        it.auth()
    }

    private fun EmbeddedChannel.auth(name: String = "Player", id: UUID = uuid): Packet.AuthResult? {
        writeInbound(Packet.Auth(name, id))
        runPendingTasks()
        return readOutbound()
    }

    @Test
    fun `wrong protocol version is refused and closed`() {
        val ch = channel { uuid }
        assertEquals(Packet.AuthResult(AuthStatus.VERSION_MISMATCH), ch.hello(version = Protocol.VERSION + 1))
        assertFalse(ch.isOpen)
    }

    @Test
    fun `valid hello gets a random 20 byte challenge`() {
        val a = channel { uuid }.hello() as Packet.AuthChallenge
        val b = channel { uuid }.hello() as Packet.AuthChallenge
        assertEquals(Protocol.SERVER_ID_BYTES, a.serverId.size)
        assertFalse(a.serverId.contentEquals(b.serverId))
    }

    @Test
    fun `matching session authenticates`() {
        val ch = channel { uuid }
        val challenge = ch.hello() as Packet.AuthChallenge
        assertEquals(Packet.AuthResult(AuthStatus.OK), ch.auth())
        assertEquals("Player" to HexFormat.of().formatHex(challenge.serverId), fetched)
        assertTrue(ch.isOpen)
        assertEquals(uuid, authenticated?.uuid)
        assertEquals("Player", authenticated?.username)
        assertEquals("1.0.0", authenticated?.modVersion)
    }

    @Test
    fun `uuid mismatch is a bad session`() {
        val ch = channel { UUID.randomUUID() }
        ch.hello()
        assertEquals(Packet.AuthResult(AuthStatus.BAD_SESSION), ch.auth())
        assertFalse(ch.isOpen)
        assertNull(authenticated)
    }

    @Test
    fun `no content from mojang is a bad session`() {
        val ch = channel { null }
        ch.hello()
        assertEquals(Packet.AuthResult(AuthStatus.BAD_SESSION), ch.auth())
        assertFalse(ch.isOpen)
    }

    @Test
    fun `fetcher failure is session unavailable`() {
        val ch = channel { throw IllegalStateException("mojang down") }
        ch.hello()
        assertEquals(Packet.AuthResult(AuthStatus.SESSION_UNAVAILABLE), ch.auth())
        assertFalse(ch.isOpen)
    }

    @Test
    fun `invalid username never reaches mojang`() {
        val ch = channel { uuid }
        ch.hello()
        assertEquals(Packet.AuthResult(AuthStatus.BAD_SESSION), ch.auth(name = "bad name!"))
        assertNull(fetched)
        assertFalse(ch.isOpen)
    }

    @Test
    fun `auth before hello closes silently`() {
        val ch = channel { uuid }
        assertNull(ch.auth())
        assertFalse(ch.isOpen)
    }

    @Test
    fun `unexpected mod version strings collapse to other`() {
        val ch = channel { uuid }
        ch.hello(modVersion = "1.0.0-beta+garbage")
        ch.auth()
        assertEquals("other", authenticated?.modVersion)
    }

    @Test
    fun `disabled relay refuses at auth`() {
        voice = manager(config.copy(enabled = false))
        val ch = channel { uuid }
        ch.hello()
        assertEquals(Packet.AuthResult(AuthStatus.DISABLED), ch.auth())
        assertFalse(ch.isOpen)
    }

    @Test
    fun `allowlist refuses verified players who are not on it`() {
        voice = manager(config.copy(allowedUuids = setOf(UUID.randomUUID())))
        val ch = channel { uuid }
        ch.hello()
        assertEquals(Packet.AuthResult(AuthStatus.NOT_ALLOWED), ch.auth())
        assertFalse(ch.isOpen)

        voice = manager(config.copy(allowedUuids = setOf(uuid)))
        val allowed = channel { uuid }
        allowed.hello()
        assertEquals(Packet.AuthResult(AuthStatus.OK), allowed.auth())
    }

    @Test
    fun `banned player is refused at auth`() {
        transaction(moderation.db) { VoiceBansTable.insert { it[userId] = uuid; it[reason] = "r"; it[bannedBy] = "s"; it[bannedAt] = 0 } }
        val ch = channel { uuid }
        ch.hello()
        assertEquals(Packet.AuthResult(AuthStatus.BANNED), ch.auth())
        assertFalse(ch.isOpen)
    }

    @Test
    fun `block and report packets are answered with a result`() {
        val ch = ready()
        ch.writeInbound(Packet.Join(VoiceTier.EVERYONE, ""))
        ch.readOutbound<Packet.Secret>()
        ch.writeInbound(Packet.Block("Player", true))
        assertEquals(Packet.Result(ResultKind.BLOCK, false, "You cannot block yourself"), ch.readOutbound())
        ch.writeInbound(Packet.Report("Nobody", "spam"))
        assertEquals(Packet.Result(ResultKind.REPORT, false, "Nobody is not on voice chat"), ch.readOutbound())
        assertTrue(ch.isOpen)
    }

    @Test
    fun `join creates a session and answers with the secret`() {
        val ch = ready()
        ch.writeInbound(Packet.World("WC12"))
        ch.writeInbound(Packet.Join(VoiceTier.EVERYONE, "housing:Me"))
        val secret = ch.readOutbound<Packet.Secret>()
        assertEquals("voice.test", secret.host)
        val session = voice.sessionOf(uuid)!!
        assertEquals("WC12", session.player.world)
        assertEquals("housing:Me", session.instance)
        assertEquals(VoiceTier.EVERYONE, session.tier)
    }

    @Test
    fun `svc version from hello is checked at join`() {
        val ch = ready(svcVersion = 18)
        ch.writeInbound(Packet.Join(VoiceTier.EVERYONE, ""))
        assertEquals(EndReason.UNSUPPORTED_SVC_VERSION, ch.readOutbound<Packet.Ended>().reason)
        assertNull(voice.sessionOf(uuid))
    }

    @Test
    fun `position and update maintain the session`() {
        val ch = ready()
        ch.writeInbound(Packet.Join(VoiceTier.EVERYONE, ""))
        ch.writeInbound(Packet.Position(1f, 2f, 3f))
        ch.writeInbound(Packet.Update(VoiceTier.PARTY, "housing:X", true, false, false))
        ch.writeInbound(Packet.World(""))
        val session = voice.sessionOf(uuid)!!
        assertEquals(Position(1f, 2f, 3f), session.player.position)
        assertEquals(VoiceTier.PARTY, session.tier)
        assertEquals("housing:X", session.instance)
        assertTrue(session.disabled)
        assertNull(session.player.world)
    }

    @Test
    fun `closing the connection leaves the session`() {
        val ch = ready()
        ch.writeInbound(Packet.Join(VoiceTier.EVERYONE, ""))
        ch.close()
        assertNull(voice.sessionOf(uuid))
    }

    @Test
    fun `guild members come from the resolver after auth and never from the client`() {
        guildMembers = setOf("Mate", "Player")
        val ch = ready()
        ch.writeInbound(Packet.Join(VoiceTier.FRIENDS_AND_GUILD, ""))
        val player = voice.sessionOf(uuid)!!.player
        assertEquals(setOf("Mate", "Player"), player.guildMembers)
        ch.writeInbound(Packet.Social(SocialKind.FRIENDS, SocialAction.SET, listOf("Impostor")))
        assertEquals(setOf("Mate", "Player"), player.guildMembers)
    }

    @Test
    fun `social packets maintain the party and friend lists`() {
        val ch = ready()
        ch.writeInbound(Packet.Join(VoiceTier.EVERYONE, ""))
        val player = voice.sessionOf(uuid)!!.player
        ch.writeInbound(Packet.Social(SocialKind.PARTY, SocialAction.SET, listOf("A", "B")))
        assertEquals(setOf("A", "B"), player.party)
        ch.writeInbound(Packet.Social(SocialKind.PARTY, SocialAction.ADD, listOf("C")))
        ch.writeInbound(Packet.Social(SocialKind.PARTY, SocialAction.REMOVE, listOf("A")))
        assertEquals(setOf("B", "C"), player.party)
        ch.writeInbound(Packet.Social(SocialKind.FRIENDS, SocialAction.ADD, listOf("F")))
        assertEquals(setOf("F"), player.friends)
        assertEquals(setOf("B", "C"), player.party)
        ch.writeInbound(Packet.Social(SocialKind.PARTY, SocialAction.SET, emptyList()))
        assertTrue(player.party.isEmpty())
    }
}
