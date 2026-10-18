package dev.opentimemanager.app.backup

import android.net.Uri
import androidx.room.withTransaction
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.dataexport.DocumentStore
import dev.opentimemanager.app.dataexport.cancellableIo
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserPreferences
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.core.backup.DEFAULT_PROJECT_CANONICAL_NAME
import dev.opentimemanager.core.backup.BackupCodec
import dev.opentimemanager.core.backup.BackupError
import dev.opentimemanager.core.backup.BackupFormat
import dev.opentimemanager.core.backup.BackupLimits
import dev.opentimemanager.core.backup.PlainBackupCodec
import dev.opentimemanager.core.backup.PortableActiveEntry
import dev.opentimemanager.core.backup.PortableEntry
import dev.opentimemanager.core.backup.PortableProject
import dev.opentimemanager.core.backup.PortablePreferences
import dev.opentimemanager.core.backup.PortableSnapshot
import dev.opentimemanager.core.backup.PortableTheme
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.EntrySource
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingState
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

class PreparedBackup internal constructor(
    val fileName: String,
    content: ByteArray,
    val format: BackupFormat = BackupFormat.ENCRYPTED,
) {
    private var bytes: ByteArray? = content

    val isCleared: Boolean
        @Synchronized get() = bytes == null

    @Synchronized
    internal fun writeAndClear(write: (ByteArray) -> Unit) {
        val value = bytes ?: throw IllegalStateException("Prepared backup was already consumed")
        try {
            write(value)
        } finally {
            value.fill(0)
            bytes = null
        }
    }

    @Synchronized
    fun clear() {
        bytes?.fill(0)
        bytes = null
    }
}

sealed interface BackupWriteResult {
    data object Success : BackupWriteResult
    data object Cancelled : BackupWriteResult
    data class Failed(val partialDocumentDeleted: Boolean) : BackupWriteResult
}

enum class BackupReadFailure {
    INTEGRITY, PLAIN_INTEGRITY, PASSWORD_REQUIRED, LIMIT, UNSUPPORTED_VERSION, INVALID_FORMAT, IO,
}

/** Detection is not authentication; a subsequent inspection revalidates the bytes. */
sealed interface BackupFormatResult {
    data object Cancelled : BackupFormatResult
    data class Detected(val format: BackupFormat) : BackupFormatResult
    data class Failed(val reason: BackupReadFailure) : BackupFormatResult
}

sealed interface BackupInspection {
    data object Cancelled : BackupInspection
    data class Ready(val preview: BackupPreview) : BackupInspection
    data class Failed(val reason: BackupReadFailure) : BackupInspection
}

class BackupFileCoordinator(
    private val database: AppDatabase,
    private val documents: DocumentStore,
    private val restoreRepository: RestoreRepository,
    private val dispatcher: CoroutineDispatcher,
    private val limits: BackupLimits = MobileBackupPolicy.codecLimits,
    private val operationalLimits: BackupOperationalLimits = MobileBackupPolicy.operationalLimits,
    private val random: SecureRandom = SecureRandom(),
    private val clock: Clock = Clock.systemUTC(),
    private val settings: UserSettings? = null,
) {
    suspend fun prepareBackup(password: CharArray): PreparedBackup {
        try {
            BackupCreationPasswordPolicy.requireValid(password)
            return prepare(BackupFormat.ENCRYPTED) { snapshot ->
                BackupCodec.encrypt(snapshot, password, random, limits)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** Opt-in only: the file exposes all projects, notes and portable settings, without authentication. */
    suspend fun preparePlainBackup(): PreparedBackup = prepare(BackupFormat.PLAINTEXT) { snapshot ->
        PlainBackupCodec.encode(snapshot, limits)
    }

    private suspend fun prepare(
        format: BackupFormat,
        encode: (PortableSnapshot) -> ByteArray,
    ): PreparedBackup {
        var prepared: PreparedBackup? = null
        try {
            val snapshot = captureSnapshot()
            return withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                val content = encode(snapshot)
                try {
                    val extension = if (format == BackupFormat.ENCRYPTED) "otmbackup" else "otmplain"
                    PreparedBackup(
                        fileName = "open-timemanager-${BACKUP_DATE.format(clock.instant().atZone(ZoneOffset.UTC))}.$extension",
                        content = content,
                        format = format,
                    ).also {
                        prepared = it
                        currentCoroutineContext().ensureActive()
                    }
                } catch (error: Throwable) {
                    content.fill(0)
                    throw error
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext may discard a completed result when its caller was
            // cancelled while encoding ran; clear that orphaned container.
            prepared?.clear()
            throw cancelled
        }
    }

    private suspend fun captureSnapshot(): PortableSnapshot =
        settings?.withStrictExportSnapshot { preferences ->
            withContext(dispatcher) {
                database.withTransaction { portableSnapshot(preferences.toPortable()) }
            }
        } ?: withContext(dispatcher) {
            database.withTransaction { portableSnapshot(PortablePreferences()) }
        }

    suspend fun writeBackup(
        prepared: PreparedBackup,
        uri: Uri?,
        newlyCreated: Boolean,
    ): BackupWriteResult {
        if (uri == null) {
            prepared.clear()
            return BackupWriteResult.Cancelled
        }
        if (!uri.isContentDocument()) {
            prepared.clear()
            return BackupWriteResult.Failed(partialDocumentDeleted = false)
        }
        return try {
            withContext(dispatcher) {
                val output = cancellableIo(discardOnCancellation = { stream -> stream?.close() }) { signal ->
                    documents.openOutput(uri, signal)
                }
                    ?: throw IOException("Document provider returned no output stream")
                output.use { stream ->
                    val job = kotlin.coroutines.coroutineContext[Job]
                    cancellableIo(closeOnCancel = { stream.close() }) {
                        prepared.writeAndClear { bytes ->
                            var offset = 0
                            while (offset < bytes.size) {
                                job?.ensureActive()
                                val count = minOf(IO_CHUNK_SIZE, bytes.size - offset)
                                stream.write(bytes, offset, count)
                                offset += count
                            }
                            job?.ensureActive()
                            stream.flush()
                        }
                    }
                }
                BackupWriteResult.Success
            }
        } catch (cancelled: CancellationException) {
            failedWrite(prepared, uri, newlyCreated)
            throw cancelled
        } catch (_: Exception) {
            failedWrite(prepared, uri, newlyCreated)
        }
    }

    /** Bounded, cancellable SAF read. No filename/extension guessing or decryption fallback. */
    suspend fun backupFormat(uri: Uri?): BackupFormatResult = withContext(dispatcher) {
        if (uri == null) return@withContext BackupFormatResult.Cancelled
        try {
            val bytes = readBackupBytes(uri)
            try {
                val format = BackupCodec.detectFormat(bytes, limits)
                currentCoroutineContext().ensureActive()
                BackupFormatResult.Detected(format)
            } finally {
                bytes.fill(0)
            }
        } catch (error: BackupReadException) {
            BackupFormatResult.Failed(error.reason)
        } catch (error: BackupError) {
            BackupFormatResult.Failed(error.readFailure())
        }
    }

    /** Plaintext needs no password. Encrypted input returns PASSWORD_REQUIRED, not a downgrade. */
    suspend fun inspectBackup(uri: Uri?, expectedFormat: BackupFormat? = null): BackupInspection =
        inspect(uri, password = null, expectedFormat = expectedFormat)

    /** Takes ownership of password and clears it on every exit, including plaintext input. */
    suspend fun inspectBackup(
        uri: Uri?,
        password: CharArray,
        expectedFormat: BackupFormat? = null,
    ): BackupInspection {
        try {
            return inspect(uri, password, expectedFormat)
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun inspect(
        uri: Uri?,
        password: CharArray?,
        expectedFormat: BackupFormat?,
    ): BackupInspection {
        var preview: BackupPreview? = null
        try {
            return withContext(dispatcher) {
                if (uri == null) return@withContext BackupInspection.Cancelled
                val bytes = try {
                    readBackupBytes(uri)
                } catch (error: BackupReadException) {
                    return@withContext BackupInspection.Failed(error.reason)
                }
                try {
                    val format = BackupCodec.detectFormat(bytes, limits)
                    if (expectedFormat != null && format != expectedFormat) {
                        return@withContext BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT)
                    }
                    val snapshot = when (format) {
                        BackupFormat.ENCRYPTED -> {
                            if (password == null) return@withContext BackupInspection.Failed(BackupReadFailure.PASSWORD_REQUIRED)
                            BackupCodec.decrypt(bytes, password, limits)
                        }
                        BackupFormat.PLAINTEXT -> PlainBackupCodec.decode(bytes, limits)
                    }
                    currentCoroutineContext().ensureActive()
                    operationalLimits.requireWithin(snapshot)
                    restoreRepository.preview(snapshot, format).let {
                        preview = it
                        currentCoroutineContext().ensureActive()
                        BackupInspection.Ready(it)
                    }
                } catch (error: BackupError) {
                    BackupInspection.Failed(error.readFailure())
                } catch (_: RestoreValidationError) {
                    BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT)
                } finally {
                    bytes.fill(0)
                }
            }
        } catch (cancelled: CancellationException) {
            preview?.discard()
            throw cancelled
        }
    }

    private suspend fun readBackupBytes(uri: Uri): ByteArray {
        if (!uri.isContentDocument()) throw BackupReadException(BackupReadFailure.INVALID_FORMAT)
        var bytes: ByteArray? = null
        try {
            val input = cancellableIo(discardOnCancellation = { stream -> stream?.close() }) { signal ->
                documents.openInput(uri, signal)
            } ?: throw IOException("Document provider returned no input stream")
            input.use { stream ->
                val job = currentCoroutineContext()[Job]
                bytes = cancellableIo(
                    closeOnCancel = { stream.close() },
                    discardOnCancellation = { value -> value.fill(0) },
                ) {
                    readBounded(stream, limits.maxBytes, job)
                }
            }
            currentCoroutineContext().ensureActive()
            return checkNotNull(bytes).also { bytes = null }
        } catch (_: ReadLimitExceeded) {
            throw BackupReadException(BackupReadFailure.LIMIT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw BackupReadException(BackupReadFailure.IO)
        } finally {
            bytes?.fill(0)
        }
    }

    suspend fun discardCreatedDocument(uri: Uri?): BackupWriteResult =
        if (uri == null) BackupWriteResult.Cancelled else failedWrite(null, uri, newlyCreated = true)

    private fun portableSnapshot(preferences: PortablePreferences): PortableSnapshot {
        val dao = database.trackingDao()
        val userTextBytes = try {
            Math.addExact(dao.projectTextBytes(), dao.entryTextBytes())
        } catch (_: ArithmeticException) {
            throw BackupError.LimitError()
        }
        operationalLimits.requireWithin(
            projects = dao.projectCount(),
            entries = dao.entryCount(),
            segments = dao.closedSegmentCount(),
            userTextBytes = userTextBytes,
        )
        val projects = dao.projects().map {
            val isDefault = it.id == ProjectRepository.DEFAULT_PROJECT_ID.value
            PortableProject(
                id = ProjectId(it.id),
                name = if (isDefault) DEFAULT_PROJECT_CANONICAL_NAME else it.name,
                colorArgb = it.colorArgb,
                createdAtUtcMillis = it.createdAtUtcMillis,
                archivedAtUtcMillis = if (isDefault) null else it.archivedAtUtcMillis,
                isDefault = isDefault,
            )
        }
        val entries = dao.entries().map {
            PortableEntry(
                id = EntryId(it.id),
                projectId = ProjectId(it.projectId),
                note = it.note,
                zoneId = java.time.ZoneId.of(it.zoneId),
                source = EntrySource.valueOf(it.source),
            )
        }
        val entryProjects = entries.associate { it.id to it.projectId }
        val segments = dao.segments().filter { it.durationMillis != null }.map {
            Segment(
                id = SegmentId(it.id),
                entryId = EntryId(it.entryId),
                projectId = requireNotNull(entryProjects[EntryId(it.entryId)]),
                startUtcMillis = it.startedAtUtcMillis,
                durationMillis = requireNotNull(it.durationMillis),
            )
        }
        val slot = dao.slot()
        val active = slot?.takeIf { it.state != TrackingState.IDLE.name }?.let {
            val entry = requireNotNull(dao.entry(requireNotNull(it.entryId)))
            PortableActiveEntry(EntryId(entry.id), ProjectId(entry.projectId), TrackingState.valueOf(it.state))
        }
        return PortableSnapshot(projects, entries, segments, active, preferences)
    }

    private suspend fun failedWrite(
        prepared: PreparedBackup?,
        uri: Uri,
        newlyCreated: Boolean,
    ): BackupWriteResult.Failed = withContext(NonCancellable + dispatcher) {
        prepared?.clear()
        val deleted = if (newlyCreated) {
            deleteCreatedDocument(uri)
        } else {
            false
        }
        BackupWriteResult.Failed(deleted)
    }

    private fun deleteCreatedDocument(uri: Uri): Boolean = try {
        uri.isContentDocument() && documents.delete(uri)
    } catch (_: Exception) {
        false
    }

    companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val APP_MAX_BYTES = MobileBackupPolicy.MAX_CONTAINER_BYTES
        const val APP_MAX_SEGMENTS = MobileBackupPolicy.MAX_SEGMENTS
        private const val IO_CHUNK_SIZE = 32 * 1024
        val APP_LIMITS = MobileBackupPolicy.codecLimits
        private val BACKUP_DATE = DateTimeFormatter.ISO_LOCAL_DATE
    }
}

private fun UserPreferences.toPortable() = PortablePreferences(
    theme = when (theme) {
        ThemeMode.SYSTEM -> PortableTheme.SYSTEM
        ThemeMode.LIGHT -> PortableTheme.LIGHT
        ThemeMode.DARK -> PortableTheme.DARK
    },
    dynamicColor = dynamicColor,
    privacyMode = privacyMode,
    dailyGoalMinutes = dailyGoalMinutes,
)

data class BackupOperationalLimits(
    val maxProjects: Long = MobileBackupPolicy.MAX_PROJECTS,
    val maxEntries: Long = MobileBackupPolicy.MAX_ENTRIES,
    val maxSegments: Long = MobileBackupPolicy.MAX_SEGMENTS.toLong(),
    val maxUserTextBytes: Long = MobileBackupPolicy.MAX_USER_TEXT_BYTES,
) {
    init {
        require(maxProjects >= 0 && maxEntries >= 0 && maxSegments >= 0 && maxUserTextBytes >= 0)
    }

    internal fun requireWithin(snapshot: PortableSnapshot) {
        var textBytes = 0L
        snapshot.projects.forEach { project ->
            textBytes = addTextBytes(textBytes, project.name)
        }
        snapshot.entries.forEach { entry ->
            textBytes = addTextBytes(textBytes, entry.note)
        }
        requireWithin(
            projects = snapshot.projects.size.toLong(),
            entries = snapshot.entries.size.toLong(),
            segments = snapshot.segments.size.toLong(),
            userTextBytes = textBytes,
        )
    }

    internal fun requireWithin(
        projects: Long,
        entries: Long,
        segments: Long,
        userTextBytes: Long,
    ) {
        if (
            projects > maxProjects ||
            entries > maxEntries ||
            segments > maxSegments ||
            userTextBytes > maxUserTextBytes
        ) {
            throw BackupError.LimitError()
        }
    }

    private fun addTextBytes(total: Long, value: String): Long = try {
        Math.addExact(total, value.utf8Bytes())
    } catch (_: ArithmeticException) {
        throw BackupError.LimitError()
    }
}

internal object MobileBackupPolicy {
    const val MAX_CONTAINER_BYTES = 4 * 1024 * 1024
    const val MAX_PROJECTS = 20_000L
    const val MAX_ENTRIES = 20_000L
    const val MAX_SEGMENTS = 20_000
    const val MAX_USER_TEXT_BYTES = 3L * 1024 * 1024

    val codecLimits = BackupLimits(maxBytes = MAX_CONTAINER_BYTES, maxSegments = MAX_SEGMENTS)
    val operationalLimits = BackupOperationalLimits(
        maxProjects = MAX_PROJECTS,
        maxEntries = MAX_ENTRIES,
        maxSegments = MAX_SEGMENTS.toLong(),
        maxUserTextBytes = MAX_USER_TEXT_BYTES,
    )
}

/** Exact UTF-8 encoded size without allocating one temporary byte array per imported string. */
private fun String.utf8Bytes(): Long {
    var bytes = 0L
    var index = 0
    while (index < length) {
        val character = this[index]
        bytes += when {
            character.code <= 0x7f -> 1
            character.code <= 0x7ff -> 2
            Character.isHighSurrogate(character) &&
                index + 1 < length &&
                Character.isLowSurrogate(this[index + 1]) -> {
                index++
                4
            }
            Character.isSurrogate(character) -> 1 // Matches the JDK UTF-8 encoder's '?' replacement.
            else -> 3
        }
        index++
    }
    return bytes
}

private class ReadLimitExceeded : Exception()

private class BackupReadException(val reason: BackupReadFailure) : Exception()

private fun BackupError.readFailure(): BackupReadFailure = when (this) {
    is BackupError.IntegrityError -> BackupReadFailure.INTEGRITY
    is BackupError.PlainIntegrityError -> BackupReadFailure.PLAIN_INTEGRITY
    is BackupError.LimitError -> BackupReadFailure.LIMIT
    is BackupError.VersionError -> BackupReadFailure.UNSUPPORTED_VERSION
    is BackupError.MagicError, is BackupError.SchemaError -> BackupReadFailure.INVALID_FORMAT
}

private fun Uri.isContentDocument(): Boolean = scheme == "content" && !authority.isNullOrBlank()

/** The staging buffer can now hold readable private data, so clear it even on I/O failure. */
private class BackupReadBuffer(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    override fun close() {
        buf.fill(0)
        reset()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        // ByteArrayOutputStream growth otherwise leaves an uncleared old backing array.
        val previous = buf
        super.write(bytes, offset, length)
        if (buf !== previous) previous.fill(0)
    }
}

private fun readBounded(input: java.io.InputStream, maxBytes: Int, job: Job?): ByteArray =
    BackupReadBuffer(minOf(maxBytes, 16 * 1024)).use { output ->
        val buffer = ByteArray(8 * 1024)
        try {
            var total = 0
            while (true) {
                job?.ensureActive()
                // Read no more than one byte past the limit, including on small custom limits.
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
                if (read == -1) break
                val count = if (read == 0) {
                    // A broken provider returning zero must not cause an unbounded busy loop.
                    val byte = input.read()
                    if (byte == -1) break
                    buffer[0] = byte.toByte()
                    1
                } else read
                total = try {
                    Math.addExact(total, count)
                } catch (_: ArithmeticException) {
                    throw ReadLimitExceeded()
                }
                if (total > maxBytes) throw ReadLimitExceeded()
                output.write(buffer, 0, count)
            }
            job?.ensureActive()
            output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }
