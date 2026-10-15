package dev.opentimemanager.app.ui

import android.app.Application
import android.app.NotificationManager
import android.provider.Settings
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.core.os.LocaleListCompat
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.system.TrackingNotification
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.EntryEntity
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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

    @Test fun noteOnlyEditPreservesExactTrackedStartAndSubMinuteDuration() {
        val exactStart = Instant.parse("2026-10-15T12:34:56.789Z").toEpochMilli()
        for (exactDuration in listOf(30_500L, 90_001L)) {
            val entry = EntryEntity(
                id = "entry-$exactDuration",
                projectId = "project",
                note = "old",
                startedAtUtcMillis = exactStart,
                endedAtUtcMillis = exactStart + exactDuration,
                source = "TIMER",
                revision = 4,
                completed = true,
            )
            val edited = entryFormState(entry, exactDuration, ZoneId.of("UTC")).copy(noteInput = "updated")

            val draft = validateEntryForm(edited, ZoneId.of("UTC")).value
            assertEquals(ManualEntryDraft("project", exactStart, exactDuration, "updated", preserveTiming = true), draft)
            assertTrue(requireNotNull(draft).preserveTiming)
        }
    }

    @Test fun dateOnlyEditPreservesHiddenSecondsAndRevertedFieldsPreserveExactValues() {
        val originalStart = Instant.parse("2026-10-15T12:34:56.789Z").toEpochMilli()
        val entry = EntryEntity("entry", "project", "note", originalStart, originalStart + 90_001, "TIMER", 2, true)
        val original = entryFormState(entry, 90_001, ZoneId.of("UTC"))

        val dateOnly = validateEntryForm(
            original.copy(dateInput = "2026-10-16", dateEdited = true),
            ZoneId.of("UTC"),
        ).value
        assertEquals(Instant.parse("2026-10-16T12:34:56.789Z").toEpochMilli(), dateOnly?.startUtcMillis)
        assertEquals(90_001L, dateOnly?.durationMillis)
        assertFalse(requireNotNull(dateOnly).preserveTiming)

        val reverted = validateEntryForm(
            original.copy(timeInput = original.timeInput, timeEdited = false),
            ZoneId.of("UTC"),
        ).value
        assertEquals(originalStart, reverted?.startUtcMillis)
        assertEquals(90_001L, reverted?.durationMillis)
        assertTrue(requireNotNull(reverted).preserveTiming)
    }

    @Test fun destructiveHistoryActionsRequireExplicitMatchingConfirmation() {
        var state = HistoryDialogState()
        state = state.requestDelete("entry-a")
        assertEquals("entry-a", state.pendingDeleteId)
        assertFalse(state.confirmDelete("entry-b").confirmed)
        assertTrue(state.confirmDelete("entry-a").confirmed)
        assertNull(state.confirmDelete("entry-a").next.pendingDeleteId)
    }

    @Test fun historySearchUsesTheLocalizedProjectNameThatIsActuallyDisplayed() {
        val project = ProjectEntity("default", "Без проекта", 0, 0)
        val entry = EntryEntity("entry", project.id, "quiet note", 1_000, 2_000, "MANUAL", 0, true)

        assertEquals(
            listOf(entry),
            filterCompletedEntries(listOf(entry), listOf(project), "no project") { "No project" },
        )
        assertEquals(
            listOf(entry),
            filterCompletedEntries(listOf(entry), listOf(project), "без проекта") { "Без проекта" },
        )
        assertTrue(filterCompletedEntries(listOf(entry), listOf(project), "invisible") { "No project" }.isEmpty())
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

    @Test fun typedDateValidationRejectsExtremeOverflowAndReversedRanges() {
        val utc = ZoneId.of("UTC")
        assertEquals(DateInputError.OUT_OF_SUPPORTED_RANGE, validateReviewEndInput(LocalDate.MIN.toString(), "00:00", utc).error)
        assertEquals(DateInputError.OUT_OF_SUPPORTED_RANGE, validateReviewEndInput(LocalDate.MAX.toString(), "23:59", utc).error)
        assertNull(validateCustomReportInput(LocalDate.MIN.toString(), "2026-10-15", utc).value)
        assertNull(validateCustomReportInput("2026-10-15", LocalDate.MAX.toString(), utc).value)
        assertEquals(
            DateInputError.REVERSED_RANGE,
            validateCustomReportInput("2026-10-16", "2026-10-15", utc).error,
        )
        assertEquals(DateInputError.MALFORMED, validateCustomReportInput("not-a-date", "2026-10-15", utc).error)
        assertEquals(
            DateInputError.OUT_OF_SUPPORTED_RANGE,
            ReportPeriod.Custom(LocalDate.MIN, LocalDate.of(2026, 10, 15)).validatedBounds(LocalDate.now(), utc).error,
        )
        assertEquals(DateInputError.OUT_OF_SUPPORTED_RANGE, ReportPeriod.WEEK.validatedBounds(LocalDate.MAX, utc).error)

        assertEquals(
            Instant.parse("2026-10-15T12:34:00Z").toEpochMilli(),
            validateReviewEndInput("2026-10-15", "12:34", utc).value,
        )
        assertEquals(
            ReportPeriod.Custom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)),
            validateCustomReportInput("2026-10-01", "2026-10-15", utc).value,
        )
    }

    @Test fun customReportRangeIsBoundedToTenYears() {
        val utc = ZoneId.of("UTC")
        assertNotNull(validateCustomReportInput("2020-01-01", "2029-12-31", utc).value)
        assertEquals(
            DateInputError.RANGE_TOO_LARGE,
            validateCustomReportInput("2020-01-01", "2030-01-02", utc).error,
        )
    }

    @Test fun historyLoadMoreRemainsExplicitlyBounded() {
        assertEquals(200, nextHistoryLimit(100))
        assertEquals(2_000, nextHistoryLimit(1_950))
        assertEquals(2_000, nextHistoryLimit(2_000))
    }

    @Test fun appCompatLocalesAreTheSingleSourceOfTruthForTheSettingsSelection() {
        assertEquals(AppLanguage.SYSTEM, applicationLanguage(LocaleListCompat.getEmptyLocaleList()))
        assertEquals(AppLanguage.ENGLISH, applicationLanguage(LocaleListCompat.forLanguageTags("en-US")))
        assertEquals(AppLanguage.RUSSIAN, applicationLanguage(LocaleListCompat.forLanguageTags("ru-RU")))
    }

    @Test fun settingsPersistThemePrivacyAndDailyGoalAcrossInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("system_surfaces", 0).edit().clear().commit()
        val name = "settings-${System.nanoTime()}"
        val first = UserSettings(context, name)
        first.setTheme(ThemeMode.DARK)
        first.setDynamicColor(false)
        first.setPrivacy(false)
        first.setDailyGoalMinutes(510)

        val persisted = withTimeout(5_000) { UserSettings(context, name).values.first { it.theme == ThemeMode.DARK } }
        assertFalse(persisted.dynamicColor)
        assertFalse(persisted.privacyMode)
        assertEquals(510, persisted.dailyGoalMinutes)
        assertFalse(context.getSharedPreferences("system_surfaces", 0).getBoolean("privacy", true))
    }

    @Test fun adaptiveNavigationUsesBottomRailAndMeaningfulTwoPane() {
        assertEquals(NavigationLayout.BOTTOM, navigationLayout(widthDp = 599, heightDp = 900))
        assertEquals(NavigationLayout.RAIL, navigationLayout(widthDp = 600, heightDp = 900))
        assertEquals(NavigationLayout.RAIL, navigationLayout(widthDp = 839, heightDp = 900))
        assertEquals(NavigationLayout.RAIL, navigationLayout(widthDp = 840, heightDp = 599))
        assertEquals(NavigationLayout.TWO_PANE, navigationLayout(widthDp = 840, heightDp = 600))
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
        val projection = confirmedDayProjection(listOf(closed), LocalDate.of(1970, 1, 1), ZoneId.of("UTC"))
        val value = todayTrackedMillis(projection, session, nowElapsedMillis = 1_100)
        assertEquals(1_500L, value)
    }

    @Test fun confirmedDayProjectionClipsCrossMidnightRowsAndTickAddsOnlyOpenTail() {
        val utc = ZoneId.of("UTC")
        val day = LocalDate.of(2026, 10, 15)
        val beforeMidnight = Instant.parse("2026-10-14T23:50:00Z").toEpochMilli()
        val beforeNextMidnight = Instant.parse("2026-10-15T23:50:00Z").toEpochMilli()
        val confirmed = listOf(
            Segment(SegmentId("from-yesterday"), EntryId("night-a"), ProjectId("project"), beforeMidnight, 20 * 60_000L),
            Segment(SegmentId("into-tomorrow"), EntryId("night-b"), ProjectId("project"), beforeNextMidnight, 20 * 60_000L),
        )
        val projection = confirmedDayProjection(confirmed, day, utc)

        assertEquals(20 * 60_000L, projection.confirmedMillis)
        assertEquals(listOf(10 * 60_000L, 10 * 60_000L), projection.rows.map { it.durationMillis })
        assertEquals(
            listOf(
                Instant.parse("2026-10-15T23:50:00Z").toEpochMilli(),
                Instant.parse("2026-10-15T00:00:00Z").toEpochMilli(),
            ),
            projection.rows.map { it.firstStartUtcMillis },
        )

        val openStart = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
        val running = TrackingSession.restore(
            TrackingState.RUNNING,
            1,
            EntryId("open-entry"),
            ProjectId("project"),
            OpenSegment(SegmentId("open"), ClockSnapshot(openStart, 100L, 1L)),
            emptyList(),
        )
        assertEquals(projection.confirmedMillis + 1_000L, todayTrackedMillis(projection, running, 1_100L))
        assertEquals(projection.confirmedMillis + 2_000L, todayTrackedMillis(projection, running, 2_100L))
        assertEquals(20 * 60_000L, projection.confirmedMillis)
    }

    @Test fun confirmedDayProjectionGroupsSplitEntryWithExactSubMinuteDurations() {
        val day = LocalDate.of(2026, 10, 15)
        val from = Instant.parse("2026-10-15T00:00:00Z").toEpochMilli()
        val entryId = EntryId("split")
        val projectId = ProjectId("project")
        val projection = confirmedDayProjection(
            listOf(
                Segment(SegmentId("first"), entryId, projectId, from + 1_000, 30_500),
                Segment(SegmentId("second"), entryId, projectId, from + 90_000, 90_001),
                Segment(SegmentId("ends-at-start"), EntryId("excluded-a"), projectId, from - 1_000, 1_000),
                Segment(SegmentId("starts-at-end"), EntryId("excluded-b"), projectId, from + 86_400_000, 1_000),
            ),
            day,
            ZoneId.of("UTC"),
        )

        assertEquals(120_501L, projection.confirmedMillis)
        assertEquals(listOf(TodayEntryProjection(entryId, projectId, from + 1_000, 120_501L)), projection.rows)
    }

    @Test fun localDayTickWaitsForTheExactNextMidnightIncludingDst() {
        assertEquals(
            500L,
            millisUntilNextLocalDay(
                Instant.parse("2026-10-18T20:59:59.500Z"),
                ZoneId.of("Europe/Moscow"),
            ),
        )
        assertEquals(
            22L * 60 * 60 * 1_000 + 30L * 60 * 1_000,
            millisUntilNextLocalDay(
                Instant.parse("2026-03-28T23:30:00Z"),
                ZoneId.of("Europe/Berlin"),
            ),
        )
    }

    @Test fun localDayFlowEmitsANewReportKeyAcrossMidnightWithoutControlsChanges() = runBlocking {
        val instants = java.util.ArrayDeque(
            listOf(
                Instant.parse("2026-10-18T20:59:59Z"),
                Instant.parse("2026-10-18T21:00:00Z"),
            ),
        )

        val days = localDayContexts(
            now = { instants.removeFirst() },
            zoneId = { ZoneId.of("Europe/Moscow") },
            waitUntilNextDay = {},
        ).take(2).toList()

        assertEquals(listOf(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 19)), days.map { it.date })
        assertEquals(listOf(ZoneId.of("Europe/Moscow"), ZoneId.of("Europe/Moscow")), days.map { it.zoneId })
    }

    @Test fun notificationControlStatusDistinguishesPermissionAndBlockedChannel() {
        assertFalse(shouldRecordNotificationDenial(false, false, false))
        assertTrue(shouldRecordNotificationDenial(false, false, true))
        assertTrue(shouldRecordNotificationDenial(false, true, false))
        assertFalse(shouldRecordNotificationDenial(true, true, false))
        assertEquals(
            NotificationControlStatus.PERMISSION_REQUESTABLE,
            notificationControlStatus(33, false, false, false, true, NotificationManager.IMPORTANCE_DEFAULT),
        )
        assertEquals(
            NotificationControlStatus.PERMISSION_REQUESTABLE,
            notificationControlStatus(33, false, true, true, true, NotificationManager.IMPORTANCE_DEFAULT),
        )
        assertEquals(
            NotificationControlStatus.PERMISSION_FIXED,
            notificationControlStatus(33, false, true, false, true, NotificationManager.IMPORTANCE_DEFAULT),
        )
        assertEquals(
            NotificationControlStatus.APP_BLOCKED,
            notificationControlStatus(36, true, true, false, false, NotificationManager.IMPORTANCE_DEFAULT),
        )
        assertEquals(
            NotificationControlStatus.CHANNEL_BLOCKED,
            notificationControlStatus(31, true, false, false, true, NotificationManager.IMPORTANCE_NONE),
        )
        assertEquals(NotificationControlAction.REQUEST_PERMISSION, notificationControlAction(NotificationControlStatus.PERMISSION_REQUESTABLE))
        assertEquals(NotificationControlAction.OPEN_APP_SETTINGS, notificationControlAction(NotificationControlStatus.PERMISSION_FIXED))
        assertEquals(NotificationControlAction.OPEN_CHANNEL_SETTINGS, notificationControlAction(NotificationControlStatus.CHANNEL_BLOCKED))

        val appIntent = notificationSettingsIntent(NotificationControlAction.OPEN_APP_SETTINGS, "dev.test")
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, appIntent?.action)
        assertEquals("dev.test", appIntent?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        val channelIntent = notificationSettingsIntent(NotificationControlAction.OPEN_CHANNEL_SETTINGS, "dev.test")
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channelIntent?.action)
        assertEquals("dev.test", channelIntent?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(TrackingNotification.CHANNEL_ID, channelIntent?.getStringExtra(Settings.EXTRA_CHANNEL_ID))
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
