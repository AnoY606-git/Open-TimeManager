package dev.opentimemanager.app.backup

import android.app.Application
import android.net.Uri
import android.os.CancellationSignal
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.CompletedEntryRepository
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.data.SegmentEntity
import dev.opentimemanager.app.dataexport.CsvExportCoordinator
import dev.opentimemanager.app.dataexport.CsvExportResult
import dev.opentimemanager.app.dataexport.DocumentStore
import dev.opentimemanager.app.ui.UtcBounds
import dev.opentimemanager.core.backup.BackupCodec
import dev.opentimemanager.core.backup.BackupLimits
import dev.opentimemanager.core.backup.PortableEntry
import dev.opentimemanager.core.backup.PortableProject
import dev.opentimemanager.core.backup.PortableSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.EntrySource
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.report.ReportAggregator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.ZoneId
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class BackupFileCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var documents: MemoryDocumentStore
    private val csvUri = Uri.parse("content://tests/report.csv")
    private val backupUri = Uri.parse("content://tests/archive.otmbackup")

    @Before
    fun setUp() {
        runBlocking {
            database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java,
            ).allowMainThreadQueries().build()
            documents = MemoryDocumentStore()
            database.trackingDao().initialize(1_000)
            CompletedEntryRepository(database, Dispatchers.IO).save(
                id = EntryId("10000000-0000-4000-8000-000000000001"),
                projectId = ProjectRepository.DEFAULT_PROJECT_ID,
                note = "Client, phase 1",
                startUtcMillis = 2_000,
                durationMillis = 90_500,
                expectedRevision = null,
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cancelledCsvDestinationDoesNotOpenOrMutateDocuments() = runBlocking {
        val coordinator = CsvExportCoordinator(database, documents, Dispatchers.IO, ZoneId.of("UTC"))

        val result = coordinator.exportCsv(UtcBounds(0, 100_000), null, newlyCreated = true)

        assertEquals(CsvExportResult.Cancelled, result)
        assertTrue(documents.bytes.isEmpty())
        assertTrue(documents.deleted.isEmpty())
    }

    @Test
    fun csvUsesRealStreamingCodecAndStableMachineValues() = runBlocking {
        val coordinator = CsvExportCoordinator(database, documents, Dispatchers.IO, ZoneId.of("UTC"))

        val result = coordinator.exportCsv(UtcBounds(0, 100_000), csvUri, newlyCreated = true)

        assertEquals(CsvExportResult.Success, result)
        val csv = documents.bytes.getValue(csvUri).toString(Charsets.UTF_8)
        assertTrue(csv.startsWith("segment_id,entry_id,project_id,project_name,start_utc,end_utc,zone_id,duration_seconds,note,source\r\n"))
        assertTrue(csv.contains("1970-01-01T00:00:02Z"))
        assertTrue(csv.contains(",90.5,\"Client, phase 1\",MANUAL\r\n"))
        assertEquals("open-timemanager-1970-01-01_1970-01-01.csv", coordinator.fileName(UtcBounds(0, 100_000)))
        assertEquals("text/csv", CsvExportCoordinator.MIME_TYPE)
    }

    @Test
    fun csvClipsEveryOverlapToTheSelectedHalfOpenReportRangeInStableOrder() = runBlocking {
        insertCompleted("20000000-0000-4000-8000-000000000001", "30000000-0000-4000-8000-000000000002", 0, 1_500, "left")
        insertCompleted("20000000-0000-4000-8000-000000000002", "30000000-0000-4000-8000-000000000003", 0, 3_000, "both")
        insertCompleted("20000000-0000-4000-8000-000000000003", "30000000-0000-4000-8000-000000000001", 0, 1_000, "end-at-from")
        insertCompleted("20000000-0000-4000-8000-000000000004", "30000000-0000-4000-8000-000000000004", 1_200, 100, "inside")
        insertCompleted("20000000-0000-4000-8000-000000000005", "30000000-0000-4000-8000-000000000005", 1_500, 1_000, "right")
        insertCompleted("20000000-0000-4000-8000-000000000006", "30000000-0000-4000-8000-000000000006", 2_000, 100, "start-at-to")
        val range = UtcBounds(1_000, 2_000)
        val coordinator = CsvExportCoordinator(database, documents, Dispatchers.IO, ZoneId.of("UTC"))

        assertEquals(CsvExportResult.Success, coordinator.exportCsv(range, csvUri, newlyCreated = true))

        val rows = documents.bytes.getValue(csvUri).toString(Charsets.UTF_8)
            .lineSequence().drop(1).filter(String::isNotBlank).map { it.split(',') }.toList()
        assertEquals(
            listOf(
                "30000000-0000-4000-8000-000000000002",
                "30000000-0000-4000-8000-000000000003",
                "30000000-0000-4000-8000-000000000004",
                "30000000-0000-4000-8000-000000000005",
            ),
            rows.map { it[0] },
        )
        assertEquals(
            listOf(
                "1970-01-01T00:00:01Z" to "1970-01-01T00:00:01.500Z",
                "1970-01-01T00:00:01Z" to "1970-01-01T00:00:02Z",
                "1970-01-01T00:00:01.200Z" to "1970-01-01T00:00:01.300Z",
                "1970-01-01T00:00:01.500Z" to "1970-01-01T00:00:02Z",
            ),
            rows.map { it[4] to it[5] },
        )
        val exportedDurationMillis = rows.sumOf {
            BigDecimal(it[7]).movePointRight(3).longValueExact()
        }
        val reportDurationMillis = ReportAggregator.aggregate(
            database.reportDao().report(range.fromUtcMillis, range.toUtcMillis),
            range.fromUtcMillis,
            range.toUtcMillis,
            ZoneId.of("UTC"),
        ).totalMillis
        assertEquals(2_100L, exportedDurationMillis)
        assertEquals(reportDurationMillis, exportedDurationMillis)
    }

    @Test
    fun failedCsvWriteDeletesOnlyANewlyCreatedPartialDocument() = runBlocking {
        documents.failWrites += csvUri
        val coordinator = CsvExportCoordinator(database, documents, Dispatchers.IO, ZoneId.of("UTC"))

        val newDocument = coordinator.exportCsv(UtcBounds(0, 100_000), csvUri, newlyCreated = true)
        assertEquals(CsvExportResult.Failed(partialDocumentDeleted = true), newDocument)
        assertTrue(csvUri in documents.deleted)

        documents.deleted.clear()
        val existingDocument = coordinator.exportCsv(UtcBounds(0, 100_000), csvUri, newlyCreated = false)
        assertEquals(CsvExportResult.Failed(partialDocumentDeleted = false), existingDocument)
        assertFalse(csvUri in documents.deleted)
    }

    @Test
    fun backupRoundTripUsesRealCodecAndClearsPasswordsAtTheBoundary() = runBlocking {
        val coordinator = coordinator()
        val createPassword = "correct horse".toCharArray()
        val prepared = coordinator.prepareBackup(createPassword)
        assertArrayEquals(CharArray(createPassword.size), createPassword)

        assertEquals(BackupWriteResult.Success, coordinator.writeBackup(prepared, backupUri, newlyCreated = true))
        val encrypted = documents.bytes.getValue(backupUri)
        assertTrue(encrypted.copyOfRange(0, 5).contentEquals("OTMB1".toByteArray()))

        val inspectPassword = "correct horse".toCharArray()
        val inspection = coordinator.inspectBackup(backupUri, inspectPassword)
        assertArrayEquals(CharArray(inspectPassword.size), inspectPassword)
        assertTrue(inspection is BackupInspection.Ready)
        val preview = (inspection as BackupInspection.Ready).preview
        assertEquals(1, preview.counts.projects)
        assertEquals(1, preview.counts.entries)
        assertEquals(1, preview.counts.segments)
        assertTrue(
            prepared.fileName.matches(
                Regex("open-timemanager-\\d{4}-\\d{2}-\\d{2}\\.otmbackup"),
            ),
        )
        assertEquals("application/octet-stream", BackupFileCoordinator.MIME_TYPE)
    }

    @Test
    fun wrongPasswordAndTamperAreIndistinguishable() = runBlocking {
        val coordinator = coordinator()
        val prepared = coordinator.prepareBackup("secret".toCharArray())
        coordinator.writeBackup(prepared, backupUri, newlyCreated = true)

        val wrong = coordinator.inspectBackup(backupUri, "wrong".toCharArray())
        val tampered = documents.bytes.getValue(backupUri).copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        documents.bytes[backupUri] = tampered
        val changed = coordinator.inspectBackup(backupUri, "secret".toCharArray())

        assertEquals(BackupInspection.Failed(BackupReadFailure.INTEGRITY), wrong)
        assertEquals(BackupInspection.Failed(BackupReadFailure.INTEGRITY), changed)
    }

    @Test
    fun readAndDecodedSegmentLimitsStopBeforePreviewOrMutation() = runBlocking {
        val tinyReadLimit = coordinator(BackupLimits(maxBytes = 128, maxSegments = 200_000))
        documents.bytes[backupUri] = ByteArray(129)
        assertEquals(
            BackupInspection.Failed(BackupReadFailure.LIMIT),
            tinyReadLimit.inspectBackup(backupUri, "secret".toCharArray()),
        )

        val snapshot = portableSnapshot(segmentCount = 2)
        documents.bytes[backupUri] = BackupCodec.encrypt(snapshot, "secret".toCharArray(), SecureRandom())
        val segmentLimited = coordinator(BackupLimits(maxBytes = 50 * 1024 * 1024, maxSegments = 1))
        assertEquals(
            BackupInspection.Failed(BackupReadFailure.LIMIT),
            segmentLimited.inspectBackup(backupUri, "secret".toCharArray()),
        )
    }

    @Test
    fun createUsesTheSameContainerLimitAsReadAndClearsPassword() = runBlocking {
        val password = "secret".toCharArray()

        try {
            coordinator(BackupLimits(maxBytes = 128)).prepareBackup(password)
            throw AssertionError("Create limit expected")
        } catch (_: dev.opentimemanager.core.backup.BackupError.LimitError) {
            Unit
        }

        assertArrayEquals(CharArray(password.size), password)
    }

    @Test
    fun databaseCountsAreRejectedBeforeSnapshotListsAreMaterialized() = runBlocking {
        val password = "secret".toCharArray()
        val limited = coordinator(
            operationalLimits = BackupOperationalLimits(
                maxProjects = 0,
                maxEntries = 20_000,
                maxSegments = 20_000,
                maxUserTextBytes = 3 * 1024 * 1024,
            ),
        )

        try {
            limited.prepareBackup(password)
            throw AssertionError("Project count limit expected")
        } catch (_: dev.opentimemanager.core.backup.BackupError.LimitError) {
            Unit
        }

        assertArrayEquals(CharArray(password.size), password)
    }

    @Test
    fun authenticatedImportEnforcesEveryOperationalObjectLimitAndClearsPasswords() = runBlocking {
        val firstProject = PortableProject(
            ProjectId("21000000-0000-4000-8000-000000000001"),
            "A",
        )
        val secondProject = PortableProject(
            ProjectId("21000000-0000-4000-8000-000000000002"),
            "B",
        )
        val firstEntry = PortableEntry(
            EntryId("31000000-0000-4000-8000-000000000001"),
            firstProject.id,
            "",
            ZoneId.of("UTC"),
            EntrySource.MANUAL,
        )
        val secondEntry = PortableEntry(
            EntryId("31000000-0000-4000-8000-000000000002"),
            firstProject.id,
            "",
            ZoneId.of("UTC"),
            EntrySource.MANUAL,
        )
        val cases = listOf(
            PortableSnapshot(projects = listOf(firstProject, secondProject)) to BackupOperationalLimits(maxProjects = 1),
            PortableSnapshot(
                projects = listOf(firstProject),
                entries = listOf(firstEntry, secondEntry),
            ) to BackupOperationalLimits(maxEntries = 1),
            portableSnapshot(segmentCount = 2) to BackupOperationalLimits(maxSegments = 1),
        )

        cases.forEach { (snapshot, policy) ->
            documents.bytes[backupUri] = BackupCodec.encrypt(
                snapshot,
                "secret".toCharArray(),
                SecureRandom(),
                BackupFileCoordinator.APP_LIMITS,
            )
            val password = "secret".toCharArray()

            val result = coordinator(operationalLimits = policy).inspectBackup(backupUri, password)

            assertEquals(BackupInspection.Failed(BackupReadFailure.LIMIT), result)
            assertArrayEquals(CharArray(password.size), password)
        }
    }

    @Test
    fun authenticatedImportEnforcesUtf8TextBytesAndAcceptsTheExactBoundary() = runBlocking {
        val project = PortableProject(ProjectId("22000000-0000-4000-8000-000000000001"), "ab")
        val entry = PortableEntry(
            EntryId("32000000-0000-4000-8000-000000000001"),
            project.id,
            "é",
            ZoneId.of("UTC"),
            EntrySource.MANUAL,
        )
        val snapshot = PortableSnapshot(projects = listOf(project), entries = listOf(entry))
        documents.bytes[backupUri] = BackupCodec.encrypt(
            snapshot,
            "secret".toCharArray(),
            SecureRandom(),
            BackupFileCoordinator.APP_LIMITS,
        )
        val exactPassword = "secret".toCharArray()

        val exact = coordinator(
            operationalLimits = BackupOperationalLimits(
                maxProjects = 1,
                maxEntries = 1,
                maxSegments = 0,
                maxUserTextBytes = 4,
            ),
        ).inspectBackup(backupUri, exactPassword)

        assertTrue(exact is BackupInspection.Ready)
        (exact as BackupInspection.Ready).preview.discard()
        assertArrayEquals(CharArray(exactPassword.size), exactPassword)

        val overLimitPassword = "secret".toCharArray()
        val overLimit = coordinator(
            operationalLimits = BackupOperationalLimits(maxUserTextBytes = 3),
        ).inspectBackup(backupUri, overLimitPassword)

        assertEquals(BackupInspection.Failed(BackupReadFailure.LIMIT), overLimit)
        assertArrayEquals(CharArray(overLimitPassword.size), overLimitPassword)
    }

    @Test
    fun cancelledAndFailedBackupWritesDoNotRetainEncryptedPayload() = runBlocking {
        val coordinator = coordinator()
        val cancelled = coordinator.prepareBackup("secret".toCharArray())
        assertEquals(BackupWriteResult.Cancelled, coordinator.writeBackup(cancelled, null, newlyCreated = true))
        assertTrue(cancelled.isCleared)

        documents.failWrites += backupUri
        val failed = coordinator.prepareBackup("secret".toCharArray())
        assertEquals(
            BackupWriteResult.Failed(partialDocumentDeleted = true),
            coordinator.writeBackup(failed, backupUri, newlyCreated = true),
        )
        assertTrue(failed.isCleared)
        assertTrue(backupUri in documents.deleted)
    }

    @Test
    fun authenticatedButDomainInvalidBackupIsRejectedBeforePreview() = runBlocking {
        val invalid = PortableSnapshot(
            projects = listOf(PortableProject(ProjectId("20000000-0000-4000-8000-000000000001"), "   ")),
        )
        documents.bytes[backupUri] = BackupCodec.encrypt(invalid, "secret".toCharArray(), SecureRandom())

        val result = coordinator().inspectBackup(backupUri, "secret".toCharArray())

        assertEquals(BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT), result)
    }

    @Test
    fun viewModelScopedTransferSessionKeepsSafWorkAcrossUiRecreation() = runBlocking {
        val backupCoordinator = coordinator()
        val transfers = DataTransferSession(
            CsvExportCoordinator(database, documents, Dispatchers.IO, ZoneId.of("UTC")),
            backupCoordinator,
        )

        assertEquals(
            "open-timemanager-1970-01-01_1970-01-01.csv",
            transfers.beginCsv(UtcBounds(0, 100_000)),
        )
        assertEquals(CsvExportResult.Success, transfers.finishCsv(csvUri))

        val backupName = transfers.beginBackup("secret".toCharArray())
        assertTrue(backupName.matches(Regex("open-timemanager-\\d{4}-\\d{2}-\\d{2}\\.otmbackup")))
        assertEquals(BackupWriteResult.Success, transfers.finishBackup(backupUri))

        val inspection = backupCoordinator.inspectBackup(backupUri, "secret".toCharArray())
        assertTrue(inspection is BackupInspection.Ready)
        val preview = (inspection as BackupInspection.Ready).preview
        transfers.retainPreview(preview)
        assertSame(preview, transfers.currentPreview())
        transfers.discardPreview()
        assertNull(transfers.currentPreview())
        assertFalse(preview.hasPayload)
    }

    @Test
    fun orphanedCreateDocumentCallbacksDeleteOnlyTheirNewDocuments() = runBlocking {
        val transfers = DataTransferSession(
            CsvExportCoordinator(database, documents, Dispatchers.IO, ZoneId.of("UTC")),
            coordinator(),
        )
        documents.bytes[csvUri] = "empty".toByteArray()
        documents.bytes[backupUri] = "empty".toByteArray()

        assertEquals(CsvExportResult.Failed(true), transfers.finishCsv(csvUri))
        assertEquals(BackupWriteResult.Failed(true), transfers.finishBackup(backupUri))
        assertTrue(csvUri in documents.deleted)
        assertTrue(backupUri in documents.deleted)
    }

    @Test
    fun cancellationDuringProviderWriteRethrowsAndDeletesNewPartialDocuments() = runBlocking {
        documents.cancelWrites += csvUri
        val csv = CsvExportCoordinator(database, documents, Dispatchers.IO, ZoneId.of("UTC"))
        try {
            csv.exportCsv(UtcBounds(0, 100_000), csvUri, newlyCreated = true)
            throw AssertionError("CSV cancellation expected")
        } catch (_: CancellationException) {
            assertTrue(csvUri in documents.deleted)
        }

        documents.cancelWrites += backupUri
        val backup = coordinator()
        val prepared = backup.prepareBackup("secret".toCharArray())
        try {
            backup.writeBackup(prepared, backupUri, newlyCreated = true)
            throw AssertionError("backup cancellation expected")
        } catch (_: CancellationException) {
            assertTrue(backupUri in documents.deleted)
            assertTrue(prepared.isCleared)
        }
    }

    @Test
    fun cancellationInterruptsAnAlreadyStartedProviderOpenAndCleansThePartialBackup() = runBlocking {
        val control = documents.blockOutputOpen(backupUri)
        val backup = coordinator()
        val prepared = backup.prepareBackup("secret".toCharArray())

        val job = launch(Dispatchers.Default) { backup.writeBackup(prepared, backupUri, newlyCreated = true) }
        assertTrue(control.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertTrue(control.cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(prepared.isCleared)
        assertTrue(backupUri in documents.deleted)
    }

    @Test
    fun cancellationClosesAnAlreadyStartedSlowWriteLoopAndCleansThePartialBackup() = runBlocking {
        val control = documents.blockWrite(backupUri)
        val backup = coordinator()
        val prepared = backup.prepareBackup("secret".toCharArray())

        val job = launch(Dispatchers.Default) { backup.writeBackup(prepared, backupUri, newlyCreated = true) }
        assertTrue(control.started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertTrue(control.closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(prepared.isCleared)
        assertTrue(backupUri in documents.deleted)
    }

    @Test
    fun promptCancellationBeforeQueuedWriteStillDeletesOrphanedSafDocuments() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val backup = BackupFileCoordinator(
                database = database,
                documents = documents,
                restoreRepository = RestoreRepository(database, Dispatchers.IO),
                dispatcher = dispatcher,
            )
            val transfers = DataTransferSession(
                CsvExportCoordinator(database, documents, dispatcher, ZoneId.of("UTC")),
                backup,
            )
            transfers.beginBackup("secret".toCharArray())

            fun blockExecutor(): CountDownLatch {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                executor.execute {
                    entered.countDown()
                    release.await()
                }
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return release
            }

            transfers.beginCsv(UtcBounds(0, 100_000))
            val releaseCsv = blockExecutor()
            val csvJob = launch(start = CoroutineStart.UNDISPATCHED) { transfers.finishCsv(csvUri) }
            csvJob.cancel()
            releaseCsv.countDown()
            csvJob.join()
            assertTrue(csvJob.isCancelled)
            assertTrue(csvUri in documents.deleted)

            val releaseBackup = blockExecutor()
            val backupJob = launch(start = CoroutineStart.UNDISPATCHED) { transfers.finishBackup(backupUri) }
            backupJob.cancel()
            releaseBackup.countDown()
            backupJob.join()
            assertTrue(backupJob.isCancelled)
            assertTrue(backupUri in documents.deleted)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun promptCancellationBeforeQueuedInspectionClearsPassword() = runBlocking {
        documents.bytes[backupUri] = BackupCodec.encrypt(portableSnapshot(1), "secret".toCharArray(), SecureRandom())
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            executor.execute {
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val password = "secret".toCharArray()
            val queued = BackupFileCoordinator(
                database = database,
                documents = documents,
                restoreRepository = RestoreRepository(database, Dispatchers.IO),
                dispatcher = dispatcher,
            )

            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                queued.inspectBackup(backupUri, password)
            }
            job.cancel()
            release.countDown()
            job.join()

            assertTrue(job.isCancelled)
            assertArrayEquals(CharArray(password.size), password)
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun coordinator(
        limits: BackupLimits = BackupFileCoordinator.APP_LIMITS,
        operationalLimits: BackupOperationalLimits = BackupOperationalLimits(),
    ) = BackupFileCoordinator(
        database = database,
        documents = documents,
        restoreRepository = RestoreRepository(database, Dispatchers.IO),
        dispatcher = Dispatchers.IO,
        limits = limits,
        operationalLimits = operationalLimits,
        random = SecureRandom(),
    )

    private fun portableSnapshot(segmentCount: Int): PortableSnapshot {
        val project = ProjectId("20000000-0000-4000-8000-000000000001")
        val entry = EntryId("30000000-0000-4000-8000-000000000001")
        return PortableSnapshot(
            projects = listOf(PortableProject(project, "Project")),
            entries = listOf(PortableEntry(entry, project, "", ZoneId.of("UTC"), EntrySource.MANUAL)),
            segments = (1..segmentCount).map { index ->
                Segment(
                    SegmentId("40000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
                    entry,
                    project,
                    index * 1_000L,
                    500,
                )
            },
        )
    }

    private fun insertCompleted(
        entryId: String,
        segmentId: String,
        startUtcMillis: Long,
        durationMillis: Long,
        note: String,
    ) {
        val project = ProjectRepository.DEFAULT_PROJECT_ID.value
        database.trackingDao().insertEntry(
            EntryEntity(
                id = entryId,
                projectId = project,
                note = note,
                startedAtUtcMillis = startUtcMillis,
                endedAtUtcMillis = startUtcMillis + durationMillis,
                source = EntrySource.MANUAL.name,
                revision = 0,
                completed = true,
                zoneId = "UTC",
            ),
        )
        database.trackingDao().saveSegment(
            SegmentEntity(
                id = segmentId,
                entryId = entryId,
                startedAtUtcMillis = startUtcMillis,
                startedElapsedRealtimeMillis = null,
                bootCount = null,
                durationMillis = durationMillis,
                endUtcMillis = startUtcMillis + durationMillis,
            ),
        )
    }
}

private class MemoryDocumentStore : DocumentStore {
    val bytes = linkedMapOf<Uri, ByteArray>()
    val deleted = linkedSetOf<Uri>()
    val failWrites = linkedSetOf<Uri>()
    val cancelWrites = linkedSetOf<Uri>()
    private val blockedOutputOpens = linkedMapOf<Uri, BlockingOpen>()
    private val blockedWrites = linkedMapOf<Uri, BlockingWrite>()

    fun blockOutputOpen(uri: Uri): BlockingOpen = BlockingOpen().also { blockedOutputOpens[uri] = it }
    fun blockWrite(uri: Uri): BlockingWrite = BlockingWrite().also { blockedWrites[uri] = it }

    override fun openInput(uri: Uri, cancellationSignal: CancellationSignal): InputStream? =
        bytes[uri]?.let(::ByteArrayInputStream)

    override fun openOutput(uri: Uri, cancellationSignal: CancellationSignal): OutputStream? {
        blockedOutputOpens.remove(uri)?.let { control ->
            cancellationSignal.setOnCancelListener {
                control.cancelled.countDown()
                control.release.countDown()
            }
            control.started.countDown()
            control.release.await()
            cancellationSignal.throwIfCanceled()
        }
        val blockingWrite = blockedWrites.remove(uri)
        val sink = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                blockingWrite?.awaitUnlessClosed()
                if (uri in cancelWrites) throw CancellationException("provider write cancelled")
                if (uri in failWrites) throw IOException("provider write failed")
                super.write(bytes, offset, length)
            }

            override fun write(value: Int) {
                if (uri in cancelWrites) throw CancellationException("provider write cancelled")
                if (uri in failWrites) throw IOException("provider write failed")
                super.write(value)
            }

            override fun close() {
                blockingWrite?.close()
                if (uri !in failWrites) bytes[uri] = toByteArray()
                super.close()
            }
        }
        return sink
    }

    override fun delete(uri: Uri): Boolean {
        deleted += uri
        bytes.remove(uri)
        return true
    }
}

private class BlockingOpen {
    val started = CountDownLatch(1)
    val cancelled = CountDownLatch(1)
    val release = CountDownLatch(1)
}

private class BlockingWrite {
    val started = CountDownLatch(1)
    val closed = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val didStart = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    fun awaitUnlessClosed() {
        if (didStart.compareAndSet(false, true)) {
            started.countDown()
            release.await()
        }
        if (isClosed.get()) throw IOException("stream closed during test write")
    }

    fun close() {
        isClosed.set(true)
        closed.countDown()
        release.countDown()
    }
}
