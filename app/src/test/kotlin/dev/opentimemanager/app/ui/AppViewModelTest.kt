package dev.opentimemanager.app.ui

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.tracking.TrackingCommand
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.CommandId
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class AppViewModelTest {
    private fun snapshot(state: TrackingState, revision: Long = 4): AppSnapshot {
        val session = when (state) {
            TrackingState.IDLE -> TrackingSession.restore(state, revision, null, null, null, emptyList())
            TrackingState.RUNNING -> TrackingSession.restore(
                state,
                revision,
                EntryId("entry"),
                ProjectId("project"),
                OpenSegment(SegmentId("open"), ClockSnapshot(1_000, 100, 1)),
                emptyList(),
            )
            TrackingState.PAUSED -> TrackingSession.restore(
                state,
                revision,
                EntryId("entry"),
                ProjectId("project"),
                null,
                listOf(Segment(SegmentId("closed"), EntryId("entry"), ProjectId("project"), 1_000, 1_000)),
            )
            TrackingState.NEEDS_REVIEW -> TrackingSession.restore(
                state,
                revision,
                EntryId("entry"),
                ProjectId("project"),
                null,
                emptyList(),
            )
        }
        return AppSnapshot(session, emptyList(), emptyList(), emptyList())
    }

    @Test fun primaryIntentMapsEachValidTimerStateWithoutGenericToggle() {
        val start = primaryCommand(snapshot(TrackingState.IDLE), ProjectId("project"), CommandId("start"))
        val pause = primaryCommand(snapshot(TrackingState.RUNNING), ProjectId("ignored"), CommandId("pause"))
        val resume = primaryCommand(snapshot(TrackingState.PAUSED), ProjectId("ignored"), CommandId("resume"))

        assertEquals(TrackingCommand.Start(CommandId("start"), ProjectId("project"), 4), start)
        assertTrue(pause is TrackingCommand.Pause)
        assertEquals(4, (pause as TrackingCommand.Pause).target.expectedRevision)
        assertTrue(resume is TrackingCommand.Resume)
        assertEquals(EntryId("entry"), (resume as TrackingCommand.Resume).target.entryId)
        assertNull(primaryCommand(snapshot(TrackingState.NEEDS_REVIEW), ProjectId("project"), CommandId("review")))
    }

    @Test fun stopIntentTargetsTheVisibleEntryAndRevision() {
        val stop = stopCommand(snapshot(TrackingState.RUNNING, revision = 9), CommandId("stop"))
        assertTrue(stop is TrackingCommand.Stop)
        assertEquals(EntryId("entry"), stop!!.target.entryId)
        assertEquals(9, stop.target.expectedRevision)
        assertNull(stopCommand(snapshot(TrackingState.IDLE), CommandId("idle")))
    }

    @Test fun selectingAnotherProjectDuringTrackingProducesAtomicSwitch() {
        val running = snapshot(TrackingState.RUNNING, revision = 6)
        val command = switchCommand(running, ProjectId("other"), CommandId("switch"))
        assertTrue(command is TrackingCommand.Switch)
        assertEquals(ProjectId("other"), command!!.projectId)
        assertEquals(6, command.target.expectedRevision)
        assertNull(switchCommand(running, ProjectId("project"), CommandId("same")))
        assertNull(switchCommand(snapshot(TrackingState.IDLE), ProjectId("other"), CommandId("idle")))
    }

    @Test fun selectedProjectFollowsPersistedSessionUntilSwitchCommits() {
        val projects = listOf(
            ProjectEntity("project", "Current", 0, 0),
            ProjectEntity("other", "Requested", 0, 0),
        )
        val requested = AppControls(selectedProjectId = "other")

        assertEquals(
            "project",
            AppUiState(snapshot(TrackingState.RUNNING).copy(projects = projects), controls = requested).selectedProjectId,
        )
        assertEquals(
            "other",
            AppUiState(snapshot(TrackingState.IDLE).copy(projects = projects), controls = requested).selectedProjectId,
        )
    }

    @Test fun manualEntryValidationRejectsInvalidRangeAndAcceptsPositiveDuration() {
        val valid = validateManualEntry(
            projectId = "project",
            startUtcMillis = 1_000,
            endUtcMillis = 61_000,
            note = "note",
        )
        assertEquals(ManualEntryDraft("project", 1_000, 60_000, "note"), valid.value)
        assertNull(validateManualEntry("", 1_000, 2_000, "").value)
        assertEquals(EntryValidationError.END_NOT_AFTER_START, validateManualEntry("project", 2_000, 2_000, "").error)
        assertEquals(EntryValidationError.NOTE_TOO_LONG, validateManualEntry("project", 1_000, 2_000, "x".repeat(2_001)).error)
    }

    @Test fun destructiveHistoryActionsRequireExplicitMatchingConfirmation() {
        var state = HistoryDialogState()
        state = state.requestDelete("entry-a")
        assertEquals("entry-a", state.pendingDeleteId)
        assertFalse(state.confirmDelete("entry-b").confirmed)
        assertTrue(state.confirmDelete("entry-a").confirmed)
        assertNull(state.confirmDelete("entry-a").next.pendingDeleteId)
    }

    @Test fun reportPeriodsUseLocalHalfOpenCalendarBoundaries() {
        val zone = ZoneId.of("Europe/Moscow")
        val today = LocalDate.of(2026, 10, 15)
        val week = ReportPeriod.WEEK.bounds(today, zone)
        val month = ReportPeriod.MONTH.bounds(today, zone)
        val custom = ReportPeriod.Custom(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 4)).bounds(today, zone)

        assertEquals(Instant.parse("2026-10-11T21:00:00Z").toEpochMilli(), week.fromUtcMillis)
        assertEquals(Instant.parse("2026-10-18T21:00:00Z").toEpochMilli(), week.toUtcMillis)
        assertEquals(Instant.parse("2026-09-30T21:00:00Z").toEpochMilli(), month.fromUtcMillis)
        assertEquals(Instant.parse("2026-10-31T21:00:00Z").toEpochMilli(), month.toUtcMillis)
        assertEquals(Instant.parse("2026-10-01T21:00:00Z").toEpochMilli(), custom.fromUtcMillis)
        assertEquals(Instant.parse("2026-10-04T21:00:00Z").toEpochMilli(), custom.toUtcMillis)
    }

    @Test fun settingsPersistThemeLanguagePrivacyAndDailyGoalAcrossInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("system_surfaces", 0).edit().clear().commit()
        val name = "settings-${System.nanoTime()}"
        val first = UserSettings(context, name)
        first.setTheme(ThemeMode.DARK)
        first.setLanguage(AppLanguage.RUSSIAN)
        first.setDynamicColor(false)
        first.setPrivacy(false)
        first.setDailyGoalMinutes(510)

        val persisted = withTimeout(5_000) { UserSettings(context, name).values.first { it.theme == ThemeMode.DARK } }
        assertEquals(AppLanguage.RUSSIAN, persisted.language)
        assertFalse(persisted.dynamicColor)
        assertFalse(persisted.privacyMode)
        assertEquals(510, persisted.dailyGoalMinutes)
        assertFalse(context.getSharedPreferences("system_surfaces", 0).getBoolean("privacy", true))
    }

    @Test fun adaptiveNavigationUsesBottomRailAndMeaningfulTwoPane() {
        assertEquals(NavigationLayout.BOTTOM, navigationLayout(widthDp = 599, heightDp = 900))
        assertEquals(NavigationLayout.RAIL, navigationLayout(widthDp = 700, heightDp = 900))
        assertEquals(NavigationLayout.TWO_PANE, navigationLayout(widthDp = 1_000, heightDp = 900))
        assertEquals(NavigationLayout.RAIL, navigationLayout(widthDp = 900, heightDp = 380))
        assertTrue(timerUsesCompactLayout(heightDp = 380, fontScale = 1f))
        assertTrue(timerUsesCompactLayout(heightDp = 700, fontScale = 2f))
    }

    @Test fun todayProgressAddsOnlyOpenTailWithoutDoubleCountingConfirmedCheckpoint() {
        val closed = Segment(SegmentId("closed"), EntryId("entry"), ProjectId("project"), 1_000, 500)
        val session = TrackingSession.restore(
            TrackingState.RUNNING,
            2,
            EntryId("entry"),
            ProjectId("project"),
            OpenSegment(SegmentId("open"), ClockSnapshot(2_000, 100, 1)),
            listOf(closed),
        )
        val value = todayTrackedMillis(
            AppSnapshot(session, emptyList(), emptyList(), listOf(closed)),
            nowElapsedMillis = 1_100,
            today = LocalDate.of(1970, 1, 1),
            zoneId = ZoneId.of("UTC"),
        )
        assertEquals(1_500L, value)
    }

    @Test fun notificationControlStatusDistinguishesPermissionAndBlockedChannel() {
        assertEquals(NotificationControlStatus.AVAILABLE, notificationControlStatus(permissionGranted = true, notificationsEnabled = true))
        assertEquals(NotificationControlStatus.PERMISSION_REQUIRED, notificationControlStatus(permissionGranted = false, notificationsEnabled = false))
        assertEquals(NotificationControlStatus.BLOCKED, notificationControlStatus(permissionGranted = true, notificationsEnabled = false))
    }

    @Test fun russianMinuteHourAndDayPluralsCoverOneFewManyElevenAndTwentyOne() {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        val context = base.createConfigurationContext(config)
        val quantities = listOf(1, 2, 5, 11, 21)
        fun forms(resource: Int) = quantities.map { context.resources.getQuantityString(resource, it, it) }
        assertEquals(listOf("1 минута", "2 минуты", "5 минут", "11 минут", "21 минута"), forms(dev.opentimemanager.app.R.plurals.minutes))
        assertEquals(listOf("1 час", "2 часа", "5 часов", "11 часов", "21 час"), forms(dev.opentimemanager.app.R.plurals.hours))
        assertEquals(listOf("1 день", "2 дня", "5 дней", "11 дней", "21 день"), forms(dev.opentimemanager.app.R.plurals.days))
    }
}
