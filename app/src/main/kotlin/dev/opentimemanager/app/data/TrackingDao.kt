package dev.opentimemanager.app.data

import androidx.room.*
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.core.model.*
import dev.opentimemanager.core.time.TrackingReducer
import java.util.UUID

@Dao
abstract class TrackingDao {
    @Query("SELECT * FROM projects ORDER BY createdAtUtcMillis, id") abstract fun projects(): List<ProjectEntity>
    @Query("SELECT * FROM entries ORDER BY startedAtUtcMillis, id") abstract fun entries(): List<EntryEntity>
    @Query("SELECT * FROM segments ORDER BY startedAtUtcMillis, id") abstract fun segments(): List<SegmentEntity>
    @Query("SELECT * FROM segments WHERE entryId = :entryId ORDER BY startedElapsedRealtimeMillis, id") abstract fun entrySegments(entryId: String): List<SegmentEntity>
    @Query("SELECT * FROM tracking_slot WHERE id = 1") abstract fun slot(): TrackingSlotEntity?
    @Query("SELECT * FROM entries WHERE id = :id") abstract fun entry(id: String): EntryEntity?
    @Query("SELECT * FROM projects WHERE id = :id") abstract fun project(id: String): ProjectEntity?
    @Query("SELECT * FROM applied_commands WHERE commandId = :id") abstract fun command(id: String): AppliedCommandEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insertDefault(project: ProjectEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insertSlot(slot: TrackingSlotEntity): Long
    @Update abstract fun updateSlot(slot: TrackingSlotEntity)
    @Insert abstract fun insertEntry(entry: EntryEntity)
    @Update abstract fun updateEntry(entry: EntryEntity)
    @Upsert abstract fun saveSegment(segment: SegmentEntity)
    @Insert abstract fun insertCommand(command: AppliedCommandEntity)
    @Query("DELETE FROM applied_commands WHERE commandId NOT IN (SELECT commandId FROM applied_commands ORDER BY revision DESC LIMIT 1024)") abstract fun pruneCommands()
    @Query("DELETE FROM segments WHERE entryId = :entryId AND durationMillis IS NULL") abstract fun deleteOpenSegment(entryId: String)
    @Query("DELETE FROM segments WHERE entryId = :entryId") abstract fun deleteAllSegments(entryId: String)
    @Query("DELETE FROM entries WHERE id = :entryId") abstract fun deleteEntryById(entryId: String)
    @Query("UPDATE entries SET note = :note WHERE id = :entryId AND completed = 0 AND revision = :revision") abstract fun updateActiveNote(entryId: String, revision: Long, note: String): Int

    @Transaction
    open fun initialize(nowUtcMillis: Long) {
        // Маркер слота создаётся в той же транзакции: удалённый пустой default не воскресает.
        if (slot() == null) {
            insertDefault(ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, "Без проекта", 0xff607d8b.toInt(), nowUtcMillis))
            insertSlot(TrackingSlotEntity())
        }
    }

    private fun restored(): TrackingSession {
        val slot = requireNotNull(slot())
        val entry = slot.entryId?.let { requireNotNull(entry(it)) }
        val rows = entry?.let { entrySegments(it.id) }.orEmpty()
        val open = rows.singleOrNull { it.durationMillis == null }?.let {
            OpenSegment(SegmentId(it.id), ClockSnapshot(it.startedAtUtcMillis, requireNotNull(it.startedElapsedRealtimeMillis), it.bootCount))
        }
        return TrackingSession.restore(TrackingState.valueOf(slot.state), slot.revision, entry?.let { EntryId(it.id) }, entry?.let { ProjectId(it.projectId) }, open,
            rows.filter { it.durationMillis != null }.map { it.closed(entry!!.projectId) })
    }

    @Transaction
    open fun snapshot(clock: ClockSnapshotSource): AppSnapshot {
        val now = clock.now()
        initialize(now.wallUtcMillis)
        recover(now)
        return snapshotWithoutRecovery()
    }

    private fun snapshotWithoutRecovery(): AppSnapshot {
        val projects = projects()
        val entries = entries()
        val projectByEntry = entries.associate { it.id to it.projectId }
        return AppSnapshot(restored(), projects, entries, segments().filter { it.durationMillis != null }.map { it.closed(projectByEntry.getValue(it.entryId)) })
    }

    private fun recover(now: ClockSnapshot) {
        val session = restored()
        val start = session.openSegment?.startedAt ?: return
        if (session.state == TrackingState.RUNNING && (start.bootCount == null || now.bootCount == null || start.bootCount != now.bootCount || now.elapsedRealtimeMillis < start.elapsedRealtimeMillis)) {
            val result = TrackingReducer.recoverAfterBoot(session, CommandTarget(session.entryId!!, session.revision, CommandId(UUID.randomUUID().toString())), now) as TransitionResult.Applied
            persist(result.session, null)
        }
    }

    @Transaction
    open fun execute(command: TrackingCommand, clock: ClockSnapshotSource): TrackingResult {
        // Снимаем опору после writer lock, иначе очередь команд может создать ложный reboot.
        val now = clock.now()
        initialize(now.wallUtcMillis)
        command(command.commandId.value)?.let { return TrackingResult.Duplicate(CommandReceipt(it.revision, it.entryId, TrackingState.valueOf(it.state))) }
        recover(now)
        val session = restored()
        if (command is TrackingCommand.ResolveReview) return resolveReview(session, command)
        val destination = when(command) { is TrackingCommand.Start -> command.projectId; is TrackingCommand.Switch -> command.projectId; else -> null }
        if (destination != null) {
            val project = project(destination.value) ?: return TrackingResult.Rejected(TrackingFailure.MISSING_PROJECT)
            if (project.archivedAtUtcMillis != null) return TrackingResult.Rejected(TrackingFailure.ARCHIVED_PROJECT)
        }
        val result = when(command) {
            is TrackingCommand.Start -> TrackingReducer.start(session, command.projectId, EntryId(UUID.randomUUID().toString()), SegmentId(UUID.randomUUID().toString()), now, command.commandId, command.expectedRevision)
            is TrackingCommand.Pause -> TrackingReducer.pause(session, command.target, now)
            is TrackingCommand.Resume -> TrackingReducer.resume(session, command.target, SegmentId(UUID.randomUUID().toString()), now)
            is TrackingCommand.Stop -> TrackingReducer.stop(session, command.target, now)
            is TrackingCommand.Switch -> TrackingReducer.stop(session, command.target, now)
            is TrackingCommand.ResolveReview -> error("Handled before reducer")
        }
        if (result is TransitionResult.Rejected) return TrackingResult.Rejected(TrackingFailure.valueOf(result.reason.name))
        check(result is TransitionResult.Applied)
        var next = result.session
        if (command is TrackingCommand.Switch && result.completedEntry != null) {
            // Switch — один commit; два проверенных перехода core увеличивают revision дважды.
            val idle = TrackingSession.restore(TrackingState.IDLE, next.revision, null, null, null, emptyList())
            val started = TrackingReducer.start(idle, command.projectId, EntryId(UUID.randomUUID().toString()), SegmentId(UUID.randomUUID().toString()), now, command.commandId, idle.revision)
            if (started is TransitionResult.Rejected) return TrackingResult.Rejected(TrackingFailure.valueOf(started.reason.name))
            check(started is TransitionResult.Applied)
            persist(next, result.completedEntry)
            next = started.session
            persist(next, null)
        } else persist(next, result.completedEntry)
        insertCommand(AppliedCommandEntity(command.commandId.value, next.revision, next.entryId?.value, next.state.name))
        pruneCommands()
        return TrackingResult.Applied(snapshotWithoutRecovery())
    }

    private fun resolveReview(session: TrackingSession, command: TrackingCommand.ResolveReview): TrackingResult {
        if (session.state != TrackingState.NEEDS_REVIEW) return TrackingResult.Rejected(TrackingFailure.INVALID_STATE)
        if (session.entryId != command.target.entryId) return TrackingResult.Rejected(TrackingFailure.STALE_ENTRY)
        if (session.revision != command.target.expectedRevision) return TrackingResult.Rejected(TrackingFailure.STALE_REVISION)
        val nextRevision = try { Math.incrementExact(session.revision) } catch (_: ArithmeticException) {
            return TrackingResult.Rejected(TrackingFailure.OVERFLOW)
        }
        val entryId = requireNotNull(session.entryId).value
        val current = requireNotNull(entry(entryId))
        val open = entrySegments(entryId).singleOrNull { it.durationMillis == null }
            ?: return TrackingResult.Rejected(TrackingFailure.INVALID_STATE)
        when (val resolution = command.resolution) {
            ReviewResolution.CONFIRMED_CHECKPOINT -> deleteOpenSegment(entryId)
            is ReviewResolution.ManualEnd -> {
                val duration = try { Math.subtractExact(resolution.endUtcMillis, open.startedAtUtcMillis) }
                catch (_: ArithmeticException) { return TrackingResult.Rejected(TrackingFailure.INVALID_DURATION) }
                if (duration <= 0) return TrackingResult.Rejected(TrackingFailure.INVALID_DURATION)
                saveSegment(open.copy(durationMillis = duration, endUtcMillis = resolution.endUtcMillis, openSlot = null))
            }
        }
        val closed = entrySegments(entryId).filter { it.durationMillis != null }
        updateSlot(TrackingSlotEntity(state = TrackingState.IDLE.name, entryId = null, revision = nextRevision))
        if (closed.isEmpty()) {
            deleteAllSegments(entryId)
            deleteEntryById(entryId)
        } else {
            updateEntry(current.copy(
                completed = true,
                currentSlot = null,
                revision = nextRevision,
                endedAtUtcMillis = closed.maxOf { requireNotNull(it.endUtcMillis) },
            ))
        }
        insertCommand(AppliedCommandEntity(command.commandId.value, nextRevision, null, TrackingState.IDLE.name))
        pruneCommands()
        return TrackingResult.Applied(snapshotWithoutRecovery())
    }

    private fun persist(session: TrackingSession, completed: CompletedEntry?) {
        val oldSlot = requireNotNull(slot())
        val current = oldSlot.entryId?.let { requireNotNull(entry(it)) }
        if (completed != null) {
            for (segment in completed.segments) saveClosed(segment)
            updateEntry(requireNotNull(current).copy(completed = true, currentSlot = null, revision = session.revision,
                endedAtUtcMillis = completed.segments.maxOfOrNull { it.endUtcMillis }))
        } else if (session.entryId != null) {
            val id = requireNotNull(session.entryId).value
            if (current == null || current.id != id) {
                insertEntry(EntryEntity(id, session.projectId!!.value, "", session.openSegment!!.startedAt.wallUtcMillis, null, EntrySource.TIMER.name, session.revision, false, 1))
            } else updateEntry(current.copy(revision = session.revision))
            for (segment in session.segments) saveClosed(segment)
            session.openSegment?.let { open ->
                saveSegment(SegmentEntity(open.id.value, id, open.startedAt.wallUtcMillis, open.startedAt.elapsedRealtimeMillis, open.startedAt.bootCount, null, null, 1))
            }
        }
        updateSlot(TrackingSlotEntity(state = session.state.name, entryId = session.entryId?.value, revision = session.revision))
    }

    private fun saveClosed(segment: Segment) {
        val old = entrySegments(segment.entryId.value).firstOrNull { it.id == segment.id.value }
        saveSegment(SegmentEntity(segment.id.value, segment.entryId.value, segment.startUtcMillis, old?.startedElapsedRealtimeMillis, old?.bootCount, segment.durationMillis, segment.endUtcMillis))
    }
}

internal fun SegmentEntity.closed(projectId: String) = Segment(SegmentId(id), EntryId(entryId), ProjectId(projectId), startedAtUtcMillis, requireNotNull(durationMillis))

enum class ActiveNoteResult { APPLIED, NOT_ACTIVE, STALE_REVISION, INVALID_INPUT }

class ActiveEntryNoteRepository(private val database: AppDatabase, private val dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
    suspend fun update(entryId: EntryId, expectedRevision: Long, note: String): ActiveNoteResult = kotlinx.coroutines.withContext(dispatcher) {
        val normalized = note.trim()
        if (normalized.codePointCount(0, normalized.length) > 2_000) return@withContext ActiveNoteResult.INVALID_INPUT
        val current = database.trackingDao().entry(entryId.value) ?: return@withContext ActiveNoteResult.NOT_ACTIVE
        if (current.completed) return@withContext ActiveNoteResult.NOT_ACTIVE
        if (current.revision != expectedRevision) return@withContext ActiveNoteResult.STALE_REVISION
        if (database.trackingDao().updateActiveNote(entryId.value, expectedRevision, normalized) == 1) ActiveNoteResult.APPLIED
        else ActiveNoteResult.STALE_REVISION
    }
}
