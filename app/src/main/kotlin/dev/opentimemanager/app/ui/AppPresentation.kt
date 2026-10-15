package dev.opentimemanager.app.ui

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.system.TrackingNotification
import androidx.core.os.LocaleListCompat
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.tracking.TrackingCommand
import dev.opentimemanager.core.model.CommandId
import dev.opentimemanager.core.model.CommandTarget
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.TrackingState
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.DateTimeException
import dev.opentimemanager.core.model.Segment

enum class NavigationLayout { BOTTOM, RAIL, TWO_PANE }
enum class NotificationControlStatus {
    AVAILABLE,
    PERMISSION_REQUESTABLE,
    PERMISSION_FIXED,
    APP_BLOCKED,
    CHANNEL_BLOCKED,
}

enum class NotificationControlAction { NONE, REQUEST_PERMISSION, OPEN_APP_SETTINGS, OPEN_CHANNEL_SETTINGS }

fun applicationLanguage(locales: LocaleListCompat): AppLanguage = when (locales[0]?.language) {
    "en" -> AppLanguage.ENGLISH
    "ru" -> AppLanguage.RUSSIAN
    else -> AppLanguage.SYSTEM
}

fun applicationLocales(language: AppLanguage): LocaleListCompat = when (language) {
    AppLanguage.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
    AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
    AppLanguage.RUSSIAN -> LocaleListCompat.forLanguageTags("ru")
}

fun notificationControlStatus(
    sdkInt: Int,
    permissionGranted: Boolean,
    permissionDeniedOnce: Boolean,
    shouldShowPermissionRationale: Boolean,
    notificationsEnabled: Boolean,
    channelImportance: Int?,
): NotificationControlStatus = when {
    sdkInt >= 33 && !permissionGranted && permissionDeniedOnce && !shouldShowPermissionRationale ->
        NotificationControlStatus.PERMISSION_FIXED
    sdkInt >= 33 && !permissionGranted -> NotificationControlStatus.PERMISSION_REQUESTABLE
    !notificationsEnabled -> NotificationControlStatus.APP_BLOCKED
    channelImportance == NotificationManager.IMPORTANCE_NONE -> NotificationControlStatus.CHANNEL_BLOCKED
    else -> NotificationControlStatus.AVAILABLE
}

/**
 * Records only an observable denial transition. A dismissed first prompt has neither a
 * pre-request nor a post-request rationale and therefore remains requestable. Checking both
 * sides also migrates an install that was denied before this marker existed: its pre-request
 * rationale is enough to recognize a subsequent fixed denial.
 */
fun shouldRecordNotificationDenial(
    permissionGranted: Boolean,
    shouldShowRationaleBeforeRequest: Boolean,
    shouldShowRationaleAfterRequest: Boolean,
): Boolean = !permissionGranted &&
    (shouldShowRationaleBeforeRequest || shouldShowRationaleAfterRequest)

fun notificationControlAction(status: NotificationControlStatus): NotificationControlAction = when (status) {
    NotificationControlStatus.AVAILABLE -> NotificationControlAction.NONE
    NotificationControlStatus.PERMISSION_REQUESTABLE -> NotificationControlAction.REQUEST_PERMISSION
    NotificationControlStatus.PERMISSION_FIXED, NotificationControlStatus.APP_BLOCKED ->
        NotificationControlAction.OPEN_APP_SETTINGS
    NotificationControlStatus.CHANNEL_BLOCKED -> NotificationControlAction.OPEN_CHANNEL_SETTINGS
}

fun notificationSettingsIntent(action: NotificationControlAction, packageName: String): Intent? = when (action) {
    NotificationControlAction.OPEN_APP_SETTINGS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    NotificationControlAction.OPEN_CHANNEL_SETTINGS -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, TrackingNotification.CHANNEL_ID)
    NotificationControlAction.NONE, NotificationControlAction.REQUEST_PERMISSION -> null
}

fun navigationLayout(widthDp: Int, heightDp: Int): NavigationLayout = when {
    widthDp >= 840 && heightDp >= 600 -> NavigationLayout.TWO_PANE
    widthDp >= 600 -> NavigationLayout.RAIL
    else -> NavigationLayout.BOTTOM
}

fun timerUsesCompactLayout(heightDp: Int, fontScale: Float): Boolean =
    heightDp < 520 || fontScale >= 1.7f

fun millisUntilNextLocalDay(now: Instant, zoneId: ZoneId): Long {
    val nextMidnight = now.atZone(zoneId).toLocalDate().plusDays(1)
        .atStartOfDay(zoneId)
        .toInstant()
    return Math.subtractExact(nextMidnight.toEpochMilli(), now.toEpochMilli()).coerceAtLeast(1L)
}

private val MinUserDate: LocalDate = LocalDate.of(1900, 1, 1)
private val MaxUserDate: LocalDate = LocalDate.of(9999, 12, 30)

enum class DateInputError { MALFORMED, OUT_OF_SUPPORTED_RANGE, REVERSED_RANGE, OVERFLOW }

sealed interface ValidatedInput<out T> {
    val value: T?
    val error: DateInputError?

    data class Valid<T>(override val value: T) : ValidatedInput<T> {
        override val error: DateInputError? = null
    }

    data class Invalid(override val error: DateInputError) : ValidatedInput<Nothing> {
        override val value: Nothing? = null
    }
}

private fun supportedDate(input: String): ValidatedInput<LocalDate> {
    val parsed = try {
        LocalDate.parse(input)
    } catch (_: DateTimeException) {
        return ValidatedInput.Invalid(DateInputError.MALFORMED)
    }
    return if (parsed in MinUserDate..MaxUserDate) ValidatedInput.Valid(parsed)
    else ValidatedInput.Invalid(DateInputError.OUT_OF_SUPPORTED_RANGE)
}

fun validateReviewEndInput(dateInput: String, timeInput: String, zoneId: ZoneId): ValidatedInput<Long> {
    val date = supportedDate(dateInput)
    if (date is ValidatedInput.Invalid) return date
    val time = try {
        LocalTime.parse(timeInput)
    } catch (_: DateTimeException) {
        return ValidatedInput.Invalid(DateInputError.MALFORMED)
    }
    return try {
        ValidatedInput.Valid(LocalDateTime.of(requireNotNull(date.value), time).atZone(zoneId).toInstant().toEpochMilli())
    } catch (_: DateTimeException) {
        ValidatedInput.Invalid(DateInputError.OUT_OF_SUPPORTED_RANGE)
    } catch (_: ArithmeticException) {
        ValidatedInput.Invalid(DateInputError.OVERFLOW)
    }
}

fun validateCustomReportInput(
    fromInput: String,
    throughInput: String,
    zoneId: ZoneId,
): ValidatedInput<ReportPeriod.Custom> {
    val from = supportedDate(fromInput)
    if (from is ValidatedInput.Invalid) return from
    val through = supportedDate(throughInput)
    if (through is ValidatedInput.Invalid) return through
    if (requireNotNull(through.value).isBefore(requireNotNull(from.value))) {
        return ValidatedInput.Invalid(DateInputError.REVERSED_RANGE)
    }
    val period = ReportPeriod.Custom(requireNotNull(from.value), requireNotNull(through.value))
    return when (val bounds = period.validatedBounds(period.through, zoneId)) {
        is ValidatedInput.Valid -> ValidatedInput.Valid(period)
        is ValidatedInput.Invalid -> bounds
    }
}

data class TodayEntryProjection(
    val entryId: dev.opentimemanager.core.model.EntryId,
    val projectId: ProjectId,
    val firstStartUtcMillis: Long,
    val durationMillis: Long,
)

data class ConfirmedDayProjection(
    val fromUtcMillis: Long,
    val toUtcMillis: Long,
    val confirmedMillis: Long,
    val rows: List<TodayEntryProjection>,
)

fun confirmedDayProjection(segments: List<Segment>, today: LocalDate, zoneId: ZoneId): ConfirmedDayProjection {
    val from = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val to = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val rows = linkedMapOf<Pair<dev.opentimemanager.core.model.EntryId, ProjectId>, TodayEntryProjection>()
    var confirmedMillis = 0L
    for (segment in segments) {
        val clippedStart = maxOf(segment.startUtcMillis, from)
        val clippedEnd = minOf(segment.endUtcMillis, to)
        if (clippedStart >= clippedEnd) continue
        val duration = Math.subtractExact(clippedEnd, clippedStart)
        confirmedMillis = Math.addExact(confirmedMillis, duration)
        val key = segment.entryId to segment.projectId
        val current = rows[key]
        rows[key] = TodayEntryProjection(
            entryId = segment.entryId,
            projectId = segment.projectId,
            firstStartUtcMillis = minOf(current?.firstStartUtcMillis ?: clippedStart, clippedStart),
            durationMillis = Math.addExact(current?.durationMillis ?: 0L, duration),
        )
    }
    return ConfirmedDayProjection(
        fromUtcMillis = from,
        toUtcMillis = to,
        confirmedMillis = confirmedMillis,
        rows = rows.values.sortedByDescending { it.firstStartUtcMillis },
    )
}

fun todayTrackedMillis(
    projection: ConfirmedDayProjection,
    session: dev.opentimemanager.core.model.TrackingSession,
    nowElapsedMillis: Long,
): Long {
    if (session.state != TrackingState.RUNNING) return projection.confirmedMillis
    val open = session.openSegment ?: return projection.confirmedMillis
    val openDuration = try {
        Math.subtractExact(nowElapsedMillis, open.startedAt.elapsedRealtimeMillis).coerceAtLeast(0L)
    } catch (_: ArithmeticException) {
        return projection.confirmedMillis
    }
    val openEnd = try {
        Math.addExact(open.startedAt.wallUtcMillis, openDuration)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
    val clippedStart = maxOf(open.startedAt.wallUtcMillis, projection.fromUtcMillis)
    val clippedEnd = minOf(openEnd, projection.toUtcMillis)
    val tail = if (clippedStart < clippedEnd) clippedEnd - clippedStart else 0L
    return try {
        Math.addExact(projection.confirmedMillis, tail)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
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
    val preserveTiming: Boolean = false,
)

data class EntryFormState(
    val projectId: String,
    val dateInput: String,
    val timeInput: String,
    val durationMinutesInput: String,
    val noteInput: String,
    val originalStartUtcMillis: Long? = null,
    val originalDurationMillis: Long? = null,
    val dateEdited: Boolean = false,
    val timeEdited: Boolean = false,
    val durationEdited: Boolean = false,
)

fun entryFormState(entry: EntryEntity, durationMillis: Long, zoneId: ZoneId): EntryFormState {
    val local = Instant.ofEpochMilli(entry.startedAtUtcMillis).atZone(zoneId).toLocalDateTime()
    return EntryFormState(
        projectId = entry.projectId,
        dateInput = local.toLocalDate().toString(),
        timeInput = local.toLocalTime().toString().take(5),
        durationMinutesInput = (durationMillis / 60_000L).coerceAtLeast(1).toString(),
        noteInput = entry.note,
        originalStartUtcMillis = entry.startedAtUtcMillis,
        originalDurationMillis = durationMillis,
    )
}

fun validateEntryForm(form: EntryFormState, zoneId: ZoneId): EntryValidation = try {
    val originalLocal = form.originalStartUtcMillis?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).toLocalDateTime()
    }
    val date = if (!form.dateEdited && originalLocal != null) originalLocal.toLocalDate()
        else LocalDate.parse(form.dateInput)
    if (date !in MinUserDate..MaxUserDate) throw DateTimeException("Date outside supported range")
    val time = if (!form.timeEdited && originalLocal != null) originalLocal.toLocalTime()
        else LocalTime.parse(form.timeInput)
    val start = if (!form.dateEdited && !form.timeEdited && form.originalStartUtcMillis != null) {
        form.originalStartUtcMillis
    } else {
        LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli()
    }
    val duration = if (!form.durationEdited && form.originalDurationMillis != null) {
        form.originalDurationMillis
    } else {
        Math.multiplyExact(form.durationMinutesInput.toLong(), 60_000L)
    }
    validateManualEntry(
        projectId = form.projectId,
        startUtcMillis = start,
        endUtcMillis = Math.addExact(start, duration),
        note = form.noteInput,
    ).let { validation ->
        if (validation.value != null && !form.dateEdited && !form.timeEdited && !form.durationEdited &&
            form.originalStartUtcMillis != null && form.originalDurationMillis != null
        ) {
            validation.copy(value = validation.value.copy(preserveTiming = true))
        } else validation
    }
} catch (_: DateTimeException) {
    EntryValidation(error = EntryValidationError.END_NOT_AFTER_START)
} catch (_: ArithmeticException) {
    EntryValidation(error = EntryValidationError.OVERFLOW)
} catch (_: NumberFormatException) {
    EntryValidation(error = EntryValidationError.END_NOT_AFTER_START)
}

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

    fun validatedBounds(today: LocalDate, zoneId: ZoneId): ValidatedInput<UtcBounds> {
        val dates = try {
            when (this) {
                WEEK -> today.with(java.time.DayOfWeek.MONDAY) to today.with(java.time.DayOfWeek.SUNDAY)
                MONTH -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
                is Custom -> from to through
            }
        } catch (_: DateTimeException) {
            return ValidatedInput.Invalid(DateInputError.OUT_OF_SUPPORTED_RANGE)
        }
        val (from, through) = dates
        if (from !in MinUserDate..MaxUserDate || through !in MinUserDate..MaxUserDate) {
            return ValidatedInput.Invalid(DateInputError.OUT_OF_SUPPORTED_RANGE)
        }
        if (through.isBefore(from)) return ValidatedInput.Invalid(DateInputError.REVERSED_RANGE)
        return try {
            val fromUtcMillis = from.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val toUtcMillis = through.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            if (fromUtcMillis >= toUtcMillis) ValidatedInput.Invalid(DateInputError.OUT_OF_SUPPORTED_RANGE)
            else ValidatedInput.Valid(UtcBounds(fromUtcMillis, toUtcMillis))
        } catch (_: DateTimeException) {
            ValidatedInput.Invalid(DateInputError.OUT_OF_SUPPORTED_RANGE)
        } catch (_: ArithmeticException) {
            ValidatedInput.Invalid(DateInputError.OVERFLOW)
        }
    }

    fun bounds(today: LocalDate, zoneId: ZoneId): UtcBounds =
        requireNotNull(validatedBounds(today, zoneId).value) { "Report period is outside the supported date range" }
}
