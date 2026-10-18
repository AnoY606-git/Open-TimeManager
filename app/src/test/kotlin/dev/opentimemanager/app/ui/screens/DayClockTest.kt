package dev.opentimemanager.app.ui.screens

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class DayClockTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private fun session(state: TrackingState) = if (state == TrackingState.IDLE) TrackingSession.idle() else TrackingSession.restore(
        state = state,
        revision = 1,
        entryId = EntryId("entry"),
        projectId = ProjectId("project"),
        openSegment = if (state == TrackingState.RUNNING) OpenSegment(SegmentId("open"), ClockSnapshot(100_000, 1_000, 1)) else null,
        segments = emptyList(),
        confirmedDurationMillis = 250,
        lastConfirmedEndUtcMillis = 100_000,
    )

    @Test fun tickDelayUsesDisplayedDurationBoundariesRatherThanDeviceUptime() {
        assertEquals(1_000L, timerTickDelayMillis(0))
        assertEquals(250L, timerTickDelayMillis(750))
        assertEquals(1L, timerTickDelayMillis(999))
        assertEquals(1_000L, timerTickDelayMillis(1_000))
        assertEquals(1_000L, timerTickDelayMillis(-1))
    }

    @Test fun startedActivityDoesNotTickAndResumeReadsBackgroundElapsedTimeImmediately() = runBlocking {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        var now = 1_500L
        val ticks = mutableListOf<Long>()
        val waits = mutableListOf<Long>()
        val job = launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            observeResumedTimer(
                owner.lifecycle,
                session(TrackingState.RUNNING),
                now = { now },
                wait = { waits += it; awaitCancellation() },
                onTick = { ticks += it },
            )
        }
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ticks.isEmpty())
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(1_500L), ticks)
            assertEquals(listOf(250L), waits)

            owner.lifecycle.currentState = Lifecycle.State.STARTED
            shadowOf(Looper.getMainLooper()).idle()
            now += 600_000L
            assertEquals(listOf(1_500L), ticks)

            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(1_500L, 601_500L), ticks)
            assertEquals(listOf(250L, 250L), waits)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            job.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            job.join()
        }
    }

    @Test fun idlePausedAndReviewStatesNeverReadOrPollTheClock() = runBlocking {
        val owner = Owner()
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        for (state in listOf(TrackingState.IDLE, TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
            observeResumedTimer(
                owner.lifecycle,
                session(state),
                now = { error("Clock must not be read for $state") },
                wait = { error("Clock must not poll for $state") },
                onTick = { error("Unexpected tick for $state") },
            )
        }
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
    }
}
