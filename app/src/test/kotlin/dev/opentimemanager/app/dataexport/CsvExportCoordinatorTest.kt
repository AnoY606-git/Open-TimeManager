package dev.opentimemanager.app.dataexport

import android.app.Application
import android.net.Uri
import android.os.CancellationSignal
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.CompletedEntryRepository
import dev.opentimemanager.app.data.EditResult
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.data.SegmentEntity
import dev.opentimemanager.app.ui.UtcBounds
import dev.opentimemanager.core.model.EntryId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CsvExportCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var cacheDirectory: File
    private lateinit var documents: CsvDocuments
    private val uri = Uri.parse("content://csv-tests/new-report")
    private val range = UtcBounds(0, 10_000_000)

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        cacheDirectory = File(context.cacheDir, "csv-test-${UUID.randomUUID()}")
        check(cacheDirectory.mkdir())
        documents = CsvDocuments { assertFalse("Provider I/O must not hold a Room transaction", database.inTransaction()) }
        withContext(Dispatchers.IO) { database.trackingDao().initialize(0) }
    }

    @After fun tearDown() {
        database.close()
        assertNoSpool()
        assertTrue(cacheDirectory.delete())
    }

    @Test fun multiWindowExportKeepsOneSnapshotAndReleasesWriterBeforeSlowProviderIo() = runBlocking {
        // More than a normal Android CursorWindow, without retaining the graph in the exporter.
        seedEntries(1_200, "x".repeat(2_000))
        val barrier = WriteBarrier()
        documents.writeBarrier = barrier
        var formattedRows = 0
        val csv = CsvExportCoordinator(
            database, documents, Dispatchers.IO, cacheDirectory,
            projectDisplayNameForSnapshot = {
                { _, storedName ->
                    assertTrue("Every row must be read in the same snapshot transaction", database.inTransaction())
                    formattedRows++
                    storedName
                }
            },
        )
        val exporting = async(Dispatchers.Default) { csv.exportCsv(range, uri, newlyCreated = true) }
        try {
            assertTrue(barrier.started.await(10, TimeUnit.SECONDS))
            assertEquals(1_200, formattedRows)
            // Timing edits replace the segment and move its row beyond the original first window.
            // This must finish while the provider is still blocked, not wait for external I/O.
            val editing = async(Dispatchers.IO) {
                CompletedEntryRepository(database, Dispatchers.IO).save(
                    EntryId("entry-0"), ProjectRepository.DEFAULT_PROJECT_ID,
                    "Changed after the snapshot", 9_000_000, 500, expectedRevision = 0,
                )
            }
            assertEquals(EditResult.Applied, withTimeout(5_000) { editing.await() })
        } finally {
            barrier.release.countDown()
        }
        assertEquals(CsvExportResult.Success, exporting.await())
        assertTrue(documents.bytes.size > 2 * 1024 * 1024)
        val rows = documents.bytes.toString(Charsets.UTF_8).lineSequence()
            .drop(1).filter(String::isNotEmpty).map { it.split(',') }.toList()
        assertEquals(1_200, rows.size)
        assertEquals((0 until 1_200).map { "segment-$it" }, rows.map { it[0] })
        assertEquals((0 until 1_200).map { "entry-$it" }, rows.map { it[1] })
        assertFalse(documents.bytes.toString(Charsets.UTF_8).contains("Changed after the snapshot"))
        assertNoSpool()
    }

    @Test fun snapshotFailureDoesNotOpenProviderAndRemovesItsNewDocumentAndSpool() = runBlocking {
        seedEntries(1)
        val csv = CsvExportCoordinator(
            database, documents, Dispatchers.IO, cacheDirectory,
            projectDisplayNameForSnapshot = { { _, _ -> throw IOException("snapshot failed") } },
        )

        assertEquals(CsvExportResult.Failed(true), csv.exportCsv(range, uri, newlyCreated = true))

        assertEquals(0, documents.opens)
        assertEquals(listOf(uri), documents.deleted)
        assertNoSpool()
    }

    @Test fun failedCopyRemovesSpoolAndOnlyDeletesANewDestination() = runBlocking {
        seedEntries(1)
        documents.failWrite = true
        val csv = coordinator()

        assertEquals(CsvExportResult.Failed(true), csv.exportCsv(range, uri, newlyCreated = true))
        assertEquals(listOf(uri), documents.deleted)
        assertNoSpool()
        documents.deleted.clear()

        assertEquals(CsvExportResult.Failed(false), csv.exportCsv(range, uri, newlyCreated = false))
        assertTrue(documents.deleted.isEmpty())
        assertNoSpool()
    }

    @Test fun cancellationDuringSnapshotCleansSpoolWithoutOpeningProvider() = runBlocking {
        seedEntries(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val csv = CsvExportCoordinator(
            database, documents, Dispatchers.IO, cacheDirectory,
            projectDisplayNameForSnapshot = {
                { _, storedName ->
                    if (first.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    storedName
                }
            },
        )
        val exporting = launch(Dispatchers.Default) { csv.exportCsv(range, uri, newlyCreated = true) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            exporting.cancel()
        } finally {
            release.countDown()
        }
        withTimeout(5_000) { exporting.join() }

        assertTrue(exporting.isCancelled)
        assertEquals(0, documents.opens)
        assertEquals(listOf(uri), documents.deleted)
        assertNoSpool()
    }

    @Test fun cancellationDuringSlowCopyClosesProviderAndRemovesSpool() = runBlocking {
        seedEntries(1)
        val barrier = WriteBarrier()
        documents.writeBarrier = barrier
        val exporting = launch(Dispatchers.Default) { coordinator().exportCsv(range, uri, newlyCreated = true) }
        try {
            assertTrue(barrier.started.await(10, TimeUnit.SECONDS))
            exporting.cancel()
            withTimeout(5_000) { exporting.join() }
        } finally {
            barrier.release.countDown()
        }

        assertTrue(exporting.isCancelled)
        assertTrue(barrier.closed.get())
        assertEquals(listOf(uri), documents.deleted)
        assertNoSpool()
    }

    @Test fun cancellationBeforeQueuedExportStillDeletesNewDestinationWithoutSpooling() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            executor.execute {
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val csv = CsvExportCoordinator(database, documents, dispatcher, cacheDirectory)
            val exporting = launch(start = CoroutineStart.UNDISPATCHED) { csv.exportCsv(range, uri, newlyCreated = true) }
            exporting.cancel()
            release.countDown()
            withTimeout(5_000) { exporting.join() }

            assertTrue(exporting.isCancelled)
            assertEquals(0, documents.opens)
            assertEquals(listOf(uri), documents.deleted)
            assertNoSpool()
        } finally {
            release.countDown()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun fileNameReadsCurrentZoneAfterCoordinatorCreationAndExplicitRangeZoneWins() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val csv = coordinator()
            val losAngeles = ZoneId.of("America/Los_Angeles")
            val date = LocalDate.of(2026, 10, 17)
            val bounds = UtcBounds(
                date.atStartOfDay(losAngeles).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(losAngeles).toInstant().toEpochMilli(),
            )
            TimeZone.setDefault(TimeZone.getTimeZone(losAngeles))
            val expected = "open-timemanager-2026-10-17_2026-10-17.csv"
            assertEquals(expected, csv.fileName(bounds))

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(expected, csv.fileName(bounds, zoneId = losAngeles))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun coordinator() = CsvExportCoordinator(database, documents, Dispatchers.IO, cacheDirectory)

    private suspend fun seedEntries(count: Int, note: String = "") = withContext(Dispatchers.IO) {
        database.withTransaction {
            val dao = database.trackingDao()
            repeat(count) { index ->
                val start = index * 1_000L
                dao.insertEntry(EntryEntity(
                    "entry-$index", ProjectRepository.DEFAULT_PROJECT_ID.value, note,
                    start, start + 500, "MANUAL", 0, true,
                    confirmedDurationMillis = 500, lastConfirmedEndUtcMillis = start + 500,
                ))
                dao.insertSegment(SegmentEntity("segment-$index", "entry-$index", start, null, null, 500, start + 500))
            }
        }
    }

    private fun assertNoSpool() {
        assertEquals(emptyList<String>(), cacheDirectory.listFiles()?.map(File::getName))
    }
}

private class CsvDocuments(private val assertOutsideTransaction: () -> Unit) : DocumentStore {
    var bytes = ByteArray(0)
    var opens = 0
    var failWrite = false
    var writeBarrier: WriteBarrier? = null
    val deleted = mutableListOf<Uri>()

    override fun openInput(uri: Uri, cancellationSignal: CancellationSignal): InputStream? = null

    override fun openOutput(uri: Uri, cancellationSignal: CancellationSignal): OutputStream {
        assertOutsideTransaction()
        opens++
        val barrier = writeBarrier
        return object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                assertOutsideTransaction()
                barrier?.let {
                    it.started.countDown()
                    check(it.release.await(10, TimeUnit.SECONDS))
                    if (it.closed.get()) throw IOException("closed")
                }
                if (failWrite) throw IOException("write failed")
                super.write(buffer, offset, length)
            }

            override fun close() {
                barrier?.closed?.set(true)
                barrier?.release?.countDown()
                bytes = toByteArray()
                super.close()
            }
        }
    }

    override fun delete(uri: Uri): Boolean {
        assertOutsideTransaction()
        deleted += uri
        bytes = ByteArray(0)
        return true
    }
}

private class WriteBarrier {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closed = AtomicBoolean(false)
}
