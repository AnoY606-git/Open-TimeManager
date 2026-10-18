package dev.opentimemanager.app.data

import androidx.room.*
import dev.opentimemanager.core.backup.DEFAULT_PROJECT_CANONICAL_NAME
import dev.opentimemanager.core.backup.DEFAULT_PROJECT_UUID
import dev.opentimemanager.core.model.ProjectId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.Normalizer

enum class ProjectEditResult { APPLIED, NOT_FOUND, DUPLICATE_ID, INVALID_NAME, LIMIT_REACHED }

@Dao
abstract class ProjectDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insert(project: ProjectEntity): Long
    @Query("SELECT COUNT(*) FROM projects") abstract fun projectCount(): Long
    @Query("UPDATE projects SET name = :name, colorArgb = :color WHERE id = :id") abstract fun update(id: String, name: String, color: Int): Int
    @Query("""
        UPDATE projects SET archivedAtUtcMillis = :at
        WHERE id = :id AND id != '$DEFAULT_PROJECT_UUID'
          AND NOT EXISTS (
              SELECT 1 FROM tracking_slot
              JOIN entries ON entries.id = tracking_slot.entryId
              WHERE entries.projectId = :id AND tracking_slot.state != 'IDLE'
          )
    """)
    abstract fun archive(id: String, at: Long): Int
    @Query("SELECT COUNT(*) FROM entries WHERE projectId = :id") abstract fun entryCount(id: String): Int
    @Query("DELETE FROM projects WHERE id = :id") abstract fun deleteEmpty(id: String): Int
    @Query("SELECT * FROM projects WHERE id = :id") abstract fun project(id: String): ProjectEntity?
    @Query("""
        SELECT * FROM projects
        WHERE archivedAtUtcMillis IS NULL
        ORDER BY createdAtUtcMillis DESC, id DESC
        LIMIT :limit
    """)
    abstract fun firstActivePage(limit: Int): List<ProjectEntity>
    @Query("""
        SELECT * FROM projects
        WHERE archivedAtUtcMillis IS NULL
          AND (createdAtUtcMillis < :createdAt OR (createdAtUtcMillis = :createdAt AND id < :id))
        ORDER BY createdAtUtcMillis DESC, id DESC
        LIMIT :limit
    """)
    abstract fun activePageAfter(createdAt: Long, id: String, limit: Int): List<ProjectEntity>
    @Transaction open fun insertBounded(project: ProjectEntity, maximum: Long): ProjectEditResult {
        if (projectCount() >= maximum) return ProjectEditResult.LIMIT_REACHED
        return if (insert(project) == -1L) ProjectEditResult.DUPLICATE_ID else ProjectEditResult.APPLIED
    }
    @Transaction open fun deleteOrArchive(id: String, now: Long): Int = if (entryCount(id) > 0) archive(id, now) else deleteEmpty(id)
}

data class ProjectPageCursor(val createdAtUtcMillis: Long, val id: String)

data class ManualEntryProjectPage(
    val projects: List<ProjectEntity>,
    val pinnedProject: ProjectEntity?,
    val nextCursor: ProjectPageCursor?,
) {
    val hasMore: Boolean get() = nextCursor != null
}

class ProjectRepository(private val database: AppDatabase, private val dispatcher: CoroutineDispatcher) {
    companion object {
        val DEFAULT_PROJECT_ID = ProjectId(DEFAULT_PROJECT_UUID)
        const val DEFAULT_PROJECT_STORAGE_NAME = DEFAULT_PROJECT_CANONICAL_NAME
        const val MAX_PROJECTS = 20_000
    }
    suspend fun create(id: ProjectId, name: String, colorArgb: Int, nowUtcMillis: Long): ProjectEditResult = withContext(dispatcher) {
        val normalized = normalizedName(name) ?: return@withContext ProjectEditResult.INVALID_NAME
        database.projectDao().insertBounded(
            ProjectEntity(id.value, normalized, colorArgb, nowUtcMillis),
            MAX_PROJECTS.toLong(),
        )
    }
    suspend fun update(id: ProjectId, name: String, colorArgb: Int): ProjectEditResult = withContext(dispatcher) {
        val normalized = normalizedName(name) ?: return@withContext ProjectEditResult.INVALID_NAME
        changed(database.projectDao().update(id.value, normalized, colorArgb))
    }
    suspend fun archive(id: ProjectId, atUtcMillis: Long): ProjectEditResult = withContext(dispatcher) {
        if (id == DEFAULT_PROJECT_ID) return@withContext ProjectEditResult.NOT_FOUND
        changed(database.projectDao().archive(id.value, atUtcMillis))
    }
    suspend fun delete(id: ProjectId, nowUtcMillis: Long): ProjectEditResult = withContext(dispatcher) { changed(database.projectDao().deleteOrArchive(id.value, nowUtcMillis)) }
    suspend fun activeProject(id: ProjectId): ProjectEntity? = withContext(dispatcher) {
        database.projectDao().project(id.value)?.takeIf { it.archivedAtUtcMillis == null }
    }
    suspend fun manualEntryPage(
        query: String,
        cursor: ProjectPageCursor?,
        pinnedProjectId: String?,
        pageSize: Int,
        displayName: (ProjectEntity) -> String,
    ): ManualEntryProjectPage = withContext(dispatcher) {
        require(pageSize in 1..MAX_MANUAL_PICKER_PAGE_SIZE)
        val dao = database.projectDao()
        val normalizedQuery = query.trim()
        val matches = ArrayList<ProjectEntity>(pageSize + 1)
        var scanCursor = cursor
        while (matches.size <= pageSize) {
            currentCoroutineContext().ensureActive()
            val batch = if (scanCursor == null) {
                dao.firstActivePage(MANUAL_PICKER_SCAN_SIZE)
            } else {
                dao.activePageAfter(scanCursor.createdAtUtcMillis, scanCursor.id, MANUAL_PICKER_SCAN_SIZE)
            }
            if (batch.isEmpty()) break
            for (project in batch) {
                scanCursor = project.cursor()
                if (normalizedQuery.isBlank() || displayName(project).contains(normalizedQuery, ignoreCase = true)) {
                    matches += project
                    if (matches.size > pageSize) break
                }
            }
            if (matches.size > pageSize || batch.size < MANUAL_PICKER_SCAN_SIZE) break
        }
        val projects = matches.take(pageSize)
        val pinned = pinnedProjectId
            ?.takeUnless { pinnedId -> projects.any { it.id == pinnedId } }
            ?.let(dao::project)
        ManualEntryProjectPage(
            projects = projects,
            pinnedProject = pinned,
            nextCursor = projects.lastOrNull()?.cursor().takeIf { matches.size > pageSize },
        )
    }
    private fun changed(rows: Int) = if (rows == 0) ProjectEditResult.NOT_FOUND else ProjectEditResult.APPLIED
    private fun normalizedName(name: String): String? = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).takeIf { it.isNotBlank() && it.codePointCount(0, it.length) <= 80 }
}

private fun ProjectEntity.cursor() = ProjectPageCursor(createdAtUtcMillis, id)

internal const val MAX_MANUAL_PICKER_PAGE_SIZE = 100
private const val MANUAL_PICKER_SCAN_SIZE = 100
