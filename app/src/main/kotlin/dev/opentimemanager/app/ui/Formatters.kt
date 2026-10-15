package dev.opentimemanager.app.ui

import android.content.Context
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun formatDuration(durationMillis: Long, includeSeconds: Boolean = true): String {
    val totalSeconds = (durationMillis.coerceAtLeast(0) / 1_000)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (includeSeconds) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.getDefault(), "%d:%02d", hours, minutes)
}

fun formatDateTime(utcMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(utcMillis).atZone(zoneId))

fun Context.projectName(project: ProjectEntity): String =
    if (project.id == ProjectRepository.DEFAULT_PROJECT_ID.value) getString(R.string.default_project_name) else project.name

fun entryDurationMillis(entryId: String, state: AppUiState): Long =
    state.snapshot?.entryDurations?.get(entryId)
        ?: state.snapshot?.segments.orEmpty().filter { it.entryId.value == entryId }.sumOf { it.durationMillis }
