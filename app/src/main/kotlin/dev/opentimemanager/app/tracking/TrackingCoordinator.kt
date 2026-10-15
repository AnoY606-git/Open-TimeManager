package dev.opentimemanager.app.tracking

import dev.opentimemanager.app.data.*
import dev.opentimemanager.core.model.*
import kotlinx.coroutines.flow.Flow

fun interface ClockSnapshotSource { fun now(): ClockSnapshot }

sealed interface TrackingCommand {
    val commandId: CommandId
    data class Start(override val commandId: CommandId, val projectId: ProjectId, val expectedRevision: Long) : TrackingCommand
    data class Pause(val target: CommandTarget) : TrackingCommand { override val commandId get() = target.commandId }
    data class Resume(val target: CommandTarget) : TrackingCommand { override val commandId get() = target.commandId }
    data class Stop(val target: CommandTarget) : TrackingCommand { override val commandId get() = target.commandId }
    data class Switch(val target: CommandTarget, val projectId: ProjectId) : TrackingCommand { override val commandId get() = target.commandId }
    data class ResolveReview(val target: CommandTarget, val resolution: ReviewResolution) : TrackingCommand { override val commandId get() = target.commandId }
}

sealed interface ReviewResolution {
    data object CONFIRMED_CHECKPOINT : ReviewResolution
    data class ManualEnd(val endUtcMillis: Long) : ReviewResolution
}

enum class TrackingFailure { INVALID_STATE, STALE_ENTRY, STALE_REVISION, INVALID_DURATION, OVERFLOW, DUPLICATE_SEGMENT, MISSING_PROJECT, ARCHIVED_PROJECT }
data class CommandReceipt(val revision: Long, val entryId: String?, val state: TrackingState)
sealed interface TrackingResult {
    data class Applied(val snapshot: AppSnapshot) : TrackingResult
    data class Duplicate(val receipt: CommandReceipt) : TrackingResult
    data class Rejected(val reason: TrackingFailure) : TrackingResult
}

data class AppSnapshot(val session: TrackingSession, val projects: List<ProjectEntity>, val entries: List<EntryEntity>, val segments: List<Segment>)

class TrackingCoordinator(private val repository: RoomTrackingRepository) {
    suspend fun execute(command: TrackingCommand): TrackingResult = repository.execute(command)
    fun observeSnapshot(): Flow<AppSnapshot> = repository.observeSnapshot()
}
