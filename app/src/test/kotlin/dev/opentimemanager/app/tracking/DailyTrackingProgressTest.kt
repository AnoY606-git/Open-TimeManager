package dev.opentimemanager.app.tracking

import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** Закрытая дневная сумма не включает весь срок текущей сессии. */
class DailyTrackingProgressTest {
    private val date = LocalDate.of(2026, 10, 11)
    private val utc = ZoneId.of("UTC")
    private val midnight = date.atStartOfDay(utc).toInstant().toEpochMilli()

    private fun running(start: Long, elapsed: Long = 100L) = TrackingSession.restore(
        TrackingState.RUNNING, 1, EntryId("entry"), ProjectId("project"),
        OpenSegment(SegmentId("open"), ClockSnapshot(start, elapsed, 7)), emptyList(),
    )

    @Test fun runningTailIsClippedAtTheStartOfTheLocalDay() {
        val progress = dailyTrackingProgress(
            date, utc, 300_000L, running(midnight - 600_000L), 1_800_100L, 120,
        )
        assertEquals(DailyTrackingProgress(date, 1_500_000L, 120), progress)
    }

    @Test fun runningTailIsClippedAtTheEndOfTheLocalDay() {
        val progress = dailyTrackingProgress(
            date, utc, 0L, running(midnight + 86_400_000L - 600_000L), 1_800_100L,
        )
        assertEquals(600_000L, progress.trackedMillis)
    }

    @Test fun daylightSavingDayDoesNotAssumeTwentyFourHours() {
        val spring = LocalDate.of(2026, 3, 29)
        val berlin = ZoneId.of("Europe/Berlin")
        val start = spring.atStartOfDay(berlin).toInstant().toEpochMilli()
        val progress = dailyTrackingProgress(spring, berlin, 0L, running(start), 90_000_100L)
        assertEquals(82_800_000L, progress.trackedMillis)
    }

    @Test fun idleAndUnconfirmedRecoveryDoNotInventAnOpenTail() {
        assertEquals(123L, dailyTrackingProgress(date, utc, 123L, TrackingSession.idle(), 9_000L).trackedMillis)
        val review = TrackingSession.restore(
            TrackingState.NEEDS_REVIEW, 1, EntryId("entry"), ProjectId("project"),
            running(midnight).openSegment, emptyList(),
        )
        assertEquals(123L, dailyTrackingProgress(date, utc, 123L, review, 9_000L).trackedMillis)
    }

    @Test fun elapsedClockRollbackAddsNothingAndGoalDoesNotClampActualTime() {
        assertEquals(123L, dailyTrackingProgress(date, utc, 123L, running(midnight), 50L).trackedMillis)
        val progress = dailyTrackingProgress(date, utc, 32_400_000L, TrackingSession.idle(), 100L)
        assertEquals(32_400_000L, progress.trackedMillis)
        assertEquals(480, progress.goalMinutes)
    }
}
