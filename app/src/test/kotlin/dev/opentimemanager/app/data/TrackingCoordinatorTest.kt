package dev.opentimemanager.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.app.ui.UiMessage
import dev.opentimemanager.app.ui.cancellationAwareUiWrite
import dev.opentimemanager.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class TrackingCoordinatorTest {
    private lateinit var db: AppDatabase
    private var now = ClockSnapshot(1_000, 100, 7)
    private fun coordinator() = TrackingCoordinator(RoomTrackingRepository(db, ClockSnapshotSource { now }, Dispatchers.IO))
    private lateinit var tracker: TrackingCoordinator
    private val defaultProject = ProjectRepository.DEFAULT_PROJECT_ID

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        tracker = coordinator()
    }
    @After fun close() { db.close() }
    private suspend fun snapshot() = tracker.observeSnapshot().first()
    private suspend fun start(id: String = "start") = tracker.execute(TrackingCommand.Start(CommandId(id), defaultProject, 0))
    private fun target(s: AppSnapshot, id: String) = CommandTarget(s.session.entryId!!, s.session.revision, CommandId(id))

    @Test fun defaultProjectIsCreatedExactlyOnce() = runBlocking {
        assertEquals(listOf(defaultProject.value), snapshot().projects.map { it.id })
        assertEquals(1_000L, snapshot().projects.single().createdAtUtcMillis)
        now = ClockSnapshot(2_000, 200, 7)
        assertEquals(1, coordinator().observeSnapshot().first().projects.size)
        assertEquals(1_000L, snapshot().projects.single().createdAtUtcMillis)
    }
    @Test fun defaultProjectIdentifierIsPortableThroughUuidBackupContract() = runBlocking {
        val id = snapshot().projects.single().id
        assertEquals(id, java.util.UUID.fromString(id).toString())
    }
    @Test fun concurrentStartsOnlyCreateOneCurrentEntry() = runBlocking {
        val results = coroutineScope { (1..8).map { async(Dispatchers.Default) { start("start-$it") } }.awaitAll() }
        assertEquals(1, results.count { it is TrackingResult.Applied })
        assertEquals(1, snapshot().entries.size)
        assertEquals(TrackingState.RUNNING, snapshot().session.state)
    }
    @Test fun pauseResumeStopPreservesOnlyMonotonicElapsed() = runBlocking {
        start()
        now = ClockSnapshot(-10_000, 600, 7)
        tracker.execute(TrackingCommand.Pause(target(snapshot(), "pause")))
        assertEquals(500L, snapshot().session.confirmedDurationMillis)
        assertTrue(snapshot().session.segments.isEmpty())
        now = ClockSnapshot(20_000, 900, 7)
        tracker.execute(TrackingCommand.Resume(target(snapshot(), "resume")))
        now = ClockSnapshot(30_000, 1_200, 7)
        tracker.execute(TrackingCommand.Stop(target(snapshot(), "stop")))
        val finished = snapshot()
        assertEquals(TrackingState.IDLE, finished.session.state)
        assertEquals(listOf(500L, 300L), finished.segments.map { it.durationMillis })
        assertTrue(finished.entries.single().completed)
    }
    @Test fun commandIsDeduplicatedAcrossCoordinatorRecreation() = runBlocking {
        start()
        assertTrue(coordinator().execute(TrackingCommand.Start(CommandId("start"), defaultProject, 0)) is TrackingResult.Duplicate)
        assertEquals(1, snapshot().entries.size)
    }
    @Test fun staleEntryAndRevisionAreTypedRejections() = runBlocking {
        start()
        val current = snapshot()
        assertEquals(TrackingResult.Rejected(TrackingFailure.STALE_ENTRY), tracker.execute(TrackingCommand.Pause(CommandTarget(EntryId("old"), current.session.revision, CommandId("old-entry")))))
        assertEquals(TrackingResult.Rejected(TrackingFailure.STALE_REVISION), tracker.execute(TrackingCommand.Stop(CommandTarget(current.session.entryId!!, 0, CommandId("old-rev")))))
        assertEquals(TrackingState.RUNNING, snapshot().session.state)
    }
    @Test fun sameBootRecreationRestoresElapsedAnchorExactly() = runBlocking {
        start()
        now = ClockSnapshot(99_000, 2_100, 7)
        tracker = coordinator()
        assertEquals(100L, snapshot().session.openSegment!!.startedAt.elapsedRealtimeMillis)
        tracker.execute(TrackingCommand.Stop(target(snapshot(), "stop")))
        assertEquals(2_000L, snapshot().segments.single().durationMillis)
    }
    @Test fun changedOrUnknownBootRequiresReviewWithoutInventingTime() = runBlocking {
        start()
        now = ClockSnapshot(50_000, 5, null)
        tracker = coordinator()
        assertEquals(TrackingState.NEEDS_REVIEW, snapshot().session.state)
        assertTrue(snapshot().segments.isEmpty())
        assertEquals(100L, snapshot().session.openSegment!!.startedAt.elapsedRealtimeMillis)
    }
    @Test fun changedKnownBootRequiresReviewAndKeepsClosedCheckpoints() = runBlocking {
        start()
        now = ClockSnapshot(1500, 600, 7)
        tracker.execute(TrackingCommand.Pause(target(snapshot(), "pause")))
        tracker.execute(TrackingCommand.Resume(target(snapshot(), "resume")))
        now = ClockSnapshot(2000, 50, 8)
        tracker = coordinator()
        assertEquals(TrackingState.NEEDS_REVIEW, snapshot().session.state)
        assertEquals(500L, snapshot().session.confirmedDurationMillis)
        assertTrue(snapshot().session.segments.isEmpty())
    }
    @Test fun snapshotFlowPublishesCommittedTransitions() = runBlocking {
        val channel = kotlinx.coroutines.channels.Channel<AppSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { tracker.observeSnapshot().collect { channel.send(it) } }
        try {
            assertEquals(TrackingState.IDLE, withTimeout(5_000) { channel.receive() }.session.state)
            start()
            val running = withTimeout(5_000) {
                var item = channel.receive()
                while (item.session.state != TrackingState.RUNNING) item = channel.receive()
                item
            }
            assertEquals(1, running.entries.size)
            assertNotNull(running.session.openSegment)
        } finally { collector.cancelAndJoin(); channel.close() }
    }
    @Test fun switchCompletesOldEntryAndStartsNewOneAtomically() = runBlocking {
        val projects = ProjectRepository(db, Dispatchers.IO)
        projects.create(ProjectId("second"), "Второй", 0xff123456.toInt(), 1_000)
        start()
        now = ClockSnapshot(1_500, 600, 7)
        assertTrue(tracker.execute(TrackingCommand.Switch(target(snapshot(), "switch"), ProjectId("second"))) is TrackingResult.Applied)
        assertEquals(ProjectId("second"), snapshot().session.projectId)
        assertEquals(1, snapshot().entries.count { it.completed })
        assertEquals(500L, snapshot().segments.single().durationMillis)
    }
    @Test fun archiveRejectsStartAndPreservesHistory() = runBlocking {
        snapshot()
        val project = ProjectId("archivable-project")
        val projects = ProjectRepository(db, Dispatchers.IO)
        assertEquals(ProjectEditResult.APPLIED, projects.create(project, "Archivable", 0, 1_000))
        tracker.execute(TrackingCommand.Start(CommandId("start"), project, 0))
        now = ClockSnapshot(1_500, 600, 7)
        tracker.execute(TrackingCommand.Stop(target(snapshot(), "stop")))
        assertEquals(ProjectEditResult.APPLIED, projects.archive(project, 2_000))
        assertEquals(TrackingResult.Rejected(TrackingFailure.ARCHIVED_PROJECT), tracker.execute(TrackingCommand.Start(CommandId("again"), project, snapshot().session.revision)))
        assertEquals(500L, snapshot().segments.single().durationMillis)
    }
    @Test fun manualEntryCrudAndHalfOpenReportQueryDoNotTouchLiveAnchor() = runBlocking {
        snapshot()
        val history = CompletedEntryRepository(db, Dispatchers.IO)
        val id = EntryId("manual")
        assertEquals(EditResult.Applied, history.save(id, defaultProject, "Заметка", 1000, 500, null))
        assertEquals(1, history.report(1200, 1500).size)
        assertTrue(history.report(1500, 2000).isEmpty())
        assertEquals(EditResult.Applied, history.save(id, defaultProject, "Правка", 2000, 700, 0))
        assertEquals(EditResult.StaleRevision, history.delete(id, 0))
        assertEquals(EditResult.Applied, history.delete(id, 1))
        start()
        val active = snapshot()
        assertEquals(EditResult.ActiveEntry, history.save(active.session.entryId!!, defaultProject, "", 0, 100, 0))
        assertEquals(active.session.openSegment, snapshot().session.openSegment)
    }

    @Test fun uiWriteBoundaryMapsEveryFaultingRepositoryGroupToActionFailed() = runBlocking {
        val notes = ActiveEntryNoteRepository(db, Dispatchers.IO)
        val projects = ProjectRepository(db, Dispatchers.IO)
        val entries = CompletedEntryRepository(db, Dispatchers.IO)
        db.close()

        val actions = listOf<suspend () -> Unit>(
            { notes.update(EntryId("entry"), 0, "note") },
            { projects.create(ProjectId("project"), "Project", 0, 0) },
            { projects.archive(ProjectId("project"), 0) },
            { entries.save(EntryId("entry"), ProjectId("project"), "note", 0, 1, null) },
            { entries.delete(EntryId("entry"), 0) },
        )

        actions.forEach { action ->
            assertEquals(UiMessage.ACTION_FAILED, cancellationAwareUiWrite { action(); UiMessage.SAVED })
        }
    }
    @Test fun completedEntryMetadataUpdatePreservesTrackedTimingAndSegmentTopology() = runBlocking {
        val projects = ProjectRepository(db, Dispatchers.IO)
        assertEquals(ProjectEditResult.APPLIED, projects.create(ProjectId("second"), "Second", 0xff123456.toInt(), 500))
        start("metadata-start")
        now = ClockSnapshot(1_500, 600, 7)
        tracker.execute(TrackingCommand.Pause(target(snapshot(), "metadata-pause")))
        now = ClockSnapshot(2_000, 900, 7)
        tracker.execute(TrackingCommand.Resume(target(snapshot(), "metadata-resume")))
        now = ClockSnapshot(3_000, 1_200, 7)
        tracker.execute(TrackingCommand.Stop(target(snapshot(), "metadata-stop")))
        val before = snapshot().entries.single()
        val segmentsBefore = withContext(Dispatchers.IO) { db.trackingDao().segments() }
        assertEquals(EntrySource.TIMER.name, before.source)
        assertEquals(4L, before.revision)
        assertEquals(2, segmentsBefore.size)

        val result = CompletedEntryRepository(db, Dispatchers.IO).updateMetadata(
            EntryId(before.id),
            ProjectId("second"),
            "updated note",
            expectedRevision = 4,
        )

        assertEquals(EditResult.Applied, result)
        val after = snapshot().entries.single()
        assertEquals("second", after.projectId)
        assertEquals("updated note", after.note)
        assertEquals(5L, after.revision)
        assertEquals(before.startedAtUtcMillis, after.startedAtUtcMillis)
        assertEquals(before.endedAtUtcMillis, after.endedAtUtcMillis)
        assertEquals(before.source, after.source)
        assertEquals(before.completed, after.completed)
        assertEquals(segmentsBefore, withContext(Dispatchers.IO) { db.trackingDao().segments() })
    }

    @Test fun completedEntryMetadataUpdateRejectsStaleAndInvalidProjectWithoutMutation() = runBlocking {
        start("validation-start")
        now = ClockSnapshot(1_500, 600, 7)
        tracker.execute(TrackingCommand.Stop(target(snapshot(), "validation-stop")))
        val projects = ProjectRepository(db, Dispatchers.IO)
        assertEquals(ProjectEditResult.APPLIED, projects.create(ProjectId("archived"), "Archived", 0, 2_000))
        assertEquals(ProjectEditResult.APPLIED, projects.archive(ProjectId("archived"), 2_500))
        val history = CompletedEntryRepository(db, Dispatchers.IO)
        val before = snapshot().entries.single()
        val segmentsBefore = withContext(Dispatchers.IO) { db.trackingDao().segments() }

        assertEquals(
            EditResult.StaleRevision,
            history.updateMetadata(EntryId(before.id), ProjectId("missing"), "stale", before.revision - 1),
        )
        assertEquals(
            EditResult.MissingProject,
            history.updateMetadata(EntryId(before.id), ProjectId("missing"), "missing", before.revision),
        )
        assertEquals(
            EditResult.ArchivedProject,
            history.updateMetadata(EntryId(before.id), ProjectId("archived"), "archived", before.revision),
        )

        assertEquals(before, snapshot().entries.single())
        assertEquals(segmentsBefore, withContext(Dispatchers.IO) { db.trackingDao().segments() })
    }
    @Test fun failedSwitchRollsBackClosingOldEntryAndDeduplication() = runBlocking {
        ProjectRepository(db, Dispatchers.IO).create(ProjectId("second"), "Другой", 0, 0)
        start()
        val before = snapshot()
        withContext(Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_new_entry BEFORE INSERT ON entries WHEN NEW.projectId = 'second' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        }
        now = ClockSnapshot(1500, 600, 7)
        try {
            tracker.execute(TrackingCommand.Switch(target(before, "switch"), ProjectId("second")))
            fail("Ожидался отказ записи")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before.session.revision, snapshot().session.revision)
        assertEquals(before.session.openSegment, snapshot().session.openSegment)
        assertTrue(snapshot().segments.isEmpty())
        withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_new_entry") }
        assertTrue(tracker.execute(TrackingCommand.Switch(target(before, "switch"), ProjectId("second"))) is TrackingResult.Applied)
    }
    @Test fun switchRevisionOverflowRejectsWithoutPartialCompletion() = runBlocking {
        start()
        withContext(Dispatchers.IO) {
            val dao = db.trackingDao()
            dao.updateSlot(dao.slot()!!.copy(revision = Long.MAX_VALUE - 1))
        }
        now = ClockSnapshot(1500, 600, 7)
        val before = snapshot()
        assertEquals(TrackingResult.Rejected(TrackingFailure.OVERFLOW), tracker.execute(TrackingCommand.Switch(target(before, "overflow"), defaultProject)))
        assertEquals(before.session.revision, snapshot().session.revision)
        assertFalse(snapshot().entries.single().completed)
        assertTrue(snapshot().segments.isEmpty())
    }
    @Test fun journalPruningKeepsRecentDuplicatesAndRejectsOldReplay() = runBlocking {
        start()
        withContext(Dispatchers.IO) {
            val dao = db.trackingDao()
            for (revision in 2L..1024L) dao.insertCommand(AppliedCommandEntity("prior-$revision", revision, dao.slot()!!.entryId, "RUNNING"))
            dao.updateSlot(dao.slot()!!.copy(revision = 1024))
        }
        now = ClockSnapshot(1500, 600, 7)
        val pause = TrackingCommand.Pause(target(snapshot(), "latest"))
        tracker.execute(pause)
        assertTrue(coordinator().execute(pause) is TrackingResult.Duplicate)
        assertEquals(TrackingResult.Rejected(TrackingFailure.STALE_REVISION), start())
        val count = withContext(Dispatchers.IO) { db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM applied_commands").use { it.moveToFirst(); it.getInt(0) } }
        assertEquals(1024, count)
    }
    @Test fun closingAndReopeningDatabaseRestoresPersistedClockAndCommandJournal() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "recreation-${java.util.UUID.randomUUID()}.db"
        try {
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            tracker = coordinator()
            start()
            db.close()
            now = ClockSnapshot(-5000, 900, 7)
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            tracker = coordinator()
            assertTrue(start() is TrackingResult.Duplicate)
            assertEquals(100L, snapshot().session.openSegment!!.startedAt.elapsedRealtimeMillis)
            tracker.execute(TrackingCommand.Stop(target(snapshot(), "stop")))
            assertEquals(800L, snapshot().segments.single().durationMillis)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
    @Test fun projectValidationAndDeletePreserveReferencedHistory() = runBlocking {
        val projects = ProjectRepository(db, Dispatchers.IO)
        assertEquals(ProjectEditResult.INVALID_NAME, projects.create(ProjectId("bad"), " ", 0, 0))
        assertEquals(ProjectEditResult.INVALID_NAME, projects.create(ProjectId("long"), "😀".repeat(81), 0, 0))
        assertEquals(ProjectEditResult.APPLIED, projects.create(ProjectId("empty"), "  Другой  ", 0, 0))
        assertEquals("Другой", snapshot().projects.single { it.id == "empty" }.name)
        assertEquals(ProjectEditResult.APPLIED, projects.update(ProjectId("empty"), "Новое", 1))
        assertEquals(ProjectEditResult.APPLIED, projects.delete(ProjectId("empty"), 100))
        assertFalse(snapshot().projects.any { it.id == "empty" })
        start()
        assertEquals(ProjectEditResult.APPLIED, projects.delete(defaultProject, 100))
        assertEquals(100L, snapshot().projects.single().archivedAtUtcMillis)
        assertEquals(1, snapshot().entries.size)
    }
    @Test fun clockSamplingIsSerializedWithDatabaseTransitions() = runBlocking {
        val clock = ClockSnapshotSource {
            // Опора, снятая до ожидания writer lock, может оказаться старше нового Start.
            check(db.inTransaction()) { "Clock sampled before transaction" }
            now
        }
        tracker = TrackingCoordinator(RoomTrackingRepository(db, clock, Dispatchers.IO))
        assertEquals(TrackingState.IDLE, snapshot().session.state)
        assertTrue(start() is TrackingResult.Applied)
    }
    @Test fun reportIncludesConfirmedCheckpointsOfCurrentEntry() = runBlocking {
        start()
        now = ClockSnapshot(1500, 600, 7)
        tracker.execute(TrackingCommand.Pause(target(snapshot(), "pause")))
        val report = CompletedEntryRepository(db, Dispatchers.IO).report(1000, 2000)
        assertEquals(500L, report.single().durationMillis)
        assertFalse(snapshot().entries.single().completed)
    }

    @Test fun activeTimerNoteIsValidatedAndPersistedWithoutChangingClockAnchor() = runBlocking {
        start()
        val running = snapshot()
        val notes = ActiveEntryNoteRepository(db, Dispatchers.IO)
        assertEquals(ActiveNoteResult.APPLIED, notes.update(running.session.entryId!!, running.session.revision, "  Focus  "))
        assertEquals("Focus", snapshot().entries.single().note)
        assertEquals(running.session.openSegment, snapshot().session.openSegment)
        assertEquals(ActiveNoteResult.INVALID_INPUT, notes.update(running.session.entryId!!, running.session.revision, "x".repeat(2_001)))
        assertEquals(ActiveNoteResult.STALE_REVISION, notes.update(running.session.entryId!!, running.session.revision - 1, "old"))
    }

    @Test fun reviewCanDiscardUnknownTailOrAcceptExplicitWallClockEnd() = runBlocking {
        start()
        now = ClockSnapshot(2_000, 600, 7)
        tracker.execute(TrackingCommand.Pause(target(snapshot(), "pause")))
        tracker.execute(TrackingCommand.Resume(target(snapshot(), "resume")))
        now = ClockSnapshot(4_000, 50, 8)
        val reviewWithCheckpoint = snapshot()
        assertEquals(TrackingState.NEEDS_REVIEW, reviewWithCheckpoint.session.state)
        val discard = TrackingCommand.ResolveReview(
            target(reviewWithCheckpoint, "discard"),
            ReviewResolution.CONFIRMED_CHECKPOINT,
        )
        assertTrue(tracker.execute(discard) is TrackingResult.Applied)
        assertEquals(TrackingState.IDLE, snapshot().session.state)
        assertEquals(500L, snapshot().segments.single().durationMillis)

        now = ClockSnapshot(10_000, 100, 8)
        val initial = snapshot()
        tracker.execute(TrackingCommand.Start(CommandId("second-start"), defaultProject, initial.session.revision))
        now = ClockSnapshot(70_000, 10, 9)
        val reviewWithoutCheckpoint = snapshot()
        val manual = TrackingCommand.ResolveReview(
            target(reviewWithoutCheckpoint, "manual"),
            ReviewResolution.ManualEnd(40_000),
        )
        assertTrue(tracker.execute(manual) is TrackingResult.Applied)
        val resolved = snapshot()
        assertEquals(TrackingState.IDLE, resolved.session.state)
        assertEquals(30_000L, resolved.segments.last().durationMillis)
    }
}
