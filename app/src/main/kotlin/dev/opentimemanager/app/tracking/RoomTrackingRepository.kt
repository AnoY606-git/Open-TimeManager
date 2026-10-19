package dev.opentimemanager.app.tracking

import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.canonicalDefaultProjectIdOrNull
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.TrackingState
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Callable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

class RoomTrackingRepository(
    private val database: AppDatabase,
    private val clock: ClockSnapshotSource,
    private val dispatcher: CoroutineDispatcher,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val awaitActiveRefresh: suspend () -> Unit = { delay(60_000L) },
) {
    suspend fun execute(command: TrackingCommand, defaultProjectId: String? = null, dailyGoalMinutes: Int = 480): TrackingResult = withContext(dispatcher) {
        database.runInTransaction(Callable {
            // Часы снимаются после writer lock и одинаковы для команды и её дневной проекции.
            val now = clock.now()
            when (val result = database.trackingDao().execute(command, ClockSnapshotSource { now })) {
                is TrackingResult.Applied -> result.copy(snapshot = systemProjection(result.snapshot, defaultProjectId, dailyGoalMinutes, now))
                else -> result
            }
        })
    }
    /** Читаем только текущие сутки; при idle нет периодического таймера. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSystemSnapshot(defaultProjectId: String? = null, dailyGoalMinutes: Int = 480): Flow<SystemSnapshot> = database.invalidationTracker
        .createFlow("projects", "entries", "segments", "tracking_slot")
        .transformLatest {
            // Invalidation отменяет и дожидается предыдущего чтения/ожидания до новой ветки.
            // Все повторные чтения используют свежую транзакцию, а не сохранённый старый слот.
            var snapshot = readSystemSnapshot(defaultProjectId, dailyGoalMinutes)
            emit(snapshot)
            while (snapshot.session.state != TrackingState.IDLE) {
                awaitActiveRefresh()
                snapshot = readSystemSnapshot(defaultProjectId, dailyGoalMinutes)
                emit(snapshot)
            }
        }

    private suspend fun readSystemSnapshot(defaultProjectId: String?, dailyGoalMinutes: Int): SystemSnapshot = withContext(dispatcher) {
        database.runInTransaction(Callable {
            val now = clock.now()
            val snapshot = database.trackingDao().systemSnapshot(ClockSnapshotSource { now })
            systemProjection(snapshot, defaultProjectId, dailyGoalMinutes, now)
        })
    }

    /** Слот, подтверждённые сегменты и активный проект читаются в одной Room-транзакции. */
    private fun systemProjection(snapshot: SystemSnapshot, defaultProjectId: String?, goalMinutes: Int, now: ClockSnapshot): SystemSnapshot {
        val preferredSnapshot = withPreferredProject(snapshot, defaultProjectId)
        // Idle отменяет уведомление и не использует дневную полосу ни в одном системном контроле.
        if (snapshot.session.state == TrackingState.IDLE) return preferredSnapshot.copy(dailyProgress = null)
        val zone = zoneId()
        val date = Instant.ofEpochMilli(now.wallUtcMillis).atZone(zone).toLocalDate()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val confirmed = database.reportDao().confirmedMillisInRange(from, to)
        return preferredSnapshot.copy(
            dailyProgress = dailyTrackingProgress(date, zone, confirmed, snapshot.session, now.elapsedRealtimeMillis, goalMinutes),
        )
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
