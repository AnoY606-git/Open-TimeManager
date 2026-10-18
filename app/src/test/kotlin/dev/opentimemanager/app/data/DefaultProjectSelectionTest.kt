package dev.opentimemanager.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.backup.RestoreMode
import dev.opentimemanager.app.backup.RestoreRepository
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.app.backup.RoomSettingsRecoveryJournal
import dev.opentimemanager.app.settings.PrivacyMirror
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.app.system.TrackingActions
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.app.ui.AppControls
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.core.backup.PortableProject
import dev.opentimemanager.core.backup.PortableSnapshot
import dev.opentimemanager.core.model.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class DefaultProjectSelectionTest {
    private lateinit var database: AppDatabase
    private lateinit var projects: ProjectRepository
    private lateinit var coordinator: TrackingCoordinator
    private val preferred = MutableStateFlow<String?>(null)
    private var now = ClockSnapshot(1_000, 100, 7)
    private val builtIn = ProjectRepository.DEFAULT_PROJECT_ID.value
    private val preferredId = "50000000-0000-4000-8000-000000000001"
    private val explicitId = "50000000-0000-4000-8000-000000000002"
    private val activeId = "50000000-0000-4000-8000-000000000003"

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        projects = ProjectRepository(database, Dispatchers.IO)
        coordinator = TrackingCoordinator(repository(), defaultProjectId = preferred)
    }

    @After fun close() = database.close()
    private fun repository() = RoomTrackingRepository(database, ClockSnapshotSource { now }, Dispatchers.IO)
    private suspend fun snapshot(pinned: String = builtIn, limit: Int = 100) =
        coordinator.observeSnapshot(projectLimit = limit, pinnedProjectId = pinned).first()
    private suspend fun system() = coordinator.observeSystemSnapshot().first()
    private fun ui(snapshot: AppSnapshot, explicit: String? = null) = AppUiState(
        snapshot = snapshot,
        controls = AppControls(selectedProjectId = explicit),
    )
    private suspend fun create(id: String, created: Long = 1) {
        assertEquals(ProjectEditResult.APPLIED, projects.create(ProjectId(id), id, 0, created))
    }
    private fun target(snapshot: SystemSnapshot, command: String) =
        CommandTarget(requireNotNull(snapshot.session.entryId), snapshot.session.revision, CommandId(command))

    @Test fun noPreferenceRetainsAutomaticUiAndSafeSingleProjectQuickStart() = runBlocking {
        val initial = snapshot()
        assertNull(initial.defaultProjectId)
        assertEquals(builtIn, ui(initial).selectedProjectId)
        assertEquals(builtIn, system().quickStartProject?.id)
        create(preferredId)
        assertEquals(builtIn, ui(snapshot()).selectedProjectId)
        assertNull(system().quickStartProject)
        assertNull(TrackingActions.primary(system()))
    }

    @Test fun preferenceAndExplicitSelectionArePinnedBeyondFirstHundredProjects() = runBlocking {
        snapshot()
        create(preferredId, 1)
        create(explicitId, 2)
        repeat(125) { create(UUID.randomUUID().toString(), 10_000L + it) }
        preferred.value = preferredId

        val firstPage = snapshot(pinned = explicitId)
        assertTrue(firstPage.hasMoreProjects)
        assertEquals(103, firstPage.projects.size)
        assertEquals(1, firstPage.projects.count { it.id == preferredId })
        assertTrue(firstPage.projects.any { it.id == explicitId })
        assertTrue(firstPage.projects.any { it.id == builtIn })
        assertEquals(preferredId, firstPage.defaultProjectId)
        assertEquals(preferredId, ui(firstPage).selectedProjectId)
        assertEquals(explicitId, ui(firstPage, explicitId).selectedProjectId)
        assertEquals(preferredId, system().quickStartProject?.id)
        assertEquals(preferredId, TrackingCoordinator(repository(), preferred).observeSnapshot().first().defaultProjectId)
    }

    @Test fun invalidExplicitSelectionFallsBackToPreferredAndArchivedPreferredFallsBackSafely() = runBlocking {
        snapshot()
        create(preferredId)
        create(explicitId, 2)
        preferred.value = preferredId
        projects.archive(ProjectId(explicitId), 2_000)
        assertEquals(preferredId, ui(snapshot(explicitId), explicitId).selectedProjectId)
        assertEquals(preferredId, ui(snapshot(), UUID.randomUUID().toString()).selectedProjectId)

        projects.archive(ProjectId(preferredId), 2_000)
        val archived = snapshot()
        assertEquals(builtIn, ui(archived).selectedProjectId)
        assertEquals(preferredId, archived.defaultProjectId)
        assertEquals(builtIn, system().quickStartProject?.id)

        create(activeId, 3)
        assertNull(system().quickStartProject)
        projects.delete(ProjectId(preferredId), 3_000)
        assertEquals(builtIn, ui(snapshot()).selectedProjectId)
        assertNull(system().quickStartProject)
    }

    @Test fun missingBuiltInAndPreferencePinGlobalFirstFallbackBeyondThePage() = runBlocking {
        snapshot()
        projects.delete(ProjectId(builtIn), 2_000)
        create(explicitId, 1)
        repeat(125) { create(UUID.randomUUID().toString(), 10_000L + it) }
        preferred.value = preferredId // Canonical, but absent after deletion/restore.

        val page = snapshot()
        assertEquals(101, page.projects.size)
        assertTrue(page.projects.any { it.id == explicitId })
        assertEquals(explicitId, ui(page).selectedProjectId)
        assertNull(system().quickStartProject)
        assertFalse(page.projects.any { it.id == builtIn }) // Do not resurrect a deleted built-in.
    }

    @Test fun noEligibleProjectsYieldsNoSelectionOrQuickStart() = runBlocking {
        snapshot()
        // Legacy/corrupted graph fixture: the public API now protects the built-in project.
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("UPDATE projects SET archivedAtUtcMillis = 2000 WHERE id = ?", arrayOf(builtIn))
        }
        preferred.value = preferredId
        assertNull(ui(snapshot()).selectedProjectId)
        assertNull(system().quickStartProject)
        assertNull(TrackingActions.primary(system()))
    }

    @Test fun runningPausedAndReviewSessionsIgnorePreferenceChangesEvenIfActiveProjectIsArchived() = runBlocking {
        val initial = system()
        create(preferredId)
        create(explicitId)
        create(activeId)
        assertTrue(coordinator.execute(TrackingCommand.Start(CommandId("start-active"), ProjectId(activeId), initial.session.revision, initial.generation)) is TrackingResult.Applied)
        val running = system()

        preferred.value = preferredId
        val changed = snapshot()
        assertEquals(activeId, ui(changed, explicitId).selectedProjectId)
        assertEquals(running.session.revision, changed.session.revision)
        assertEquals(running.session.openSegment, changed.session.openSegment)
        assertEquals(activeId, system().activeProject?.id)
        // Simulate a legacy archived active project; new archive commands reject this race.
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("UPDATE projects SET archivedAtUtcMillis = 1500 WHERE id = ?", arrayOf(activeId))
        }
        assertEquals(activeId, ui(snapshot(), explicitId).selectedProjectId)

        now = ClockSnapshot(2_000, 600, 7)
        assertTrue(coordinator.execute(TrackingCommand.Pause(target(system(), "pause"), initial.generation)) is TrackingResult.Applied)
        val paused = system()
        preferred.value = explicitId
        assertEquals(TrackingState.PAUSED, snapshot().session.state)
        assertEquals(activeId, ui(snapshot(), preferredId).selectedProjectId)
        assertEquals(paused.session.confirmedDurationMillis, snapshot().session.confirmedDurationMillis)

        assertTrue(coordinator.execute(TrackingCommand.Resume(target(system(), "resume"), initial.generation)) is TrackingResult.Applied)
        now = ClockSnapshot(3_000, 50, 8)
        val review = system()
        assertEquals(TrackingState.NEEDS_REVIEW, review.session.state)
        preferred.value = null
        val unchangedReview = snapshot()
        assertEquals(activeId, ui(unchangedReview, explicitId).selectedProjectId)
        assertEquals(review.session.revision, unchangedReview.session.revision)
        assertEquals(review.session.openSegment, unchangedReview.session.openSegment)
    }

    @Test fun quickStartAndImmediateStopResultUsePreferredProjectWithMultipleEligibleProjects() = runBlocking {
        system()
        create(preferredId)
        preferred.value = preferredId
        val command = TrackingActions.primary(system()) as TrackingCommand.Start
        assertEquals(preferredId, command.projectId.value)
        assertTrue(coordinator.execute(command) is TrackingResult.Applied)
        assertEquals(preferredId, system().activeProject?.id)
        now = ClockSnapshot(2_000, 600, 7)
        val stopped = coordinator.execute(TrackingActions.stop(system())) as TrackingResult.Applied
        assertEquals(TrackingState.IDLE, stopped.snapshot.session.state)
        assertEquals(preferredId, stopped.snapshot.quickStartProject?.id)
        assertEquals(preferredId, (TrackingActions.primary(stopped.snapshot) as TrackingCommand.Start).projectId.value)
    }

    @Test fun preferredQuickStartCapturedBeforeArchiveIsRejectedWithoutStartingAnotherProject() = runBlocking {
        system()
        create(preferredId)
        preferred.value = preferredId
        val command = requireNotNull(TrackingActions.primary(system()))
        projects.archive(ProjectId(preferredId), 2_000)
        assertEquals(TrackingResult.Rejected(TrackingFailure.ARCHIVED_PROJECT), coordinator.execute(command))
        assertEquals(TrackingState.IDLE, system().session.state)
        assertEquals(builtIn, system().quickStartProject?.id)

        projects.delete(ProjectId(preferredId), 3_000)
        assertEquals(TrackingResult.Rejected(TrackingFailure.MISSING_PROJECT), coordinator.execute(command))
        assertEquals(TrackingState.IDLE, system().session.state)
    }

    @Test fun malformedInjectedOverrideIsIgnoredEvenIfSuchAProjectExists() = runBlocking {
        snapshot()
        create("not-a-uuid")
        preferred.value = "not-a-uuid"
        assertNull(snapshot().defaultProjectId)
        assertEquals(builtIn, ui(snapshot()).selectedProjectId)
        assertNull(system().quickStartProject)
    }

    @Test fun preferenceChangesPublishSystemAndPinnedUiSnapshotsWithoutRoomWrites() = runBlocking {
        system()
        create(preferredId)
        val settings = UserSettings(
            ApplicationProvider.getApplicationContext<Application>(),
            "default-project-live-${UUID.randomUUID()}",
            privacyMirror = PrivacyMirror { true },
        )
        val live = TrackingCoordinator(repository(), settings.defaultProjectId)
        val systems = Channel<SystemSnapshot>(Channel.UNLIMITED)
        val snapshots = Channel<AppSnapshot>(Channel.UNLIMITED)
        val systemCollector = launch(Dispatchers.Default) { live.observeSystemSnapshot().collect { systems.send(it) } }
        val uiCollector = launch(Dispatchers.Default) { live.observeSnapshot().collect { snapshots.send(it) } }
        try {
            assertNull(withTimeout(5_000) { systems.receive() }.quickStartProject)
            assertNull(withTimeout(5_000) { snapshots.receive() }.defaultProjectId)
            settings.setDefaultProjectId(preferredId)
            assertEquals(preferredId, systems.await { it.quickStartProject?.id == preferredId }.quickStartProject?.id)
            assertEquals(preferredId, ui(snapshots.await { it.defaultProjectId == preferredId }).selectedProjectId)
            settings.setDefaultProjectId(null)
            assertNull(systems.await { it.quickStartProject == null }.quickStartProject)
            assertNull(snapshots.await { it.defaultProjectId == null }.defaultProjectId)
        } finally {
            systemCollector.cancelAndJoin()
            uiCollector.cancelAndJoin()
            systems.close()
            snapshots.close()
        }
    }

    @Test fun replaceRestorePreservesLocalPreferenceButMissingProjectCannotBecomeQuickStart() = runBlocking {
        system()
        create(preferredId)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val journal = RoomSettingsRecoveryJournal(database, Dispatchers.IO)
        val settings = UserSettings(context, "default-project-restore-${UUID.randomUUID()}", recoveryJournal = journal, privacyMirror = PrivacyMirror { true })
        settings.setDefaultProjectId(preferredId)
        val restore = RestoreRepository(database, Dispatchers.IO, settings = settings, settingsJournal = journal)
        val restored = PortableSnapshot(projects = listOf(
            PortableProject(ProjectId(builtIn), ProjectRepository.DEFAULT_PROJECT_STORAGE_NAME, 0, 1),
            PortableProject(ProjectId(explicitId), "Restored", 0, 2),
        ))
        assertTrue(restore.restore(restore.preview(restored), RestoreMode.REPLACE) is RestoreResult.Applied)

        val recreated = TrackingCoordinator(repository(), settings.defaultProjectId)
        assertEquals(preferredId, settings.defaultProjectId.first())
        val page = recreated.observeSnapshot().first()
        assertFalse(page.projects.any { it.id == preferredId })
        assertEquals(builtIn, ui(page).selectedProjectId)
        assertNull(recreated.observeSystemSnapshot().first().quickStartProject)
    }

    private suspend fun <T> Channel<T>.await(predicate: (T) -> Boolean): T = withTimeout(5_000) {
        var next = receive()
        while (!predicate(next)) next = receive()
        next
    }
}
