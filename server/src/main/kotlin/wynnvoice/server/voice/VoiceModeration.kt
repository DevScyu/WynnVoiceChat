package wynnvoice.server.voice

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import wynnvoice.protocol.Packet.Position

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

/**
 * Blocks, bans and report rows. Blocks are cached in memory because routing asks per mic frame;
 * bans are read on demand (auth) and by the 60 s poll in VoiceManager.
 */
class VoiceModeration(val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    private val logger = LoggerFactory.getLogger(VoiceModeration::class.java)

    private val blocks = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    fun init() {
        transaction(db) { SchemaUtils.create(VoiceBlocksTable, VoiceBansTable, VoiceReportsTable) }
        blocks.clear()
        transaction(db) { VoiceBlocksTable.selectAll().map { it[VoiceBlocksTable.blocker] to it[VoiceBlocksTable.blocked] } }
            .forEach { (blocker, blocked) -> blocks.computeIfAbsent(blocker) { ConcurrentHashMap.newKeySet() }.add(blocked) }
        logger.info("Voice moderation loaded: {} blocks", blocks.values.sumOf { it.size })
    }

    fun isBlocked(a: UUID, b: UUID): Boolean =
        blocks[a]?.contains(b) == true || blocks[b]?.contains(a) == true

    fun block(blocker: UUID, blocked: UUID) {
        val added = blocks.computeIfAbsent(blocker) { ConcurrentHashMap.newKeySet() }.add(blocked)
        if (!added) return
        val now = clock()
        transaction(db) {
            VoiceBlocksTable.insert {
                it[VoiceBlocksTable.blocker] = blocker
                it[VoiceBlocksTable.blocked] = blocked
                it[createdAt] = now
            }
        }
    }

    fun unblock(blocker: UUID, blocked: UUID) {
        blocks[blocker]?.remove(blocked)
        transaction(db) {
            VoiceBlocksTable.deleteWhere { (VoiceBlocksTable.blocker eq blocker) and (VoiceBlocksTable.blocked eq blocked) }
        }
    }

    fun isBanned(userId: UUID): Boolean = activeBans(listOf(userId)).isNotEmpty()

    fun activeBans(userIds: Collection<UUID>): Set<UUID> {
        if (userIds.isEmpty()) return emptySet()
        val now = clock()
        return transaction(db) {
            VoiceBansTable.selectAll()
                .where {
                    (VoiceBansTable.userId inList userIds) and
                        VoiceBansTable.liftedAt.isNull() and
                        (VoiceBansTable.expiresAt.isNull() or (VoiceBansTable.expiresAt greater now))
                }
                .map { it[VoiceBansTable.userId] }
                .toSet()
        }
    }

    fun createReport(report: NewReport): Int {
        val now = clock()
        return transaction(db) {
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

    fun attachAudio(reportId: Int, reporterPath: String?, targetPath: String?) {
        transaction(db) {
            VoiceReportsTable.update({ VoiceReportsTable.id eq reportId }) {
                it[reporterAudioPath] = reporterPath
                it[targetAudioPath] = targetPath
            }
        }
    }

    companion object {
        fun openSqlite(path: String): Database = Database.connect("jdbc:sqlite:$path")
    }
}
