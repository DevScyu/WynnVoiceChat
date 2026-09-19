package wynnvoicechat.server.voice

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import wynnvoicechat.protocol.Packet.Position
import wynnvoicechat.protocol.VoiceTier
import wynnvoicechat.server.Metrics
import wynnvoicechat.server.Metrics.sloTimer

data class NewReport(
    val reporterId: UUID,
    val targetId: UUID,
    val reason: String,
    val world: String,
    val instance: String,
    val reporterPos: Position?,
    val targetPos: Position?,
    val witnesses: List<UUID>,
    val audioFrom: Long?,
    val audioTo: Long?,
)

enum class Verdict { ACTIONED, DISMISSED }

data class Ban(
    val userId: UUID, val reason: String, val bannedBy: String, val expiresAt: Long?,
    val bannedAt: Long = 0, val liftedAt: Long? = null, val liftedBy: String? = null,
)

class GuildMute(val guildId: UUID, val expiresAt: Long?)

enum class DbOp {
    INIT, BLOCK, UNBLOCK, GUILD_MUTE, GUILD_UNMUTE, BAN, UNBAN, ACTIVE_BAN_ROWS, BAN_HISTORY, CREATE_REPORT, REPORT_PARTIES, OPEN_REPORTS, MARK_HANDLED,
    ATTACH_AUDIO, PENDING_OUTCOMES, MARK_NOTIFIED, RECORD_SESSION, END_SESSION, PRUNE_SESSIONS, PRUNE_BANS, PRUNE_REPORTS, REPORTS_WITH_CLIPS,
}

/**
 * Blocks, guild mutes, bans and report rows. Blocks and mutes are cached in memory because routing asks per mic frame;
 * bans are read on demand (auth) and by the 60 s poll in VoiceManager.
 */
class VoiceModeration(val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    private val logger = LoggerFactory.getLogger(VoiceModeration::class.java)

    private val blocks = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    // ponytail: one mute per target; a player is in one guild at a time, so a mute from another guild never applies
    private val guildMutes = ConcurrentHashMap<UUID, GuildMute>()

    private fun <T> db(op: DbOp, statement: JdbcTransaction.() -> T): T =
        DB_TIMERS.getValue(op).recordCallable { transaction(db, statement = statement) }

    fun init() {
        db(DbOp.INIT) {
            SchemaUtils.create(VoiceBlocksTable, GuildMutesTable, VoiceBansTable, VoiceReportsTable, VoiceSessionsTable)
            SchemaUtils.addMissingColumnsStatements(VoiceReportsTable, VoiceBansTable).forEach { exec(it) }
        }
        blocks.clear()
        db(DbOp.INIT) { VoiceBlocksTable.selectAll().map { it[VoiceBlocksTable.blocker] to it[VoiceBlocksTable.blocked] } }
            .forEach { (blocker, blocked) -> blocks.computeIfAbsent(blocker) { ConcurrentHashMap.newKeySet() }.add(blocked) }
        guildMutes.clear()
        val now = clock()
        db(DbOp.INIT) {
            GuildMutesTable.deleteWhere { expiresAt less now }
            GuildMutesTable.selectAll().map { it[GuildMutesTable.targetId] to GuildMute(it[GuildMutesTable.guildId], it[GuildMutesTable.expiresAt]) }
        }.forEach { (target, mute) -> guildMutes[target] = mute }
        logger.info("Voice moderation loaded: {} blocks, {} guild mutes", blockCount, guildMutes.size)
    }

    val blockCount: Int get() = blocks.values.sumOf { it.size }

    fun isBlocked(a: UUID, b: UUID): Boolean =
        blocks[a]?.contains(b) == true || blocks[b]?.contains(a) == true

    fun block(blocker: UUID, blocked: UUID) {
        val added = blocks.computeIfAbsent(blocker) { ConcurrentHashMap.newKeySet() }.add(blocked)
        if (!added) return
        val now = clock()
        db(DbOp.BLOCK) {
            VoiceBlocksTable.insert {
                it[VoiceBlocksTable.blocker] = blocker
                it[VoiceBlocksTable.blocked] = blocked
                it[createdAt] = now
            }
        }
    }

    fun unblock(blocker: UUID, blocked: UUID) {
        blocks[blocker]?.remove(blocked)
        db(DbOp.UNBLOCK) {
            VoiceBlocksTable.deleteWhere { (VoiceBlocksTable.blocker eq blocker) and (VoiceBlocksTable.blocked eq blocked) }
        }
    }

    fun blocksOf(blocker: UUID): Set<UUID> = blocks[blocker]?.toSet() ?: emptySet()

    fun isGuildMuted(guildId: UUID, target: UUID): Boolean {
        val mute = guildMutes[target] ?: return false
        return mute.guildId == guildId && (mute.expiresAt == null || mute.expiresAt > clock())
    }

    fun guildMute(guildId: UUID, target: UUID, by: UUID, expiresAt: Long?) {
        guildMutes[target] = GuildMute(guildId, expiresAt)
        val now = clock()
        db(DbOp.GUILD_MUTE) {
            GuildMutesTable.deleteWhere { targetId eq target }
            GuildMutesTable.insert {
                it[GuildMutesTable.guildId] = guildId
                it[targetId] = target
                it[mutedBy] = by
                it[GuildMutesTable.expiresAt] = expiresAt
                it[createdAt] = now
            }
        }
    }

    fun guildUnmute(guildId: UUID, target: UUID) {
        guildMutes.computeIfPresent(target) { _, mute -> mute.takeIf { it.guildId != guildId } }
        db(DbOp.GUILD_UNMUTE) {
            GuildMutesTable.deleteWhere { (GuildMutesTable.guildId eq guildId) and (targetId eq target) }
        }
    }

    fun isBanned(userId: UUID): Boolean = activeBan(userId) != null

    /** The ban currently keeping [userId] off voice, if any; the mod shows its reason. */
    fun activeBan(userId: UUID): Ban? = activeBanRows(listOf(userId)).firstOrNull()

    fun ban(userId: UUID, reason: String, bannedBy: String, expiresAt: Long?) {
        val now = clock()
        db(DbOp.BAN) {
            VoiceBansTable.insert {
                it[VoiceBansTable.userId] = userId
                it[VoiceBansTable.reason] = reason
                it[VoiceBansTable.bannedBy] = bannedBy
                it[bannedAt] = now
                it[VoiceBansTable.expiresAt] = expiresAt
            }
        }
    }

    /** Lifts every active ban of the player; returns how many were lifted. */
    fun unban(userId: UUID, liftedBy: String): Int {
        val now = clock()
        return db(DbOp.UNBAN) {
            VoiceBansTable.update({ (VoiceBansTable.userId eq userId) and VoiceBansTable.liftedAt.isNull() }) {
                it[liftedAt] = now
                it[VoiceBansTable.liftedBy] = liftedBy
            }
        }
    }

    /** Every ban ever recorded for [userId], oldest first, lifted and expired ones included: the audit trail. */
    fun banHistory(userId: UUID): List<Ban> = db(DbOp.BAN_HISTORY) {
        VoiceBansTable.selectAll().where { VoiceBansTable.userId eq userId }.orderBy(VoiceBansTable.bannedAt).map {
            Ban(it[VoiceBansTable.userId], it[VoiceBansTable.reason], it[VoiceBansTable.bannedBy], it[VoiceBansTable.expiresAt],
                it[VoiceBansTable.bannedAt], it[VoiceBansTable.liftedAt], it[VoiceBansTable.liftedBy])
        }
    }

    fun activeBanRows(userIds: Collection<UUID>? = null): List<Ban> {
        val now = clock()
        return db(DbOp.ACTIVE_BAN_ROWS) {
            VoiceBansTable.selectAll()
                .where { VoiceBansTable.liftedAt.isNull() and (VoiceBansTable.expiresAt.isNull() or (VoiceBansTable.expiresAt greater now)) }
                .apply { if (userIds != null) andWhere { VoiceBansTable.userId inList userIds } }
                .map { Ban(it[VoiceBansTable.userId], it[VoiceBansTable.reason], it[VoiceBansTable.bannedBy], it[VoiceBansTable.expiresAt]) }
        }
    }

    fun activeBans(userIds: Collection<UUID>): Set<UUID> =
        if (userIds.isEmpty()) emptySet() else activeBanRows(userIds).map { it.userId }.toSet()

    fun createReport(report: NewReport): Int {
        val now = clock()
        return db(DbOp.CREATE_REPORT) {
            VoiceReportsTable.insertAndGetId {
                it[reporterId] = report.reporterId
                it[targetId] = report.targetId
                it[reason] = report.reason
                it[world] = report.world
                it[instance] = report.instance
                it[reporterX] = report.reporterPos?.x
                it[reporterY] = report.reporterPos?.y
                it[reporterZ] = report.reporterPos?.z
                it[targetX] = report.targetPos?.x
                it[targetY] = report.targetPos?.y
                it[targetZ] = report.targetPos?.z
                it[witnessIds] = report.witnesses.joinToString(",")
                it[audioFrom] = report.audioFrom
                it[audioTo] = report.audioTo
                it[createdAt] = now
            }.value
        }
    }

    /** Reporter to target. */
    fun reportParties(reportId: Int): Pair<UUID, UUID>? = db(DbOp.REPORT_PARTIES) {
        VoiceReportsTable.selectAll().where { VoiceReportsTable.id eq reportId }.singleOrNull()
            ?.let { it[VoiceReportsTable.reporterId] to it[VoiceReportsTable.targetId] }
    }

    /** Ids of unhandled reports against [targetId]. */
    fun openReportsAgainst(targetId: UUID): List<Int> = db(DbOp.OPEN_REPORTS) {
        VoiceReportsTable.selectAll()
            .where { (VoiceReportsTable.targetId eq targetId) and VoiceReportsTable.outcome.isNull() }
            .map { it[VoiceReportsTable.id].value }
    }

    fun markHandled(reportId: Int, by: String, verdict: Verdict) {
        val now = clock()
        db(DbOp.MARK_HANDLED) {
            VoiceReportsTable.update({ VoiceReportsTable.id eq reportId }) {
                it[handled] = true
                it[handledBy] = by
                it[handledAt] = now
                it[outcome] = verdict.name
            }
        }
    }

    /** Handled reports whose reporter has not been told yet: id to verdict. */
    fun pendingOutcomes(reporterId: UUID): List<Pair<Int, Verdict>> = db(DbOp.PENDING_OUTCOMES) {
        VoiceReportsTable.selectAll()
            .where { (VoiceReportsTable.reporterId eq reporterId) and VoiceReportsTable.outcome.isNotNull() and VoiceReportsTable.notifiedAt.isNull() }
            .map { it[VoiceReportsTable.id].value to Verdict.valueOf(it[VoiceReportsTable.outcome]!!) }
    }

    /** False when someone else already notified, so a concurrent auth and button click deliver once. */
    fun markNotified(reportId: Int): Boolean {
        val now = clock()
        return db(DbOp.MARK_NOTIFIED) {
            VoiceReportsTable.update({ (VoiceReportsTable.id eq reportId) and VoiceReportsTable.notifiedAt.isNull() }) { it[notifiedAt] = now }
        } > 0
    }

    fun attachAudio(reportId: Int, reporterPath: String?, targetPath: String?) {
        db(DbOp.ATTACH_AUDIO) {
            VoiceReportsTable.update({ VoiceReportsTable.id eq reportId }) {
                it[reporterAudioPath] = reporterPath
                it[targetAudioPath] = targetPath
            }
        }
    }

    fun recordSession(uuid: UUID, name: String, startedAt: Long, tier: VoiceTier, world: String?): Int = db(DbOp.RECORD_SESSION) {
        VoiceSessionsTable.insertAndGetId {
            it[VoiceSessionsTable.uuid] = uuid
            it[VoiceSessionsTable.name] = name
            it[VoiceSessionsTable.startedAt] = startedAt
            it[VoiceSessionsTable.tier] = tier.name
            it[VoiceSessionsTable.world] = world
        }.value
    }

    fun endSession(sessionId: Int, endedAt: Long, reason: String) {
        db(DbOp.END_SESSION) {
            VoiceSessionsTable.update({ VoiceSessionsTable.id eq sessionId }) {
                it[VoiceSessionsTable.endedAt] = endedAt
                it[endReason] = reason
            }
        }
    }

    fun pruneSessions(startedBefore: Long) {
        db(DbOp.PRUNE_SESSIONS) { VoiceSessionsTable.deleteWhere { startedAt less startedBefore } }
    }

    /** Deletes bans that expired before [expiredBefore]; permanent bans have no expiry and stay. Returns the count. */
    fun pruneBans(expiredBefore: Long): Int = db(DbOp.PRUNE_BANS) {
        VoiceBansTable.deleteWhere { expiresAt.isNotNull() and (expiresAt less expiredBefore) }
    }

    fun pruneReports(createdBefore: Long): Int = db(DbOp.PRUNE_REPORTS) {
        VoiceReportsTable.deleteWhere { createdAt less createdBefore }
    }

    /** Reports filed before [createdBefore] whose clips are still on record; [attachAudio] with nulls once the files are gone. */
    fun reportsWithClipsBefore(createdBefore: Long): List<Int> = db(DbOp.REPORTS_WITH_CLIPS) {
        VoiceReportsTable.selectAll()
            .where { (VoiceReportsTable.createdAt less createdBefore) and (VoiceReportsTable.reporterAudioPath.isNotNull() or VoiceReportsTable.targetAudioPath.isNotNull()) }
            .map { it[VoiceReportsTable.id].value }
    }

    companion object {
        private val DB_TIMERS = DbOp.entries.associateWith { Metrics.registry.sloTimer("voice_db_seconds", Metrics.DB_SLO, "op", it.name.lowercase()) }

        fun openSqlite(path: String): Database = Database.connect("jdbc:sqlite:$path")
    }
}
