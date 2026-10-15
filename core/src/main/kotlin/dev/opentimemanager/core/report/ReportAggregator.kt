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
    /** Totals-only projection used by UI reports; it never walks local-day boundaries. */
    fun aggregateTotals(
        segments: List<Segment>,
        fromInclusive: Long,
        toExclusive: Long,
    ): ReportTotals {
        require(fromInclusive <= toExclusive) { "Начало диапазона позже конца" }
        Math.subtractExact(toExclusive, fromInclusive)
        require(segments.map { it.id }.toSet().size == segments.size) { "Повтор идентификатора сегмента" }
        var total = 0L
        val projects = mutableMapOf<ProjectId, Long>()
        for (segment in segments) {
            val start = maxOf(segment.startUtcMillis, fromInclusive)
            val end = minOf(segment.endUtcMillis, toExclusive)
            if (start < end) {
                val duration = Math.subtractExact(end, start)
                total = Math.addExact(total, duration)
                projects.add(segment.projectId, duration)
            }
        }
        return ReportTotals(total, emptyMap(), projects, emptyMap())
    }

    /** Интервалы полуоткрытые. Некорректный диапазон и переполнение отклоняются явно. */
    fun aggregate(segments: List<Segment>, fromInclusive: Long, toExclusive: Long, zoneId: ZoneId): ReportTotals {
        require(fromInclusive <= toExclusive) { "Начало диапазона позже конца" }
        Math.subtractExact(toExclusive, fromInclusive)
        require(segments.map { it.id }.toSet().size == segments.size) { "Повтор идентификатора сегмента" }
        // Сначала проверяем сумму, чтобы не обходить миллионы суток при заведомом переполнении.
        val total = aggregateTotals(segments, fromInclusive, toExclusive).totalMillis
        val days = mutableMapOf<LocalDate, Long>()
        val projects = mutableMapOf<ProjectId, Long>()
        val dayProjects = mutableMapOf<DayProject, Long>()
        for (segment in segments) {
            var cursor = maxOf(segment.startUtcMillis, fromInclusive)
            val end = minOf(segment.endUtcMillis, toExclusive)
            while (cursor < end) {
                val instant = Instant.ofEpochMilli(cursor)
                val date = instant.atZone(zoneId).toLocalDate()
                val boundary = nextDateBoundary(instant, Instant.ofEpochMilli(end), zoneId).toEpochMilli()
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

    private fun nextDateBoundary(start: Instant, end: Instant, zoneId: ZoneId): Instant {
        val date = start.atZone(zoneId).toLocalDate()
        val rules = zoneId.rules
        var cursor = start
        while (true) {
            // До ближайшего offset transition действует текущий offset. Разрешение
            // полуночи через ZoneId могло бы выбрать уже прошедшую первую полночь.
            val midnight = date.plusDays(1).atStartOfDay().toInstant(rules.getOffset(cursor))
            val transition = rules.nextTransition(cursor)?.instant
            val candidate = if (transition == null) minOf(midnight, end) else minOf(midnight, transition, end)
            if (candidate == end || candidate.atZone(zoneId).toLocalDate() != date) return candidate
            // Переход может сохранить дату или вернуть её назад; проверяем новую
            // дату на самом instant перехода, а не предполагаем следующий день.
            cursor = candidate
        }
    }

    private fun <K> MutableMap<K, Long>.add(key: K, duration: Long) {
        this[key] = Math.addExact(this[key] ?: 0L, duration)
    }
}
