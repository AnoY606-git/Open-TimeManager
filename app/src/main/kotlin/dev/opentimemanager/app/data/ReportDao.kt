package dev.opentimemanager.app.data

import androidx.room.*
import dev.opentimemanager.core.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface EditResult {
    data object Applied : EditResult
    data object NotFound : EditResult
    data object StaleRevision : EditResult
    data object ActiveEntry : EditResult
    data object InvalidInput : EditResult
    data object MissingProject : EditResult
    data object ArchivedProject : EditResult
}

@Dao
abstract class ReportDao {
    @Query("SELECT segments.* FROM segments WHERE segments.durationMillis IS NOT NULL AND segments.startedAtUtcMillis < :to AND segments.endUtcMillis > :from ORDER BY segments.startedAtUtcMillis, segments.id")
    abstract fun range(from: Long, to: Long): List<SegmentEntity>
    @Query("SELECT * FROM entries WHERE id = :id") abstract fun entry(id: String): EntryEntity?
    @Query("SELECT * FROM projects WHERE id = :id") abstract fun project(id: String): ProjectEntity?
    @Query("DELETE FROM segments WHERE entryId = :id") abstract fun deleteSegments(id: String)
    @Query("DELETE FROM entries WHERE id = :id") abstract fun deleteEntry(id: String)
    @Upsert abstract fun saveEntry(entry: EntryEntity)
    @Insert abstract fun insertSegment(segment: SegmentEntity)

    @Transaction
    open fun save(id: String, projectId: String, note: String, start: Long, duration: Long, expectedRevision: Long?): EditResult {
        val current = entry(id)
        if (current != null && !current.completed) return EditResult.ActiveEntry
        if (current == null && expectedRevision != null) return EditResult.NotFound
        if (current != null && current.revision != expectedRevision) return EditResult.StaleRevision
        if (note.codePointCount(0, note.length) > 2000 || duration <= 0) return EditResult.InvalidInput
        val end = try { Math.addExact(start, duration) } catch (_: ArithmeticException) { return EditResult.InvalidInput }
        val revision = try { current?.revision?.let { Math.incrementExact(it) } ?: 0 } catch (_: ArithmeticException) { return EditResult.InvalidInput }
        val project = project(projectId) ?: return EditResult.MissingProject
        if (project.archivedAtUtcMillis != null && current?.projectId != projectId) return EditResult.ArchivedProject
        saveEntry(EntryEntity(id, projectId, note, start, end, EntrySource.MANUAL.name, revision, true))
        deleteSegments(id)
        insertSegment(SegmentEntity(UUID.randomUUID().toString(), id, start, null, null, duration, end))
        return EditResult.Applied
    }

    @Transaction
    open fun delete(id: String, expectedRevision: Long): EditResult {
        val current = entry(id) ?: return EditResult.NotFound
        if (!current.completed) return EditResult.ActiveEntry
        if (current.revision != expectedRevision) return EditResult.StaleRevision
        deleteSegments(id)
        deleteEntry(id)
        return EditResult.Applied
    }

    @Transaction
    open fun report(from: Long, to: Long): List<Segment> {
        require(from < to)
        return range(from, to).map { it.closed(requireNotNull(entry(it.entryId)).projectId) }
    }
}

class CompletedEntryRepository(private val database: AppDatabase, private val dispatcher: CoroutineDispatcher) {
    suspend fun save(id: EntryId, projectId: ProjectId, note: String, startUtcMillis: Long, durationMillis: Long, expectedRevision: Long?): EditResult = withContext(dispatcher) {
        database.reportDao().save(id.value, projectId.value, note, startUtcMillis, durationMillis, expectedRevision)
    }
    suspend fun delete(id: EntryId, expectedRevision: Long): EditResult = withContext(dispatcher) { database.reportDao().delete(id.value, expectedRevision) }
    suspend fun report(fromUtcMillis: Long, toUtcMillis: Long): List<Segment> = withContext(dispatcher) { database.reportDao().report(fromUtcMillis, toUtcMillis) }
}
