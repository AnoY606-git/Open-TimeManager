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
        assertThrows(IllegalArgumentException::class.java) {
            TrackingSession.restore(
                TrackingState.PAUSED, 1, EntryId("e"), ProjectId("p"), null, emptyList(),
                confirmedDurationMillis = 10, lastConfirmedEndUtcMillis = null,
            )
        }
    }
    @Test fun compactRestorationKeepsAggregateWithoutMaterializingConfirmedSegments() {
        val restored = TrackingSession.restore(
            TrackingState.PAUSED,
            7,
            EntryId("e"),
            ProjectId("p"),
            null,
            emptyList(),
            confirmedDurationMillis = 20_000,
            lastConfirmedEndUtcMillis = 50_000,
        )

        assertEquals(20_000L, restored.confirmedDurationMillis)
        assertEquals(50_000L, restored.lastConfirmedEndUtcMillis)
        assertTrue(restored.segments.isEmpty())
        val stopped = TrackingReducer.stop(
            restored,
            CommandTarget(EntryId("e"), 7, CommandId("stop")),
            ClockSnapshot(99_000, 5_000, 1),
        ) as TransitionResult.Applied
        assertEquals(20_000L, stopped.completedEntry!!.durationMillis)
        assertEquals(50_000L, stopped.completedEntry!!.endedAtUtcMillis)
        assertTrue(stopped.completedEntry!!.segments.isEmpty())
    }
    @Test fun unknownBootEvidenceRequiresReviewEvenWhenBothCountsAreUnknown() {
        val started = (TrackingReducer.start(TrackingSession.idle(), ProjectId("p"), EntryId("e"), SegmentId("s"), ClockSnapshot(0, 1, null), CommandId("start"), 0) as TransitionResult.Applied).session
        val recovered = TrackingReducer.recoverAfterBoot(started, CommandTarget(EntryId("e"), 1, CommandId("recover")), ClockSnapshot(10, 10, null)) as TransitionResult.Applied
        assertEquals(TrackingState.NEEDS_REVIEW, recovered.session.state)
    }
}
