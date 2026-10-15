package dev.opentimemanager.app.tracking

import dev.opentimemanager.app.data.*
import dev.opentimemanager.core.model.*
import kotlinx.coroutines.flow.Flow

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
    /** Non-null only when exactly one non-archived project is eligible for quick Start. */
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
)

class TrackingCoordinator(private val repository: RoomTrackingRepository) {
    suspend fun execute(command: TrackingCommand): TrackingResult = repository.execute(command)
    fun observeSystemSnapshot(): Flow<SystemSnapshot> = repository.observeSystemSnapshot()
    fun observeSnapshot(historyLimit: Int = 100, projectLimit: Int = 100): Flow<AppSnapshot> =
        repository.observeSnapshot(historyLimit, projectLimit)
}
