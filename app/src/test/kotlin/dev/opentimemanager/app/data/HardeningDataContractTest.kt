package dev.opentimemanager.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.app.ui.confirmedDayProjection
import dev.opentimemanager.app.ui.MAX_PROJECT_LIMIT
import dev.opentimemanager.core.model.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.Executor
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun uiProjectionPagesTwentyThousandActiveProjects() = runBlocking {
        coordinator.observeSystemSnapshot().first()
        database.openHelper.writableDatabase.beginTransaction()
        try {
            repeat(20_000) { index ->
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO projects VALUES (?, ?, 0, ?, NULL)",
                    arrayOf<Any?>("active-$index", "Active $index", index.toLong() + 1),
                )
            }
            database.openHelper.writableDatabase.setTransactionSuccessful()
        } finally {
            database.openHelper.writableDatabase.endTransaction()
        }

        val firstPage = coordinator.observeSnapshot(historyLimit = 100, projectLimit = 100).first()
        val pinnedPage = coordinator.observeSnapshot(
            historyLimit = 100,
            projectLimit = 100,
            pinnedProjectId = "active-0",
        ).first()
        val secondPage = coordinator.observeSnapshot(historyLimit = 100, projectLimit = 200).first()
        val finalPage = coordinator.observeSnapshot(historyLimit = 100, projectLimit = MAX_PROJECT_LIMIT).first()

        assertEquals(101, firstPage.projects.size)
        assertTrue(firstPage.projects.any { it.id == ProjectRepository.DEFAULT_PROJECT_ID.value })
        assertTrue(firstPage.hasMoreProjects)
        assertTrue(pinnedPage.projects.any { it.id == "active-0" })
        assertEquals(201, secondPage.projects.size)
        assertTrue(secondPage.hasMoreProjects)
        assertEquals(MAX_PROJECT_LIMIT, finalPage.projects.size)
        assertFalse(finalPage.hasMoreProjects)
        assertEquals(
            ProjectEditResult.LIMIT_REACHED,
            ProjectRepository(database, Dispatchers.IO).create(ProjectId("beyond-cap"), "Beyond", 0, 30_000),
        )
    }

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
                    """INSERT INTO entries (
                        id, projectId, note, startedAtUtcMillis, endedAtUtcMillis, source,
                        revision, completed, currentSlot, zoneId, confirmedDurationMillis,
                        lastConfirmedEndUtcMillis
                    ) VALUES (?, ?, '', ?, ?, 'MANUAL', 0, 1, NULL, 'UTC', 1, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(entry, project, index.toLong(), index.toLong() + 1, index.toLong() + 1),
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

    @Test fun activeEntryWithTwentyThousandCheckpointsUsesConstantProjectionAndDeltaWrites() = runBlocking {
        val queries = Collections.synchronizedList(mutableListOf<String>())
        val measured = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).setQueryCallback({ sql, _ -> queries += sql }, Executor(Runnable::run)).build()
        try {
            val measuredCoordinator = TrackingCoordinator(
                RoomTrackingRepository(measured, ClockSnapshotSource { now }, Dispatchers.IO),
            )
            measuredCoordinator.observeSystemSnapshot().first()
            withContext(Dispatchers.IO) {
                measured.trackingDao().insertEntry(
                    EntryEntity(
                        id = "active-heavy",
                        projectId = ProjectRepository.DEFAULT_PROJECT_ID.value,
                        note = "",
                        startedAtUtcMillis = 0,
                        endedAtUtcMillis = null,
                        source = EntrySource.TIMER.name,
                        revision = 1,
                        completed = false,
                        currentSlot = 1,
                        zoneId = "UTC",
                        confirmedDurationMillis = 20_000,
                        lastConfirmedEndUtcMillis = 20_000,
                    ),
                )
                measured.openHelper.writableDatabase.beginTransaction()
                try {
                    repeat(20_000) { index ->
                        measured.openHelper.writableDatabase.execSQL(
                            "INSERT INTO segments VALUES (?, ?, ?, NULL, NULL, 1, ?, NULL)",
                            arrayOf<Any?>("closed-$index", "active-heavy", index.toLong(), index.toLong() + 1),
                        )
                    }
                    measured.openHelper.writableDatabase.execSQL(
                        "INSERT INTO segments VALUES ('active-open', 'active-heavy', 30000, 100, 7, NULL, NULL, 1)",
                    )
                    measured.openHelper.writableDatabase.execSQL(
                        "UPDATE tracking_slot SET state = 'RUNNING', entryId = 'active-heavy', revision = 1 WHERE id = 1",
                    )
                    measured.openHelper.writableDatabase.setTransactionSuccessful()
                } finally {
                    measured.openHelper.writableDatabase.endTransaction()
                }
                measured.openHelper.writableDatabase.execSQL("CREATE TABLE segment_write_audit (operation TEXT NOT NULL)")
                measured.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER audit_segment_update AFTER UPDATE ON segments BEGIN INSERT INTO segment_write_audit VALUES ('UPDATE'); END",
                )
                measured.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER audit_segment_insert AFTER INSERT ON segments BEGIN INSERT INTO segment_write_audit VALUES ('INSERT'); END",
                )
                measured.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER protect_confirmed BEFORE UPDATE ON segments WHEN OLD.durationMillis IS NOT NULL BEGIN SELECT RAISE(ABORT, 'confirmed segment changed'); END",
                )
            }
            queries.clear()
            now = ClockSnapshot(30_500, 600, 7)

            val before = withTimeout(8_000) { measuredCoordinator.observeSystemSnapshot().first() }
            val result = withTimeout(8_000) {
                measuredCoordinator.execute(
                    TrackingCommand.Pause(
                        CommandTarget(EntryId("active-heavy"), 1, CommandId("heavy-pause")),
                    ),
                )
            }

            assertTrue(result is TrackingResult.Applied)
            assertEquals(20_000L, before.session.confirmedDurationMillis)
            assertTrue(before.session.segments.isEmpty())
            val afterPause = measuredCoordinator.observeSystemSnapshot().first()
            assertEquals(20_500L, afterPause.session.confirmedDurationMillis)
            assertTrue(afterPause.session.segments.isEmpty())
            now = ClockSnapshot(30_500, 600, 7)
            assertTrue(
                measuredCoordinator.execute(
                    TrackingCommand.Resume(
                        CommandTarget(EntryId("active-heavy"), afterPause.session.revision, CommandId("heavy-resume")),
                    ),
                ) is TrackingResult.Applied,
            )
            val running = measuredCoordinator.observeSystemSnapshot().first()
            now = ClockSnapshot(31_000, 1_100, 7)
            assertTrue(
                measuredCoordinator.execute(
                    TrackingCommand.Stop(
                        CommandTarget(EntryId("active-heavy"), running.session.revision, CommandId("heavy-stop")),
                    ),
                ) is TrackingResult.Applied,
            )
            val commandQueries = queries.toList()
            withContext(Dispatchers.IO) {
                assertEquals(20_002L, measured.trackingDao().closedSegmentCount())
                assertEquals(3, measured.openHelper.readableDatabase.query("SELECT COUNT(*) FROM segment_write_audit").use { it.moveToFirst(); it.getInt(0) })
                assertEquals(1L, measured.trackingDao().entrySegments("active-heavy").first { it.id == "closed-0" }.durationMillis)
                assertEquals(21_000L, measured.trackingDao().entry("active-heavy")!!.confirmedDurationMillis)
                val plan = measured.openHelper.readableDatabase.query(
                    "EXPLAIN QUERY PLAN SELECT * FROM segments WHERE openSlot = 1 AND entryId = 'active-heavy' LIMIT 2",
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                    }
                }
                assertTrue(plan.any { it.contains("index_segments_openSlot") })
            }
            assertTrue(commandQueries.none { it.contains("ORDER BY startedElapsedRealtimeMillis", ignoreCase = true) })
            assertTrue(commandQueries.count { it.contains("FROM segments", ignoreCase = true) } <= 24)
        } finally {
            measured.close()
        }
    }

    @Test fun manualReviewRejectsAggregateOverflowBeforeWritingAndAcceptsExactLongMax() = runBlocking {
        coordinator.observeSystemSnapshot().first()
        withContext(Dispatchers.IO) {
            database.trackingDao().insertEntry(
                EntryEntity(
                    id = "review-overflow",
                    projectId = ProjectRepository.DEFAULT_PROJECT_ID.value,
                    note = "",
                    startedAtUtcMillis = 0,
                    endedAtUtcMillis = null,
                    source = EntrySource.TIMER.name,
                    revision = 1,
                    completed = false,
                    currentSlot = 1,
                    zoneId = "UTC",
                    confirmedDurationMillis = Long.MAX_VALUE,
                    lastConfirmedEndUtcMillis = Long.MAX_VALUE,
                ),
            )
            database.trackingDao().saveSegment(SegmentEntity("confirmed-max", "review-overflow", 0, null, null, Long.MAX_VALUE, Long.MAX_VALUE))
            database.trackingDao().saveSegment(SegmentEntity("review-open", "review-overflow", 0, 0, 7, null, null, 1))
            database.trackingDao().updateSlot(TrackingSlotEntity(state = TrackingState.NEEDS_REVIEW.name, entryId = "review-overflow", revision = 1))
        }
        val command = TrackingCommand.ResolveReview(
            CommandTarget(EntryId("review-overflow"), 1, CommandId("overflow-tail")),
            ReviewResolution.ManualEnd(1),
        )
        val beforeRejected = withContext(Dispatchers.IO) {
            Triple(
                database.trackingDao().entry("review-overflow"),
                database.trackingDao().entrySegments("review-overflow"),
                database.trackingDao().slot(),
            )
        }

        assertEquals(TrackingResult.Rejected(TrackingFailure.OVERFLOW), coordinator.execute(command))
        withContext(Dispatchers.IO) {
            assertEquals(beforeRejected.first, database.trackingDao().entry("review-overflow"))
            assertEquals(beforeRejected.second, database.trackingDao().entrySegments("review-overflow"))
            assertEquals(beforeRejected.third, database.trackingDao().slot())
            assertEquals(null, database.trackingDao().command("overflow-tail"))
            val entry = database.trackingDao().entry("review-overflow")!!
            database.trackingDao().updateEntry(
                entry.copy(confirmedDurationMillis = Long.MAX_VALUE - 1, lastConfirmedEndUtcMillis = Long.MAX_VALUE - 1),
            )
            database.trackingDao().saveSegment(
                database.trackingDao().entrySegments("review-overflow").single { it.id == "confirmed-max" }
                    .copy(durationMillis = Long.MAX_VALUE - 1, endUtcMillis = Long.MAX_VALUE - 1),
            )
        }

        val exact = coordinator.execute(
            TrackingCommand.ResolveReview(
                CommandTarget(EntryId("review-overflow"), 1, CommandId("exact-max")),
                ReviewResolution.ManualEnd(1),
            ),
        )
        assertTrue(exact is TrackingResult.Applied)
        assertEquals(Long.MAX_VALUE, withContext(Dispatchers.IO) { database.trackingDao().entry("review-overflow")!!.confirmedDurationMillis })
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

    @Test fun rangeProjectionCarriesMetadataOutsideTheHistoryPage() = runBlocking {
        coordinator.observeSystemSnapshot().first()
        val oldProject = ProjectEntity("old-project", "Long-lived project", 0xff123456.toInt(), 1)
        withContext(Dispatchers.IO) {
            database.projectDao().insert(oldProject)
            database.reportDao().save("old-entry", oldProject.id, "Important note", 0, 1_000, null)
            database.projectDao().archive(oldProject.id, 2_000)
            repeat(101) { index ->
                database.reportDao().save(
                    "new-$index",
                    ProjectRepository.DEFAULT_PROJECT_ID.value,
                    "",
                    10_000L + index * 2_000L,
                    1_000,
                    null,
                )
            }
        }
        val history = coordinator.observeSnapshot(historyLimit = 100, projectLimit = 100).first()
        assertFalse(history.projects.any { it.id == oldProject.id })

        val range = CompletedEntryRepository(database, Dispatchers.IO)
            .observeReportDetails(0, 1_500)
            .first()
        val summary = CompletedEntryRepository(database, Dispatchers.IO)
            .observeReportSummary(0, 1_500)
            .first()

        assertEquals(listOf(oldProject.copy(archivedAtUtcMillis = 2_000)), range.projects)
        assertEquals("Important note", range.entries.single().note)
        assertEquals("old-entry", range.segments.single().entryId.value)
        assertEquals(range.projects, summary.projects)
        assertEquals(1_000L, summary.totals.totalMillis)
    }
}
