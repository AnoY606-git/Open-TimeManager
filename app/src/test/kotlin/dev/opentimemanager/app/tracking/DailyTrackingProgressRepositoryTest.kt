package dev.opentimemanager.app.tracking

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.CompletedEntryRepository
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.CommandId
import dev.opentimemanager.core.model.CommandTarget
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.TrackingState
import java.util.Collections
import java.util.concurrent.Executor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Дневной report, текущий слот и цель публикуются одним согласованным снимком. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class DailyTrackingProgressRepositoryTest {
    private lateinit var database: AppDatabase
    @Volatile private var now = ClockSnapshot(Instant.parse("2026-10-11T00:10:00Z").toEpochMilli(), 100L, 7)
    private val project = ProjectRepository.DEFAULT_PROJECT_ID
    private val goals = MutableStateFlow(480)
    private val refreshes = Channel<Unit>(Channel.UNLIMITED)
    private val refreshRequests = Channel<Unit>(Channel.UNLIMITED)
    private val waitingForRefresh = AtomicInteger()
    private val queries = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var tracker: TrackingCoordinator

    // Room дописывает SQL из фоновых потоков; обход synchronizedList требует её монитора.
    private fun querySnapshot(): List<String> = synchronized(queries) { queries.toList() }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries += sql }, Executor(Runnable::run)).build()
        tracker = TrackingCoordinator(
            RoomTrackingRepository(
                database, ClockSnapshotSource { now }, Dispatchers.IO,
                zoneId = { ZoneId.of("UTC") },
                awaitActiveRefresh = {
                    waitingForRefresh.incrementAndGet()
                    try {
                        refreshRequests.send(Unit)
                        refreshes.receive()
                    } finally {
                        waitingForRefresh.decrementAndGet()
                    }
                },
            ),
            dailyGoalMinutes = goals,
        )
    }

    @After fun close() { database.close(); refreshes.close(); refreshRequests.close() }

    private suspend fun start(): SystemSnapshot {
        tracker.observeSystemSnapshot().first()
        return (tracker.execute(TrackingCommand.Start(CommandId("start"), project, 0)) as TrackingResult.Applied).snapshot
    }

    private suspend fun Channel<SystemSnapshot>.until(predicate: (SystemSnapshot) -> Boolean): SystemSnapshot = withTimeout(5_000) {
        var snapshot = receive()
        while (!predicate(snapshot)) snapshot = receive()
        snapshot
    }

    @Test fun commandAndObservationSumOnlyTodayClosedSegmentsAndCurrentOpenTail() = runBlocking {
        tracker.observeSystemSnapshot().first()
        val history = CompletedEntryRepository(database, Dispatchers.IO)
        val midnight = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
        history.save(EntryId("older"), project, "", midnight - 7_200_000L, 3_600_000L, null)
        history.save(EntryId("cross-midnight"), project, "", midnight - 300_000L, 600_000L, null)
        history.save(EntryId("today"), project, "", midnight + 300_000L, 300_000L, null)
        val started = start()
        assertEquals(600_000L, started.dailyProgress?.trackedMillis)

        // Wall clock correction does not replace the monotonic interval by one hour.
        now = ClockSnapshot(midnight + 4_200_000L, 600_100L, 7)
        val observed = tracker.observeSystemSnapshot().first()
        assertEquals(1_200_000L, observed.dailyProgress?.trackedMillis)
        val paused = (tracker.execute(TrackingCommand.Pause(CommandTarget(
            started.session.entryId!!, started.session.revision, CommandId("pause"),
        ))) as TrackingResult.Applied).snapshot
        assertEquals(TrackingState.PAUSED, paused.session.state)
        assertEquals(1_200_000L, paused.dailyProgress?.trackedMillis)
        assertEquals(LocalDate.of(2026, 10, 11), paused.dailyProgress?.date)
    }

    @Test fun longPauseCannotRestartTheTwelveHourIslandLifetime() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "MANUFACTURER", "Xiaomi")
        android.provider.Settings.System.putInt(context.contentResolver, "notification_focus_protocol", 3)
        val keyguard = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(false)
        keyguard.setKeyguardLocked(false)
        dev.opentimemanager.app.system.LiveUpdatePreferences(context).setEnabled(true)
        val started = start()
        now = now.copy(wallUtcMillis = now.wallUtcMillis + 60_000, elapsedRealtimeMillis = now.elapsedRealtimeMillis + 60_000)
        val paused = (tracker.execute(TrackingCommand.Pause(CommandTarget(
            started.session.entryId!!, started.session.revision, CommandId("pause-for-thirteen-hours"),
        ))) as TrackingResult.Applied).snapshot
        val renderer = dev.opentimemanager.app.system.TrackingNotification(context)
        assertTrue(renderer.build(paused, nowWallMillis = now.wallUtcMillis, nowElapsedMillis = now.elapsedRealtimeMillis).extras.containsKey("miui.focus.param"))
        val expired = renderer.build(paused, nowWallMillis = now.wallUtcMillis + 46_800_000, nowElapsedMillis = now.elapsedRealtimeMillis + 46_800_000)
        assertNull(expired.extras.getString("miui.focus.param"))
        assertEquals(60_000L, paused.session.confirmedDurationMillis)
    }

    @Test fun changedGoalReadsTheCurrentCommittedSessionInsteadOfReusingAnOldSnapshot() = runBlocking {
        val started = start()
        val snapshots = Channel<SystemSnapshot>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { tracker.observeSystemSnapshot().collect { snapshots.send(it) } }
        try {
            snapshots.until { it.session.state == TrackingState.RUNNING }
            now = now.copy(wallUtcMillis = now.wallUtcMillis + 60_000L, elapsedRealtimeMillis = 60_100L)
            val paused = tracker.execute(TrackingCommand.Pause(CommandTarget(
                started.session.entryId!!, started.session.revision, CommandId("pause"),
            ))) as TrackingResult.Applied
            goals.value = 60
            val updated = snapshots.until { it.dailyProgress?.goalMinutes == 60 }
            assertEquals(paused.snapshot.session.revision, updated.session.revision)
            assertEquals(TrackingState.PAUSED, updated.session.state)
            assertEquals(60_000L, updated.dailyProgress?.trackedMillis)
            val resumed = tracker.execute(TrackingCommand.Resume(CommandTarget(
                updated.session.entryId!!, updated.session.revision, CommandId("resume"),
            ))) as TrackingResult.Applied
            assertEquals(60, resumed.snapshot.dailyProgress?.goalMinutes)
        } finally { collector.cancelAndJoin(); snapshots.close() }
    }

    @Test fun activeRefreshCrossesMidnightAndStopsWaitingWhenSessionBecomesIdle() = runBlocking {
        now = ClockSnapshot(Instant.parse("2026-10-11T23:59:00Z").toEpochMilli(), 100L, 7)
        val started = start()
        val snapshots = Channel<SystemSnapshot>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { tracker.observeSystemSnapshot().collect { snapshots.send(it) } }
        try {
            snapshots.until { it.session.state == TrackingState.RUNNING }
            withTimeout(5_000) { refreshRequests.receive() }
            now = ClockSnapshot(Instant.parse("2026-10-12T00:01:00Z").toEpochMilli(), 120_100L, 7)
            refreshes.send(Unit)
            val nextDay = snapshots.until { it.dailyProgress?.date == LocalDate.of(2026, 10, 12) }
            assertEquals(60_000L, nextDay.dailyProgress?.trackedMillis)
            val stopped = tracker.execute(TrackingCommand.Stop(CommandTarget(
                started.session.entryId!!, started.session.revision, CommandId("stop"),
            ))) as TrackingResult.Applied
            val idle = snapshots.until { it.session.state == TrackingState.IDLE }
            assertNull(stopped.snapshot.dailyProgress)
            assertNull(idle.dailyProgress)
            assertEquals(0, waitingForRefresh.get())
        } finally { collector.cancelAndJoin(); snapshots.close() }
        assertEquals(0, waitingForRefresh.get())
    }

    @Test fun twentyThousandCheckpointsUseOneClippedScalarInsteadOfMaterializingSegmentRows() = runBlocking {
        tracker.observeSystemSnapshot().first()
        val history = CompletedEntryRepository(database, Dispatchers.IO)
        val midnight = Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
        val tomorrow = midnight + 86_400_000L
        history.save(EntryId("checkpoints"), project, "", midnight + 3_600_000L, 20_000L, null)
        history.save(EntryId("cross-start"), project, "", midnight - 500L, 1_000L, null)
        history.save(EntryId("cross-end"), project, "", tomorrow - 700L, 1_000L, null)
        history.save(EntryId("ends-at-start"), project, "", midnight - 1_000L, 1_000L, null)
        history.save(EntryId("starts-at-end"), project, "", tomorrow, 1_000L, null)
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            database.runInTransaction {
                val sql = database.openHelper.writableDatabase
                sql.execSQL("DELETE FROM segments WHERE entryId = 'checkpoints'")
                repeat(20_000) { index ->
                    val start = midnight + 3_600_000L + index
                    sql.execSQL(
                        "INSERT INTO segments VALUES (?, 'checkpoints', ?, NULL, NULL, 1, ?, NULL)",
                        arrayOf<Any?>("checkpoint-$index", start, start + 1),
                    )
                }
            }
        }
        start()
        queries.clear()

        val snapshot = tracker.observeSystemSnapshot().first()

        // 20 000 × 1 ms + 500 ms после начала + 700 ms до конца суток.
        assertEquals(21_200L, snapshot.dailyProgress?.trackedMillis)
        val segmentReads = querySnapshot().filter {
            it.trimStart().startsWith("SELECT", ignoreCase = true) && it.contains("FROM segments", ignoreCase = true)
        }
        assertTrue("Дневная сумма должна читаться скалярным агрегатом", segmentReads.any { it.contains("SUM(", ignoreCase = true) })
        assertTrue("System snapshot не должен загружать строки закрытых сегментов", segmentReads.all {
            it.contains("SUM(", ignoreCase = true) || it.contains("LIMIT 2", ignoreCase = true)
        })
    }

    @Test fun idleSnapshotDoesNotReadDailyTotalsOrScheduleRefresh() = runBlocking {
        tracker.observeSystemSnapshot().first()
        queries.clear()

        val snapshot = tracker.observeSystemSnapshot().first()

        assertNull(snapshot.dailyProgress)
        assertEquals(0, waitingForRefresh.get())
        assertTrue(querySnapshot().none { it.contains("SUM(", ignoreCase = true) && it.contains("FROM segments", ignoreCase = true) })
    }
}
