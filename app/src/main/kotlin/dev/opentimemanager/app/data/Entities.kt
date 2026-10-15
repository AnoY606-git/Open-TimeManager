package dev.opentimemanager.app.data

import androidx.room.*

@Entity(
    tableName = "projects",
    indices = [
        Index("archivedAtUtcMillis"),
        Index(value = ["archivedAtUtcMillis", "createdAtUtcMillis", "id"]),
    ],
)
data class ProjectEntity(@PrimaryKey val id: String, val name: String, val colorArgb: Int, val createdAtUtcMillis: Long, val archivedAtUtcMillis: Long? = null)

@Entity(tableName = "entries", foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("projectId"), Index("startedAtUtcMillis"), Index(value = ["currentSlot"], unique = true)])
data class EntryEntity(
    @PrimaryKey val id: String, val projectId: String, val note: String,
    val startedAtUtcMillis: Long, val endedAtUtcMillis: Long?, val source: String,
    val revision: Long, val completed: Boolean, val currentSlot: Int? = null,
    @ColumnInfo(defaultValue = "'UTC'") val zoneId: String = "UTC",
    @ColumnInfo(defaultValue = "0") val confirmedDurationMillis: Long = 0,
    val lastConfirmedEndUtcMillis: Long? = null,
)

@Entity(tableName = "segments", foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("entryId"), Index(value = ["startedAtUtcMillis", "endUtcMillis"]), Index(value = ["openSlot"], unique = true)])
data class SegmentEntity(
    @PrimaryKey val id: String, val entryId: String, val startedAtUtcMillis: Long,
    val startedElapsedRealtimeMillis: Long?, val bootCount: Long?, val durationMillis: Long?,
    val endUtcMillis: Long?, val openSlot: Int? = null,
)

@Entity(tableName = "tracking_slot", foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("entryId")])
data class TrackingSlotEntity(
    @PrimaryKey val id: Int = 1,
    val state: String = "IDLE",
    val entryId: String? = null,
    val revision: Long = 0,
    val generation: String = dev.opentimemanager.app.tracking.LEGACY_COMMAND_GENERATION,
)

/** Последние 1024 успешные команды; более старые повторы отвергаются монотонной revision. */
@Entity(tableName = "applied_commands", indices = [Index(value = ["revision"], unique = true)])
data class AppliedCommandEntity(@PrimaryKey val commandId: String, val revision: Long, val entryId: String?, val state: String)

/** Durable intent to reconcile DataStore after a Room restore commit. */
@Entity(tableName = "pending_preferences_restore")
data class PendingPreferencesRestoreEntity(
    @PrimaryKey val id: Int = 1,
    val operationId: String,
    val theme: String,
    val dynamicColor: Boolean,
    val privacyMode: Boolean,
    val dailyGoalMinutes: Int,
)
