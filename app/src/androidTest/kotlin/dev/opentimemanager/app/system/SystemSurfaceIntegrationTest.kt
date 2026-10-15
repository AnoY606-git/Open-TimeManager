package dev.opentimemanager.app.system

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Требует устройства: настоящий immutable PendingIntent проходит receiver → coordinator → Room. */
@RunWith(AndroidJUnit4::class)
class SystemSurfaceIntegrationTest {
    @Test fun systemPendingIntentsPersistStartPauseResumeAndStop() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<OpenTimeManagerApplication>()
        val coordinator = app.container.trackingCoordinator
        suspend fun awaitState(state: TrackingState) = withTimeout(10_000) {
            coordinator.observeSystemSnapshot().first { it.session.state == state }
        }
        val initial = coordinator.observeSystemSnapshot().first()
        assertEquals("Run on a clean test installation", TrackingState.IDLE, initial.session.state)
        try {
            TrackingActions.pendingIntent(app, TrackingActions.primary(initial)!!).send()
            val running = awaitState(TrackingState.RUNNING)
            // Ненулевой сегмент нужен для допустимого Pause.
            while (android.os.SystemClock.elapsedRealtime() <= running.session.openSegment!!.startedAt.elapsedRealtimeMillis) yield()
            TrackingActions.pendingIntent(app, TrackingActions.primary(running)!!).send()
            val paused = awaitState(TrackingState.PAUSED)
            assertTrue(paused.session.segments.single().durationMillis > 0)
            TrackingActions.pendingIntent(app, TrackingActions.primary(paused)!!).send()
            val resumed = awaitState(TrackingState.RUNNING)
            assertEquals(running.session.entryId, resumed.session.entryId)
            TrackingActions.pendingIntent(app, TrackingActions.stop(resumed)).send()
            awaitState(TrackingState.IDLE)
            val stopped = coordinator.observeSnapshot().first()
            assertTrue(stopped.entries.single { it.id == running.session.entryId!!.value }.completed)
        } finally {
            val current = coordinator.observeSystemSnapshot().first()
            if (current.session.state == TrackingState.RUNNING || current.session.state == TrackingState.PAUSED) coordinator.execute(TrackingActions.stop(current))
        }
    }
}
