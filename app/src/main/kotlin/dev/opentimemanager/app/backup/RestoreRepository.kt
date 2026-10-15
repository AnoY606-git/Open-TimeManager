package dev.opentimemanager.app.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.withTransaction
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.data.PendingPreferencesRestoreEntity
import dev.opentimemanager.app.data.SegmentEntity
import dev.opentimemanager.app.data.TrackingSlotEntity
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserPreferences
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.core.backup.DEFAULT_PROJECT_CANONICAL_NAME
import dev.opentimemanager.core.backup.DEFAULT_PROJECT_COLOR_ARGB
import dev.opentimemanager.core.backup.PortablePreferences
import dev.opentimemanager.core.backup.PortableProject
import dev.opentimemanager.core.backup.PortableSnapshot
import dev.opentimemanager.core.model.TrackingState
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class RestoreRepository(
    private val database: AppDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
    private val settings: UserSettings? = null,
    private val afterReplaceDelete: suspend () -> Unit = {},
) {
    suspend fun preview(snapshot: PortableSnapshot): BackupPreview = withContext(dispatcher) {
        val payload = snapshot.toRestorePayload(nowUtcMillis())
        BackupPreview(
            counts = BackupCounts(snapshot.projects.size, snapshot.entries.size, snapshot.segments.size),
            conflicts = database.withTransaction { database.restoreDao().conflicts(payload) },
            skippedEntriesWithoutConfirmedTime = snapshot.entries.size - payload.entries.size,
            activeStateWillBeStopped = snapshot.activeEntry != null,
            payload = payload,
        )
    }

    suspend fun restore(preview: BackupPreview, mode: RestoreMode): RestoreResult = preview.usePayload { payload ->
        val result = withContext(dispatcher) {
            database.withTransaction { applyPayload(payload, mode) }
        }
        if (result is RestoreResult.Applied && mode == RestoreMode.REPLACE && settings != null) {
            // The Room graph is already durable. A DataStore/mirror failure leaves
            // the journal for startup retry and must not turn success into failure.
            try {
                withContext(NonCancellable) { reconcilePendingPreferences() }
            } catch (_: Exception) {
                // Durable recovery intent remains in Room.
            }
        }
        result
    }

    suspend fun reconcilePendingPreferences() = withContext(dispatcher) {
        val target = settings ?: return@withContext
        val pending = database.restoreDao().pendingPreferences() ?: return@withContext
        target.replace(pending.toUserPreferences())
        database.withTransaction {
            if (database.restoreDao().pendingPreferences()?.operationId == pending.operationId) {
                database.restoreDao().deletePendingPreferences(pending.operationId)
            }
        }
    }

    private suspend fun applyPayload(payload: RestorePayload, mode: RestoreMode): RestoreResult {
        val dao = database.restoreDao()
        val currentState = dao.slot()?.let { TrackingState.valueOf(it.state) } ?: TrackingState.IDLE
        if (currentState != TrackingState.IDLE) return RestoreResult.ActiveTimerBlocked(currentState)
        if (mode == RestoreMode.MERGE) {
            val conflicts = dao.conflicts(payload)
            if (conflicts.isNotEmpty()) return RestoreResult.Conflicts(conflicts)
        }
        if (dao.slot() == null) dao.insertSlot(TrackingSlotEntity())
        if (mode == RestoreMode.REPLACE) {
            dao.resetSlot(UUID.randomUUID().toString())
            dao.deleteCommands()
            dao.deleteSegments()
            dao.deleteEntries()
            dao.deleteProjects()
            afterReplaceDelete()
            currentCoroutineContext().ensureActive()
        }
        suspend fun <T> insertBatches(values: List<T>, insert: (List<T>) -> List<Long>): Int {
            var inserted = 0
            values.chunked(SQLITE_BIND_BATCH).forEach { batch ->
                currentCoroutineContext().ensureActive()
                inserted += insert(batch).count { it != -1L }
            }
            return inserted
        }
        val projects = insertBatches(payload.projects, dao::insertProjects)
        val entries = insertBatches(payload.entries, dao::insertEntries)
        val segments = insertBatches(payload.segments, dao::insertSegments)
        if (mode == RestoreMode.REPLACE && settings != null) {
            dao.savePendingPreferences(payload.pendingPreferences())
        }
        return RestoreResult.Applied(projects, entries, segments)
    }
}

data class RestorePayload(
    val projects: List<ProjectEntity>,
    val entries: List<EntryEntity>,
    val segments: List<SegmentEntity>,
    val preferences: PortablePreferences,
    val operationId: String,
    val sourceSchemaVersion: Int,
)

@Dao
abstract class RestoreDao {
    @Query("SELECT * FROM projects WHERE id IN (:ids) ORDER BY id")
    abstract fun projectsByIds(ids: List<String>): List<ProjectEntity>

    @Query("SELECT * FROM entries WHERE id IN (:ids) ORDER BY id")
    abstract fun entriesByIds(ids: List<String>): List<EntryEntity>

    @Query("SELECT * FROM segments WHERE id IN (:ids) ORDER BY id")
    abstract fun segmentsByIds(ids: List<String>): List<SegmentEntity>

    @Query("SELECT entryId, COUNT(*) AS count FROM segments WHERE entryId IN (:entryIds) GROUP BY entryId")
    abstract fun segmentCountsByEntryIds(entryIds: List<String>): List<EntrySegmentCount>

    @Query("SELECT * FROM tracking_slot WHERE id = 1")
    abstract fun slot(): TrackingSlotEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertProjects(projects: List<ProjectEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertEntries(entries: List<EntryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertSegments(segments: List<SegmentEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertSlot(slot: TrackingSlotEntity): Long

    @Query("UPDATE tracking_slot SET state = 'IDLE', entryId = NULL, revision = 0, generation = :generation WHERE id = 1")
    abstract fun resetSlot(generation: String)

    @Query("SELECT * FROM pending_preferences_restore WHERE id = 1")
    abstract fun pendingPreferences(): PendingPreferencesRestoreEntity?

    @Upsert
    abstract fun savePendingPreferences(value: PendingPreferencesRestoreEntity)

    @Query("DELETE FROM pending_preferences_restore WHERE id = 1 AND operationId = :operationId")
    abstract fun deletePendingPreferences(operationId: String)

    @Query("DELETE FROM applied_commands")
    abstract fun deleteCommands()

    @Query("DELETE FROM segments")
    abstract fun deleteSegments()

    @Query("DELETE FROM entries")
    abstract fun deleteEntries()

    @Query("DELETE FROM projects")
    abstract fun deleteProjects()

    @Transaction
    open fun conflicts(payload: RestorePayload): List<BackupConflict> = findConflicts(payload)

    private fun findConflicts(payload: RestorePayload): List<BackupConflict> {
        val localProjects = payload.projects.map(ProjectEntity::id).batched(::projectsByIds).associateBy(ProjectEntity::id)
        val localEntries = payload.entries.map(EntryEntity::id).batched(::entriesByIds).associateBy(EntryEntity::id)
        val localSegments = payload.segments.map(SegmentEntity::id).batched(::segmentsByIds).associateBy(SegmentEntity::id)
        val payloadSegmentsByEntry = payload.segments.groupBy(SegmentEntity::entryId)
        val localSegmentCounts = payload.entries.map(EntryEntity::id)
            .batched(::segmentCountsByEntryIds)
            .associate { it.entryId to it.count }
        val conflicts = mutableListOf<BackupConflict>()

        payload.projects.forEach { imported ->
            localProjects[imported.id]?.let { local ->
                val same = if (payload.sourceSchemaVersion == 1) {
                    local.name == imported.name
                } else {
                    local == imported
                }
                if (!same) {
                    conflicts += BackupConflict(BackupConflictKind.PROJECT, imported.id)
                }
            }
        }
        payload.entries.forEach { imported ->
            localEntries[imported.id]?.let { local ->
                val sameMetadata = local.projectId == imported.projectId &&
                    local.note == imported.note &&
                    local.source == imported.source &&
                    local.zoneId == imported.zoneId &&
                    local.completed
                val importedSegments = payloadSegmentsByEntry[imported.id].orEmpty()
                val sameSegments = localSegmentCounts[local.id].orZero() == importedSegments.size.toLong() &&
                    importedSegments.all { segment ->
                        localSegments[segment.id]?.let(::stableSegment) == stableSegment(segment)
                    }
                if (!sameMetadata || !sameSegments) {
                    conflicts += BackupConflict(BackupConflictKind.ENTRY, imported.id)
                }
            }
        }
        payload.segments.forEach { imported ->
            localSegments[imported.id]?.let { local ->
                if (stableSegment(local) != stableSegment(imported)) {
                    conflicts += BackupConflict(BackupConflictKind.SEGMENT, imported.id)
                }
            }
        }
        return conflicts.distinct().sortedWith(compareBy({ it.kind.ordinal }, BackupConflict::id))
    }
}

data class EntrySegmentCount(
    val entryId: String,
    val count: Long,
)

private const val SQLITE_BIND_BATCH = 500

private fun <T> List<String>.batched(query: (List<String>) -> List<T>): List<T> =
    chunked(SQLITE_BIND_BATCH).flatMap(query)

private fun Long?.orZero(): Long = this ?: 0L

private data class StableSegment(
    val id: String,
    val entryId: String,
    val startedAtUtcMillis: Long,
    val durationMillis: Long?,
    val endUtcMillis: Long?,
)

private fun stableSegment(value: SegmentEntity) = StableSegment(
    value.id,
    value.entryId,
    value.startedAtUtcMillis,
    value.durationMillis,
    value.endUtcMillis,
)

private fun PortableSnapshot.toRestorePayload(nowUtcMillis: Long): RestorePayload {
    validateForRestore()
    val segmentsByEntry = segments.groupBy { it.entryId }
    val importableEntries = entries.filter { segmentsByEntry[it.id].orEmpty().isNotEmpty() }
    val sourceProjects = projects.ifEmpty {
        listOf(
            PortableProject(
                ProjectRepository.DEFAULT_PROJECT_ID,
                DEFAULT_PROJECT_CANONICAL_NAME,
                DEFAULT_PROJECT_COLOR_ARGB,
                nowUtcMillis,
                null,
                true,
            ),
        )
    }
    val importedProjects = sourceProjects.map { project ->
        val isDefault = project.id == ProjectRepository.DEFAULT_PROJECT_ID
        ProjectEntity(
            id = project.id.value,
            name = if (isDefault) ProjectRepository.DEFAULT_PROJECT_STORAGE_NAME else Normalizer.normalize(project.name.trim(), Normalizer.Form.NFC),
            colorArgb = project.colorArgb,
            createdAtUtcMillis = project.createdAtUtcMillis,
            archivedAtUtcMillis = if (isDefault) null else project.archivedAtUtcMillis,
        )
    }
    val entryEntities = importableEntries.map { entry ->
        val entrySegments = requireNotNull(segmentsByEntry[entry.id])
        EntryEntity(
            id = entry.id.value,
            projectId = entry.projectId.value,
            note = entry.note,
            startedAtUtcMillis = entrySegments.minOf { it.startUtcMillis },
            endedAtUtcMillis = entrySegments.maxOf { it.endUtcMillis },
            source = entry.source.name,
            revision = 0,
            completed = true,
            currentSlot = null,
            zoneId = entry.zoneId.id,
        )
    }
    val importedEntryIds = entryEntities.mapTo(hashSetOf(), EntryEntity::id)
    val segmentEntities = segments.asSequence()
        .filter { it.entryId.value in importedEntryIds }
        .map { segment ->
            SegmentEntity(
                id = segment.id.value,
                entryId = segment.entryId.value,
                startedAtUtcMillis = segment.startUtcMillis,
                startedElapsedRealtimeMillis = null,
                bootCount = null,
                durationMillis = segment.durationMillis,
                endUtcMillis = segment.endUtcMillis,
                openSlot = null,
            )
        }
        .toList()
    return RestorePayload(
        importedProjects,
        entryEntities,
        segmentEntities,
        preferences,
        UUID.randomUUID().toString(),
        sourceSchemaVersion,
    )
}

internal class RestoreValidationError : IllegalArgumentException()

private fun PortableSnapshot.validateForRestore() {
    projects.forEach { project ->
        val normalized = Normalizer.normalize(project.name.trim(), Normalizer.Form.NFC)
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length) > 80) {
            throw RestoreValidationError()
        }
        val reserved = project.id == ProjectRepository.DEFAULT_PROJECT_ID
        if (project.isDefault != reserved || (reserved && project.name != DEFAULT_PROJECT_CANONICAL_NAME)) {
            throw RestoreValidationError()
        }
    }
    entries.forEach { entry ->
        if (entry.note.codePointCount(0, entry.note.length) > 2_000) throw RestoreValidationError()
    }
    if (preferences.dailyGoalMinutes !in 15..1_440) throw RestoreValidationError()
    val durationByEntry = mutableMapOf<String, Long>()
    segments.forEach { segment ->
        durationByEntry[segment.entryId.value] = try {
            Math.addExact(durationByEntry[segment.entryId.value] ?: 0L, segment.durationMillis)
        } catch (_: ArithmeticException) {
            throw RestoreValidationError()
        }
    }
}

private fun RestorePayload.pendingPreferences() = PendingPreferencesRestoreEntity(
    operationId = operationId,
    theme = preferences.theme.name,
    dynamicColor = preferences.dynamicColor,
    privacyMode = preferences.privacyMode,
    dailyGoalMinutes = preferences.dailyGoalMinutes,
)

private fun PendingPreferencesRestoreEntity.toUserPreferences() = UserPreferences(
    theme = ThemeMode.valueOf(theme),
    dynamicColor = dynamicColor,
    privacyMode = privacyMode,
    dailyGoalMinutes = dailyGoalMinutes,
)
