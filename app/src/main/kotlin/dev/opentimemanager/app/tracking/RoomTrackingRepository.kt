package dev.opentimemanager.app.tracking

import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.canonicalDefaultProjectIdOrNull
import java.util.concurrent.Callable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomTrackingRepository(private val database: AppDatabase, private val clock: ClockSnapshotSource, private val dispatcher: CoroutineDispatcher) {
    suspend fun execute(command: TrackingCommand, defaultProjectId: String? = null): TrackingResult = withContext(dispatcher) {
        database.runInTransaction(Callable {
            when (val result = database.trackingDao().execute(command, clock)) {
                is TrackingResult.Applied -> result.copy(snapshot = withPreferredProject(result.snapshot, defaultProjectId))
                else -> result
            }
        })
    }
    /** Малая проекция не зависит от размера архива. */
    fun observeSystemSnapshot(defaultProjectId: String? = null): Flow<SystemSnapshot> = database.invalidationTracker
        .createFlow("projects", "entries", "segments", "tracking_slot")
        .map {
            withContext(dispatcher) {
                database.runInTransaction(Callable {
                    withPreferredProject(database.trackingDao().systemSnapshot(clock), defaultProjectId)
                })
            }
        }

    /** UI читает только явно ограниченные страницы истории и проектов. */
    fun observeSnapshot(
        historyLimit: Int,
        projectLimit: Int,
        pinnedProjectId: String,
        defaultProjectId: String? = null,
    ): Flow<AppSnapshot> {
        require(historyLimit in 1..20_000)
        require(projectLimit in 1..20_000)
        return database.invalidationTracker.createFlow("projects", "entries", "segments", "tracking_slot").map {
            withContext(dispatcher) {
                database.runInTransaction(Callable {
                    val dao = database.trackingDao()
                    val snapshot = dao.snapshot(clock, historyLimit, projectLimit, pinnedProjectId)
                    val preferredId = canonicalDefaultProjectIdOrNull(defaultProjectId)
                    val preferred = preferredId?.let(::activeProject)
                    val fallback = activeProject(ProjectRepository.DEFAULT_PROJECT_ID.value)
                        ?: dao.eligibleProjects().firstOrNull()
                    snapshot.copy(
                        projects = (snapshot.projects + listOfNotNull(preferred, fallback))
                            .distinctBy(ProjectEntity::id)
                            .sortedWith(compareBy(ProjectEntity::createdAtUtcMillis, ProjectEntity::id)),
                        defaultProjectId = preferredId,
                    )
                })
            }
        }
    }

    /** Called inside the snapshot/command transaction so archive/delete cannot race validation. */
    private fun withPreferredProject(snapshot: SystemSnapshot, defaultProjectId: String?): SystemSnapshot {
        val preferred = canonicalDefaultProjectIdOrNull(defaultProjectId)?.let(::activeProject)
        return if (preferred == null) snapshot else snapshot.copy(quickStartProject = preferred)
    }

    private fun activeProject(id: String): ProjectEntity? =
        database.projectDao().project(id)?.takeIf { it.archivedAtUtcMillis == null }
}
