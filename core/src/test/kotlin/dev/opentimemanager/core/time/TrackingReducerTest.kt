package dev.opentimemanager.core.time

import dev.opentimemanager.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TrackingReducerTest {
    private val project = ProjectId("project")
    private val entry = EntryId("entry")
    private fun clock(wall: Long = 1_000, elapsed: Long = 100, boot: Long? = 7) =
        ClockSnapshot(wall, elapsed, boot)
    private fun target(revision: Long, id: String = "command-$revision", entryId: EntryId = entry) =
        CommandTarget(entryId, revision, CommandId(id))
    private fun applied(result: TransitionResult) = (result as TransitionResult.Applied).session
    private fun start() = applied(TrackingReducer.start(
        TrackingSession.idle(), project, entry, SegmentId("first"), clock(), CommandId("start"), 0,
    ))
    private fun rejected(reason: RejectionReason, result: TransitionResult) {
        assertEquals(reason, (result as TransitionResult.Rejected).reason)
    }

    // Потеря active entry/open segment или неверная ревизия нарушит старт.
    @Test fun `start from idle creates one running entry and clock anchor`() {
        val session = start()
        assertEquals(TrackingState.RUNNING, session.state)
        assertEquals(entry, session.entryId)
        assertEquals(project, session.projectId)
        assertEquals(1L, session.revision)
        assertEquals(clock(), session.openSegment!!.startedAt)
        assertEquals(SegmentId("first"), session.openSegment!!.id)
        assertTrue(session.segments.isEmpty())
    }

    // Учёт паузы в длительности даст 1000 вместо 500 мс.
    @Test fun `pause and resume only accumulate running elapsed time`() {
        val paused = applied(TrackingReducer.pause(start(), target(1), clock(1_200, 300)))
        assertEquals(TrackingState.PAUSED, paused.state)
        assertNull(paused.openSegment)
        assertEquals(200L, paused.segments.single().durationMillis)
        val resumed = applied(TrackingReducer.resume(paused, target(2), SegmentId("second"), clock(1_700, 800)))
        val result = TrackingReducer.stop(resumed, target(3), clock(2_000, 1_100)) as TransitionResult.Applied
        assertEquals(500L, result.completedEntry!!.durationMillis)
        assertEquals(listOf(200L, 300L), result.completedEntry!!.segments.map { it.durationMillis })
        assertEquals(TrackingState.IDLE, result.session.state)
        assertNull(result.session.entryId)
        assertEquals(4L, result.session.revision)
    }

    @Test fun `stop while paused preserves completed segments`() {
        val paused = applied(TrackingReducer.pause(start(), target(1), clock(elapsed = 400)))
        val stopped = TrackingReducer.stop(paused, target(2), clock(elapsed = 9_000)) as TransitionResult.Applied
        assertEquals(300L, stopped.completedEntry!!.durationMillis)
        assertEquals(1, stopped.completedEntry!!.segments.size)
    }

    @Test fun `wall clock jumps do not change measured duration or interval end`() {
        for (wall in listOf(-90_000L, 9_000_000L)) {
            val stopped = TrackingReducer.stop(start(), target(1), clock(wall, 600)) as TransitionResult.Applied
            val segment = stopped.completedEntry!!.segments.single()
            assertEquals(500L, segment.durationMillis)
            assertEquals(1_500L, segment.endUtcMillis)
        }
    }

    @Test fun `duplicate command is ignored even after later state transitions`() {
        val running = start()
        val paused = applied(TrackingReducer.pause(running, target(1), clock(elapsed = 200)))
        val duplicate = TrackingReducer.start(paused, project, entry, SegmentId("other"), clock(), CommandId("start"), 0)
        assertSame(paused, (duplicate as TransitionResult.Duplicate).session)
        val stopped = applied(TrackingReducer.stop(paused, target(2), clock()))
        assertTrue(TrackingReducer.pause(stopped, target(1), clock()) is TransitionResult.Duplicate)
    }

    @Test fun `stale revision and different entry are rejected without mutation`() {
        val running = start()
        rejected(RejectionReason.STALE_REVISION, TrackingReducer.pause(running, target(0), clock(elapsed = 200)))
        rejected(RejectionReason.STALE_ENTRY, TrackingReducer.stop(running, target(1, entryId = EntryId("other")), clock(elapsed = 200)))
        assertEquals(1L, running.revision)
        assertTrue(running.segments.isEmpty())
    }

    @Test fun `invalid state transitions cannot create a second active entry`() {
        val running = start()
        rejected(RejectionReason.INVALID_STATE, TrackingReducer.start(running, project, EntryId("other"), SegmentId("other"), clock(), CommandId("again"), 1))
        rejected(RejectionReason.INVALID_STATE, TrackingReducer.resume(running, target(1), SegmentId("second"), clock()))
        rejected(RejectionReason.INVALID_STATE, TrackingReducer.stop(TrackingSession.idle(), target(0), clock()))
        val paused = applied(TrackingReducer.pause(running, target(1), clock(elapsed = 200)))
        rejected(RejectionReason.INVALID_STATE, TrackingReducer.pause(paused, target(2), clock(elapsed = 300)))
    }

    @Test fun `boot mismatch preserves known work and requires review`() {
        val paused = applied(TrackingReducer.pause(start(), target(1), clock(elapsed = 200)))
        val running = applied(TrackingReducer.resume(paused, target(2), SegmentId("second"), clock(elapsed = 500)))
        val reviewed = applied(TrackingReducer.recoverAfterBoot(running, target(3), clock(10_000, 10, 8)))
        assertEquals(TrackingState.NEEDS_REVIEW, reviewed.state)
        assertEquals(100L, reviewed.segments.single().durationMillis)
        assertEquals(clock(elapsed = 500), reviewed.openSegment!!.startedAt)
        rejected(RejectionReason.INVALID_STATE, TrackingReducer.stop(reviewed, target(4), clock(elapsed = 900)))
    }

    @Test fun `pause and stop detect reboot before measuring duration`() {
        val running = start()
        for (result in listOf(
            TrackingReducer.pause(running, target(1), clock(elapsed = 900, boot = 8)),
            TrackingReducer.stop(running, target(1), clock(elapsed = 900, boot = 8)),
        )) {
            val session = applied(result)
            assertEquals(TrackingState.NEEDS_REVIEW, session.state)
            assertTrue(session.segments.isEmpty())
        }
    }

    @Test fun `recovery in same boot keeps anchor and running state`() {
        val running = start()
        val recovered = applied(TrackingReducer.recoverAfterBoot(running, target(1), clock(elapsed = 900)))
        assertEquals(TrackingState.RUNNING, recovered.state)
        assertEquals(clock(), recovered.openSegment!!.startedAt)
    }

    @Test fun `unknown boot count preserves uncertain anchor without charging time`() {
        val running = applied(TrackingReducer.start(TrackingSession.idle(), project, entry, SegmentId("first"), clock(boot = null), CommandId("start"), 0))
        val stopped = TrackingReducer.stop(running, target(1), clock(elapsed = 300, boot = null)) as TransitionResult.Applied
        assertNull(stopped.completedEntry)
        assertEquals(TrackingState.NEEDS_REVIEW, stopped.session.state)
        assertEquals(running.openSegment, stopped.session.openSegment)
    }

    @Test fun `zero and backward elapsed durations are rejected`() {
        rejected(RejectionReason.INVALID_DURATION, TrackingReducer.pause(start(), target(1), clock(elapsed = 100)))
        rejected(RejectionReason.INVALID_DURATION, TrackingReducer.stop(start(), target(1), clock(elapsed = 99)))
    }

    @Test fun `UTC interval addition overflow is rejected`() {
        val running = applied(TrackingReducer.start(TrackingSession.idle(), project, entry, SegmentId("first"), clock(Long.MAX_VALUE - 5, 0), CommandId("start"), 0))
        rejected(RejectionReason.OVERFLOW, TrackingReducer.stop(running, target(1), clock(elapsed = 10)))
    }

    @Test fun `entry duration accumulation overflow is rejected`() {
        val running = applied(TrackingReducer.start(TrackingSession.idle(), project, entry, SegmentId("first"), clock(0, 0), CommandId("start"), 0))
        val paused = applied(TrackingReducer.pause(running, target(1), clock(elapsed = Long.MAX_VALUE - 1)))
        val resumed = applied(TrackingReducer.resume(paused, target(2), SegmentId("second"), clock(0, 0)))
        rejected(RejectionReason.OVERFLOW, TrackingReducer.stop(resumed, target(3), clock(elapsed = 2)))
    }

    @Test fun `segment ids cannot be reused on resume`() {
        val paused = applied(TrackingReducer.pause(start(), target(1), clock(elapsed = 200)))
        rejected(RejectionReason.DUPLICATE_SEGMENT, TrackingReducer.resume(paused, target(2), SegmentId("first"), clock()))
    }

    @Test fun `invalid clock and blank identifiers are rejected at boundary`() {
        assertThrows(IllegalArgumentException::class.java) { ClockSnapshot(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { ClockSnapshot(0, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { ProjectId(" ") }
        assertThrows(IllegalArgumentException::class.java) { EntryId("") }
        assertThrows(IllegalArgumentException::class.java) { SegmentId("") }
        assertThrows(IllegalArgumentException::class.java) { CommandId("") }
    }
}
