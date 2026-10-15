package dev.opentimemanager.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.app.ui.confirmedDayProjection
import dev.opentimemanager.core.model.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HardeningDataContractTest {
    private lateinit var database: AppDatabase
    private lateinit var coordinator: TrackingCoordinator
    private var now = ClockSnapshot(1_000, 100, 7)

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        coordinator = TrackingCoordinator(
            RoomTrackingRepository(database, ClockSnapshotSource { now }, Dispatchers.IO),
        )
    }

    @After fun tearDown() = database.close()

    @Test fun systemProjectionCardinalityDoesNotGrowWithTwentyThousandArchivedRows() = runBlocking {
        val initial = coordinator.observeSystemSnapshot().first()
        coordinator.execute(
            TrackingCommand.Start(
                CommandId("start"),
                ProjectRepository.DEFAULT_PROJECT_ID,
                initial.session.revision,
                initial.generation,
            ),
        )
        database.openHelper.writableDatabase.beginTransaction()
        try {
            for (index in 0 until 20_000) {
                val project = "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                val entry = "20000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                val segment = "30000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO projects VALUES (?, ?, 0, ?, ?)",
                    arrayOf<Any?>(project, "Archived $index", index.toLong(), index.toLong() + 1),
                )
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO entries VALUES (?, ?, '', ?, ?, 'MANUAL', 0, 1, NULL, 'UTC')",
                    arrayOf<Any?>(entry, project, index.toLong(), index.toLong() + 1),
                )
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO segments VALUES (?, ?, ?, NULL, NULL, 1, ?, NULL)",
                    arrayOf<Any?>(segment, entry, index.toLong(), index.toLong() + 1),
                )
            }
            database.openHelper.writableDatabase.setTransactionSuccessful()
        } finally {
            database.openHelper.writableDatabase.endTransaction()
        }

        val system = coordinator.observeSystemSnapshot().first()
        assertEquals(TrackingState.RUNNING, system.session.state)
        assertEquals(0, system.session.segments.size)
        assertEquals(ProjectRepository.DEFAULT_PROJECT_ID.value, system.activeProject?.id)
        assertEquals(ProjectRepository.DEFAULT_PROJECT_ID.value, system.quickStartProject?.id)

        val firstPage = coordinator.observeSnapshot(historyLimit = 100).first()
        assertEquals(100, firstPage.entries.count(EntryEntity::completed))
        assertEquals(101, firstPage.projects.size)
        assertEquals(100, firstPage.entryDurations.size)
        assertEquals(100, firstPage.segments.size)
        assertTrue(firstPage.hasMoreHistory)
    }

    @Test fun dailyTotalUsesItsOwnRangeProjectionInsteadOfTheHundredRowHistoryPage() = runBlocking {
        coordinator.observeSystemSnapshot().first()
        val projectId = ProjectRepository.DEFAULT_PROJECT_ID.value
        withContext(Dispatchers.IO) {
            repeat(101) { index ->
                database.reportDao().save(
                    id = "today-$index",
                    projectId = projectId,
                    note = "",
                    start = index * 60_000L,
                    duration = 60_000L,
                    expectedRevision = null,
                )
            }
        }

        val page = coordinator.observeSnapshot(historyLimit = 100).first()
        val total = CompletedEntryRepository(database, Dispatchers.IO)
            .observeReportTotals(0, 86_400_000)
            .first()
        val day = CompletedEntryRepository(database, Dispatchers.IO)
            .observeReport(0, 86_400_000)
            .first()
            .let { confirmedDayProjection(it, LocalDate.ofEpochDay(0), ZoneId.of("UTC")) }

        assertEquals(100, page.entries.count(EntryEntity::completed))
        assertEquals(101 * 60_000L, total.totalMillis)
        assertEquals(101 * 60_000L, day.confirmedMillis)
        assertEquals(101, day.rows.size)
    }

    @Test fun completedEntryWithThousandsOfSegmentsUsesOneDurationAndABoundedSegmentGraph() = runBlocking {
        coordinator.observeSystemSnapshot().first()
        val projectId = ProjectRepository.DEFAULT_PROJECT_ID.value
        withContext(Dispatchers.IO) {
            database.trackingDao().insertEntry(
                EntryEntity("heavy", projectId, "", 0, 2_501, "MANUAL", 0, true, zoneId = "UTC"),
            )
            database.openHelper.writableDatabase.beginTransaction()
            try {
                repeat(2_501) { index ->
                    database.openHelper.writableDatabase.execSQL(
                        "INSERT INTO segments VALUES (?, ?, ?, NULL, NULL, 1, ?, NULL)",
                        arrayOf<Any?>("heavy-$index", "heavy", index.toLong(), index.toLong() + 1),
                    )
                }
                database.openHelper.writableDatabase.setTransactionSuccessful()
            } finally {
                database.openHelper.writableDatabase.endTransaction()
            }
        }

        val page = coordinator.observeSnapshot(historyLimit = 1).first()

        assertEquals(MAX_UI_SEGMENT_ROWS, page.segments.size)
        assertEquals(2_501L, page.entryDurations.getValue("heavy"))
    }

    @Test fun overflowRecoveryRebasesToReviewAndRotatesGeneration() = runBlocking {
        val initial = coordinator.observeSystemSnapshot().first()
        coordinator.execute(
            TrackingCommand.Start(
                CommandId("start"),
                ProjectRepository.DEFAULT_PROJECT_ID,
                initial.session.revision,
                initial.generation,
            ),
        )
        val before = coordinator.observeSystemSnapshot().first()
        withContext(Dispatchers.IO) {
            database.trackingDao().updateEntry(
                database.trackingDao().entry(before.session.entryId!!.value)!!.copy(revision = Long.MAX_VALUE),
            )
            database.trackingDao().updateSlot(database.trackingDao().slot()!!.copy(revision = Long.MAX_VALUE))
        }
        now = ClockSnapshot(2_000, 10, 8)

        val recovered = coordinator.observeSystemSnapshot().first()

        assertEquals(TrackingState.NEEDS_REVIEW, recovered.session.state)
        assertEquals(0L, recovered.session.revision)
        assertNotEquals(before.generation, recovered.generation)
        assertEquals(
            0L,
            withContext(Dispatchers.IO) {
                database.trackingDao().entry(recovered.session.entryId!!.value)!!.revision
            },
        )
        val stale = TrackingCommand.Pause(
            CommandTarget(recovered.session.entryId!!, Long.MAX_VALUE, CommandId("stale")),
            before.generation,
        )
        assertEquals(
            TrackingResult.Rejected(TrackingFailure.STALE_GENERATION),
            coordinator.execute(stale),
        )
    }

    @Test fun reportProjectionReadsOnlySegmentsThatIntersectRequestedRange() = runBlocking {
        coordinator.observeSystemSnapshot().first()
        val projectId = ProjectRepository.DEFAULT_PROJECT_ID.value
        withContext(Dispatchers.IO) {
            database.reportDao().save(
                id = "before",
                projectId = projectId,
                note = "",
                start = 0,
                duration = 100,
                expectedRevision = null,
            )
            database.reportDao().save(
                id = "inside",
                projectId = projectId,
                note = "",
                start = 1_000,
                duration = 100,
                expectedRevision = null,
            )
            database.reportDao().save(
                id = "after",
                projectId = projectId,
                note = "",
                start = 2_000,
                duration = 100,
                expectedRevision = null,
            )
        }

        val result = withContext(Dispatchers.IO) { database.reportDao().report(950, 1_150) }

        assertEquals(listOf("inside"), result.map { it.entryId.value })
    }
}
