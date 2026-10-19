package dev.opentimemanager.app.system

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.DailyTrackingProgress
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Проверяем реальный платформенный ProgressStyle, без публикации и изменения Room. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class NotificationProgressApi36Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun fixture(state: TrackingState = TrackingState.RUNNING, trackedMillis: Long = 7_200_000): SystemSnapshot {
        val project = ProjectEntity("project", "Private progress project", 0, 0)
        val session = TrackingSession.restore(
            state, 7, EntryId("progress-entry"), ProjectId(project.id),
            if (state == TrackingState.RUNNING) OpenSegment(SegmentId("open"), ClockSnapshot(7_000, 2_000, 1)) else null,
            emptyList(), 5_000, 6_000,
        )
        return SystemSnapshot(session, "progress-generation", project, project, DailyTrackingProgress(LocalDate.of(2026, 10, 20), trackedMillis))
    }

    @Test fun runningAndPausedUseFourRealProgressSegmentsAndKeepTimerActions() {
        val builder = TrackingNotification(context)
        for (state in listOf(TrackingState.RUNNING, TrackingState.PAUSED)) {
            val notification = builder.build(fixture(state), privacyMode = false, nowWallMillis = 20_000, nowElapsedMillis = 4_000)
            val style = Notification.Builder.recoverBuilder(context, notification).style as Notification.ProgressStyle
            assertEquals(listOf(250, 250, 250, 250), style.progressSegments.map { it.length })
            assertEquals(1_000, style.progressMax)
            assertEquals(250, style.progress)
            assertTrue(style.isStyledByProgress)
            assertFalse(style.isProgressIndeterminate)
            assertEquals(state == TrackingState.RUNNING, notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            if (state == TrackingState.RUNNING) assertEquals(13_000L, notification.`when`)
            assertEquals(2, notification.actions.size)
            assertTrue(notification.actions.all { it.actionIntent.isImmutable })
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Private progress project"))
            assertTrue(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank())
            assertFalse(notification.publicVersion.extras.toString().contains("Private progress project"))
            assertEquals(0, notification.publicVersion.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TITLE) == notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        }
    }

    @Test fun overtimeClampsOnlyTheBarAndReviewNeverShowsDailyProgress() {
        val builder = TrackingNotification(context)
        val overtime = builder.build(fixture(trackedMillis = 32_400_000))
        val style = Notification.Builder.recoverBuilder(context, overtime).style as Notification.ProgressStyle
        assertEquals(1_000, style.progress)
        assertTrue(overtime.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("9:00"))
        assertFalse(overtime.extras.toString().contains("Private progress project"))
        val review = builder.build(fixture(TrackingState.NEEDS_REVIEW))
        assertFalse(Notification.Builder.recoverBuilder(context, review).style is Notification.ProgressStyle)
        assertEquals(0, review.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        val unavailable = builder.build(fixture().copy(dailyProgress = null))
        assertFalse(Notification.Builder.recoverBuilder(context, unavailable).style is Notification.ProgressStyle)
        assertTrue(unavailable.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }
}
