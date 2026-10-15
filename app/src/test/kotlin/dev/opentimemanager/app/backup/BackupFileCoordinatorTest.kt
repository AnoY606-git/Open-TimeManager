package dev.opentimemanager.app.backup

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.CompletedEntryRepository
import dev.opentimemanager.app.data.ProjectRepository
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    private fun coordinator(limits: BackupLimits = BackupLimits()) = BackupFileCoordinator(
        database = database,
        documents = documents,
        restoreRepository = RestoreRepository(database, Dispatchers.IO),
        dispatcher = Dispatchers.IO,
        limits = limits,
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
}

private class MemoryDocumentStore : DocumentStore {
    val bytes = linkedMapOf<Uri, ByteArray>()
    val deleted = linkedSetOf<Uri>()
    val failWrites = linkedSetOf<Uri>()
    val cancelWrites = linkedSetOf<Uri>()

    override fun openInput(uri: Uri): InputStream? = bytes[uri]?.let(::ByteArrayInputStream)

    override fun openOutput(uri: Uri): OutputStream? {
        val sink = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
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
