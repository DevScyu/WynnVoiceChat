package wynnvoice.server.voice

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object VoiceBlocksTable : Table("voice_blocks") {
    val blocker = javaUUID("blocker_id")
    val blocked = javaUUID("blocked_id")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(blocker, blocked)
}

/** One active mute per guild and member; unmuting deletes the row. */
object GuildMutesTable : Table("guild_mutes") {
    val guildId = javaUUID("guild_id")
    val targetId = javaUUID("target_id")
    val mutedBy = javaUUID("muted_by")
    val expiresAt = long("expires_at").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(guildId, targetId)
}

object VoiceBansTable : IntIdTable("voice_bans") {
    val userId = javaUUID("user_id").index()
    val reason = text("reason")
    val bannedBy = text("banned_by")
    val bannedAt = long("banned_at")
    val expiresAt = long("expires_at").nullable()
    val liftedAt = long("lifted_at").nullable()
}

object VoiceReportsTable : IntIdTable("voice_reports") {
    val reporterId = javaUUID("reporter_id").index()
    val targetId = javaUUID("target_id").index()
    val reason = text("reason")
    val world = text("world")
    val instance = text("instance")
    val reporterX = float("reporter_x").nullable()
    val reporterY = float("reporter_y").nullable()
    val reporterZ = float("reporter_z").nullable()
    val targetX = float("target_x").nullable()
    val targetY = float("target_y").nullable()
    val targetZ = float("target_z").nullable()
    val witnessIds = text("witness_ids") // comma-separated UUIDs of everyone audible to the reporter
    val audioFrom = long("audio_from").nullable()
    val audioTo = long("audio_to").nullable()
    val reporterAudioPath = text("reporter_audio_path").nullable()
    val targetAudioPath = text("target_audio_path").nullable()
    val createdAt = long("created_at")
    val handled = bool("handled").default(false)
    val handledBy = text("handled_by").nullable()
    val handledAt = long("handled_at").nullable()
    val outcome = text("outcome").nullable() // Verdict name
    val notifiedAt = long("notified_at").nullable()
}

/** One row per voice session; the only place identity meets analytics (Grafana's SQLite datasource). */
object VoiceSessionsTable : IntIdTable("voice_sessions") {
    val uuid = javaUUID("uuid").index()
    val name = text("name")
    val startedAt = long("started_at").index()
    val endedAt = long("ended_at").nullable()
    val tier = text("tier")
    val world = text("world").nullable()
    val endReason = text("end_reason").nullable()
}
