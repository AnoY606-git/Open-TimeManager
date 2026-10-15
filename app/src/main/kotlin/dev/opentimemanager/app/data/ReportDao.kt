package dev.opentimemanager.app.data

import androidx.room.*
import dev.opentimemanager.core.model.*
import dev.opentimemanager.core.report.ReportAggregator
import dev.opentimemanager.core.report.ReportTotals
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.ZoneId
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
    @Query("""SELECT segments.id, segments.entryId, entries.projectId,
        segments.startedAtUtcMillis, segments.durationMillis
        FROM segments JOIN entries ON entries.id = segments.entryId
        WHERE segments.durationMillis IS NOT NULL
          AND segments.startedAtUtcMillis < :to AND segments.endUtcMillis > :from
        ORDER BY segments.startedAtUtcMillis, segments.id""")
    abstract fun range(from: Long, to: Long): List<ReportSegmentRow>
    @Query("""SELECT DISTINCT entries.* FROM entries
        JOIN segments ON segments.entryId = entries.id
        WHERE segments.durationMillis IS NOT NULL
          AND segments.startedAtUtcMillis < :to AND segments.endUtcMillis > :from
        ORDER BY entries.startedAtUtcMillis, entries.id""")
    abstract fun rangeEntries(from: Long, to: Long): List<EntryEntity>
    @Query("""SELECT DISTINCT projects.* FROM projects
        JOIN entries ON entries.projectId = projects.id
        JOIN segments ON segments.entryId = entries.id
        WHERE segments.durationMillis IS NOT NULL
          AND segments.startedAtUtcMillis < :to AND segments.endUtcMillis > :from
        ORDER BY projects.createdAtUtcMillis, projects.id""")
    abstract fun rangeProjects(from: Long, to: Long): List<ProjectEntity>
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
        saveEntry(EntryEntity(
            id,
            projectId,
            note,
            start,
            end,
            EntrySource.MANUAL.name,
            revision,
            true,
            zoneId = current?.zoneId ?: ZoneId.systemDefault().id,
            confirmedDurationMillis = duration,
            lastConfirmedEndUtcMillis = end,
        ))
        deleteSegments(id)
        insertSegment(SegmentEntity(UUID.randomUUID().toString(), id, start, null, null, duration, end))
        return EditResult.Applied
    }

    @Transaction
    open fun updateMetadata(id: String, projectId: String, note: String, expectedRevision: Long): EditResult {
        val current = entry(id) ?: return EditResult.NotFound
        if (!current.completed) return EditResult.ActiveEntry
        if (current.revision != expectedRevision) return EditResult.StaleRevision
        if (note.codePointCount(0, note.length) > 2000) return EditResult.InvalidInput
        val revision = try { Math.incrementExact(current.revision) } catch (_: ArithmeticException) {
            return EditResult.InvalidInput
        }
        val project = project(projectId) ?: return EditResult.MissingProject
        if (project.archivedAtUtcMillis != null && current.projectId != projectId) return EditResult.ArchivedProject
        saveEntry(current.copy(projectId = projectId, note = note, revision = revision))
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
        return range(from, to).map(ReportSegmentRow::toDomain)
    }

    @Transaction
    open fun reportSummary(from: Long, to: Long): ReportRangeSummary {
        val segments = report(from, to)
        return ReportRangeSummary(segments, rangeProjects(from, to))
    }

    @Transaction
    open fun reportDetails(from: Long, to: Long): ReportRangeDetails {
        val segments = report(from, to)
        return ReportRangeDetails(segments, rangeEntries(from, to), rangeProjects(from, to))
    }
}

data class ReportSegmentRow(
    val id: String,
    val entryId: String,
    val projectId: String,
    val startedAtUtcMillis: Long,
    val durationMillis: Long,
) {
    fun toDomain() = Segment(
        SegmentId(id),
        EntryId(entryId),
        ProjectId(projectId),
        startedAtUtcMillis,
        durationMillis,
    )
}

data class ReportRangeSummary(
    val segments: List<Segment>,
    val projects: List<ProjectEntity>,
)

data class ReportRangeDetails(
    val segments: List<Segment>,
    val entries: List<EntryEntity>,
    val projects: List<ProjectEntity>,
)

data class ReportSummaryProjection(
    val totals: ReportTotals,
    val projects: List<ProjectEntity>,
)

class CompletedEntryRepository(private val database: AppDatabase, private val dispatcher: CoroutineDispatcher) {
    suspend fun save(id: EntryId, projectId: ProjectId, note: String, startUtcMillis: Long, durationMillis: Long, expectedRevision: Long?): EditResult = withContext(dispatcher) {
        database.reportDao().save(id.value, projectId.value, note, startUtcMillis, durationMillis, expectedRevision)
    }
    suspend fun updateMetadata(id: EntryId, projectId: ProjectId, note: String, expectedRevision: Long): EditResult = withContext(dispatcher) {
        database.reportDao().updateMetadata(id.value, projectId.value, note, expectedRevision)
    }
    suspend fun delete(id: EntryId, expectedRevision: Long): EditResult = withContext(dispatcher) { database.reportDao().delete(id.value, expectedRevision) }
    suspend fun report(fromUtcMillis: Long, toUtcMillis: Long): List<Segment> = withContext(dispatcher) { database.reportDao().report(fromUtcMillis, toUtcMillis) }
    fun observeReportTotals(fromUtcMillis: Long, toUtcMillis: Long): Flow<ReportTotals> =
        observeReportSummary(fromUtcMillis, toUtcMillis).map { it.totals }

    fun observeReportSummary(fromUtcMillis: Long, toUtcMillis: Long): Flow<ReportSummaryProjection> =
        database.invalidationTracker.createFlow("projects", "entries", "segments").map {
            withContext(dispatcher) {
                val summary = database.reportDao().reportSummary(fromUtcMillis, toUtcMillis)
                ReportSummaryProjection(
                    ReportAggregator.aggregateTotals(
                        summary.segments,
                        fromUtcMillis,
                        toUtcMillis,
                    ),
                    summary.projects,
                )
            }
        }

    fun observeReport(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Segment>> =
        observeReportDetails(fromUtcMillis, toUtcMillis).map { it.segments }

    fun observeReportDetails(fromUtcMillis: Long, toUtcMillis: Long): Flow<ReportRangeDetails> =
        database.invalidationTracker.createFlow("projects", "entries", "segments").map {
            withContext(dispatcher) { database.reportDao().reportDetails(fromUtcMillis, toUtcMillis) }
        }
}
