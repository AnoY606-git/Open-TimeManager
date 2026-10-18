package dev.opentimemanager.app.backup

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.CompletedEntryRepository
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.dataexport.CsvExportCoordinator
import dev.opentimemanager.app.settings.PrivacyMirror
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserPreferences
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.core.backup.BackupFormat
import dev.opentimemanager.core.backup.PlainBackupCodec
import dev.opentimemanager.core.backup.PortableActiveEntry
import dev.opentimemanager.core.backup.PortableEntry
import dev.opentimemanager.core.backup.PortablePreferences
import dev.opentimemanager.core.backup.PortableProject
import dev.opentimemanager.core.backup.PortableSnapshot
import dev.opentimemanager.core.backup.PortableTheme
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.EntrySource
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingState
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class PlainBackupRestoreTest {
    private lateinit var database: AppDatabase
    private lateinit var documents: PlainBackupTestDocuments
    private lateinit var restore: RestoreRepository
    private val uri = Uri.parse("content://tests/restore.otmplain")
    private val project = ProjectId("50000000-0000-4000-8000-000000000001")
    private val entry = EntryId("60000000-0000-4000-8000-000000000001")
    private val segment = SegmentId("70000000-0000-4000-8000-000000000001")

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        documents = PlainBackupTestDocuments()
        restore = RestoreRepository(database, Dispatchers.IO)
        database.trackingDao().initialize(100)
        CompletedEntryRepository(database, Dispatchers.IO).save(
            EntryId("80000000-0000-4000-8000-000000000001"), ProjectRepository.DEFAULT_PROJECT_ID,
            "Keep local rows", 1_000, 500, null,
        )
        Unit
    }

    @After fun tearDown() { database.close() }

    @Test fun plaintextMergeRechecksConflictsAndRetriesTheSameInspectedPayloadWithoutRereadingTheFile() = runBlocking {
        database.projectDao().insert(ProjectEntity(project.value, "Local", 7, 10, 900))
        val preview = inspect(source())
        val conflicts = listOf(BackupConflict(BackupConflictKind.PROJECT, project.value))
        assertEquals(BackupFormat.PLAINTEXT, preview.format)
        assertEquals(conflicts, preview.conflicts)
        assertEquals(RestoreResult.Conflicts(conflicts), restore.restore(preview, RestoreMode.MERGE))
        assertTrue(preview.hasPayload)
        assertNull(database.trackingDao().entry(entry.value))

        // Changing the provider cannot alter the already-inspected transaction payload.
        documents.bytes[uri] = PlainBackupCodec.encode(PortableSnapshot())
        database.projectDao().update(project.value, "Imported", 7)
        assertEquals(RestoreResult.Applied(0, 1, 1), restore.restore(preview, RestoreMode.MERGE))
        assertEquals("Incoming note", database.trackingDao().entry(entry.value)?.note)
        assertEquals(900L, database.trackingDao().project(project.value)?.archivedAtUtcMillis)
        assertFalse(preview.hasPayload)
        assertEquals(RestoreResult.Applied(0, 1, 1), restore.restore(preview, RestoreMode.MERGE))
        val reread = inspect(source())
        assertEquals(RestoreResult.Applied(0, 0, 0), restore.restore(reread, RestoreMode.MERGE))
        assertEquals(1, database.trackingDao().entrySegments(entry.value).size)
    }

    @Test fun plaintextReplaceRollsBackAllRoomChangesOnSqliteFailureAndKeepsPreviewForRetry() = runBlocking {
        val dao = database.trackingDao()
        val beforeProjects = dao.projects()
        val beforeEntries = dao.entries()
        val beforeSegments = dao.segments()
        val beforeSlot = dao.slot()
        val preview = inspect(source())
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_plain_restore BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT, 'plain restore failure'); END",
        )

        try {
            restore.restore(preview, RestoreMode.REPLACE)
            throw AssertionError("SQLite failure expected")
        } catch (_: android.database.sqlite.SQLiteException) {
            Unit
        }
        assertEquals(beforeProjects, dao.projects())
        assertEquals(beforeEntries, dao.entries())
        assertEquals(beforeSegments, dao.segments())
        assertEquals(beforeSlot, dao.slot())
        assertNull(database.restoreDao().pendingPreferences())
        assertTrue(preview.hasPayload)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_plain_restore")

        assertEquals(RestoreResult.Applied(1, 1, 1), restore.restore(preview, RestoreMode.REPLACE))
        assertEquals(listOf(project.value), dao.projects().map { it.id })
        assertEquals(listOf(entry.value), dao.entries().map { it.id })
        assertEquals(TrackingState.IDLE.name, dao.slot()?.state)
        assertFalse(preview.hasPayload)
    }

    @Test fun cancelledPlaintextReplaceRollsBackDeletesAndSessionKeepsThePreviewRetryable() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancellable = RestoreRepository(database, Dispatchers.IO, afterReplaceDelete = {
            reached.complete(Unit)
            release.await()
        })
        val backup = coordinator(cancellable)
        val transfers = DataTransferSession(
            CsvExportCoordinator(database, documents, Dispatchers.IO, ZoneId.of("UTC")), backup,
        )
        val beforeProjects = database.trackingDao().projects()
        val beforeEntries = database.trackingDao().entries()
        val preview = inspect(source(), cancellable)
        transfers.retainPreview(preview)
        val operation = async { cancellable.restore(preview, RestoreMode.REPLACE) }
        reached.await()
        operation.cancelAndJoin()

        assertEquals(beforeProjects, database.trackingDao().projects())
        assertEquals(beforeEntries, database.trackingDao().entries())
        assertTrue(preview.hasPayload)
        assertSame(preview, transfers.currentPreview())
        assertEquals(BackupFormat.PLAINTEXT, preview.format)
        release.complete(Unit)
        val result = cancellable.restore(preview, RestoreMode.REPLACE)
        assertEquals(RestoreResult.Applied(1, 1, 1), result)
        transfers.onRestoreResult(preview, result)
        assertNull(transfers.currentPreview())
        assertFalse(preview.hasPayload)
    }

    @Test fun plaintextRestoreCannotReplaceOrMergeOverAnyNonIdleTimerAndRetainsPreviewForRetry() = runBlocking {
        val dao = database.trackingDao()
        val beforeProjects = dao.projects()
        val localEntry = dao.entries().single()
        val idle = requireNotNull(dao.slot())
        val preview = inspect(source())
        for (state in listOf(TrackingState.RUNNING, TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
            dao.updateSlot(idle.copy(state = state.name, entryId = localEntry.id))
            for (mode in RestoreMode.entries) {
                assertEquals(RestoreResult.ActiveTimerBlocked(state), restore.restore(preview, mode))
                assertEquals(beforeProjects, dao.projects())
                assertTrue(preview.hasPayload)
            }
        }
        dao.updateSlot(idle)
        assertEquals(RestoreResult.Applied(1, 1, 1), restore.restore(preview, RestoreMode.REPLACE))
        assertFalse(preview.hasPayload)
    }

    @Test fun plaintextImportedActiveEntryNeverResumesAndUnconfirmedEntriesAreReportedThenOmitted() = runBlocking {
        val source = source()
        val unconfirmed = PortableEntry(
            EntryId("61000000-0000-4000-8000-000000000001"), project, "No confirmed time", ZoneId.of("UTC"),
        )
        val withActive = PortableSnapshot(
            source.projects, source.entries + unconfirmed, source.segments,
            PortableActiveEntry(unconfirmed.id, project, TrackingState.RUNNING),
        )
        val preview = inspect(withActive)
        assertEquals(BackupCounts(1, 2, 1), preview.counts)
        assertEquals(1, preview.skippedEntriesWithoutConfirmedTime)
        assertTrue(preview.activeStateWillBeStopped)
        assertEquals(RestoreResult.Applied(1, 1, 1), restore.restore(preview, RestoreMode.REPLACE))
        assertNull(database.trackingDao().entry(unconfirmed.id.value))
        assertTrue(requireNotNull(database.trackingDao().entry(entry.value)).completed)
        val slot = requireNotNull(database.trackingDao().slot())
        assertEquals(TrackingState.IDLE.name, slot.state)
        assertNull(slot.entryId)
        assertTrue(database.trackingDao().segments().all { it.durationMillis != null && it.openSlot == null })
    }

    @Test fun plaintextMergeKeepsLocalPreferencesWhileReplaceAppliesPortablePreferencesAfterCommit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val settings = UserSettings(context, "plain-merge-settings-${System.nanoTime()}", privacyMirror = PrivacyMirror { true })
        val initial = UserPreferences(ThemeMode.LIGHT, true, true, 300)
        settings.replace(initial)
        val configured = RestoreRepository(database, Dispatchers.IO, settings = settings)
        val source = source()
        val preferences = PortablePreferences(PortableTheme.DARK, false, false, 615)
        val withPreferences = PortableSnapshot(source.projects, source.entries, source.segments, preferences = preferences)

        assertEquals(RestoreResult.Applied(1, 1, 1), configured.restore(inspect(withPreferences, configured), RestoreMode.MERGE))
        assertEquals(initial, settings.current())
        assertNull(database.restoreDao().pendingPreferences())
        assertEquals(RestoreResult.Applied(1, 1, 1), configured.restore(inspect(withPreferences, configured), RestoreMode.REPLACE))
        assertEquals(UserPreferences(ThemeMode.DARK, false, false, 615), settings.current())
        assertNull(database.restoreDao().pendingPreferences())
    }

    @Test fun discardingPlainPreviewDropsItsPayloadAndInMemoryPreviewHasNoAuthenticatedFormat() = runBlocking {
        val preview = inspect(source())
        preview.discard()
        assertFalse(preview.hasPayload)
        assertThrows(IllegalStateException::class.java) { runBlocking { restore.restore(preview, RestoreMode.REPLACE) } }
        val inMemory = restore.preview(source())
        assertNull(inMemory.format)
        inMemory.discard()
    }

    private fun source() = PortableSnapshot(
        projects = listOf(PortableProject(project, "Imported", 7, 10, 900, false)),
        entries = listOf(PortableEntry(entry, project, "Incoming note", ZoneId.of("Europe/Moscow"), EntrySource.IMPORT)),
        segments = listOf(Segment(segment, entry, project, -123, 1_500)),
    )

    private fun coordinator(repository: RestoreRepository) = BackupFileCoordinator(database, documents, repository, Dispatchers.IO)

    private suspend fun inspect(snapshot: PortableSnapshot, repository: RestoreRepository = restore): BackupPreview {
        documents.bytes[uri] = PlainBackupCodec.encode(snapshot, BackupFileCoordinator.APP_LIMITS)
        return (coordinator(repository).inspectBackup(uri, expectedFormat = BackupFormat.PLAINTEXT) as BackupInspection.Ready).preview
    }
}
