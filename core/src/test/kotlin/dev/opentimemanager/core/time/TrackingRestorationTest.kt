package dev.opentimemanager.core.time

import dev.opentimemanager.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TrackingRestorationTest {
    @Test fun restorationKeepsPersistedAnchorAndRevision() {
        val anchor = OpenSegment(SegmentId("s"), ClockSnapshot(1000, 100, 7))
        val restored = TrackingSession.restore(TrackingState.RUNNING, 42, EntryId("e"), ProjectId("p"), anchor, emptyList())
        assertEquals(42L, restored.revision)
        assertEquals(anchor, restored.openSegment)
    }
    @Test fun restorationRejectsImpossibleStatesAndForeignSegments() {
        assertThrows(IllegalArgumentException::class.java) { TrackingSession.restore(TrackingState.RUNNING, 0, null, null, null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TrackingSession.restore(TrackingState.IDLE, -1, null, null, null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TrackingSession.restore(TrackingState.PAUSED, 1, EntryId("e"), ProjectId("p"), null, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TrackingSession.restore(TrackingState.PAUSED, 1, EntryId("e"), ProjectId("p"), null, listOf(Segment(SegmentId("s"), EntryId("other"), ProjectId("p"), 0, 10))) }
    }
    @Test fun unknownBootEvidenceRequiresReviewEvenWhenBothCountsAreUnknown() {
        val started = (TrackingReducer.start(TrackingSession.idle(), ProjectId("p"), EntryId("e"), SegmentId("s"), ClockSnapshot(0, 1, null), CommandId("start"), 0) as TransitionResult.Applied).session
        val recovered = TrackingReducer.recoverAfterBoot(started, CommandTarget(EntryId("e"), 1, CommandId("recover")), ClockSnapshot(10, 10, null)) as TransitionResult.Applied
        assertEquals(TrackingState.NEEDS_REVIEW, recovered.session.state)
    }
}
