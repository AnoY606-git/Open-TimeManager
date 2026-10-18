package dev.opentimemanager.app.tracking

import dev.opentimemanager.app.data.*
import dev.opentimemanager.app.settings.canonicalDefaultProjectIdOrNull
import dev.opentimemanager.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

fun interface ClockSnapshotSource { fun now(): ClockSnapshot }

const val LEGACY_COMMAND_GENERATION = "legacy"

sealed interface TrackingCommand {
    val commandId: CommandId
    val expectedGeneration: String
    data class Start(
        override val commandId: CommandId,
        val projectId: ProjectId,
        val expectedRevision: Long,
        override val expectedGeneration: String = LEGACY_COMMAND_GENERATION,
    ) : TrackingCommand
    data class Pause(val target: CommandTarget, override val expectedGeneration: String = LEGACY_COMMAND_GENERATION) : TrackingCommand { override val commandId get() = target.commandId }
    data class Resume(val target: CommandTarget, override val expectedGeneration: String = LEGACY_COMMAND_GENERATION) : TrackingCommand { override val commandId get() = target.commandId }
    data class Stop(val target: CommandTarget, override val expectedGeneration: String = LEGACY_COMMAND_GENERATION) : TrackingCommand { override val commandId get() = target.commandId }
    data class Switch(val target: CommandTarget, val projectId: ProjectId, override val expectedGeneration: String = LEGACY_COMMAND_GENERATION) : TrackingCommand { override val commandId get() = target.commandId }
    data class ResolveReview(val target: CommandTarget, val resolution: ReviewResolution, override val expectedGeneration: String = LEGACY_COMMAND_GENERATION) : TrackingCommand { override val commandId get() = target.commandId }
}

sealed interface ReviewResolution {
    data object CONFIRMED_CHECKPOINT : ReviewResolution
    data class ManualEnd(val endUtcMillis: Long) : ReviewResolution
}

enum class TrackingFailure { INVALID_STATE, STALE_ENTRY, STALE_REVISION, STALE_GENERATION, INVALID_DURATION, OVERFLOW, DUPLICATE_SEGMENT, MISSING_PROJECT, ARCHIVED_PROJECT }
data class CommandReceipt(val revision: Long, val entryId: String?, val state: TrackingState)
sealed interface TrackingResult {
    data class Applied(val snapshot: SystemSnapshot) : TrackingResult
    data class Duplicate(val receipt: CommandReceipt) : TrackingResult
    data class Rejected(val reason: TrackingFailure) : TrackingResult
}

data class SystemSnapshot(
    val session: TrackingSession,
    val generation: String,
    val activeProject: ProjectEntity?,
    /** Valid preferred project, or the sole non-archived project when no valid override exists. */
    val quickStartProject: ProjectEntity?,
)

data class AppSnapshot(
    val session: TrackingSession,
    val projects: List<ProjectEntity>,
    val entries: List<EntryEntity>,
    val segments: List<Segment>,
    val generation: String = LEGACY_COMMAND_GENERATION,
    val hasMoreHistory: Boolean = false,
    /** Exact totals for the bounded history entries; completed segment rows are not retained. */
    val entryDurations: Map<String, Long> = emptyMap(),
    val hasMoreProjects: Boolean = false,
    /** Local override; an eligible preferred project is pinned in the same snapshot. */
    val defaultProjectId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TrackingCoordinator(
    private val repository: RoomTrackingRepository,
    defaultProjectId: Flow<String?> = flowOf(null),
) {
    private val defaultProjectIds = defaultProjectId
        .map(::canonicalDefaultProjectIdOrNull)
        .distinctUntilChanged()

    suspend fun execute(command: TrackingCommand): TrackingResult =
        repository.execute(command, defaultProjectIds.first())

    fun observeSystemSnapshot(): Flow<SystemSnapshot> = defaultProjectIds.flatMapLatest { preferred ->
        repository.observeSystemSnapshot(preferred)
    }
    fun observeSnapshot(
        historyLimit: Int = 100,
        projectLimit: Int = 100,
        pinnedProjectId: String = ProjectRepository.DEFAULT_PROJECT_ID.value,
    ): Flow<AppSnapshot> = defaultProjectIds.flatMapLatest { preferred ->
        repository.observeSnapshot(historyLimit, projectLimit, pinnedProjectId, preferred)
    }
}
