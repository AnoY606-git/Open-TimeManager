package dev.opentimemanager.core.report

import dev.opentimemanager.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReportAggregatorTest {
    private val project = ProjectId("a")
    private val utc = ZoneId.of("UTC")
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()
    private fun segment(start: Long, duration: Long, id: String = "s", projectId: ProjectId = project) =
        Segment(SegmentId(id), EntryId("e"), projectId, start, duration)

    // Ошибка границы суток даст 7200000 одной дате вместо двух равных частей.
    @Test fun `segment crossing midnight is split between local days`() {
        val result = ReportAggregator.aggregate(listOf(segment(millis("2026-10-14T23:00:00Z"), 7_200_000)), millis("2026-10-14T00:00:00Z"), millis("2026-10-16T00:00:00Z"), utc)
        assertEquals(mapOf(LocalDate.parse("2026-10-14") to 3_600_000L, LocalDate.parse("2026-10-15") to 3_600_000L), result.dayTotals)
        assertEquals(mapOf(project to 7_200_000L), result.projectTotals)
        assertEquals(7_200_000L, result.totalMillis)
        assertEquals(3_600_000L, result.dayProjectTotals[DayProject(LocalDate.parse("2026-10-14"), project)])
    }

    @Test fun `spring DST day contains 23 actual hours`() {
        val result = ReportAggregator.aggregate(listOf(segment(millis("2026-03-28T23:00:00Z"), 90_000_000)), millis("2026-03-28T23:00:00Z"), millis("2026-03-30T00:00:00Z"), ZoneId.of("Europe/Berlin"))
        assertEquals(mapOf(LocalDate.parse("2026-03-29") to 82_800_000L, LocalDate.parse("2026-03-30") to 7_200_000L), result.dayTotals)
        assertEquals(90_000_000L, result.totalMillis)
    }

    @Test fun `autumn DST day contains 25 actual hours`() {
        val result = ReportAggregator.aggregate(listOf(segment(millis("2026-10-24T22:00:00Z"), 93_600_000)), millis("2026-10-24T22:00:00Z"), millis("2026-10-26T00:00:00Z"), ZoneId.of("Europe/Berlin"))
        assertEquals(mapOf(LocalDate.parse("2026-10-25") to 90_000_000L, LocalDate.parse("2026-10-26") to 3_600_000L), result.dayTotals)
        assertEquals(93_600_000L, result.totalMillis)
    }

    @Test fun `range clips both ends and excludes adjacent segments`() {
        val result = ReportAggregator.aggregate(listOf(segment(100, 900), segment(0, 200, "before"), segment(800, 200, "after")), 200, 800, utc)
        assertEquals(600L, result.totalMillis)
        assertEquals(mapOf(project to 600L), result.projectTotals)
    }

    @Test fun `projects are grouped independently on the same day`() {
        val result = ReportAggregator.aggregate(listOf(segment(0, 100), segment(100, 200, "b", ProjectId("b"))), 0, 300, utc)
        assertEquals(mapOf(project to 100L, ProjectId("b") to 200L), result.projectTotals)
        assertEquals(mapOf(LocalDate.parse("1970-01-01") to 300L), result.dayTotals)
        assertEquals(300L, result.totalMillis)
    }

    @Test fun `empty input and empty range produce zero totals`() {
        assertEquals(0L, ReportAggregator.aggregate(emptyList(), 0, 100, utc).totalMillis)
        val result = ReportAggregator.aggregate(listOf(segment(0, 100)), 50, 50, utc)
        assertEquals(0L, result.totalMillis)
        assertTrue(result.dayTotals.isEmpty())
    }

    @Test fun `zero and negative segment duration are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { segment(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { segment(0, -1) }
    }

    @Test fun `segment UTC endpoint overflow is rejected`() {
        assertThrows(ArithmeticException::class.java) { segment(Long.MAX_VALUE, 1) }
    }

    @Test fun `reversed or overflowing query interval is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ReportAggregator.aggregate(emptyList(), 1, 0, utc) }
        assertThrows(ArithmeticException::class.java) { ReportAggregator.aggregate(emptyList(), Long.MIN_VALUE, Long.MAX_VALUE, utc) }
    }

    @Test fun `duplicate segment ids are rejected to prevent double counting`() {
        assertThrows(IllegalArgumentException::class.java) { ReportAggregator.aggregate(listOf(segment(0, 1), segment(1, 1)), 0, 2, utc) }
    }

    @Test fun `total duration overflow is rejected before splitting into days`() {
        assertThrows(ArithmeticException::class.java) {
            ReportAggregator.aggregate(listOf(segment(Long.MIN_VALUE, Long.MAX_VALUE), segment(Long.MIN_VALUE, Long.MAX_VALUE, "other")), Long.MIN_VALUE, -1, utc)
        }
    }
}
