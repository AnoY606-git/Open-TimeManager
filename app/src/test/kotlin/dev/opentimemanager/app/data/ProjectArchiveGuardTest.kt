package dev.opentimemanager.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.core.model.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class ProjectArchiveGuardTest {
    private lateinit var database: AppDatabase
    private lateinit var projects: ProjectRepository
    private lateinit var coordinator: TrackingCoordinator
    private var now = ClockSnapshot(1_000, 100, 7)
    private val projectId = ProjectId("50000000-0000-4000-8000-000000000001")

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        projects = ProjectRepository(database, Dispatchers.IO)
        coordinator = TrackingCoordinator(RoomTrackingRepository(database, ClockSnapshotSource { now }, Dispatchers.IO))
    }

    @After fun close() = database.close()
    private suspend fun snapshot() = coordinator.observeSystemSnapshot().first()
    private suspend fun create(id: ProjectId = projectId) {
        assertEquals(ProjectEditResult.APPLIED, projects.create(id, "Project", 0, 1))
    }
    private fun start(snapshot: SystemSnapshot, id: ProjectId = projectId) = TrackingCommand.Start(
        CommandId(UUID.randomUUID().toString()), id, snapshot.session.revision, snapshot.generation,
    )
    private fun target(snapshot: SystemSnapshot) = CommandTarget(
        requireNotNull(snapshot.session.entryId), snapshot.session.revision, CommandId(UUID.randomUUID().toString()),
    )
    private suspend fun archivedAt(id: ProjectId = projectId) = withContext(Dispatchers.IO) {
        requireNotNull(database.projectDao().project(id.value)).archivedAtUtcMillis
    }

    @Test fun startBeforeArchiveRefusesArchiveAndPauseResumeStopStillWork() = runBlocking {
        val initial = snapshot()
        create()
        assertTrue(coordinator.execute(start(initial)) is TrackingResult.Applied)
        val running = snapshot()
        assertEquals(ProjectEditResult.NOT_FOUND, projects.archive(projectId, 2_000))
        assertNull(archivedAt())
        assertEquals(running.session.revision, snapshot().session.revision)

        now = ClockSnapshot(2_000, 600, 7)
        assertTrue(coordinator.execute(TrackingCommand.Pause(target(running), running.generation)) is TrackingResult.Applied)
        val paused = snapshot()
        assertEquals(TrackingState.PAUSED, paused.session.state)
        assertEquals(ProjectEditResult.NOT_FOUND, projects.archive(projectId, 2_000))
        assertNull(archivedAt())
        assertEquals(paused.session.confirmedDurationMillis, snapshot().session.confirmedDurationMillis)
        assertTrue(coordinator.execute(TrackingCommand.Resume(target(paused), paused.generation)) is TrackingResult.Applied)

        now = ClockSnapshot(3_000, 1_100, 7)
        val resumed = snapshot()
        assertTrue(coordinator.execute(TrackingCommand.Stop(target(resumed), resumed.generation)) is TrackingResult.Applied)
        assertEquals(ProjectEditResult.APPLIED, projects.archive(projectId, 3_000))
        assertEquals(3_000L, archivedAt())
    }

    @Test fun archiveBeforeStartRejectsStartWithoutCreatingAnEntry() = runBlocking {
        val initial = snapshot()
        create()
        assertEquals(ProjectEditResult.APPLIED, projects.archive(projectId, 2_000))
        assertEquals(TrackingResult.Rejected(TrackingFailure.ARCHIVED_PROJECT), coordinator.execute(start(initial)))
        assertEquals(TrackingState.IDLE, snapshot().session.state)
        assertEquals(initial.session.revision, snapshot().session.revision)
        assertTrue(withContext(Dispatchers.IO) { database.trackingDao().entries().isEmpty() })
    }

    @Test fun needsReviewProjectCannotArchiveUntilTheReviewIsResolved() = runBlocking {
        val initial = snapshot()
        create()
        assertTrue(coordinator.execute(start(initial)) is TrackingResult.Applied)
        now = ClockSnapshot(3_000, 50, 8)
        val review = snapshot()
        assertEquals(TrackingState.NEEDS_REVIEW, review.session.state)
        assertEquals(ProjectEditResult.NOT_FOUND, projects.archive(projectId, 3_000))
        assertNull(archivedAt())
        assertEquals(review.session.revision, snapshot().session.revision)
        assertTrue(coordinator.execute(TrackingCommand.ResolveReview(target(review), ReviewResolution.CONFIRMED_CHECKPOINT, review.generation)) is TrackingResult.Applied)
        assertEquals(ProjectEditResult.APPLIED, projects.archive(projectId, 4_000))
    }

    @Test fun unrelatedProjectCanArchiveWhileAnotherProjectIsRunning() = runBlocking {
        val initial = snapshot()
        create()
        val unrelated = ProjectId(UUID.randomUUID().toString())
        create(unrelated)
        assertTrue(coordinator.execute(start(initial)) is TrackingResult.Applied)
        assertEquals(ProjectEditResult.APPLIED, projects.archive(unrelated, 2_000))
        assertEquals(2_000L, archivedAt(unrelated))
        assertEquals(projectId, snapshot().session.projectId)
        assertNull(archivedAt())
    }

    @Test fun reservedBuiltInCannotArchiveDirectlyOrThroughDeleteWithHistory() = runBlocking {
        val initial = snapshot()
        val builtIn = ProjectRepository.DEFAULT_PROJECT_ID
        assertEquals(ProjectEditResult.NOT_FOUND, projects.archive(builtIn, 2_000))
        assertEquals(0, withContext(Dispatchers.IO) { database.projectDao().archive(builtIn.value, 2_000) })
        assertNull(archivedAt(builtIn))

        assertTrue(coordinator.execute(start(initial, builtIn)) is TrackingResult.Applied)
        now = ClockSnapshot(2_000, 600, 7)
        val running = snapshot()
        assertTrue(coordinator.execute(TrackingCommand.Stop(target(running), running.generation)) is TrackingResult.Applied)
        assertEquals(ProjectEditResult.NOT_FOUND, projects.delete(builtIn, 3_000))
        assertNull(archivedAt(builtIn))
    }

    @Test fun concurrentStartAndArchiveOnlyProduceSafeSerializedOutcomes() = runBlocking {
        repeat(10) {
            val initial = snapshot()
            val id = ProjectId(UUID.randomUUID().toString())
            create(id)
            val ready = CompletableDeferred<Unit>()
            val starting = async(Dispatchers.IO) { ready.await(); coordinator.execute(start(initial, id)) }
            val archiving = async(Dispatchers.IO) { ready.await(); projects.archive(id, 2_000) }
            ready.complete(Unit)
            val startResult = starting.await()
            val archiveResult = archiving.await()
            val current = snapshot()
            when (startResult) {
                is TrackingResult.Applied -> {
                    assertEquals(ProjectEditResult.NOT_FOUND, archiveResult)
                    assertEquals(TrackingState.RUNNING, current.session.state)
                    assertEquals(id, current.session.projectId)
                    assertNull(archivedAt(id))
                    now = now.copy(wallUtcMillis = now.wallUtcMillis + 1, elapsedRealtimeMillis = now.elapsedRealtimeMillis + 1)
                    assertTrue(coordinator.execute(TrackingCommand.Stop(target(current), current.generation)) is TrackingResult.Applied)
                }
                else -> {
                    assertEquals(TrackingResult.Rejected(TrackingFailure.ARCHIVED_PROJECT), startResult)
                    assertEquals(ProjectEditResult.APPLIED, archiveResult)
                    assertEquals(TrackingState.IDLE, current.session.state)
                    assertEquals(2_000L, archivedAt(id))
                }
            }
        }
    }
}
