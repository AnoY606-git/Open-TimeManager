package dev.opentimemanager.app.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.backup.RestoreMode
import dev.opentimemanager.app.backup.RestoreRepository
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.core.backup.PortableEntry
import dev.opentimemanager.core.backup.PortableProject
import dev.opentimemanager.core.backup.PortableSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.EntrySource
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class HistoryGenerationTest {
    private lateinit var database: AppDatabase
    private lateinit var history: CompletedEntryRepository
    private lateinit var restore: RestoreRepository
    private val projectId = ProjectId("50000000-0000-4000-8000-000000000001")
    private val entryId = EntryId("60000000-0000-4000-8000-000000000001")
    private val newEntryId = EntryId("60000000-0000-4000-8000-000000000002")

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        history = CompletedEntryRepository(database, Dispatchers.IO)
        restore = RestoreRepository(database, Dispatchers.IO)
        runBlocking {
            withContext(Dispatchers.IO) {
                database.trackingDao().initialize(100)
                database.projectDao().insert(ProjectEntity(projectId.value, "Local project", 0, 100))
            }
        }
    }

    @After fun tearDown() {
        database.close()
    }

    @Test fun freshGenerationAllowsCreateTimingEditMetadataEditAndDelete() = runBlocking {
        val fixture = replacedHistory()
        val generation = fixture.restored.slot.generation

        assertEquals(
            EditResult.Applied,
            history.save(newEntryId, projectId, "Created", 20_000, 1_000, null, generation),
        )
        val created = storedHistory()
        assertEquals(0L, created.entries.single { it.id == newEntryId.value }.revision)
        assertEquals(1_000L, created.segments.single { it.entryId == newEntryId.value }.durationMillis)

        assertEquals(
            EditResult.Applied,
            history.save(newEntryId, projectId, "Timing edited", 21_000, 1_500, 0, generation),
        )
        val timingEdited = storedHistory()
        val editedEntry = timingEdited.entries.single { it.id == newEntryId.value }
        assertEquals(1L, editedEntry.revision)
        assertEquals(21_000L, editedEntry.startedAtUtcMillis)
        assertEquals(1_500L, editedEntry.confirmedDurationMillis)

        assertEquals(
            EditResult.Applied,
            history.updateMetadata(newEntryId, projectId, "Metadata edited", 1, generation),
        )
        val metadataEdited = storedHistory()
        assertEquals(
            editedEntry.copy(note = "Metadata edited", revision = 2),
            metadataEdited.entries.single { it.id == newEntryId.value },
        )
        assertEquals(timingEdited.segments, metadataEdited.segments)

        assertEquals(EditResult.Applied, history.delete(newEntryId, 2, generation))
        assertEquals(fixture.restored, storedHistory())
    }

    @Test fun staleGenerationRejectsTimingEditAfterReplaceDespiteMatchingIdAndRevision() = runBlocking {
        val fixture = replacedHistory()

        assertEquals(
            EditResult.StaleRevision,
            history.save(
                entryId,
                projectId,
                "Stale timing edit",
                30_000,
                900,
                expectedRevision = 0,
                expectedGeneration = fixture.staleGeneration,
            ),
        )
        assertEquals(fixture.restored, storedHistory())
    }

    @Test fun staleGenerationRejectsMetadataEditAfterReplaceDespiteMatchingIdAndRevision() = runBlocking {
        val fixture = replacedHistory()

        assertEquals(
            EditResult.StaleRevision,
            history.updateMetadata(
                entryId,
                projectId,
                "Stale metadata edit",
                expectedRevision = 0,
                expectedGeneration = fixture.staleGeneration,
            ),
        )
        assertEquals(fixture.restored, storedHistory())
    }

    @Test fun staleGenerationRejectsDeleteAfterReplaceDespiteMatchingIdAndRevision() = runBlocking {
        val fixture = replacedHistory()

        assertEquals(
            EditResult.StaleRevision,
            history.delete(entryId, expectedRevision = 0, expectedGeneration = fixture.staleGeneration),
        )
        assertEquals(fixture.restored, storedHistory())
    }

    @Test fun staleGenerationAlsoRejectsCreationFromAPreReplaceForm() = runBlocking {
        val fixture = replacedHistory()

        assertEquals(
            EditResult.StaleRevision,
            history.save(
                newEntryId,
                projectId,
                "Stale creation",
                30_000,
                900,
                expectedRevision = null,
                expectedGeneration = fixture.staleGeneration,
            ),
        )
        assertEquals(fixture.restored, storedHistory())
    }

    private suspend fun replacedHistory(): ReplacedHistory {
        val staleGeneration = storedHistory().slot.generation
        assertEquals(
            EditResult.Applied,
            history.save(entryId, projectId, "Local note", 1_000, 500, null, staleGeneration),
        )
        val localEntry = storedHistory().entries.single()
        assertEquals(0L, localEntry.revision)
        val snapshot = PortableSnapshot(
            projects = listOf(PortableProject(projectId, "Restored project")),
            entries = listOf(PortableEntry(entryId, projectId, "Restored note", ZoneId.of("UTC"), EntrySource.IMPORT)),
            segments = listOf(
                Segment(
                    SegmentId("70000000-0000-4000-8000-000000000001"),
                    entryId,
                    projectId,
                    10_000,
                    2_500,
                ),
            ),
        )

        assertTrue(restore.restore(restore.preview(snapshot), RestoreMode.REPLACE) is RestoreResult.Applied)
        val restored = storedHistory()
        val restoredEntry = restored.entries.single()
        assertNotEquals(staleGeneration, restored.slot.generation)
        assertEquals(localEntry.id, restoredEntry.id)
        assertEquals(localEntry.revision, restoredEntry.revision)
        assertEquals("Restored note", restoredEntry.note)
        return ReplacedHistory(staleGeneration, restored)
    }

    private suspend fun storedHistory(): StoredHistory = withContext(Dispatchers.IO) {
        database.withTransaction {
            val dao = database.trackingDao()
            StoredHistory(dao.projects(), dao.entries(), dao.segments(), requireNotNull(dao.slot()))
        }
    }

    private data class ReplacedHistory(val staleGeneration: String, val restored: StoredHistory)

    private data class StoredHistory(
        val projects: List<ProjectEntity>,
        val entries: List<EntryEntity>,
        val segments: List<SegmentEntity>,
        val slot: TrackingSlotEntity,
    )
}
