package dev.opentimemanager.app.system

import android.app.Application
import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.DailyTrackingProgress
import dev.opentimemanager.core.model.TrackingState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Текстовые роли уведомления не должны повторять одну строку в двух местах. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class NotificationPresentationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun privateNotificationStatesDoNotRepeatTheTitleAsBody() {
        val builder = TrackingNotification(context)
        for (state in listOf(TrackingState.RUNNING, TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
            val notification = builder.build(liveUpdateSnapshot(state), privacyMode = true)
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            assertEquals(context.getString(TrackingNotification.statusString(state)), title)
            assertNotEquals("A single status must not fill both title and body", title, body)
            assertFalse(notification.extras.toString().contains("Private project"))
        }
    }

    @Test fun visibleProjectIsSecondaryContentAndStateRemainsTheTitle() {
        val builder = TrackingNotification(context)
        for (state in listOf(TrackingState.RUNNING, TrackingState.PAUSED)) {
            val notification = builder.build(liveUpdateSnapshot(state), privacyMode = false)
            assertEquals(context.getString(TrackingNotification.statusString(state)), notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().contains("Private project"))
            assertFalse(notification.publicVersion.extras.toString().contains("Private project"))
        }
    }

    @Test fun projectNamedLikeTheCurrentStatusDoesNotAddADuplicateLine() {
        val snapshot = liveUpdateSnapshot().copy(activeProject = ProjectEntity("project", "Tracking", 0, 0))
        val notification = TrackingNotification(context).build(snapshot, privacyMode = false)
        assertEquals("Tracking", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertNotEquals("Tracking", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun textLayoutPreservesChronometerFrozenTimeAndImmutableActions() {
        val builder = TrackingNotification(context)
        val running = builder.build(liveUpdateSnapshot(), privacyMode = false, nowWallMillis = 20_000, nowElapsedMillis = 4_000)
        assertEquals(13_000L, running.`when`)
        assertTrue(running.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(listOf("Pause", "Stop"), running.actions.map { it.title.toString() })
        assertTrue(running.actions.all { it.actionIntent.isImmutable })
        assertEquals("Tracking", running.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertTrue(running.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank())

        val paused = builder.build(liveUpdateSnapshot(TrackingState.PAUSED), nowWallMillis = 90_000, nowElapsedMillis = 80_000)
        assertFalse(paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(paused.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals("00:05", paused.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals(listOf("Resume", "Stop"), paused.actions.map { it.title.toString() })

        val review = builder.build(liveUpdateSnapshot(TrackingState.NEEDS_REVIEW))
        assertTrue(review.actions.isNullOrEmpty())
        assertNotNull(review.contentIntent)
        assertFalse(review.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(0, review.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(review.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test fun olderAndroidShowsDailyProgressAndClampsTheBarWithoutClampingTrackedTime() {
        val snapshot = liveUpdateSnapshot().copy(dailyProgress = DailyTrackingProgress(LocalDate.of(2026, 10, 20), 7_200_000))
        val builder = TrackingNotification(context)
        val quarter = builder.build(snapshot, privacyMode = true)
        assertEquals(1_000, quarter.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(250, quarter.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals("Today 2:00 of 8:00", quarter.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(Notification.BigTextStyle::class.java.name, quarter.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(quarter.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank())
        assertEquals(0, quarter.publicVersion.extras.getInt(Notification.EXTRA_PROGRESS_MAX))

        val beyond = builder.build(snapshot.copy(dailyProgress = snapshot.dailyProgress!!.copy(trackedMillis = 32_400_000)))
        assertEquals(1_000, beyond.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals("Today 9:00 of 8:00", beyond.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(0, builder.build(liveUpdateSnapshot()).extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        val review = builder.build(liveUpdateSnapshot(TrackingState.NEEDS_REVIEW).copy(dailyProgress = snapshot.dailyProgress))
        assertEquals(0, review.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(review.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().contains("8:00"))
    }

    @Test fun dailyProjectionKeyChangesByMinuteDateAndGoalWhileRespectingProjectPrivacy() {
        val snapshot = liveUpdateSnapshot().copy(dailyProgress = DailyTrackingProgress(LocalDate.of(2026, 10, 20), 1_800_001))
        val key = systemSurfaceKey(context, snapshot, privacyMode = true)
        assertEquals(key, systemSurfaceKey(context, snapshot.copy(dailyProgress = snapshot.dailyProgress!!.copy(trackedMillis = 1_859_999)), true))
        assertNotEquals(key, systemSurfaceKey(context, snapshot.copy(dailyProgress = snapshot.dailyProgress!!.copy(trackedMillis = 1_860_000)), true))
        assertNotEquals(key, systemSurfaceKey(context, snapshot.copy(dailyProgress = snapshot.dailyProgress!!.copy(date = LocalDate.of(2026, 10, 21))), true))
        assertNotEquals(key, systemSurfaceKey(context, snapshot.copy(dailyProgress = snapshot.dailyProgress!!.copy(goalMinutes = 240)), true))
        val renamed = snapshot.copy(activeProject = snapshot.activeProject!!.copy(name = "Renamed private project"))
        assertEquals(key, systemSurfaceKey(context, renamed, true))
        assertNotEquals(systemSurfaceKey(context, snapshot, false), systemSurfaceKey(context, renamed, false))
    }
}
