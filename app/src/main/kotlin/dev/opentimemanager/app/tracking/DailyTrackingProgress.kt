package dev.opentimemanager.app.tracking

import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.time.LocalDate
import java.time.ZoneId

/** Дневная сумма всех проектов; цель не ограничивает фактически учтённое время. */
data class DailyTrackingProgress(
    val date: LocalDate,
    val trackedMillis: Long,
    val goalMinutes: Int = 480,
) {
    init {
        require(trackedMillis >= 0)
        require(goalMinutes > 0)
    }
}

/** Закрытая сумма уже ограничена сутками; незакрытый хвост считаем только монотонно. */
internal fun dailyTrackingProgress(
    date: LocalDate,
    zoneId: ZoneId,
    confirmedMillis: Long,
    session: TrackingSession,
    nowElapsedMillis: Long,
    goalMinutes: Int = 480,
): DailyTrackingProgress {
    val open = session.openSegment?.takeIf { session.state == TrackingState.RUNNING }
        ?: return DailyTrackingProgress(date, confirmedMillis, goalMinutes)
    val duration = try {
        Math.subtractExact(nowElapsedMillis, open.startedAt.elapsedRealtimeMillis).coerceAtLeast(0L)
    } catch (_: ArithmeticException) {
        return DailyTrackingProgress(date, confirmedMillis, goalMinutes)
    }
    val end = try {
        Math.addExact(open.startedAt.wallUtcMillis, duration)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
    val from = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val clippedStart = maxOf(open.startedAt.wallUtcMillis, from)
    val clippedEnd = minOf(end, to)
    val tail = if (clippedStart < clippedEnd) clippedEnd - clippedStart else 0L
    val total = try {
        Math.addExact(confirmedMillis, tail)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
    return DailyTrackingProgress(date, total, goalMinutes)
}
