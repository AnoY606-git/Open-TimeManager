package dev.opentimemanager.app.ui

import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.tracking.TrackingCommand
import dev.opentimemanager.core.model.CommandId
import dev.opentimemanager.core.model.CommandTarget
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.TrackingState
import java.time.LocalDate
import java.time.ZoneId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.report.ReportAggregator

enum class NavigationLayout { BOTTOM, RAIL, TWO_PANE }
enum class NotificationControlStatus { AVAILABLE, PERMISSION_REQUIRED, BLOCKED }

fun notificationControlStatus(permissionGranted: Boolean, notificationsEnabled: Boolean): NotificationControlStatus = when {
    !permissionGranted -> NotificationControlStatus.PERMISSION_REQUIRED
    !notificationsEnabled -> NotificationControlStatus.BLOCKED
    else -> NotificationControlStatus.AVAILABLE
}

fun navigationLayout(widthDp: Int, heightDp: Int): NavigationLayout = when {
    widthDp >= 840 && heightDp >= 600 -> NavigationLayout.TWO_PANE
    widthDp >= 600 -> NavigationLayout.RAIL
    else -> NavigationLayout.BOTTOM
}

fun timerUsesCompactLayout(heightDp: Int, fontScale: Float): Boolean =
    heightDp < 520 || fontScale >= 1.7f

fun todayTrackedMillis(
    snapshot: AppSnapshot,
    nowElapsedMillis: Long,
    today: LocalDate,
    zoneId: ZoneId,
): Long {
    val segments = snapshot.segments.toMutableList()
    if (snapshot.session.state == TrackingState.RUNNING) {
        val open = snapshot.session.openSegment
        val entryId = snapshot.session.entryId
        val projectId = snapshot.session.projectId
        if (open != null && entryId != null && projectId != null) {
            val duration = nowElapsedMillis - open.startedAt.elapsedRealtimeMillis
            if (duration > 0) segments += Segment(open.id, entryId, projectId, open.startedAt.wallUtcMillis, duration)
        }
    }
    val from = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val to = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    return ReportAggregator.aggregate(segments, from, to, zoneId).totalMillis
}

fun primaryCommand(
    snapshot: AppSnapshot,
    selectedProjectId: ProjectId,
    commandId: CommandId,
): TrackingCommand? = when (snapshot.session.state) {
    TrackingState.IDLE -> TrackingCommand.Start(commandId, selectedProjectId, snapshot.session.revision)
    TrackingState.RUNNING -> TrackingCommand.Pause(
        CommandTarget(requireNotNull(snapshot.session.entryId), snapshot.session.revision, commandId),
    )
    TrackingState.PAUSED -> TrackingCommand.Resume(
        CommandTarget(requireNotNull(snapshot.session.entryId), snapshot.session.revision, commandId),
    )
    TrackingState.NEEDS_REVIEW -> null
}

fun stopCommand(snapshot: AppSnapshot, commandId: CommandId): TrackingCommand.Stop? =
    if (snapshot.session.state == TrackingState.RUNNING || snapshot.session.state == TrackingState.PAUSED) {
        TrackingCommand.Stop(
            CommandTarget(requireNotNull(snapshot.session.entryId), snapshot.session.revision, commandId),
        )
    } else null

fun switchCommand(
    snapshot: AppSnapshot,
    projectId: ProjectId,
    commandId: CommandId,
): TrackingCommand.Switch? {
    if (snapshot.session.state != TrackingState.RUNNING && snapshot.session.state != TrackingState.PAUSED) return null
    if (snapshot.session.projectId == projectId) return null
    return TrackingCommand.Switch(
        CommandTarget(requireNotNull(snapshot.session.entryId), snapshot.session.revision, commandId),
        projectId,
    )
}

data class ManualEntryDraft(
    val projectId: String,
    val startUtcMillis: Long,
    val durationMillis: Long,
    val note: String,
)

enum class EntryValidationError { PROJECT_REQUIRED, END_NOT_AFTER_START, NOTE_TOO_LONG, OVERFLOW }
data class EntryValidation(val value: ManualEntryDraft? = null, val error: EntryValidationError? = null)

fun validateManualEntry(
    projectId: String,
    startUtcMillis: Long,
    endUtcMillis: Long,
    note: String,
): EntryValidation {
    if (projectId.isBlank()) return EntryValidation(error = EntryValidationError.PROJECT_REQUIRED)
    if (note.codePointCount(0, note.length) > 2_000) return EntryValidation(error = EntryValidationError.NOTE_TOO_LONG)
    val duration = try {
        Math.subtractExact(endUtcMillis, startUtcMillis)
    } catch (_: ArithmeticException) {
        return EntryValidation(error = EntryValidationError.OVERFLOW)
    }
    if (duration <= 0) return EntryValidation(error = EntryValidationError.END_NOT_AFTER_START)
    return EntryValidation(ManualEntryDraft(projectId, startUtcMillis, duration, note))
}

data class DeleteConfirmation(
    val confirmed: Boolean,
    val next: HistoryDialogState,
)

data class HistoryDialogState(val pendingDeleteId: String? = null) {
    fun requestDelete(entryId: String) = copy(pendingDeleteId = entryId)
    fun confirmDelete(entryId: String): DeleteConfirmation = if (entryId == pendingDeleteId) {
        DeleteConfirmation(true, copy(pendingDeleteId = null))
    } else {
        DeleteConfirmation(false, this)
    }
}

data class UtcBounds(val fromUtcMillis: Long, val toUtcMillis: Long)
sealed interface ReportPeriod {
    data object WEEK : ReportPeriod
    data object MONTH : ReportPeriod
    data class Custom(val from: LocalDate, val through: LocalDate) : ReportPeriod

    fun bounds(today: LocalDate, zoneId: ZoneId): UtcBounds {
        val (from, through) = when (this) {
            WEEK -> today.with(java.time.DayOfWeek.MONDAY) to today.with(java.time.DayOfWeek.SUNDAY)
            MONTH -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
            is Custom -> from to through
        }
        require(!through.isBefore(from))
        return UtcBounds(
            from.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            through.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
        )
    }
}
