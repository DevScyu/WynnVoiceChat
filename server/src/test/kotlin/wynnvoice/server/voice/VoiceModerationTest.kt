package wynnvoice.server.voice

import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import wynnvoice.protocol.Packet.Position

class VoiceModerationTest {
    private lateinit var db: Database
    private var now = 1_000_000L
    private lateinit var moderation: VoiceModeration

    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        moderation = testModeration("voice-moderation-test") { now }
        db = VoiceModeration.openSqlite("build/tmp/voice-moderation-test.db")
    }

    @Test
    fun `blocks are symmetric and persisted`() {
        assertFalse(moderation.isBlocked(alice, bob))

        moderation.block(alice, bob)

        assertTrue(moderation.isBlocked(alice, bob))
        assertTrue(moderation.isBlocked(bob, alice))

        val reloaded = VoiceModeration(db) { now }.also { it.init() }
        assertTrue(reloaded.isBlocked(bob, alice))

        moderation.unblock(alice, bob)
        assertFalse(moderation.isBlocked(alice, bob))
        assertFalse(VoiceModeration(db) { now }.also { it.init() }.isBlocked(alice, bob))
    }

    @Test
    fun `block is idempotent`() {
        moderation.block(alice, bob)
        moderation.block(alice, bob)
        assertEquals(1L, transaction(db) { VoiceBlocksTable.selectAll().where { VoiceBlocksTable.blocker eq alice }.count() })
    }

    @Test
    fun `active bans honour expiry and lift`() {
        transaction(db) {
            VoiceBansTable.insert { it[userId] = alice; it[reason] = "r"; it[bannedBy] = "staff"; it[bannedAt] = now; it[expiresAt] = now + 10 }
            VoiceBansTable.insert { it[userId] = bob; it[reason] = "r"; it[bannedBy] = "staff"; it[bannedAt] = now; it[liftedAt] = now }
        }

        assertEquals(setOf(alice), moderation.activeBans(listOf(alice, bob)))
        assertTrue(moderation.isBanned(alice))
        assertFalse(moderation.isBanned(bob))

        now += 11
        assertTrue(moderation.activeBans(listOf(alice, bob)).isEmpty())
    }

    @Test
    fun `create report and attach audio`() {
        val id = moderation.createReport(
            NewReport(
                reporterId = alice, targetId = bob, reason = "slurs", world = "WC1", instance = "",
                reporterPos = Position(1f, 2f, 3f), targetPos = null, witnesses = listOf(bob, alice),
                audioFrom = now - 5000, audioTo = now,
            )
        )
        moderation.attachAudio(id, "/r/$id/reporter.opus", "/r/$id/target.opus")

        val row = transaction(db) { VoiceReportsTable.selectAll().where { VoiceReportsTable.id eq id }.single() }
        assertEquals("slurs", row[VoiceReportsTable.reason])
        assertEquals("WC1", row[VoiceReportsTable.world])
        assertEquals(1f, row[VoiceReportsTable.reporterX])
        assertNull(row[VoiceReportsTable.targetX])
        assertEquals("$bob,$alice", row[VoiceReportsTable.witnessIds])
        assertEquals("/r/$id/target.opus", row[VoiceReportsTable.targetAudioPath])
        assertEquals(false, row[VoiceReportsTable.handled])
        assertEquals(now, row[VoiceReportsTable.createdAt])
    }
}
