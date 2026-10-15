package dev.opentimemanager.core.backup

import dev.opentimemanager.core.model.*
import java.time.ZoneId
import java.util.Collections

const val DEFAULT_PROJECT_UUID = "00000000-0000-4000-8000-000000000001"
const val DEFAULT_PROJECT_CANONICAL_NAME = "@default"
const val DEFAULT_PROJECT_COLOR_ARGB: Int = 0xff607d8b.toInt()

data class PortableProject(
    val id: ProjectId,
    val name: String,
    val colorArgb: Int = DEFAULT_PROJECT_COLOR_ARGB,
    val createdAtUtcMillis: Long = 0,
    val archivedAtUtcMillis: Long? = null,
    val isDefault: Boolean = id.value.equals(DEFAULT_PROJECT_UUID, ignoreCase = true),
)
data class PortableEntry(val id: EntryId, val projectId: ProjectId, val note: String, val zoneId: ZoneId, val source: EntrySource = EntrySource.TIMER)
data class PortableActiveEntry(val entryId: EntryId, val projectId: ProjectId, val state: TrackingState)
enum class PortableTheme { SYSTEM, LIGHT, DARK }
data class PortablePreferences(
    val theme: PortableTheme = PortableTheme.SYSTEM,
    val dynamicColor: Boolean = true,
    val privacyMode: Boolean = true,
    val dailyGoalMinutes: Int = 480,
)

/** Переносит закрытые интервалы и метаданные; runtime clock/open anchor отсутствуют. */
class PortableSnapshot(
    projects: List<PortableProject> = emptyList(),
    entries: List<PortableEntry> = emptyList(),
    segments: List<Segment> = emptyList(),
    val activeEntry: PortableActiveEntry? = null,
    val preferences: PortablePreferences = PortablePreferences(),
) {
    val projects: List<PortableProject> = Collections.unmodifiableList(projects.toList())
    val entries: List<PortableEntry> = Collections.unmodifiableList(entries.toList())
    val segments: List<Segment> = Collections.unmodifiableList(segments.toList())
}

data class BackupLimits(val maxBytes: Int = 50 * 1024 * 1024, val maxSegments: Int = 200000) {
    init {
        require(maxBytes in 1..50 * 1024 * 1024) { "Недопустимый предел размера backup" }
        require(maxSegments in 0..200000) { "Недопустимый предел сегментов backup" }
    }
}

sealed class BackupError(message: String) : Exception(message) {
    class MagicError : BackupError("Неверная сигнатура backup")
    class VersionError : BackupError("Неподдерживаемая версия backup")
    class IntegrityError : BackupError("Неверный пароль или повреждённый backup")
    class LimitError : BackupError("Превышены ограничения backup")
    class SchemaError : BackupError("Некорректная структура backup")
}
