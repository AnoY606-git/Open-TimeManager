package dev.opentimemanager.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarMonthReportTest {
    @Test fun leapFebruaryAndDecemberUseExclusiveCalendarBoundaries() {
        val today = LocalDate.of(2026, 10, 19)
        val utc = ZoneId.of("UTC")
        val leap = ReportPeriod.CalendarMonth(YearMonth.of(2024, 2)).bounds(today, utc)
        assertEquals(Instant.parse("2024-02-01T00:00:00Z").toEpochMilli(), leap.fromUtcMillis)
        assertEquals(Instant.parse("2024-03-01T00:00:00Z").toEpochMilli(), leap.toUtcMillis)
        val december = ReportPeriod.CalendarMonth(YearMonth.of(2025, 12)).bounds(today, utc)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), december.toUtcMillis)
    }

    @Test fun daylightSavingMonthIsNotAFixedNumberOfTwentyFourHourDays() {
        val range = ReportPeriod.CalendarMonth(YearMonth.of(2026, 10))
            .bounds(LocalDate.of(2026, 10, 19), ZoneId.of("Europe/Berlin"))
        assertEquals(Instant.parse("2026-09-30T22:00:00Z").toEpochMilli(), range.fromUtcMillis)
        assertEquals(Instant.parse("2026-10-31T23:00:00Z").toEpochMilli(), range.toUtcMillis)
    }

    @Test fun monthsOutsideTheSupportedUserDateRangeAreRejected() {
        for (month in listOf(YearMonth.of(1899, 12), YearMonth.of(9999, 12))) {
            assertEquals(DateInputError.OUT_OF_SUPPORTED_RANGE,
                ReportPeriod.CalendarMonth(month).validatedBounds(LocalDate.of(2026, 10, 19), ZoneId.of("UTC")).error)
        }
    }
}
