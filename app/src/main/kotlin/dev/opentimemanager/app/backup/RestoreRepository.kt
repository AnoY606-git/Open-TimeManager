package dev.opentimemanager.app.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.data.SegmentEntity
import dev.opentimemanager.app.data.TrackingSlotEntity
import dev.opentimemanager.core.backup.PortableSnapshot
import dev.opentimemanager.core.model.TrackingState
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class RestoreRepository(
    private val database: AppDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val nowUtcMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun preview(snapshot: PortableSnapshot): BackupPreview = withContext(dispatcher) {
        val payload = snapshot.toRestorePayload(nowUtcMillis())
        BackupPreview(
            counts = BackupCounts(snapshot.projects.size, snapshot.entries.size, snapshot.segments.size),
            conflicts = database.restoreDao().conflicts(payload),
            skippedEntriesWithoutConfirmedTime = snapshot.entries.size - payload.entries.size,
            activeStateWillBeStopped = snapshot.activeEntry != null,
            snapshot = snapshot,
        )
    }

    suspend fun restore(preview: BackupPreview, mode: RestoreMode): RestoreResult {
        val snapshot = preview.takePayload()
        return withContext(dispatcher) {
            database.restoreDao().restore(snapshot.toRestorePayload(nowUtcMillis()), mode)
        }
    }
}

data class RestorePayload(
    val projects: List<ProjectEntity>,
    val entries: List<EntryEntity>,
    val segments: List<SegmentEntity>,
)

@Dao
abstract class RestoreDao {
    @Query("SELECT * FROM projects ORDER BY id")
    abstract fun projects(): List<ProjectEntity>

    @Query("SELECT * FROM entries ORDER BY id")
    abstract fun entries(): List<EntryEntity>

    @Query("SELECT * FROM segments ORDER BY id")
    abstract fun segments(): List<SegmentEntity>

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

    @Query("UPDATE tracking_slot SET state = 'IDLE', entryId = NULL, revision = 0 WHERE id = 1")
    abstract fun resetSlot()

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

    @Transaction
    open fun restore(payload: RestorePayload, mode: RestoreMode): RestoreResult {
        val currentState = slot()?.let { TrackingState.valueOf(it.state) } ?: TrackingState.IDLE
        if (currentState != TrackingState.IDLE) return RestoreResult.ActiveTimerBlocked(currentState)

        val conflicts = findConflicts(payload)
        if (mode == RestoreMode.MERGE && conflicts.isNotEmpty()) return RestoreResult.Conflicts(conflicts)

        if (slot() == null) insertSlot(TrackingSlotEntity())
        if (mode == RestoreMode.REPLACE) {
            // Сначала убираем FK из singleton slot; вся последовательность покрыта
            // одной Room-транзакцией, поэтому любой SQLite-отказ вернёт исходную БД.
            resetSlot()
            deleteCommands()
            deleteSegments()
            deleteEntries()
            deleteProjects()
        }

        val projectResults = insertProjects(payload.projects)
        val entryResults = insertEntries(payload.entries)
        val segmentResults = insertSegments(payload.segments)
        return RestoreResult.Applied(
            projects = projectResults.count { it != -1L },
            entries = entryResults.count { it != -1L },
            segments = segmentResults.count { it != -1L },
        )
    }

    private fun findConflicts(payload: RestorePayload): List<BackupConflict> {
        val localProjects = projects().associateBy(ProjectEntity::id)
        val localEntries = entries().associateBy(EntryEntity::id)
        val localSegments = segments().associateBy(SegmentEntity::id)
        val payloadSegmentsByEntry = payload.segments.groupBy(SegmentEntity::entryId)
        val localSegmentsByEntry = localSegments.values.groupBy(SegmentEntity::entryId)
        val conflicts = mutableListOf<BackupConflict>()

        payload.projects.forEach { imported ->
            localProjects[imported.id]?.let { local ->
                if (local.name != imported.name) {
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
                val sameSegments = localSegmentsByEntry[local.id].orEmpty().map(::stableSegment)
                    .toSet() == payloadSegmentsByEntry[imported.id].orEmpty().map(::stableSegment).toSet()
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
        listOf(dev.opentimemanager.core.backup.PortableProject(ProjectRepository.DEFAULT_PROJECT_ID, "Без проекта"))
    }
    val importedProjects = sourceProjects.map { project ->
        ProjectEntity(
            id = project.id.value,
            name = Normalizer.normalize(project.name.trim(), Normalizer.Form.NFC),
            colorArgb = stableProjectColor(project.id.value),
            createdAtUtcMillis = nowUtcMillis,
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
    return RestorePayload(importedProjects, entryEntities, segmentEntities)
}

internal class RestoreValidationError : IllegalArgumentException()

private fun PortableSnapshot.validateForRestore() {
    projects.forEach { project ->
        val normalized = Normalizer.normalize(project.name.trim(), Normalizer.Form.NFC)
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length) > 80) {
            throw RestoreValidationError()
        }
    }
    entries.forEach { entry ->
        if (entry.note.codePointCount(0, entry.note.length) > 2_000) throw RestoreValidationError()
    }
}

private val importedProjectColors = intArrayOf(
    0xff006a6a.toInt(),
    0xff745b00.toInt(),
    0xff4f635e.toInt(),
    0xff6b5778.toInt(),
)

private fun stableProjectColor(id: String): Int =
    importedProjectColors[(id.lowercase(Locale.ROOT).hashCode() and Int.MAX_VALUE) % importedProjectColors.size]
