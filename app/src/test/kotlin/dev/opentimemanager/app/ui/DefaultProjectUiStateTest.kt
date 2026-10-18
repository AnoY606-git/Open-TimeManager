package dev.opentimemanager.app.ui

import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DefaultProjectUiStateTest {
    private val preferredId = "50000000-0000-4000-8000-000000000001"
    private val explicitId = "50000000-0000-4000-8000-000000000002"
    private val builtIn = ProjectRepository.DEFAULT_PROJECT_ID.value
    private val projects = listOf(
        ProjectEntity(explicitId, "Explicit", 0, 0),
        ProjectEntity(preferredId, "Preferred", 0, 1),
        ProjectEntity(builtIn, "@default", 0, 2),
    )

    private fun idle(projects: List<ProjectEntity> = this.projects) = AppSnapshot(
        TrackingSession.restore(TrackingState.IDLE, 0, null, null, null, emptyList()),
        projects, emptyList(), emptyList(),
    )

    @Test fun explicitSelectionOverridesPreferenceOnlyWhileIdle() {
        val state = AppUiState(snapshot = idle(), defaultProjectId = preferredId)
        assertEquals(preferredId, state.selectedProjectId)
        assertEquals(explicitId, state.copy(controls = AppControls(selectedProjectId = explicitId)).selectedProjectId)
        assertEquals(preferredId, state.copy(controls = AppControls(selectedProjectId = "missing")).selectedProjectId)
    }

    @Test fun automaticFallbackPrefersEligibleBuiltInRatherThanListOrder() {
        assertEquals(builtIn, AppUiState(snapshot = idle()).selectedProjectId)
        val archivedBuiltIn = projects.map { if (it.id == builtIn) it.copy(archivedAtUtcMillis = 3) else it }
        assertEquals(explicitId, AppUiState(snapshot = idle(archivedBuiltIn)).selectedProjectId)
        assertNull(AppUiState(snapshot = idle(emptyList())).selectedProjectId)
    }

    @Test fun archivedExplicitAndPreferredProjectsCannotBecomeIdleSelection() {
        val archived = projects.map { if (it.id == builtIn) it else it.copy(archivedAtUtcMillis = 3) }
        val state = AppUiState(
            snapshot = idle(archived),
            controls = AppControls(selectedProjectId = explicitId),
            defaultProjectId = preferredId,
        )
        assertEquals(builtIn, state.selectedProjectId)
    }

    @Test fun runningPausedAndReviewAlwaysExposeSessionProjectEvenWhenArchivedOrAbsentFromOptions() {
        for (state in listOf(TrackingState.RUNNING, TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
            val active = "active"
            val session = TrackingSession.restore(
                state, 1, EntryId("entry"), ProjectId(active),
                if (state == TrackingState.RUNNING) OpenSegment(SegmentId("open"), ClockSnapshot(1_000, 100, 1)) else null,
                emptyList(),
                confirmedDurationMillis = if (state == TrackingState.PAUSED) 1 else 0,
                lastConfirmedEndUtcMillis = if (state == TrackingState.PAUSED) 1_001 else null,
            )
            val snapshot = idle().copy(session = session)
            val ui = AppUiState(snapshot = snapshot, controls = AppControls(selectedProjectId = explicitId), defaultProjectId = preferredId)
            assertEquals(active, ui.selectedProjectId)
            assertEquals(active, ui.copy(snapshot = snapshot.copy(projects = projects + ProjectEntity(active, "Active", 0, 0, 1))).selectedProjectId)
        }
    }
}
