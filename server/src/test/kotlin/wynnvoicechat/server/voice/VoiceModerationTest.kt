package wynnvoicechat.server.voice

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
import wynnvoicechat.protocol.Packet.Position

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
    fun `the database runs in wal mode so a writer never stalls the event loops reading it`() {
        val mode = transaction(db) { exec("PRAGMA journal_mode") { it.next(); it.getString(1) } }
        assertEquals("wal", mode)
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

    @Test
    fun `outcome columns are added to a reports table from before they existed`() {
        transaction(db) {
            exec("DROP TABLE voice_reports")
            exec("CREATE TABLE voice_reports (id INTEGER PRIMARY KEY AUTOINCREMENT, reporter_id BLOB NOT NULL, target_id BLOB NOT NULL, reason TEXT NOT NULL, world TEXT NOT NULL, instance TEXT NOT NULL, witness_ids TEXT NOT NULL, created_at BIGINT NOT NULL, handled BOOLEAN DEFAULT 0 NOT NULL)")
        }
        VoiceModeration(db) { now }.init()

        val id = moderation.createReport(NewReport(alice, bob, "", "", "", null, null, emptyList(), null, null))
        moderation.markHandled(id, "modbob", Verdict.ACTIONED)
        assertEquals(listOf(id to Verdict.ACTIONED), moderation.pendingOutcomes(alice))
    }

    @Test
    fun `an outcome is pending until notified exactly once`() {
        val id = moderation.createReport(NewReport(alice, bob, "", "", "", null, null, emptyList(), null, null))
        assertEquals(emptyList(), moderation.pendingOutcomes(alice))
        moderation.markHandled(id, "modbob", Verdict.DISMISSED)
        assertEquals(listOf(id to Verdict.DISMISSED), moderation.pendingOutcomes(alice))
        assertEquals(emptyList(), moderation.pendingOutcomes(bob))

        assertTrue(moderation.markNotified(id))
        assertFalse(moderation.markNotified(id))
        assertEquals(emptyList(), moderation.pendingOutcomes(alice))
        val row = transaction(db) { VoiceReportsTable.selectAll().where { VoiceReportsTable.id eq id }.single() }
        assertEquals("DISMISSED", row[VoiceReportsTable.outcome])
        assertEquals(now, row[VoiceReportsTable.notifiedAt])
        assertEquals(alice to bob, moderation.reportParties(id))
    }

    private val day = 24 * 60 * 60_000L

    private fun ban(user: UUID, expiresAt: Long?) = transaction(db) {
        VoiceBansTable.insert { it[userId] = user; it[reason] = "r"; it[bannedBy] = "staff"; it[bannedAt] = now - 400 * day; it[VoiceBansTable.expiresAt] = expiresAt }
    }

    @Test
    fun `bans expired over a year ago are pruned, permanent ones never`() {
        ban(alice, now - 366 * day)
        ban(bob, now - 364 * day)
        val permanent = UUID.randomUUID()
        ban(permanent, null)

        assertEquals(1, moderation.pruneBans(now - 365 * day))

        val left = transaction(db) { VoiceBansTable.selectAll().map { it[VoiceBansTable.userId] }.toSet() }
        assertEquals(setOf(bob, permanent), left)
    }

    @Test
    fun `report rows older than the cutoff are pruned and clips expire separately`() {
        val old = moderation.createReport(NewReport(alice, bob, "", "", "", null, null, emptyList(), null, null))
        moderation.attachAudio(old, "/r/$old/reporter.opus", "/r/$old/target.opus")
        now += 31 * day
        val recent = moderation.createReport(NewReport(alice, bob, "", "", "", null, null, emptyList(), null, null))
        moderation.attachAudio(recent, null, "/r/$recent/target.opus")

        assertEquals(listOf(old), moderation.reportsWithClipsBefore(now - 30 * day))
        moderation.attachAudio(old, null, null)
        assertEquals(emptyList(), moderation.reportsWithClipsBefore(now - 30 * day), "detached clips are not listed again")
        assertEquals(alice to bob, moderation.reportParties(old), "row outlives its clips")

        now += 335 * day
        assertEquals(1, moderation.pruneReports(now - 365 * day))
        assertNull(moderation.reportParties(old))
        assertEquals(alice to bob, moderation.reportParties(recent))
    }
}
