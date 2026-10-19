package dev.opentimemanager.app.system

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.LEGACY_COMMAND_GENERATION
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.app.tracking.TrackingCommand
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class SystemSurfaceRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun fixture(
        state: TrackingState = TrackingState.RUNNING,
        revision: Long = 1,
        generation: String = LEGACY_COMMAND_GENERATION,
        confirmedMillis: Long = 5_000,
        startedElapsedMillis: Long = 2_000,
    ): SystemSnapshot {
        val session = if (state == TrackingState.IDLE) TrackingSession.idle() else TrackingSession.restore(
            state, revision, EntryId("entry"), ProjectId("project"),
            if (state == TrackingState.RUNNING) OpenSegment(SegmentId("open"), ClockSnapshot(7_000, startedElapsedMillis, 1)) else null,
            emptyList(), confirmedMillis, 6_000,
        )
        val project = ProjectEntity("project", "Secret project", 0, 0)
        return SystemSnapshot(session, generation, project, project)
    }

    private fun posted() = shadowOf(manager).getNotification(TrackingNotification.ID)

    @Test fun reconstructedSnapshotsDoNotRepostOrResetTheHostChronometer() {
        val updater = SystemSurfaceUpdater(context)
        val firstSnapshot = fixture()
        updater.refresh(firstSnapshot)
        val first = posted()
        assertNotNull(first)
        assertTrue(first.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))

        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        val restored = fixture()
        assertNotSame(firstSnapshot.session, restored.session)
        updater.refresh(restored)
        assertSame("Equal value projections must not replace the host's running chronometer", first, posted())
    }

    @Test fun changedRevisionAndGenerationStillReplaceImmutableActionTargets() {
        val updater = SystemSurfaceUpdater(context)
        updater.refresh(fixture())
        val first = posted()
        val firstAction = first.actions.first().actionIntent

        updater.refresh(fixture(revision = 2))
        val revised = posted()
        assertNotSame(first, revised)
        assertNotEquals(firstAction, revised.actions.first().actionIntent)
        val revisedCommand = TrackingActions.decode(shadowOf(revised.actions.first().actionIntent).savedIntent) as TrackingCommand.Pause
        assertEquals(2L, revisedCommand.target.expectedRevision)
        assertTrue(revised.actions.first().actionIntent.isImmutable)

        updater.refresh(fixture(revision = 2, generation = "restored-generation"))
        val restored = posted()
        assertNotSame(revised, restored)
        val restoredCommand = TrackingActions.decode(shadowOf(restored.actions.first().actionIntent).savedIntent) as TrackingCommand.Pause
        assertEquals("restored-generation", restoredCommand.expectedGeneration)
        assertNotEquals(revised.actions.first().actionIntent, restored.actions.first().actionIntent)
    }

    @Test fun durationAndMonotonicAnchorChangesInvalidateTheProjection() {
        val updater = SystemSurfaceUpdater(context)
        updater.refresh(fixture())
        val first = posted()
        updater.refresh(fixture(confirmedMillis = 6_000))
        val changedDuration = posted()
        assertNotSame(first, changedDuration)
        updater.refresh(fixture(confirmedMillis = 6_000, startedElapsedMillis = 3_000))
        assertNotSame(changedDuration, posted())
    }

    @Test fun idleQuickStartProjectChangesInvalidateTheRenderedActionIdentity() {
        val first = fixture(TrackingState.IDLE).copy(activeProject = null)
        val second = first.copy(quickStartProject = ProjectEntity("preferred", "Preferred", 0, 0))
        val firstKey = systemSurfaceKey(context, first, privacyMode = true)
        val secondKey = systemSurfaceKey(context, second, privacyMode = true)
        assertNotEquals(firstKey, secondKey)
        assertEquals(ProjectId("preferred"), (secondKey.primary as TrackingCommand.Start).projectId)
        assertNotEquals(secondKey, systemSurfaceKey(context, second.copy(quickStartProject = null), privacyMode = true))
    }

    @Test fun privacyChangesInvalidateTheProjectionWithoutATrackingTransition() {
        val preferences = context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
        val updater = SystemSurfaceUpdater(context)
        updater.refresh(fixture())
        assertEquals("Tracking", posted().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        try {
            assertTrue(preferences.edit().putBoolean("privacy", false).commit())
            updater.refresh(fixture())
            assertEquals("Tracking", posted().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("Secret project", posted().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertFalse(posted().publicVersion.extras.toString().contains("Secret project"))
        } finally {
            preferences.edit().remove("privacy").commit()
        }
    }

    @Test fun notificationAvailabilityChangesInvalidateTheProjection() {
        val updater = SystemSurfaceUpdater(context)
        updater.refresh(fixture())
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationAvailability.BLOCKED, updater.refresh(fixture()))
        manager.cancel(TrackingNotification.ID)

        shadowOf(manager).setNotificationsEnabled(true)
        assertEquals(NotificationAvailability.AVAILABLE, updater.refresh(fixture()))
        assertNotNull(posted())
    }

    @Test fun failedRefreshRemainsRetryable() {
        var stopAttempts = 0
        val failingOnce = object : ContextWrapper(context) {
            override fun stopService(service: Intent): Boolean {
                stopAttempts++
                if (stopAttempts == 1) throw IllegalStateException("Transient system failure")
                return super.stopService(service)
            }
        }
        val updater = SystemSurfaceUpdater(failingOnce)
        try {
            updater.refresh(fixture(TrackingState.IDLE))
            fail("The first publish must fail")
        } catch (_: IllegalStateException) {
            assertEquals(1, stopAttempts)
        }
        updater.refresh(fixture(TrackingState.IDLE))
        assertEquals(2, stopAttempts)
        updater.refresh(fixture(TrackingState.IDLE))
        assertEquals("Only a successfully published projection may be cached", 2, stopAttempts)
    }

    @Test fun pausedAndReviewNotificationsShowFixedConfirmedDurationInsteadOfATimestamp() {
        val notification = TrackingNotification(context)
        for (state in listOf(TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
            val snapshot = fixture(state)
            val first = notification.build(snapshot, nowWallMillis = 20_000, nowElapsedMillis = 4_000)
            val muchLater = notification.build(snapshot, nowWallMillis = 3_620_000, nowElapsedMillis = 3_604_000)
            for (rendered in listOf(first, muchLater)) {
                assertFalse(rendered.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
                assertFalse(rendered.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
                assertEquals("00:05", rendered.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
            }
        }
    }

    @Test fun runningDurationUsesOnlyTheMonotonicAnchorWhenWallTimeChanges() {
        val notification = TrackingNotification(context)
        val snapshot = fixture()
        val before = notification.build(snapshot, nowWallMillis = 20_000, nowElapsedMillis = 4_000)
        val afterWallJump = notification.build(snapshot, nowWallMillis = 3_620_000, nowElapsedMillis = 4_000)
        assertEquals(7_000L, 20_000L - before.`when`)
        assertEquals(7_000L, 3_620_000L - afterWallJump.`when`)
        assertTrue(afterWallJump.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }
}
