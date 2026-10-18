package dev.opentimemanager.app.ui.screens

import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.ui.DateInputError
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerRefinementTest {
    @Test fun narrowAndEnlargedTimerActionsStackWithoutReducingTextScale() {
        // 320dp window, 20dp screen gutters, 16/24dp card padding.
        for (width in listOf(232f, 248f)) {
            for (scale in listOf(1f, 1.3f, 2f)) assertTrue(timerControlsShouldStack(width, scale))
        }
        assertTrue(timerControlsShouldStack(500f, 1.3f))
        assertTrue(timerControlsShouldStack(500f, 2f))
        assertFalse(timerControlsShouldStack(360f, 1f))
    }

    @Test fun projectSearchUsesDisplayedNamesAndNeverOffersArchivedTimerProjects() {
        val default = ProjectEntity("default", "storage name", 0, 0)
        val active = ProjectEntity("active", "Client planning", 0, 0)
        val archived = ProjectEntity("archived", "Client archived", 0, 0, archivedAtUtcMillis = 1)
        val projects = listOf(default, active, archived)
        val name: (ProjectEntity) -> String = { if (it.id == default.id) "Без проекта" else it.name }

        assertEquals(listOf(default), filterTimerProjects(projects, "  БЕЗ ПРОЕКТА  ", name))
        assertEquals(listOf(active), filterTimerProjects(projects, "CLIENT", name))
        assertEquals(listOf(default, active), filterTimerProjects(projects, " ", name))
        assertTrue(filterTimerProjects(projects, "storage", name).isEmpty())
    }

    @Test fun projectNameValidationMatchesTrimmedNormalizedCodePointLimit() {
        assertFalse(validProjectName(" \n "))
        assertTrue(validProjectName(" a "))
        assertTrue(validProjectName("\uD83D\uDE80".repeat(80)))
        assertFalse(validProjectName("\uD83D\uDE80".repeat(81)))
        assertTrue(validProjectName("e\u0301".repeat(80)))
        assertFalse(validProjectName("x".repeat(81)))
    }

    @Test fun reviewRequiresAnExplicitValidEndAfterTheUnconfirmedIntervalBegan() {
        val utc = ZoneId.of("UTC")
        val start = Instant.parse("2026-10-18T12:34:30Z").toEpochMilli()
        assertNull(reviewEndMillis("2026-10-18", "", utc, start))
        assertNull(reviewEndMillis("2026-10-18", "12:34", utc, start))
        assertNull(reviewEndMillis("2026-10-18", "12:34:30", utc, start))
        assertNull(reviewEndMillis("not-a-date", "12:35", utc, start))
        assertEquals(Instant.parse("2026-10-18T12:35:00Z").toEpochMilli(), reviewEndMillis("2026-10-18", "12:35", utc, start))
    }

    @Test fun reportDateErrorsNeverAskUsersToCheckUnrelatedProjectsOrNotes() {
        assertEquals(R.string.ux_report_invalid_dates, customPeriodErrorResource(DateInputError.MALFORMED))
        assertEquals(R.string.ux_report_reversed_dates, customPeriodErrorResource(DateInputError.REVERSED_RANGE))
        assertEquals(R.string.ux_report_range_too_large, customPeriodErrorResource(DateInputError.RANGE_TOO_LARGE))
        assertEquals(R.string.ux_date_out_of_range, customPeriodErrorResource(DateInputError.OUT_OF_SUPPORTED_RANGE))
        assertEquals(R.string.ux_date_out_of_range, customPeriodErrorResource(DateInputError.OVERFLOW))
    }

    @Test fun partialHistorySearchExplainsItsScopeWithoutPromisingLoadMorePastTheLimit() {
        assertNull(historySearchScopeResource(hasMoreHistory = false, historyLimit = 100))
        assertEquals(R.string.ux_history_search_loaded_hint, historySearchScopeResource(hasMoreHistory = true, historyLimit = 100))
        assertEquals(R.string.ux_history_search_limit_hint, historySearchScopeResource(hasMoreHistory = true, historyLimit = 2_000))
    }
}
