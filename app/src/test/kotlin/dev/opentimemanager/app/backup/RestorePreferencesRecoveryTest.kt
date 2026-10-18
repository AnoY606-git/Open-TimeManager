package dev.opentimemanager.app.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.CompletedEntryRepository
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.PendingUserPreferences
import dev.opentimemanager.app.settings.PrivacyMirror
import dev.opentimemanager.app.settings.RestorePreferenceTarget
import dev.opentimemanager.app.settings.SettingsMutationBoundary
import dev.opentimemanager.app.settings.SettingsMutationResult
import dev.opentimemanager.app.settings.SettingsRecoveryJournal
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserPreferences
import dev.opentimemanager.app.settings.UserSettings
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
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class RestorePreferencesRecoveryTest {
    private lateinit var database: AppDatabase
    private val project = ProjectId("51000000-0000-4000-8000-000000000001")
    private val entry = EntryId("61000000-0000-4000-8000-000000000001")
    private val segment = SegmentId("71000000-0000-4000-8000-000000000001")
    private val expectedPreferences = UserPreferences(ThemeMode.DARK, false, true, 615)

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.trackingDao().initialize(100)
        CompletedEntryRepository(database, Dispatchers.IO).save(
            EntryId("81000000-0000-4000-8000-000000000001"), ProjectRepository.DEFAULT_PROJECT_ID,
            "Local fixture", 1_000, 500, null,
        )
        Unit
    }

    @After fun tearDown() { database.close() }

    @Test fun replaceReportsPendingWhenPrivacyMirrorReturnsFalseOrThrowsAndSuccessfulRetryConverges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (throwFromMirror in listOf(false, true)) {
            val journal = RoomSettingsRecoveryJournal(database, Dispatchers.IO)
            var failMirror = false
            var mirroredPrivacy = true
            val settings = UserSettings(
                context = context,
                name = "restore-pending-mirror-$throwFromMirror-${System.nanoTime()}",
                mutationBoundary = SettingsMutationBoundary(),
                recoveryJournal = journal,
                privacyMirror = PrivacyMirror { value ->
                    if (failMirror) {
                        if (throwFromMirror) throw IOException("Privacy mirror unavailable")
                        false
                    } else {
                        mirroredPrivacy = value
                        true
                    }
                },
            )
            assertEquals(SettingsMutationResult.APPLIED, settings.replace(UserPreferences(privacyMode = false)))
            assertFalse(mirroredPrivacy)
            failMirror = true
            val repository = RestoreRepository(database, Dispatchers.IO, settings = settings, settingsJournal = journal)
            val preview = repository.preview(source())

            val result = repository.restore(preview, RestoreMode.REPLACE) as RestoreResult.Applied

            assertEquals(RestoreResult.Applied(1, 1, 1, preferencesPending = true), result)
            assertCommittedGraph()
            // DataStore already says privacy is on, but the system-surface mirror is still off.
            assertEquals(expectedPreferences, settings.current())
            assertFalse(mirroredPrivacy)
            val pending = checkNotNull(journal.pending())
            assertEquals(expectedPreferences, pending.preferences)
            assertFalse(preview.hasPayload)
            val generation = database.trackingDao().slot()?.generation
            assertEquals(result, repository.restore(preview, RestoreMode.REPLACE))
            assertEquals(generation, database.trackingDao().slot()?.generation)

            assertEquals(SettingsMutationResult.APPLIED_PENDING_RECOVERY, repository.reconcilePendingPreferences())
            assertEquals(pending, journal.pending())
            assertFalse(mirroredPrivacy)
            failMirror = false
            assertEquals(SettingsMutationResult.APPLIED, repository.reconcilePendingPreferences())
            assertTrue(mirroredPrivacy)
            assertNull(journal.pending())
            assertEquals(generation, database.trackingDao().slot()?.generation)
            assertCommittedGraph()
        }
    }

    @Test fun preferenceIOExceptionAfterRoomCommitReturnsAppliedPendingConsumesPreviewAndKeepsRecoveryIntent() = runBlocking {
        var failPreferences = true
        val target = RecoveryTarget {
            if (failPreferences) throw IOException("Preferences unavailable")
            SettingsMutationResult.APPLIED
        }
        val repository = RestoreRepository(database, Dispatchers.IO, settings = target)
        val preview = repository.preview(source())

        val result = repository.restore(preview, RestoreMode.REPLACE) as RestoreResult.Applied

        assertTrue(result.preferencesPending)
        assertEquals(1, result.projects)
        assertEquals(1, result.entries)
        assertEquals(1, result.segments)
        assertCommittedGraph()
        assertNull(target.current)
        assertEquals(expectedPreferences, database.restoreDao().pendingPreferences()?.let {
            UserPreferences(ThemeMode.valueOf(it.theme), it.dynamicColor, it.privacyMode, it.dailyGoalMinutes)
        })
        assertFalse(preview.hasPayload)
        val calls = target.calls.size
        assertEquals(result, repository.restore(preview, RestoreMode.REPLACE))
        assertEquals(calls, target.calls.size)

        // Explicit reconciliation still propagates exceptions, without losing the committed intent.
        assertThrows(IOException::class.java) { runBlocking { repository.reconcilePendingPreferences() } }
        assertNotNull(database.restoreDao().pendingPreferences())
        failPreferences = false
        assertEquals(SettingsMutationResult.APPLIED, repository.reconcilePendingPreferences())
        assertEquals(expectedPreferences, target.current)
        assertNull(database.restoreDao().pendingPreferences())
        assertCommittedGraph()
    }

    @Test fun journalReadOrDeleteIOExceptionAfterCommitReportsPendingAndLaterRetryRemovesOnlyTheRecoveryIntent() = runBlocking {
        for (failOnRead in listOf(true, false)) {
            val durable = RoomSettingsRecoveryJournal(database, Dispatchers.IO)
            var failJournal = true
            val journal = object : SettingsRecoveryJournal {
                override suspend fun pending(): PendingUserPreferences? {
                    if (failJournal && failOnRead) throw IOException("Journal read unavailable")
                    return durable.pending()
                }
                override suspend fun save(value: PendingUserPreferences) = durable.save(value)
                override suspend fun delete(operationId: String): Boolean {
                    if (failJournal && !failOnRead) throw IOException("Journal delete unavailable")
                    return durable.delete(operationId)
                }
            }
            val target = RecoveryTarget()
            val repository = RestoreRepository(database, Dispatchers.IO, settings = target, settingsJournal = journal)
            val preview = repository.preview(source())

            val result = repository.restore(preview, RestoreMode.REPLACE) as RestoreResult.Applied

            assertTrue(result.preferencesPending)
            assertCommittedGraph()
            assertFalse(preview.hasPayload)
            assertEquals(if (failOnRead) 0 else 1, target.calls.size)
            val pending = checkNotNull(durable.pending())
            assertEquals(expectedPreferences, pending.preferences)
            val generation = database.trackingDao().slot()?.generation
            failJournal = false
            assertEquals(SettingsMutationResult.APPLIED, repository.reconcilePendingPreferences())
            assertEquals(expectedPreferences, target.current)
            assertNull(durable.pending())
            assertEquals(generation, database.trackingDao().slot()?.generation)
            assertCommittedGraph()
        }
    }

    @Test fun successfulReplaceMergeAndNoSettingsRestoreKeepTheCompatibleDefaultFalseResult() = runBlocking {
        val target = RecoveryTarget()
        val repository = RestoreRepository(database, Dispatchers.IO, settings = target)
        val merged = repository.restore(repository.preview(source()), RestoreMode.MERGE) as RestoreResult.Applied
        assertEquals(RestoreResult.Applied(1, 1, 1), merged)
        assertFalse(merged.preferencesPending)
        assertTrue(target.calls.isEmpty())
        assertNull(database.restoreDao().pendingPreferences())
        val mergedAgain = repository.restore(repository.preview(source()), RestoreMode.MERGE) as RestoreResult.Applied
        assertEquals(RestoreResult.Applied(0, 0, 0), mergedAgain)
        assertFalse(mergedAgain.preferencesPending)
        assertTrue(target.calls.isEmpty())

        val replaced = repository.restore(repository.preview(source()), RestoreMode.REPLACE) as RestoreResult.Applied
        assertEquals(RestoreResult.Applied(1, 1, 1), replaced)
        assertFalse(replaced.preferencesPending)
        assertEquals(expectedPreferences, target.current)
        assertNull(database.restoreDao().pendingPreferences())
        assertEquals(SettingsMutationResult.APPLIED, repository.reconcilePendingPreferences())
        val withoutSettings = RestoreRepository(database, Dispatchers.IO)
        val noPreferences = withoutSettings.restore(withoutSettings.preview(source()), RestoreMode.REPLACE) as RestoreResult.Applied
        assertEquals(RestoreResult.Applied(1, 1, 1), noPreferences)
        assertFalse(noPreferences.preferencesPending)
        assertEquals(SettingsMutationResult.APPLIED, withoutSettings.reconcilePendingPreferences())
        assertNull(database.restoreDao().pendingPreferences())
    }

    @Test fun cancellationBeforeCommitStillRollsBackRoomAndNeverStartsPreferenceRecovery() = runBlocking {
        val beforeProjects = database.trackingDao().projects()
        val beforeEntries = database.trackingDao().entries()
        val beforeSegments = database.trackingDao().segments()
        val beforeSlot = database.trackingDao().slot()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val target = RecoveryTarget()
        val repository = RestoreRepository(database, Dispatchers.IO, settings = target, afterReplaceDelete = {
            reached.complete(Unit)
            release.await()
        })
        val preview = repository.preview(source())
        val operation = async { repository.restore(preview, RestoreMode.REPLACE) }
        reached.await()
        operation.cancelAndJoin()

        assertTrue(operation.isCancelled)
        assertEquals(beforeProjects, database.trackingDao().projects())
        assertEquals(beforeEntries, database.trackingDao().entries())
        assertEquals(beforeSegments, database.trackingDao().segments())
        assertEquals(beforeSlot, database.trackingDao().slot())
        assertTrue(preview.hasPayload)
        assertTrue(target.calls.isEmpty())
        assertNull(database.restoreDao().pendingPreferences())
        release.complete(Unit)
        val result = repository.restore(preview, RestoreMode.REPLACE) as RestoreResult.Applied
        assertFalse(result.preferencesPending)
        assertFalse(preview.hasPayload)
        assertCommittedGraph()
    }

    @Test fun cancellationDuringPostCommitPendingRecoveryDoesNotMakeAnAppliedReplaceReplayable() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val target = RecoveryTarget {
            reached.complete(Unit)
            release.await()
            SettingsMutationResult.APPLIED_PENDING_RECOVERY
        }
        val repository = RestoreRepository(database, Dispatchers.IO, settings = target)
        val preview = repository.preview(source())
        val operation = async { repository.restore(preview, RestoreMode.REPLACE) }
        reached.await()
        assertCommittedGraph()
        assertNotNull(database.restoreDao().pendingPreferences())
        operation.cancel()
        release.complete(Unit)
        withTimeout(5_000) { operation.join() }

        assertTrue(operation.isCancelled)
        assertFalse(preview.hasPayload)
        val generation = database.trackingDao().slot()?.generation
        val calls = target.calls.size
        val committed = repository.restore(preview, RestoreMode.REPLACE) as RestoreResult.Applied
        assertTrue(committed.preferencesPending)
        assertEquals(RestoreResult.Applied(1, 1, 1, preferencesPending = true), committed)
        assertEquals(calls, target.calls.size)
        assertEquals(generation, database.trackingDao().slot()?.generation)
        assertNotNull(database.restoreDao().pendingPreferences())
        assertCommittedGraph()
    }

    @Test fun sqliteFailureBeforeCommitIsStillAnErrorWithNoPreferencesAppliedAndARetryablePreview() = runBlocking {
        val beforeProjects = database.trackingDao().projects()
        val beforeEntries = database.trackingDao().entries()
        val target = RecoveryTarget()
        val repository = RestoreRepository(database, Dispatchers.IO, settings = target)
        val preview = repository.preview(source())
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_preferences_restore BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT, 'restore failure'); END",
        )
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            runBlocking { repository.restore(preview, RestoreMode.REPLACE) }
        }
        assertEquals(beforeProjects, database.trackingDao().projects())
        assertEquals(beforeEntries, database.trackingDao().entries())
        assertTrue(target.calls.isEmpty())
        assertNull(database.restoreDao().pendingPreferences())
        assertTrue(preview.hasPayload)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_preferences_restore")
        val result = repository.restore(preview, RestoreMode.REPLACE) as RestoreResult.Applied
        assertFalse(result.preferencesPending)
        assertFalse(preview.hasPayload)
        assertCommittedGraph()
    }

    private fun source() = PortableSnapshot(
        projects = listOf(PortableProject(project, "Imported", 7, 10)),
        entries = listOf(PortableEntry(entry, project, "Imported note", ZoneId.of("UTC"), EntrySource.IMPORT)),
        segments = listOf(Segment(segment, entry, project, 2_000, 1_500)),
        preferences = PortablePreferences(PortableTheme.DARK, false, true, 615),
    )

    private fun assertCommittedGraph() {
        assertEquals(listOf(project.value), database.trackingDao().projects().map { it.id })
        assertEquals(listOf(entry.value), database.trackingDao().entries().map { it.id })
        assertEquals(listOf(segment.value), database.trackingDao().segments().map { it.id })
    }

    private class RecoveryTarget(
        private val action: suspend (UserPreferences) -> SettingsMutationResult = { SettingsMutationResult.APPLIED },
    ) : RestorePreferenceTarget {
        override val mutationBoundary = SettingsMutationBoundary()
        val calls = mutableListOf<UserPreferences>()
        var current: UserPreferences? = null

        override suspend fun replaceWithinMutationBoundary(value: UserPreferences): SettingsMutationResult {
            calls += value
            val result = action(value)
            current = value
            return result
        }
    }
}
