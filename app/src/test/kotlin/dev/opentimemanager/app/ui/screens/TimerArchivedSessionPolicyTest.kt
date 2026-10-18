package dev.opentimemanager.app.ui.screens

import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.primaryCommand
import dev.opentimemanager.app.ui.stopCommand
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.CommandId
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerArchivedSessionPolicyTest {
    private val archived = ProjectEntity("project", "Archived", 0, 0, archivedAtUtcMillis = 1)

    private fun session(state: TrackingState) = TrackingSession.restore(
        state = state,
        revision = 2,
        entryId = EntryId("entry"),
        projectId = ProjectId(archived.id),
        openSegment = if (state == TrackingState.RUNNING) OpenSegment(SegmentId("open"), ClockSnapshot(2_000, 100, 1)) else null,
        segments = emptyList(),
        confirmedDurationMillis = 1_000,
        lastConfirmedEndUtcMillis = 2_000,
    )

    @Test fun pauseResumeAndStopRemainAvailableForLegacyArchivedProjects() {
        for (state in listOf(TrackingState.RUNNING, TrackingState.PAUSED)) {
            val session = session(state)
            assertTrue(timerPrimaryActionEnabled(session, selectedProject = null))
            assertTrue(timerPrimaryActionEnabled(session, selectedProject = archived))
            val snapshot = AppSnapshot(session, listOf(archived), emptyList(), emptyList())
            assertNotNull(primaryCommand(snapshot, ProjectId(archived.id), CommandId("primary-$state")))
            assertNotNull(stopCommand(snapshot, CommandId("stop-$state")))
        }
    }

    @Test fun startRequiresAnAvailableNonArchivedProject() {
        assertFalse(timerPrimaryActionEnabled(TrackingSession.idle(), selectedProject = null))
        assertFalse(timerPrimaryActionEnabled(TrackingSession.idle(), selectedProject = archived))
        assertTrue(timerPrimaryActionEnabled(TrackingSession.idle(), selectedProject = archived.copy(archivedAtUtcMillis = null)))
    }

    @Test fun interruptedEntriesStillRequireReviewInsteadOfStartingOrResuming() {
        assertFalse(timerPrimaryActionEnabled(session(TrackingState.NEEDS_REVIEW), selectedProject = archived.copy(archivedAtUtcMillis = null)))
    }
}
