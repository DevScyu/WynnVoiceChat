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
import wynnvoice.protocol.Packet
import wynnvoice.protocol.Protocol

class ControlHandlerTest {
    private val uuid = UUID.randomUUID()
    private var fetched: Pair<String, String>? = null
    private var authenticated: ControlHandler? = null

    private fun channel(session: () -> UUID?): EmbeddedChannel {
        val fetcher = SessionFetcher { username, serverId ->
            fetched = username to serverId
            runCatching { CompletableFuture.completedFuture(session()) }.getOrElse { CompletableFuture.failedFuture(it) }
        }
        return EmbeddedChannel(ControlHandler(fetcher) { authenticated = it })
    }

    private fun EmbeddedChannel.hello(version: Int = Protocol.VERSION, modVersion: String = "1.0.0"): Packet? {
        writeInbound(Packet.Hello(version, 20, modVersion))
        return readOutbound()
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
}
