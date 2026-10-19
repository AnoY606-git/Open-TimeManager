package dev.opentimemanager.app.data

import androidx.room.*
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.core.model.*
import dev.opentimemanager.core.time.TrackingReducer
import java.time.ZoneId
import java.util.UUID

@Dao
abstract class TrackingDao {
    @Query("SELECT * FROM projects ORDER BY createdAtUtcMillis, id") abstract fun projects(): List<ProjectEntity>
    @Query("SELECT * FROM entries ORDER BY startedAtUtcMillis, id") abstract fun entries(): List<EntryEntity>
    @Query("SELECT * FROM segments ORDER BY startedAtUtcMillis, id") abstract fun segments(): List<SegmentEntity>
    @Query("SELECT * FROM projects WHERE archivedAtUtcMillis IS NULL ORDER BY createdAtUtcMillis, id LIMIT 2") abstract fun eligibleProjects(): List<ProjectEntity>
    @Query("""SELECT * FROM projects
        WHERE id = :activeProjectId OR id = :pinnedProjectId OR id IN (
            SELECT projectId FROM entries WHERE completed = 1
            ORDER BY startedAtUtcMillis DESC, id DESC LIMIT :historyLimit
        ) OR id IN (
            SELECT id FROM projects WHERE archivedAtUtcMillis IS NULL
            ORDER BY createdAtUtcMillis DESC, id DESC LIMIT :projectLimit
        )
        ORDER BY createdAtUtcMillis, id""")
    abstract fun uiProjects(
        historyLimit: Int,
        projectLimit: Int,
        activeProjectId: String?,
        pinnedProjectId: String,
    ): List<ProjectEntity>
    @Query("SELECT * FROM entries WHERE completed = 1 ORDER BY startedAtUtcMillis DESC, id DESC LIMIT :limit") abstract fun recentCompletedEntries(limit: Int): List<EntryEntity>
    @Query("SELECT COUNT(*) FROM entries WHERE completed = 1") abstract fun completedEntryCount(): Long
    @Query("""SELECT entryId, SUM(durationMillis) AS durationMillis FROM segments
        WHERE durationMillis IS NOT NULL AND entryId IN (
            SELECT id FROM entries WHERE completed = 1
            ORDER BY startedAtUtcMillis DESC, id DESC LIMIT :historyLimit
        )
        GROUP BY entryId""")
    abstract fun entryDurations(historyLimit: Int): List<EntryDurationRow>
    @Query("""SELECT * FROM segments
        WHERE durationMillis IS NOT NULL AND entryId IN (
            SELECT id FROM entries WHERE completed = 1
            ORDER BY startedAtUtcMillis DESC, id DESC LIMIT :historyLimit
        )
        ORDER BY startedAtUtcMillis, id LIMIT :segmentLimit""")
    abstract fun recentCompletedSegments(historyLimit: Int, segmentLimit: Int): List<SegmentEntity>
    @Query("SELECT * FROM segments WHERE entryId = :entryId ORDER BY startedElapsedRealtimeMillis, id") abstract fun entrySegments(entryId: String): List<SegmentEntity>
    @Query("SELECT * FROM segments WHERE id = :id") abstract fun segment(id: String): SegmentEntity?
    @Query("SELECT * FROM segments WHERE openSlot = 1 AND entryId = :entryId LIMIT 2")
    abstract fun openSegments(entryId: String): List<SegmentEntity>
    @Query("SELECT * FROM tracking_slot WHERE id = 1") abstract fun slot(): TrackingSlotEntity?
    @Query("SELECT * FROM entries WHERE id = :id") abstract fun entry(id: String): EntryEntity?
    @Query("SELECT * FROM projects WHERE id = :id") abstract fun project(id: String): ProjectEntity?
    @Query("SELECT * FROM applied_commands WHERE commandId = :id") abstract fun command(id: String): AppliedCommandEntity?
    @Query("SELECT COUNT(*) FROM projects") abstract fun projectCount(): Long
    @Query("SELECT COUNT(*) FROM projects WHERE archivedAtUtcMillis IS NULL") abstract fun activeProjectCount(): Long
    @Query("SELECT COUNT(*) FROM entries") abstract fun entryCount(): Long
    @Query("SELECT COUNT(*) FROM segments WHERE durationMillis IS NOT NULL") abstract fun closedSegmentCount(): Long
    @Query("SELECT COALESCE(SUM(LENGTH(CAST(name AS BLOB))), 0) FROM projects") abstract fun projectTextBytes(): Long
    @Query("SELECT COALESCE(SUM(LENGTH(CAST(note AS BLOB))), 0) FROM entries") abstract fun entryTextBytes(): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insertDefault(project: ProjectEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract fun insertSlot(slot: TrackingSlotEntity): Long
    @Update abstract fun updateSlot(slot: TrackingSlotEntity)
    @Insert abstract fun insertEntry(entry: EntryEntity)
    @Update abstract fun updateEntry(entry: EntryEntity)
    @Upsert abstract fun saveSegment(segment: SegmentEntity)
    @Insert abstract fun insertSegment(segment: SegmentEntity)
    @Query("""UPDATE segments SET durationMillis = :durationMillis, endUtcMillis = :endUtcMillis, openSlot = NULL
        WHERE id = :segmentId AND entryId = :entryId AND durationMillis IS NULL""")
    abstract fun closeOpenSegment(segmentId: String, entryId: String, durationMillis: Long, endUtcMillis: Long): Int
    @Insert abstract fun insertCommand(command: AppliedCommandEntity)
    @Query("DELETE FROM applied_commands WHERE commandId NOT IN (SELECT commandId FROM applied_commands ORDER BY revision DESC LIMIT 1024)") abstract fun pruneCommands()
    @Query("DELETE FROM applied_commands") abstract fun clearCommands()
    @Query("DELETE FROM segments WHERE openSlot = 1 AND entryId = :entryId") abstract fun deleteOpenSegment(entryId: String)
    @Query("DELETE FROM segments WHERE entryId = :entryId") abstract fun deleteAllSegments(entryId: String)
    @Query("DELETE FROM entries WHERE id = :entryId") abstract fun deleteEntryById(entryId: String)
    @Query("UPDATE entries SET note = :note WHERE id = :entryId AND completed = 0 AND revision = :revision") abstract fun updateActiveNote(entryId: String, revision: Long, note: String): Int

    @Transaction
    open fun initialize(nowUtcMillis: Long) {
        // Маркер слота создаётся в той же транзакции: удалённый пустой default не воскресает.
        if (slot() == null) {
            insertDefault(ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, ProjectRepository.DEFAULT_PROJECT_STORAGE_NAME, 0xff607d8b.toInt(), nowUtcMillis))
            insertSlot(TrackingSlotEntity())
        }
    }

    private fun restored(
        slot: TrackingSlotEntity = requireNotNull(slot()),
        entry: EntryEntity? = slot.entryId?.let { requireNotNull(entry(it)) },
    ): TrackingSession {
        val openRows = entry?.let { openSegments(it.id) }.orEmpty()
        check(openRows.size <= 1) { "Multiple open segments" }
        val open = openRows.singleOrNull()?.let {
            OpenSegment(SegmentId(it.id), ClockSnapshot(it.startedAtUtcMillis, requireNotNull(it.startedElapsedRealtimeMillis), it.bootCount))
        }
        return TrackingSession.restore(
            TrackingState.valueOf(slot.state),
            slot.revision,
            entry?.let { EntryId(it.id) },
            entry?.let { ProjectId(it.projectId) },
            open,
            emptyList(),
            confirmedDurationMillis = entry?.confirmedDurationMillis ?: 0,
            lastConfirmedEndUtcMillis = entry?.lastConfirmedEndUtcMillis,
        )
    }

    @Transaction
    open fun snapshot(
        clock: ClockSnapshotSource,
        historyLimit: Int,
        projectLimit: Int,
        pinnedProjectId: String,
    ): AppSnapshot {
        val now = clock.now()
        initialize(now.wallUtcMillis)
        recover(now)
        return uiSnapshotWithoutRecovery(historyLimit, projectLimit, pinnedProjectId)
    }

    @Transaction
    open fun systemSnapshot(clock: ClockSnapshotSource): SystemSnapshot {
        val now = clock.now()
        initialize(now.wallUtcMillis)
        recover(now)
        return systemSnapshotWithoutRecovery()
    }

    private fun systemSnapshotWithoutRecovery(): SystemSnapshot {
        val currentSlot = requireNotNull(slot())
        val currentEntry = currentSlot.entryId?.let { requireNotNull(entry(it)) }
        val session = restored(currentSlot, currentEntry)
        val eligible = eligibleProjects()
        return SystemSnapshot(
            session = session,
            generation = currentSlot.generation,
            activeProject = session.projectId?.let { project(it.value) },
            quickStartProject = eligible.singleOrNull(),
            entryStartedAtUtcMillis = currentEntry?.startedAtUtcMillis,
        )
    }

    private fun uiSnapshotWithoutRecovery(
        historyLimit: Int,
        projectLimit: Int,
        pinnedProjectId: String,
    ): AppSnapshot {
        require(historyLimit in 1..20_000)
        require(projectLimit in 1..20_000)
        val session = restored()
        val completed = recentCompletedEntries(historyLimit)
        val active = session.entryId?.value?.let(::entry)?.takeUnless(EntryEntity::completed)
        val entries = buildList {
            active?.let(::add)
            addAll(completed)
        }
        val projectByEntry = entries.associate { it.id to it.projectId }
        val projects = uiProjects(historyLimit, projectLimit, session.projectId?.value, pinnedProjectId)
        val durations = if (completed.isEmpty()) emptyMap() else {
            entryDurations(historyLimit).associate { it.entryId to it.durationMillis }
        }
        val completedSegments = if (completed.isEmpty()) emptyList() else {
            recentCompletedSegments(historyLimit, MAX_UI_SEGMENT_ROWS)
                .map { it.closed(projectByEntry.getValue(it.entryId)) }
        }
        return AppSnapshot(
            session = session,
            projects = projects,
            entries = entries,
            segments = completedSegments,
            generation = requireNotNull(slot()).generation,
            hasMoreHistory = completedEntryCount() > completed.size,
            entryDurations = durations,
            hasMoreProjects = projectLimit < ProjectRepository.MAX_PROJECTS && activeProjectCount() > projectLimit,
        )
    }

    private fun recover(now: ClockSnapshot) {
        val session = restored()
        val start = session.openSegment?.startedAt ?: return
        if (session.state == TrackingState.RUNNING && (start.bootCount == null || now.bootCount == null || start.bootCount != now.bootCount || now.elapsedRealtimeMillis < start.elapsedRealtimeMillis)) {
            when (val result = TrackingReducer.recoverAfterBoot(session, CommandTarget(session.entryId!!, session.revision, CommandId(UUID.randomUUID().toString())), now)) {
                is TransitionResult.Applied -> persist(result.session, null)
                is TransitionResult.Rejected -> {
                    check(result.reason == RejectionReason.OVERFLOW)
                    rebaseOverflowedSession(session)
                }
                is TransitionResult.Duplicate -> error("Recovery command cannot be a duplicate")
            }
        }
    }

    private fun rebaseOverflowedSession(session: TrackingSession) {
        val entryId = requireNotNull(session.entryId).value
        val current = requireNotNull(entry(entryId))
        clearCommands()
        updateEntry(current.copy(revision = 0L))
        updateSlot(
            TrackingSlotEntity(
                state = TrackingState.NEEDS_REVIEW.name,
                entryId = entryId,
                revision = 0L,
                generation = UUID.randomUUID().toString(),
            ),
        )
    }

    @Transaction
    open fun execute(command: TrackingCommand, clock: ClockSnapshotSource): TrackingResult {
        // Снимаем опору после writer lock, иначе очередь команд может создать ложный reboot.
        val now = clock.now()
        initialize(now.wallUtcMillis)
        recover(now)
        if (command.expectedGeneration != requireNotNull(slot()).generation) {
            return TrackingResult.Rejected(TrackingFailure.STALE_GENERATION)
        }
        command(command.commandId.value)?.let { return TrackingResult.Duplicate(CommandReceipt(it.revision, it.entryId, TrackingState.valueOf(it.state))) }
        val session = restored()
        if (command is TrackingCommand.ResolveReview) return resolveReview(session, command)
        val destination = when(command) { is TrackingCommand.Start -> command.projectId; is TrackingCommand.Switch -> command.projectId; else -> null }
        if (destination != null) {
            val project = project(destination.value) ?: return TrackingResult.Rejected(TrackingFailure.MISSING_PROJECT)
            if (project.archivedAtUtcMillis != null) return TrackingResult.Rejected(TrackingFailure.ARCHIVED_PROJECT)
        }
        val result = when(command) {
            is TrackingCommand.Start -> TrackingReducer.start(session, command.projectId, EntryId(UUID.randomUUID().toString()), freshSegmentId(), now, command.commandId, command.expectedRevision)
            is TrackingCommand.Pause -> TrackingReducer.pause(session, command.target, now)
            is TrackingCommand.Resume -> TrackingReducer.resume(session, command.target, freshSegmentId(), now)
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
            val started = TrackingReducer.start(idle, command.projectId, EntryId(UUID.randomUUID().toString()), freshSegmentId(), now, command.commandId, idle.revision)
            if (started is TransitionResult.Rejected) return TrackingResult.Rejected(TrackingFailure.valueOf(started.reason.name))
            check(started is TransitionResult.Applied)
            persist(next, result.completedEntry)
            next = started.session
            persist(next, null)
        } else persist(next, result.completedEntry)
        insertCommand(AppliedCommandEntity(command.commandId.value, next.revision, next.entryId?.value, next.state.name))
        pruneCommands()
        return TrackingResult.Applied(systemSnapshotWithoutRecovery())
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
        val open = openSegments(entryId).singleOrNull()
            ?: return TrackingResult.Rejected(TrackingFailure.INVALID_STATE)
        var confirmedDuration = current.confirmedDurationMillis
        var lastConfirmedEnd = current.lastConfirmedEndUtcMillis
        when (val resolution = command.resolution) {
            ReviewResolution.CONFIRMED_CHECKPOINT -> deleteOpenSegment(entryId)
            is ReviewResolution.ManualEnd -> {
                val duration = try { Math.subtractExact(resolution.endUtcMillis, open.startedAtUtcMillis) }
                catch (_: ArithmeticException) { return TrackingResult.Rejected(TrackingFailure.INVALID_DURATION) }
                if (duration <= 0) return TrackingResult.Rejected(TrackingFailure.INVALID_DURATION)
                confirmedDuration = try { Math.addExact(confirmedDuration, duration) }
                catch (_: ArithmeticException) { return TrackingResult.Rejected(TrackingFailure.OVERFLOW) }
                lastConfirmedEnd = maxOf(lastConfirmedEnd ?: resolution.endUtcMillis, resolution.endUtcMillis)
                check(closeOpenSegment(open.id, entryId, duration, resolution.endUtcMillis) == 1)
            }
        }
        updateSlot(requireNotNull(slot()).copy(state = TrackingState.IDLE.name, entryId = null, revision = nextRevision))
        if (confirmedDuration == 0L) {
            deleteEntryById(entryId)
        } else {
            updateEntry(current.copy(
                completed = true,
                currentSlot = null,
                revision = nextRevision,
                endedAtUtcMillis = requireNotNull(lastConfirmedEnd),
                confirmedDurationMillis = confirmedDuration,
                lastConfirmedEndUtcMillis = lastConfirmedEnd,
            ))
        }
        insertCommand(AppliedCommandEntity(command.commandId.value, nextRevision, null, TrackingState.IDLE.name))
        pruneCommands()
        return TrackingResult.Applied(systemSnapshotWithoutRecovery())
    }

    private fun persist(session: TrackingSession, completed: CompletedEntry?) {
        val oldSlot = requireNotNull(slot())
        val current = oldSlot.entryId?.let { requireNotNull(entry(it)) }
        if (completed != null) {
            for (segment in completed.segments) persistClosedDelta(segment)
            updateEntry(requireNotNull(current).copy(
                completed = true,
                currentSlot = null,
                revision = session.revision,
                endedAtUtcMillis = completed.endedAtUtcMillis,
                confirmedDurationMillis = completed.durationMillis,
                lastConfirmedEndUtcMillis = completed.endedAtUtcMillis,
            ))
        } else if (session.entryId != null) {
            val id = requireNotNull(session.entryId).value
            if (current == null || current.id != id) {
                insertEntry(EntryEntity(
                    id,
                    session.projectId!!.value,
                    "",
                    session.openSegment!!.startedAt.wallUtcMillis,
                    null,
                    EntrySource.TIMER.name,
                    session.revision,
                    false,
                    1,
                    ZoneId.systemDefault().id,
                    session.confirmedDurationMillis,
                    session.lastConfirmedEndUtcMillis,
                ))
            }
            for (segment in session.segments) persistClosedDelta(segment)
            if (current != null && current.id == id) {
                updateEntry(current.copy(
                    revision = session.revision,
                    confirmedDurationMillis = session.confirmedDurationMillis,
                    lastConfirmedEndUtcMillis = session.lastConfirmedEndUtcMillis,
                ))
            }
            session.openSegment?.let { open ->
                val persisted = segment(open.id.value)
                if (persisted == null) {
                    insertSegment(SegmentEntity(open.id.value, id, open.startedAt.wallUtcMillis, open.startedAt.elapsedRealtimeMillis, open.startedAt.bootCount, null, null, 1))
                } else {
                    check(persisted.entryId == id && persisted.durationMillis == null)
                }
            }
        }
        updateSlot(oldSlot.copy(state = session.state.name, entryId = session.entryId?.value, revision = session.revision))
    }

    private fun persistClosedDelta(segment: Segment) {
        check(
            closeOpenSegment(
                segment.id.value,
                segment.entryId.value,
                segment.durationMillis,
                segment.endUtcMillis,
            ) == 1,
        ) { "Open segment delta is missing" }
    }

    private fun freshSegmentId(): SegmentId {
        repeat(8) {
            val candidate = SegmentId(UUID.randomUUID().toString())
            if (segment(candidate.value) == null) return candidate
        }
        error("Unable to allocate a unique segment id")
    }
}

data class EntryDurationRow(
    val entryId: String,
    val durationMillis: Long,
)

internal const val MAX_UI_SEGMENT_ROWS = 2_000

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
