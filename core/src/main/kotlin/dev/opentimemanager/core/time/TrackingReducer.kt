package dev.opentimemanager.core.time

import dev.opentimemanager.core.model.*

/** Чистые переходы: ввод времени/ID явный, сохранение результата принадлежит вызывающему коду. */
object TrackingReducer {
    fun start(
        session: TrackingSession,
        projectId: ProjectId,
        entryId: EntryId,
        segmentId: SegmentId,
        now: ClockSnapshot,
        commandId: CommandId,
        expectedRevision: Long,
    ): TransitionResult {
        guard(session, commandId, expectedRevision)?.let { return it }
        if (session.state != TrackingState.IDLE) return reject(RejectionReason.INVALID_STATE)
        return checked {
            TransitionResult.Applied(TrackingSession(
                TrackingState.RUNNING, Math.incrementExact(session.revision), entryId, projectId,
                OpenSegment(segmentId, now), 0, null, emptyList(), session.processedCommands + commandId,
            ))
        }
    }

    fun pause(session: TrackingSession, target: CommandTarget, now: ClockSnapshot): TransitionResult =
        close(session, target, now, stop = false)

    fun stop(session: TrackingSession, target: CommandTarget, now: ClockSnapshot): TransitionResult =
        close(session, target, now, stop = true)

    fun resume(
        session: TrackingSession,
        target: CommandTarget,
        segmentId: SegmentId,
        now: ClockSnapshot,
    ): TransitionResult {
        guard(session, target)?.let { return it }
        if (session.state != TrackingState.PAUSED) return reject(RejectionReason.INVALID_STATE)
        if (session.segments.any { it.id == segmentId }) return reject(RejectionReason.DUPLICATE_SEGMENT)
        return checked {
            TransitionResult.Applied(next(session, target.commandId, TrackingState.RUNNING, OpenSegment(segmentId, now)))
        }
    }

    /** При недостоверной опоре сохраняем её для будущего ручного разрешения, не начисляя время. */
    fun recoverAfterBoot(session: TrackingSession, target: CommandTarget, now: ClockSnapshot): TransitionResult {
        guard(session, target)?.let { return it }
        if (session.state != TrackingState.RUNNING) return reject(RejectionReason.INVALID_STATE)
        return checked {
            val start = session.openSegment!!.startedAt
            val state = if (!sameBoot(start, now) || now.elapsedRealtimeMillis < start.elapsedRealtimeMillis) TrackingState.NEEDS_REVIEW else TrackingState.RUNNING
            TransitionResult.Applied(next(session, target.commandId, state, session.openSegment))
        }
    }

    private fun close(session: TrackingSession, target: CommandTarget, now: ClockSnapshot, stop: Boolean): TransitionResult {
        // Повтор проверяется до состояния/ревизии: повтор старой успешной команды безопасен.
        guard(session, target)?.let { return it }
        if (session.state != TrackingState.RUNNING && !(stop && session.state == TrackingState.PAUSED)) {
            return reject(RejectionReason.INVALID_STATE)
        }
        return checked {
            val open = session.openSegment
            if (open != null && !sameBoot(open.startedAt, now)) {
                return@checked TransitionResult.Applied(next(session, target.commandId, TrackingState.NEEDS_REVIEW, open))
            }
            val segments = if (open == null) session.segments else {
                val duration = Math.subtractExact(now.elapsedRealtimeMillis, open.startedAt.elapsedRealtimeMillis)
                if (duration <= 0) return@checked reject(RejectionReason.INVALID_DURATION)
                session.segments + Segment(open.id, session.entryId!!, session.projectId!!, open.startedAt.wallUtcMillis, duration)
            }
            // Проверка суммы нужна уже при pause, до записи переполненного состояния.
            val completed = CompletedEntry(
                session.entryId!!,
                session.projectId!!,
                segments,
                session.confirmedDurationBaselineMillis,
                session.lastConfirmedEndBaselineUtcMillis,
            )
            if (stop) {
                TransitionResult.Applied(TrackingSession(
                    TrackingState.IDLE, Math.incrementExact(session.revision), null, null, null, 0, null, emptyList(),
                    session.processedCommands + target.commandId,
                ), completed)
            } else {
                TransitionResult.Applied(next(session, target.commandId, TrackingState.PAUSED, null, segments))
            }
        }
    }

    private fun guard(session: TrackingSession, target: CommandTarget): TransitionResult? {
        guard(session, target.commandId, target.expectedRevision)?.let { return it }
        if (session.state == TrackingState.IDLE) return reject(RejectionReason.INVALID_STATE)
        if (session.entryId != target.entryId) return reject(RejectionReason.STALE_ENTRY)
        return null
    }

    private fun guard(session: TrackingSession, commandId: CommandId, expectedRevision: Long): TransitionResult? = when {
        commandId in session.processedCommands -> TransitionResult.Duplicate(session)
        session.revision != expectedRevision -> reject(RejectionReason.STALE_REVISION)
        else -> null
    }

    private fun next(
        session: TrackingSession,
        commandId: CommandId,
        state: TrackingState,
        open: OpenSegment?,
        segments: List<Segment> = session.segments,
    ) = TrackingSession(state, Math.incrementExact(session.revision), session.entryId, session.projectId,
        open, session.confirmedDurationBaselineMillis, session.lastConfirmedEndBaselineUtcMillis,
        segments, session.processedCommands + commandId)

    private fun checked(block: () -> TransitionResult): TransitionResult = try {
        block()
    } catch (_: ArithmeticException) {
        reject(RejectionReason.OVERFLOW)
    }

    private fun reject(reason: RejectionReason) = TransitionResult.Rejected(reason)

    private fun sameBoot(start: ClockSnapshot, now: ClockSnapshot): Boolean =
        start.bootCount != null && start.bootCount == now.bootCount
}
