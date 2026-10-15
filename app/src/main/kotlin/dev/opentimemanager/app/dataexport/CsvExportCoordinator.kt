package dev.opentimemanager.app.dataexport

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
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
    private val zoneId: ZoneId,
    private val projectDisplayName: (projectId: String, storedName: String) -> String = { _, storedName -> storedName },
) {
    suspend fun exportCsv(
        range: UtcBounds,
        uri: Uri?,
        newlyCreated: Boolean,
    ): CsvExportResult {
        if (uri == null) return CsvExportResult.Cancelled
        return try {
            require(range.fromUtcMillis < range.toUtcMillis) { "Invalid CSV range" }
            withContext(dispatcher) {
                val output = cancellableIo(discardOnCancellation = { stream -> stream?.close() }) { signal ->
                    documents.openOutput(uri, signal)
                }
                    ?: throw IOException("Document provider returned no output stream")
                BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                    val job = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                    cancellableIo(closeOnCancel = { output.close() }) { signal ->
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
                                    val projectId = ProjectId(cursor.getString(2))
                                    yield(
                                        ExportRecord(
                                            segment = Segment(
                                                id = SegmentId(cursor.getString(0)),
                                                entryId = EntryId(cursor.getString(1)),
                                                projectId = projectId,
                                                startUtcMillis = cursor.getLong(4),
                                                durationMillis = cursor.getLong(5),
                                            ),
                                            projectName = projectDisplayName(cursor.getString(2), cursor.getString(3)),
                                            note = cursor.getString(6),
                                            zoneId = ZoneId.of(cursor.getString(7)),
                                            source = EntrySource.valueOf(cursor.getString(8)),
                                        ),
                                    )
                                }
                            }
                            CsvExporter.write(records, writer)
                        }
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

    fun fileName(range: UtcBounds): String {
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
