package dev.opentimemanager.core.model

import java.util.Collections

@JvmInline value class ProjectId(val value: String) {
    init { require(value.isNotBlank()) { "Пустой идентификатор проекта" } }
}
@JvmInline value class EntryId(val value: String) {
    init { require(value.isNotBlank()) { "Пустой идентификатор записи" } }
}
@JvmInline value class SegmentId(val value: String) {
    init { require(value.isNotBlank()) { "Пустой идентификатор сегмента" } }
}
@JvmInline value class CommandId(val value: String) {
    init { require(value.isNotBlank()) { "Пустой идентификатор команды" } }
}

data class ClockSnapshot(
    val wallUtcMillis: Long,
    val elapsedRealtimeMillis: Long,
    val bootCount: Long?,
) {
    init {
        require(elapsedRealtimeMillis >= 0) { "Отрицательное монотонное время" }
        require(bootCount == null || bootCount >= 0) { "Отрицательный счётчик загрузок" }
    }
}

enum class TrackingState { IDLE, RUNNING, PAUSED, NEEDS_REVIEW }

/** Закрытый полуоткрытый UTC-интервал; длительность измерена монотонными часами. */
data class Segment(
    val id: SegmentId,
    val entryId: EntryId,
    val projectId: ProjectId,
    val startUtcMillis: Long,
    val durationMillis: Long,
) {
    init {
        require(durationMillis > 0) { "Длительность должна быть положительной" }
    }
    val endUtcMillis: Long = Math.addExact(startUtcMillis, durationMillis)
}

data class OpenSegment(val id: SegmentId, val startedAt: ClockSnapshot)

data class CommandTarget(val entryId: EntryId, val expectedRevision: Long, val commandId: CommandId)

/** Создание и переходы принадлежат reducer; коллекции защищены от внешнего изменения. */
class TrackingSession internal constructor(
    val state: TrackingState,
    val revision: Long,
    val entryId: EntryId?,
    val projectId: ProjectId?,
    val openSegment: OpenSegment?,
    segments: List<Segment>,
    processedCommands: Set<CommandId>,
) {
    val segments: List<Segment> = Collections.unmodifiableList(segments.toList())
    val processedCommands: Set<CommandId> = Collections.unmodifiableSet(processedCommands.toSet())

    companion object {
        fun idle(): TrackingSession = TrackingSession(TrackingState.IDLE, 0, null, null, null, emptyList(), emptySet())
    }
}

class CompletedEntry internal constructor(val id: EntryId, val projectId: ProjectId, segments: List<Segment>) {
    val segments: List<Segment> = Collections.unmodifiableList(segments.toList())
    val durationMillis: Long = segments.fold(0L) { total, segment -> Math.addExact(total, segment.durationMillis) }
}

enum class RejectionReason {
    INVALID_STATE, STALE_ENTRY, STALE_REVISION, INVALID_DURATION, OVERFLOW, DUPLICATE_SEGMENT,
}

sealed interface TransitionResult {
    data class Applied(val session: TrackingSession, val completedEntry: CompletedEntry? = null) : TransitionResult
    data class Duplicate(val session: TrackingSession) : TransitionResult
    data class Rejected(val reason: RejectionReason) : TransitionResult
}
