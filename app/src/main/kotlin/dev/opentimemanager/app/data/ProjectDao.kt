package dev.opentimemanager.app.data

import androidx.room.*
import dev.opentimemanager.core.backup.DEFAULT_PROJECT_CANONICAL_NAME
import dev.opentimemanager.core.backup.DEFAULT_PROJECT_UUID
import dev.opentimemanager.core.model.ProjectId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.text.Normalizer

enum class ProjectEditResult { APPLIED, NOT_FOUND, DUPLICATE_ID, INVALID_NAME }

@Dao
abstract class ProjectDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insert(project: ProjectEntity): Long
    @Query("UPDATE projects SET name = :name, colorArgb = :color WHERE id = :id") abstract fun update(id: String, name: String, color: Int): Int
    @Query("UPDATE projects SET archivedAtUtcMillis = :at WHERE id = :id") abstract fun archive(id: String, at: Long): Int
    @Query("SELECT COUNT(*) FROM entries WHERE projectId = :id") abstract fun entryCount(id: String): Int
    @Query("DELETE FROM projects WHERE id = :id") abstract fun deleteEmpty(id: String): Int
    @Transaction open fun deleteOrArchive(id: String, now: Long): Int = if (entryCount(id) > 0) archive(id, now) else deleteEmpty(id)
}

class ProjectRepository(private val database: AppDatabase, private val dispatcher: CoroutineDispatcher) {
    companion object {
        val DEFAULT_PROJECT_ID = ProjectId(DEFAULT_PROJECT_UUID)
        const val DEFAULT_PROJECT_STORAGE_NAME = DEFAULT_PROJECT_CANONICAL_NAME
    }
    suspend fun create(id: ProjectId, name: String, colorArgb: Int, nowUtcMillis: Long): ProjectEditResult = withContext(dispatcher) {
        val normalized = normalizedName(name) ?: return@withContext ProjectEditResult.INVALID_NAME
        if (database.projectDao().insert(ProjectEntity(id.value, normalized, colorArgb, nowUtcMillis)) == -1L) ProjectEditResult.DUPLICATE_ID else ProjectEditResult.APPLIED
    }
    suspend fun update(id: ProjectId, name: String, colorArgb: Int): ProjectEditResult = withContext(dispatcher) {
        val normalized = normalizedName(name) ?: return@withContext ProjectEditResult.INVALID_NAME
        changed(database.projectDao().update(id.value, normalized, colorArgb))
    }
    suspend fun archive(id: ProjectId, atUtcMillis: Long): ProjectEditResult = withContext(dispatcher) { changed(database.projectDao().archive(id.value, atUtcMillis)) }
    suspend fun delete(id: ProjectId, nowUtcMillis: Long): ProjectEditResult = withContext(dispatcher) { changed(database.projectDao().deleteOrArchive(id.value, nowUtcMillis)) }
    private fun changed(rows: Int) = if (rows == 0) ProjectEditResult.NOT_FOUND else ProjectEditResult.APPLIED
    private fun normalizedName(name: String): String? = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).takeIf { it.isNotBlank() && it.codePointCount(0, it.length) <= 80 }
}
