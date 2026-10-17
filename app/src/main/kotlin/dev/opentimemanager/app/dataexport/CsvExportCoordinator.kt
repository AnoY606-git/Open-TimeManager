package dev.opentimemanager.app.dataexport

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.ui.UtcBounds
import dev.opentimemanager.core.export.CsvExporter
import dev.opentimemanager.core.export.ExportRecord
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.EntrySource
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Единственная подменяемая граница в tests: внешний document provider. */
interface DocumentStore {
    fun openInput(uri: Uri, cancellationSignal: CancellationSignal): InputStream?
    fun openOutput(uri: Uri, cancellationSignal: CancellationSignal): OutputStream?
    fun delete(uri: Uri): Boolean
}

class ResolverDocumentStore(context: Context) : DocumentStore {
    private val applicationContext = context.applicationContext
    private val resolver: ContentResolver = applicationContext.contentResolver

    override fun openInput(uri: Uri, cancellationSignal: CancellationSignal): InputStream? =
        resolver.openFileDescriptor(uri, "r", cancellationSignal)?.let(ParcelFileDescriptor::AutoCloseInputStream)

    override fun openOutput(uri: Uri, cancellationSignal: CancellationSignal): OutputStream? =
        resolver.openFileDescriptor(uri, "wt", cancellationSignal)?.let(ParcelFileDescriptor::AutoCloseOutputStream)
    override fun delete(uri: Uri): Boolean = when {
        DocumentsContract.isDocumentUri(applicationContext, uri) -> DocumentsContract.deleteDocument(resolver, uri)
        uri.scheme == ContentResolver.SCHEME_CONTENT -> resolver.delete(uri, null, null) > 0
        else -> false
    }
}

sealed interface CsvExportResult {
    data object Success : CsvExportResult
    data object Cancelled : CsvExportResult
    data class Failed(val partialDocumentDeleted: Boolean) : CsvExportResult
}

class CsvExportCoordinator(
    private val database: AppDatabase,
    private val documents: DocumentStore,
    private val dispatcher: CoroutineDispatcher,
    private val cacheDirectory: File,
    private val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
    private val projectDisplayNameForSnapshot: () -> ((projectId: String, storedName: String) -> String) = {
        { _, storedName -> storedName }
    },
) {
    /** Explicit fixed-zone callers retain their range's zone. Android's tmpdir is its private cache. */
    constructor(
        database: AppDatabase,
        documents: DocumentStore,
        dispatcher: CoroutineDispatcher,
        zoneId: ZoneId,
        projectDisplayName: (projectId: String, storedName: String) -> String = { _, storedName -> storedName },
    ) : this(
        database,
        documents,
        dispatcher,
        File(checkNotNull(System.getProperty("java.io.tmpdir")) { "Temporary directory is not configured" }),
        zoneIdProvider = { zoneId },
        projectDisplayNameForSnapshot = { projectDisplayName },
    )

    suspend fun exportCsv(
        range: UtcBounds,
        uri: Uri?,
        newlyCreated: Boolean,
    ): CsvExportResult {
        if (uri == null) return CsvExportResult.Cancelled
        return try {
            require(range.fromUtcMillis < range.toUtcMillis) { "Invalid CSV range" }
            withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                val displayName = projectDisplayNameForSnapshot()
                CsvSnapshotFile(cacheDirectory).use { snapshot ->
                    FileChannel.open(
                        snapshot.path,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.DELETE_ON_CLOSE,
                    ).use { channel ->
                        // A Cursor alone is not a snapshot across CursorWindow refills. Only local,
                        // bounded-buffer I/O runs under this transaction; no provider is called here.
                        writeSnapshot(range, channel, displayName)
                        currentCoroutineContext().ensureActive()
                        channel.position(0)
                        copySnapshot(channel, uri)
                    }
                }
                CsvExportResult.Success
            }
        } catch (cancelled: CancellationException) {
            failed(uri, newlyCreated)
            throw cancelled
        } catch (_: Exception) {
            failed(uri, newlyCreated)
        }
    }

    private suspend fun writeSnapshot(
        range: UtcBounds,
        channel: FileChannel,
        displayName: (String, String) -> String,
    ) = database.withTransaction {
        val job = currentCoroutineContext()[Job]
        cancellableIo(closeOnCancel = { channel.close() }) { signal ->
            // The channel belongs to exportCsv and must stay open for the subsequent copy.
            val writer = BufferedWriter(OutputStreamWriter(Channels.newOutputStream(channel), Charsets.UTF_8), IO_CHUNK_SIZE)
            database.openHelper.readableDatabase.query(
                SimpleSQLiteQuery(
                    EXPORT_SQL,
                    arrayOf(
                        range.fromUtcMillis,
                        range.toUtcMillis,
                        range.fromUtcMillis,
                        range.toUtcMillis,
                        range.fromUtcMillis,
                    ),
                ),
                signal,
            ).use { cursor ->
                val records = sequence {
                    while (cursor.moveToNext()) {
                        job?.ensureActive()
                        yield(
                            ExportRecord(
                                segment = Segment(
                                    id = SegmentId(cursor.getString(0)),
                                    entryId = EntryId(cursor.getString(1)),
                                    projectId = ProjectId(cursor.getString(2)),
                                    startUtcMillis = cursor.getLong(4),
                                    durationMillis = cursor.getLong(5),
                                ),
                                projectName = displayName(cursor.getString(2), cursor.getString(3)),
                                note = cursor.getString(6),
                                zoneId = ZoneId.of(cursor.getString(7)),
                                source = EntrySource.valueOf(cursor.getString(8)),
                            ),
                        )
                    }
                }
                CsvExporter.write(records, writer)
                job?.ensureActive()
                writer.flush()
            }
        }
    }

    private suspend fun copySnapshot(channel: FileChannel, uri: Uri) {
        val output = cancellableIo(discardOnCancellation = { stream -> stream?.close() }) { signal ->
            documents.openOutput(uri, signal)
        } ?: throw IOException("Document provider returned no output stream")
        output.use { stream ->
            val job = currentCoroutineContext()[Job]
            cancellableIo(closeOnCancel = {
                try { channel.close() } finally { stream.close() }
            }) {
                val input = Channels.newInputStream(channel)
                val buffer = ByteArray(IO_CHUNK_SIZE)
                while (true) {
                    job?.ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    stream.write(buffer, 0, count)
                }
                job?.ensureActive()
                stream.flush()
            }
        }
    }

    fun fileName(range: UtcBounds, zoneId: ZoneId = zoneIdProvider()): String {
        require(range.fromUtcMillis < range.toUtcMillis) { "Invalid CSV range" }
        val from = Instant.ofEpochMilli(range.fromUtcMillis).atZone(zoneId).toLocalDate()
        val through = Instant.ofEpochMilli(Math.subtractExact(range.toUtcMillis, 1L)).atZone(zoneId).toLocalDate()
        return "open-timemanager-${DATE.format(from)}_${DATE.format(through)}.csv"
    }

    suspend fun discardCreatedDocument(uri: Uri?): CsvExportResult =
        if (uri == null) CsvExportResult.Cancelled else failed(uri, newlyCreated = true)

    private suspend fun failed(uri: Uri, newlyCreated: Boolean): CsvExportResult.Failed =
        withContext(NonCancellable + dispatcher) {
            val deleted = if (newlyCreated) {
                try {
                    documents.delete(uri)
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
            CsvExportResult.Failed(deleted)
        }

    companion object {
        const val MIME_TYPE = "text/csv"
        private const val IO_CHUNK_SIZE = 32 * 1024
        private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
        private const val EXPORT_SQL = """
            SELECT segments.id, segments.entryId, entries.projectId, projects.name,
                   MAX(segments.startedAtUtcMillis, ?) AS clippedStartUtcMillis,
                   MIN(segments.endUtcMillis, ?) - MAX(segments.startedAtUtcMillis, ?) AS clippedDurationMillis,
                   entries.note,
                   entries.zoneId, entries.source
            FROM segments
            JOIN entries ON entries.id = segments.entryId
            JOIN projects ON projects.id = entries.projectId
            WHERE segments.durationMillis IS NOT NULL
              AND segments.startedAtUtcMillis < ?
              AND segments.endUtcMillis > ?
            ORDER BY clippedStartUtcMillis, segments.id
        """
    }
}

/** DELETE_ON_CLOSE unlinks an open spool on Android, including before slow external I/O. */
private class CsvSnapshotFile(directory: File) : Closeable {
    val path = Files.createTempFile(directory.toPath(), "otm-csv-", ".tmp")
    override fun close() {
        // Also handles channel-open failures and platforms that defer DELETE_ON_CLOSE until close.
        Files.deleteIfExists(path)
    }
}
