package dev.opentimemanager.app.backup

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.AppliedCommandEntity
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.data.SegmentEntity
import dev.opentimemanager.app.data.TrackingSlotEntity
import dev.opentimemanager.app.data.PendingPreferencesRestoreEntity
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserPreferences
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.app.settings.RestorePreferenceTarget
import dev.opentimemanager.app.settings.SettingsMutationBoundary
import dev.opentimemanager.core.backup.PortableActiveEntry
import dev.opentimemanager.core.backup.PortableEntry
import dev.opentimemanager.core.backup.PortableProject
import dev.opentimemanager.core.backup.PortablePreferences
import dev.opentimemanager.core.backup.PortableSnapshot
import dev.opentimemanager.core.backup.PortableTheme
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.EntrySource
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingState
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class RestoreRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RestoreRepository
    private val projectId = ProjectId("50000000-0000-4000-8000-000000000001")
    private val entryId = EntryId("60000000-0000-4000-8000-000000000001")
    private val segmentId = SegmentId("70000000-0000-4000-8000-000000000001")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RestoreRepository(database, Dispatchers.IO)
        database.trackingDao().initialize(100)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun previewReportsDeterministicUuidConflictsWithoutMutatingRoom() = runBlocking {
        database.projectDao().insert(ProjectEntity(projectId.value, "Local", 1, 1))

        val preview = repository.preview(snapshot(projectName = "Backup"))

        assertEquals(BackupCounts(1, 1, 1), preview.counts)
        assertEquals(listOf(BackupConflict(BackupConflictKind.PROJECT, projectId.value)), preview.conflicts)
        assertEquals("Local", database.trackingDao().project(projectId.value)?.name)
        preview.discard()
    }

    @Test
    fun previewBatchesImportedUuidLookupsAcrossSqliteBindBoundaries() = runBlocking {
        val projects = (1..501).map { index ->
            PortableProject(ProjectId(uuid(0x51000000, index)), "Backup $index")
        }
        val conflicted = projects.last()
        database.projectDao().insert(ProjectEntity(conflicted.id.value, "Local", 1, 1))

        val preview = repository.preview(PortableSnapshot(projects = projects))

        assertEquals(
            listOf(BackupConflict(BackupConflictKind.PROJECT, conflicted.id.value)),
            preview.conflicts,
        )
        preview.discard()
    }

    @Test
    fun mergeIsIdempotentAndKeepsArchiveMetadataAndForeignKeys() = runBlocking {
        database.projectDao().insert(ProjectEntity(projectId.value, "Backup", 0xff112233.toInt(), 9, archivedAtUtcMillis = 10))

        val matching = snapshot(projectColor = 0xff112233.toInt(), projectCreatedAt = 9, projectArchivedAt = 10)
        val first = repository.restore(repository.preview(matching), RestoreMode.MERGE)
        val second = repository.restore(repository.preview(matching), RestoreMode.MERGE)

        assertEquals(RestoreResult.Applied(projects = 0, entries = 1, segments = 1), first)
        assertEquals(RestoreResult.Applied(projects = 0, entries = 0, segments = 0), second)
        assertEquals(1, database.trackingDao().entries().count { it.id == entryId.value })
        assertEquals(1, database.trackingDao().segments().count { it.id == segmentId.value })
        assertEquals(10L, database.trackingDao().project(projectId.value)?.archivedAtUtcMillis)
    }

    @Test
    fun v1MergeComparesOnlyMetadataRepresentedByV1() = runBlocking {
        database.projectDao().insert(ProjectEntity(projectId.value, "Backup", 0xff123456.toInt(), 9_999, archivedAtUtcMillis = 10))
        val legacy = PortableSnapshot(
            projects = listOf(PortableProject(projectId, "Backup")),
            sourceSchemaVersion = 1,
        )
        val preview = repository.preview(legacy)

        assertTrue(preview.conflicts.isEmpty())
        assertEquals(RestoreResult.Applied(0, 0, 0), repository.restore(preview, RestoreMode.MERGE))
        assertEquals(0xff123456.toInt(), database.trackingDao().project(projectId.value)?.colorArgb)
        assertEquals(9_999L, database.trackingDao().project(projectId.value)?.createdAtUtcMillis)
        assertEquals(10L, database.trackingDao().project(projectId.value)?.archivedAtUtcMillis)
    }

    @Test
    fun v2MergeTreatsCanonicalDefaultAsTheSameProjectAcrossInstallations() = runBlocking {
        val localDefault = requireNotNull(
            database.trackingDao().project(ProjectRepository.DEFAULT_PROJECT_ID.value),
        )
        val fromAnotherInstallation = PortableSnapshot(
            projects = listOf(
                PortableProject(
                    id = ProjectRepository.DEFAULT_PROJECT_ID,
                    name = dev.opentimemanager.core.backup.DEFAULT_PROJECT_CANONICAL_NAME,
                    colorArgb = localDefault.colorArgb,
                    createdAtUtcMillis = localDefault.createdAtUtcMillis + 9_999,
                    archivedAtUtcMillis = null,
                    isDefault = true,
                ),
            ),
        )

        val preview = repository.preview(fromAnotherInstallation)

        assertTrue(preview.conflicts.isEmpty())
        assertEquals(RestoreResult.Applied(0, 0, 0), repository.restore(preview, RestoreMode.MERGE))
        assertEquals(
            localDefault.createdAtUtcMillis,
            database.trackingDao().project(ProjectRepository.DEFAULT_PROJECT_ID.value)?.createdAtUtcMillis,
        )
    }

    @Test
    fun mergeRejectsProjectUnionBeyondMobileLimitWithoutMutation() = runBlocking {
        (1 until ProjectRepository.MAX_PROJECTS).chunked(500).forEach { indexes ->
            database.restoreDao().insertProjects(
                indexes.map { index -> ProjectEntity(uuid(0x52000000, index), "Local $index", 1, index.toLong()) },
            )
        }
        assertEquals(ProjectRepository.MAX_PROJECTS.toLong(), database.trackingDao().projectCount())
        val preview = repository.preview(snapshot())

        val result = repository.restore(preview, RestoreMode.MERGE)

        assertEquals(RestoreResult.LimitExceeded(ProjectRepository.MAX_PROJECTS), result)
        assertEquals(ProjectRepository.MAX_PROJECTS.toLong(), database.trackingDao().projectCount())
        assertFalse(database.trackingDao().projects().any { it.id == projectId.value })
        assertTrue(preview.hasPayload)
    }

    @Test
    fun mergeRejectsChangedUuidContentWithoutPartialInsert() = runBlocking {
        database.projectDao().insert(ProjectEntity(projectId.value, "Local", 1, 1))
        val preview = repository.preview(snapshot(projectName = "Backup"))

        val result = repository.restore(preview, RestoreMode.MERGE)

        assertEquals(RestoreResult.Conflicts(listOf(BackupConflict(BackupConflictKind.PROJECT, projectId.value))), result)
        assertFalse(database.trackingDao().entries().any { it.id == entryId.value })
        assertTrue(preview.hasPayload)
    }

    @Test
    fun replaceRollsBackDeletesAndInsertsWhenSqliteFails() = runBlocking {
        val local = ProjectEntity(
            "80000000-0000-4000-8000-000000000001",
            "Local",
            1,
            1,
            archivedAtUtcMillis = 777,
        )
        val localEntry = EntryEntity(
            id = "81000000-0000-4000-8000-000000000001",
            projectId = local.id,
            note = "Keep all rows",
            startedAtUtcMillis = 2_000,
            endedAtUtcMillis = 2_500,
            source = EntrySource.MANUAL.name,
            revision = 8,
            completed = true,
            zoneId = "Europe/Moscow",
        )
        val localSegment = SegmentEntity(
            id = "82000000-0000-4000-8000-000000000001",
            entryId = localEntry.id,
            startedAtUtcMillis = 2_000,
            startedElapsedRealtimeMillis = null,
            bootCount = null,
            durationMillis = 500,
            endUtcMillis = 2_500,
        )
        val localSlot = TrackingSlotEntity(revision = 42)
        val receipt = AppliedCommandEntity("83000000-0000-4000-8000-000000000001", 42, null, TrackingState.IDLE.name)
        database.projectDao().insert(local)
        database.trackingDao().insertEntry(localEntry)
        database.trackingDao().saveSegment(localSegment)
        database.trackingDao().updateSlot(localSlot)
        database.trackingDao().insertCommand(receipt)
        val preview = repository.preview(snapshot())
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_restore BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT, 'test restore failure'); END",
            )
        }

        try {
            repository.restore(preview, RestoreMode.REPLACE)
            throw AssertionError("SQLite failure expected")
        } catch (_: android.database.sqlite.SQLiteException) {
            // Transaction must restore the exact pre-operation database.
        }

        assertEquals(local, database.trackingDao().project(local.id))
        assertEquals(localEntry, database.trackingDao().entry(localEntry.id))
        assertEquals(listOf(localSegment), database.trackingDao().entrySegments(localEntry.id))
        assertEquals(localSlot, database.trackingDao().slot())
        assertEquals(receipt, database.trackingDao().command(receipt.commandId))
        assertTrue(preview.hasPayload)
    }

    @Test
    fun replacePreservesProjectMetadataAndAppliesPreferencesAfterRoomCommit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = UserSettings(context, "restore-settings-${System.nanoTime()}")
        val configured = RestoreRepository(database, Dispatchers.IO, settings = settings)
        val source = PortableSnapshot(
            projects = listOf(
                PortableProject(projectId, "Archived", 0xff123456.toInt(), -123, 9_999, false),
            ),
            preferences = PortablePreferences(PortableTheme.DARK, false, false, 615),
        )

        assertEquals(RestoreResult.Applied(1, 0, 0), configured.restore(configured.preview(source), RestoreMode.REPLACE))

        assertEquals(ProjectEntity(projectId.value, "Archived", 0xff123456.toInt(), -123, 9_999), database.trackingDao().project(projectId.value))
        assertEquals(UserPreferences(ThemeMode.DARK, false, false, 615), settings.current())
    }

    @Test
    fun startupReconciliationAppliesDurablePendingPreferencesAndClearsJournal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = UserSettings(context, "pending-settings-${System.nanoTime()}")
        database.restoreDao().savePendingPreferences(
            PendingPreferencesRestoreEntity(
                operationId = "operation",
                theme = ThemeMode.LIGHT.name,
                dynamicColor = false,
                privacyMode = false,
                dailyGoalMinutes = 300,
            ),
        )
        val configured = RestoreRepository(database, Dispatchers.IO, settings = settings)

        configured.reconcilePendingPreferences()

        assertEquals(UserPreferences(ThemeMode.LIGHT, false, false, 300), settings.current())
        assertEquals(null, database.restoreDao().pendingPreferences())
    }

    @Test
    fun startupReconcileCannotOverwriteNewerReplacePreferencesOrDeleteItsJournal() = runBlocking {
        val enteredA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val boundary = SettingsMutationBoundary()
        val target = RecordingRestoreSettings(boundary) { value ->
            if (value.theme == ThemeMode.LIGHT) {
                enteredA.complete(Unit)
                releaseA.await()
            }
        }
        database.restoreDao().savePendingPreferences(
            PendingPreferencesRestoreEntity(1, "A", ThemeMode.LIGHT.name, false, false, 300),
        )
        val configured = RestoreRepository(
            database,
            Dispatchers.IO,
            settings = target,
        )
        val startup = async { configured.reconcilePendingPreferences() }
        enteredA.await()
        val sourceB = PortableSnapshot(
            projects = listOf(PortableProject(projectId, "B")),
            preferences = PortablePreferences(PortableTheme.DARK, true, true, 600),
        )
        val previewB = configured.preview(sourceB)
        val replaceB = async { configured.restore(previewB, RestoreMode.REPLACE) }

        delay(50)
        assertFalse(replaceB.isCompleted)
        releaseA.complete(Unit)
        startup.await()
        assertTrue(replaceB.await() is RestoreResult.Applied)

        assertEquals("B", database.trackingDao().project(projectId.value)?.name)
        assertEquals(UserPreferences(ThemeMode.DARK, true, true, 600), target.current)
        assertEquals(null, database.restoreDao().pendingPreferences())
    }

    @Test
    fun reconcileCompareDeleteLoopsWhenAWasReplacedByBWhileApplying() = runBlocking {
        val preferencesB = PendingPreferencesRestoreEntity(
            1, "B", ThemeMode.DARK.name, true, false, 700,
        )
        database.restoreDao().savePendingPreferences(
            PendingPreferencesRestoreEntity(1, "A", ThemeMode.LIGHT.name, false, true, 300),
        )
        val target = RecordingRestoreSettings(SettingsMutationBoundary()) { value ->
            if (value.theme == ThemeMode.LIGHT) database.restoreDao().savePendingPreferences(preferencesB)
        }
        val configured = RestoreRepository(
            database,
            Dispatchers.IO,
            settings = target,
        )

        configured.reconcilePendingPreferences()

        assertEquals(listOf(ThemeMode.LIGHT, ThemeMode.DARK), target.applied.map(UserPreferences::theme))
        assertEquals(preferencesB.toExpectedPreferences(), target.current)
        assertEquals(null, database.restoreDao().pendingPreferences())
    }

    @Test
    fun failedPreferenceApplicationRetainsJournalAndCanBeRetried() = runBlocking {
        val pending = PendingPreferencesRestoreEntity(
            1, "retry", ThemeMode.DARK.name, false, false, 720,
        )
        database.restoreDao().savePendingPreferences(pending)
        val boundary = SettingsMutationBoundary()
        val failing = RestoreRepository(
            database,
            Dispatchers.IO,
            settings = RecordingRestoreSettings(boundary) { throw IllegalStateException("DataStore unavailable") },
        )

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            runBlocking { failing.reconcilePendingPreferences() }
        }
        assertEquals(pending, database.restoreDao().pendingPreferences())

        val succeedingTarget = RecordingRestoreSettings(boundary)
        RestoreRepository(
            database,
            Dispatchers.IO,
            settings = succeedingTarget,
        ).reconcilePendingPreferences()
        assertEquals(pending.toExpectedPreferences(), succeedingTarget.current)
        assertEquals(null, database.restoreDao().pendingPreferences())
    }

    @Test
    fun cancellationAfterReplaceDeletesRollsBackEveryRoomTableAndKeepsPreviewRetryable() = runBlocking {
        val local = ProjectEntity("85000000-0000-4000-8000-000000000001", "Local", 7, 77)
        database.projectDao().insert(local)
        val reachedBarrier = CompletableDeferred<Unit>()
        val holdBarrier = CompletableDeferred<Unit>()
        val cancellable = RestoreRepository(
            database,
            Dispatchers.IO,
            afterReplaceDelete = {
                reachedBarrier.complete(Unit)
                holdBarrier.await()
            },
        )
        val preview = cancellable.preview(snapshot())
        val operation = async { cancellable.restore(preview, RestoreMode.REPLACE) }

        reachedBarrier.await()
        operation.cancelAndJoin()

        assertEquals(local, database.trackingDao().project(local.id))
        assertTrue(preview.hasPayload)
    }

    @Test
    fun discardDuringInFlightRestoreIsDeferredAndNeverThrowsFromLifecycleCleanup() = runBlocking {
        val reachedBarrier = CompletableDeferred<Unit>()
        val holdBarrier = CompletableDeferred<Unit>()
        val cancellable = RestoreRepository(
            database,
            Dispatchers.IO,
            afterReplaceDelete = {
                reachedBarrier.complete(Unit)
                holdBarrier.await()
            },
        )
        val preview = cancellable.preview(snapshot())
        val operation = async { cancellable.restore(preview, RestoreMode.REPLACE) }
        reachedBarrier.await()

        preview.discard()
        operation.cancelAndJoin()

        assertFalse(preview.hasPayload)
    }

    @Test
    fun restoreDefenseRejectsPerEntryDurationOverflowWithoutChangingDatabase() = runBlocking {
        val local = database.trackingDao().projects()
        val overflow = PortableSnapshot(
            projects = listOf(PortableProject(projectId, "P")),
            entries = listOf(PortableEntry(entryId, projectId, "", ZoneId.of("UTC"), EntrySource.IMPORT)),
            segments = listOf(
                Segment(segmentId, entryId, projectId, 0, Long.MAX_VALUE),
                Segment(SegmentId("71000000-0000-4000-8000-000000000001"), entryId, projectId, 0, 1),
            ),
        )

        org.junit.Assert.assertThrows(RestoreValidationError::class.java) {
            runBlocking { repository.preview(overflow) }
        }
        assertEquals(local, database.trackingDao().projects())
    }

    @Test
    fun restoreRejectsRunningPausedAndNeedsReviewSessions() = runBlocking {
        for (state in listOf(TrackingState.RUNNING, TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
            val db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            try {
                db.trackingDao().initialize(100)
                db.putActiveFixture(state)
                val result = RestoreRepository(db, Dispatchers.IO).restore(
                    RestoreRepository(db, Dispatchers.IO).preview(snapshot()),
                    RestoreMode.REPLACE,
                )
                assertEquals("state=$state", RestoreResult.ActiveTimerBlocked(state), result)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun activeTimerRejectionKeepsPreviewForRetryAfterTimerStops() = runBlocking {
        database.putActiveFixture(TrackingState.PAUSED)
        val preview = repository.preview(snapshot())

        assertEquals(RestoreResult.ActiveTimerBlocked(TrackingState.PAUSED), repository.restore(preview, RestoreMode.REPLACE))
        assertTrue(preview.hasPayload)

        val slot = database.trackingDao().slot()!!
        database.trackingDao().updateSlot(slot.copy(state = TrackingState.IDLE.name, entryId = null))
        assertTrue(repository.restore(preview, RestoreMode.REPLACE) is RestoreResult.Applied)
        assertFalse(preview.hasPayload)
    }

    @Test
    fun concurrentRestoreAttemptsShareOneAppliedPreviewResult() = runBlocking {
        val preview = repository.preview(snapshot())

        val results = listOf(
            async { repository.restore(preview, RestoreMode.MERGE) },
            async { repository.restore(preview, RestoreMode.MERGE) },
        ).awaitAll()

        assertEquals(listOf(RestoreResult.Applied(1, 1, 1), RestoreResult.Applied(1, 1, 1)), results)
        assertFalse(preview.hasPayload)
    }

    @Test
    fun importedActiveSnapshotNeverBecomesActiveAndReplaceClearsReceipts() = runBlocking {
        val withActive = snapshot(active = PortableActiveEntry(entryId, projectId, TrackingState.NEEDS_REVIEW))
        val previousGeneration = database.trackingDao().slot()!!.generation

        val result = repository.restore(repository.preview(withActive), RestoreMode.REPLACE)

        assertEquals(RestoreResult.Applied(1, 1, 1), result)
        val slot = database.trackingDao().slot()!!
        assertEquals(TrackingState.IDLE.name, slot.state)
        assertEquals(0L, slot.revision)
        assertEquals(null, slot.entryId)
        assertNotEquals(previousGeneration, slot.generation)
        assertTrue(database.trackingDao().entry(entryId.value)!!.completed)
        assertTrue(database.trackingDao().entrySegments(entryId.value).all { it.durationMillis != null })
        assertEquals(0, database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM applied_commands").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
    }

    @Test
    fun activeEntryWithoutConfirmedSegmentsIsReportedAndOmitted() = runBlocking {
        val emptyActiveEntry = EntryId("90000000-0000-4000-8000-000000000001")
        val input = PortableSnapshot(
            projects = listOf(PortableProject(projectId, "Backup")),
            entries = listOf(PortableEntry(emptyActiveEntry, projectId, "", ZoneId.of("UTC"), EntrySource.TIMER)),
            activeEntry = PortableActiveEntry(emptyActiveEntry, projectId, TrackingState.NEEDS_REVIEW),
        )
        val preview = repository.preview(input)

        assertEquals(1, preview.skippedEntriesWithoutConfirmedTime)
        assertTrue(preview.activeStateWillBeStopped)
        assertEquals(RestoreResult.Applied(1, 0, 0), repository.restore(preview, RestoreMode.REPLACE))
        assertFalse(database.trackingDao().entries().any { it.id == emptyActiveEntry.value })
    }

    @Test
    fun replacingWithEmptySnapshotRecreatesUsableDefaultProject() = runBlocking {
        val result = repository.restore(repository.preview(PortableSnapshot()), RestoreMode.REPLACE)

        assertEquals(RestoreResult.Applied(1, 0, 0), result)
        val projects = database.trackingDao().projects()
        assertEquals(listOf(ProjectRepository.DEFAULT_PROJECT_ID.value), projects.map { it.id })
        assertEquals(null, projects.single().archivedAtUtcMillis)
    }

    @Test
    fun migrationOneToFourPreservesGraphBackfillsSummariesAndAddsHardeningState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        try {
            val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
            try {
                createVersionOneSchema(raw)
                raw.execSQL(
                    "INSERT INTO projects VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>("a0000000-0000-4000-8000-000000000001", "Legacy", 7, 100, 900),
                )
                raw.execSQL(
                    "INSERT INTO projects VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any?>(ProjectRepository.DEFAULT_PROJECT_ID.value, "Без проекта", 11, 50, null),
                )
                raw.execSQL(
                    "INSERT INTO entries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>("a1000000-0000-4000-8000-000000000001", "a0000000-0000-4000-8000-000000000001", "Done", 1_000, 1_500, "MANUAL", 4, 1, null),
                )
                raw.execSQL(
                    "INSERT INTO entries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>("a1000000-0000-4000-8000-000000000002", "a0000000-0000-4000-8000-000000000001", "Active", 2_000, null, "TIMER", 5, 0, 1),
                )
                raw.execSQL(
                    "INSERT INTO segments VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>("a2000000-0000-4000-8000-000000000001", "a1000000-0000-4000-8000-000000000001", 1_000, null, null, 500, 1_500, null),
                )
                raw.execSQL(
                    "INSERT INTO segments VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>("a2000000-0000-4000-8000-000000000002", "a1000000-0000-4000-8000-000000000002", 2_000, 200, 1, null, null, 1),
                )
                raw.execSQL(
                    "INSERT INTO tracking_slot VALUES (1, 'RUNNING', ?, 5)",
                    arrayOf<Any?>("a1000000-0000-4000-8000-000000000002"),
                )
                raw.execSQL(
                    "INSERT INTO applied_commands VALUES ('legacy-command', 5, ?, 'RUNNING')",
                    arrayOf<Any?>("a1000000-0000-4000-8000-000000000002"),
                )
                raw.version = 1
            } finally {
                raw.close()
            }

            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()
            try {
                migrated.openHelper.writableDatabase
                val entries = migrated.trackingDao().entries()
                assertEquals(2, entries.size)
                assertTrue(entries.all { it.zoneId == "UTC" })
                assertEquals(500L, entries.single { it.completed }.confirmedDurationMillis)
                assertEquals(1_500L, entries.single { it.completed }.lastConfirmedEndUtcMillis)
                assertEquals(0L, entries.single { !it.completed }.confirmedDurationMillis)
                assertEquals(null, entries.single { !it.completed }.lastConfirmedEndUtcMillis)
                assertEquals(2, migrated.trackingDao().segments().size)
                assertEquals("RUNNING", migrated.trackingDao().slot()?.state)
                assertEquals("legacy", migrated.trackingDao().slot()?.generation)
                assertEquals("RUNNING", migrated.trackingDao().command("legacy-command")?.state)
                assertEquals(900L, migrated.trackingDao().project("a0000000-0000-4000-8000-000000000001")?.archivedAtUtcMillis)
                assertEquals(ProjectRepository.DEFAULT_PROJECT_STORAGE_NAME, migrated.trackingDao().project(ProjectRepository.DEFAULT_PROJECT_ID.value)?.name)
                migrated.openHelper.writableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'pending_preferences_restore'",
                ).use { cursor -> assertTrue(cursor.moveToFirst()) }
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun snapshot(
        projectName: String = "Backup",
        projectColor: Int = dev.opentimemanager.core.backup.DEFAULT_PROJECT_COLOR_ARGB,
        projectCreatedAt: Long = 0,
        projectArchivedAt: Long? = null,
        active: PortableActiveEntry? = null,
    ) = PortableSnapshot(
        projects = listOf(PortableProject(projectId, projectName, projectColor, projectCreatedAt, projectArchivedAt)),
        entries = listOf(PortableEntry(entryId, projectId, "Imported note", ZoneId.of("Europe/Moscow"), EntrySource.TIMER)),
        segments = listOf(Segment(segmentId, entryId, projectId, 10_000, 2_500)),
        activeEntry = active,
    )

    private fun AppDatabase.putActiveFixture(state: TrackingState) {
        require(state != TrackingState.IDLE)
        val isOpen = state != TrackingState.PAUSED
        trackingDao().insertEntry(
            EntryEntity(
                id = entryId.value,
                projectId = ProjectRepository.DEFAULT_PROJECT_ID.value,
                note = "Active",
                startedAtUtcMillis = 1_000,
                endedAtUtcMillis = null,
                source = EntrySource.TIMER.name,
                revision = 1,
                completed = false,
                currentSlot = 1,
                zoneId = "UTC",
            ),
        )
        trackingDao().saveSegment(
            SegmentEntity(
                id = segmentId.value,
                entryId = entryId.value,
                startedAtUtcMillis = 1_000,
                startedElapsedRealtimeMillis = if (isOpen) 100 else null,
                bootCount = if (isOpen) 1 else null,
                durationMillis = if (isOpen) null else 500,
                endUtcMillis = if (isOpen) null else 1_500,
                openSlot = if (isOpen) 1 else null,
            ),
        )
        trackingDao().updateSlot(
            TrackingSlotEntity(state = state.name, entryId = entryId.value, revision = 1),
        )
    }

    private fun createVersionOneSchema(database: SQLiteDatabase) {
        listOf(
            "CREATE TABLE projects (id TEXT NOT NULL, name TEXT NOT NULL, colorArgb INTEGER NOT NULL, createdAtUtcMillis INTEGER NOT NULL, archivedAtUtcMillis INTEGER, PRIMARY KEY(id))",
            "CREATE TABLE entries (id TEXT NOT NULL, projectId TEXT NOT NULL, note TEXT NOT NULL, startedAtUtcMillis INTEGER NOT NULL, endedAtUtcMillis INTEGER, source TEXT NOT NULL, revision INTEGER NOT NULL, completed INTEGER NOT NULL, currentSlot INTEGER, PRIMARY KEY(id), FOREIGN KEY(projectId) REFERENCES projects(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
            "CREATE INDEX index_entries_projectId ON entries(projectId)",
            "CREATE INDEX index_entries_startedAtUtcMillis ON entries(startedAtUtcMillis)",
            "CREATE UNIQUE INDEX index_entries_currentSlot ON entries(currentSlot)",
            "CREATE TABLE segments (id TEXT NOT NULL, entryId TEXT NOT NULL, startedAtUtcMillis INTEGER NOT NULL, startedElapsedRealtimeMillis INTEGER, bootCount INTEGER, durationMillis INTEGER, endUtcMillis INTEGER, openSlot INTEGER, PRIMARY KEY(id), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
            "CREATE INDEX index_segments_entryId ON segments(entryId)",
            "CREATE INDEX index_segments_startedAtUtcMillis_endUtcMillis ON segments(startedAtUtcMillis, endUtcMillis)",
            "CREATE UNIQUE INDEX index_segments_openSlot ON segments(openSlot)",
            "CREATE TABLE tracking_slot (id INTEGER NOT NULL, state TEXT NOT NULL, entryId TEXT, revision INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(entryId) REFERENCES entries(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
            "CREATE INDEX index_tracking_slot_entryId ON tracking_slot(entryId)",
            "CREATE TABLE applied_commands (commandId TEXT NOT NULL, revision INTEGER NOT NULL, entryId TEXT, state TEXT NOT NULL, PRIMARY KEY(commandId))",
            "CREATE UNIQUE INDEX index_applied_commands_revision ON applied_commands(revision)",
        ).forEach(database::execSQL)
    }

    private fun uuid(prefix: Int, suffix: Int): String = String.format(
        Locale.ROOT,
        "%08x-0000-4000-8000-%012x",
        prefix,
        suffix,
    )

    private class RecordingRestoreSettings(
        override val mutationBoundary: SettingsMutationBoundary = SettingsMutationBoundary(),
        private val action: suspend (UserPreferences) -> Unit = {},
    ) : RestorePreferenceTarget {
        val applied = mutableListOf<UserPreferences>()
        var current: UserPreferences? = null

        override suspend fun replaceWithinMutationBoundary(value: UserPreferences) {
            action(value)
            applied += value
            current = value
        }
    }

    private fun PendingPreferencesRestoreEntity.toExpectedPreferences() = UserPreferences(
        theme = ThemeMode.valueOf(theme),
        dynamicColor = dynamicColor,
        privacyMode = privacyMode,
        dailyGoalMinutes = dailyGoalMinutes,
    )
}
