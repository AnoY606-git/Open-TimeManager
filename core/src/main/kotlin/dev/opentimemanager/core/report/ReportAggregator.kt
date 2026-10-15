package dev.opentimemanager.core.report

import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

data class DayProject(val day: LocalDate, val projectId: ProjectId)

class ReportTotals internal constructor(
    val totalMillis: Long,
    dayTotals: Map<LocalDate, Long>,
    projectTotals: Map<ProjectId, Long>,
    dayProjectTotals: Map<DayProject, Long>,
) {
    val dayTotals: Map<LocalDate, Long> = Collections.unmodifiableMap(dayTotals.toMap())
    val projectTotals: Map<ProjectId, Long> = Collections.unmodifiableMap(projectTotals.toMap())
    val dayProjectTotals: Map<DayProject, Long> = Collections.unmodifiableMap(dayProjectTotals.toMap())
}

object ReportAggregator {
    /** Интервалы полуоткрытые. Некорректный диапазон и переполнение отклоняются явно. */
    fun aggregate(segments: List<Segment>, fromInclusive: Long, toExclusive: Long, zoneId: ZoneId): ReportTotals {
        require(fromInclusive <= toExclusive) { "Начало диапазона позже конца" }
        Math.subtractExact(toExclusive, fromInclusive)
        require(segments.map { it.id }.toSet().size == segments.size) { "Повтор идентификатора сегмента" }
        // Сначала проверяем сумму, чтобы не обходить миллионы суток при заведомом переполнении.
        val total = segments.fold(0L) { sum, segment ->
            val start = maxOf(segment.startUtcMillis, fromInclusive)
            val end = minOf(segment.endUtcMillis, toExclusive)
            if (start < end) Math.addExact(sum, Math.subtractExact(end, start)) else sum
        }
        val days = mutableMapOf<LocalDate, Long>()
        val projects = mutableMapOf<ProjectId, Long>()
        val dayProjects = mutableMapOf<DayProject, Long>()
        for (segment in segments) {
            var cursor = maxOf(segment.startUtcMillis, fromInclusive)
            val end = minOf(segment.endUtcMillis, toExclusive)
            while (cursor < end) {
                val date = Instant.ofEpochMilli(cursor).atZone(zoneId).toLocalDate()
                // Длина суток задаётся ZoneId: при DST она может отличаться от 24 часов.
                val nextDay = date.plusDays(1).atStartOfDay(zoneId).toInstant()
                val boundary = if (nextDay < Instant.ofEpochMilli(end)) nextDay.toEpochMilli() else end
                val duration = Math.subtractExact(boundary, cursor)
                check(duration > 0) { "Граница суток не продвигает время" }
                days.add(date, duration)
                projects.add(segment.projectId, duration)
                dayProjects.add(DayProject(date, segment.projectId), duration)
                cursor = boundary
            }
        }
        return ReportTotals(total, days, projects, dayProjects)
    }

    private fun <K> MutableMap<K, Long>.add(key: K, duration: Long) {
        this[key] = Math.addExact(this[key] ?: 0L, duration)
    }
}
