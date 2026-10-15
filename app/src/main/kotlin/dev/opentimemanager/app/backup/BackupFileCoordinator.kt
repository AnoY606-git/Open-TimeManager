package dev.opentimemanager.app.backup

import android.net.Uri
import androidx.room.withTransaction
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.dataexport.DocumentStore
import dev.opentimemanager.app.dataexport.cancellableIo
import dev.opentimemanager.core.backup.BackupCodec
import dev.opentimemanager.core.backup.BackupError
import dev.opentimemanager.core.backup.BackupLimits
import dev.opentimemanager.core.backup.PortableActiveEntry
import dev.opentimemanager.core.backup.PortableEntry
import dev.opentimemanager.core.backup.PortableProject
import dev.opentimemanager.core.backup.PortableSnapshot
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

class PreparedBackup internal constructor(
    val fileName: String,
    encrypted: ByteArray,
) {
    private var bytes: ByteArray? = encrypted

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

enum class BackupReadFailure { INTEGRITY, LIMIT, UNSUPPORTED_VERSION, INVALID_FORMAT, IO }

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
) {
    suspend fun prepareBackup(password: CharArray): PreparedBackup {
        var prepared: PreparedBackup? = null
        try {
            return withContext(dispatcher) {
                val snapshot = database.withTransaction { portableSnapshot() }
                val encrypted = BackupCodec.encrypt(snapshot, password, random, limits)
                PreparedBackup(
                    fileName = "open-timemanager-${BACKUP_DATE.format(clock.instant().atZone(ZoneOffset.UTC))}.otmbackup",
                    encrypted = encrypted,
                ).also { prepared = it }
            }
        } catch (cancelled: CancellationException) {
            // withContext may discard a completed result when its caller was
            // cancelled while encryption ran; clear that orphaned ciphertext.
            prepared?.clear()
            throw cancelled
        } finally {
            password.fill('\u0000')
        }
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

    suspend fun inspectBackup(uri: Uri?, password: CharArray): BackupInspection {
        try {
            return withContext(dispatcher) {
                if (uri == null) return@withContext BackupInspection.Cancelled
                val input = try {
                    cancellableIo(discardOnCancellation = { stream -> stream?.close() }) { signal ->
                        documents.openInput(uri, signal)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withContext BackupInspection.Failed(BackupReadFailure.IO)
                } ?: return@withContext BackupInspection.Failed(BackupReadFailure.IO)
                val bytes = try {
                    input.use { stream ->
                        val job = kotlin.coroutines.coroutineContext[Job]
                        cancellableIo(closeOnCancel = { stream.close() }) {
                            readBounded(stream, limits.maxBytes, job)
                        }
                    }
                } catch (_: ReadLimitExceeded) {
                    return@withContext BackupInspection.Failed(BackupReadFailure.LIMIT)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withContext BackupInspection.Failed(BackupReadFailure.IO)
                }
                try {
                    val snapshot = BackupCodec.decrypt(bytes, password, limits)
                    operationalLimits.requireWithin(snapshot)
                    BackupInspection.Ready(restoreRepository.preview(snapshot))
                } catch (_: BackupError.IntegrityError) {
                    BackupInspection.Failed(BackupReadFailure.INTEGRITY)
                } catch (_: BackupError.LimitError) {
                    BackupInspection.Failed(BackupReadFailure.LIMIT)
                } catch (_: BackupError.VersionError) {
                    BackupInspection.Failed(BackupReadFailure.UNSUPPORTED_VERSION)
                } catch (_: BackupError.MagicError) {
                    BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT)
                } catch (_: BackupError.SchemaError) {
                    BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT)
                } catch (_: RestoreValidationError) {
                    BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT)
                } finally {
                    bytes.fill(0)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun discardCreatedDocument(uri: Uri?): BackupWriteResult =
        if (uri == null) BackupWriteResult.Cancelled else failedWrite(null, uri, newlyCreated = true)

    private fun portableSnapshot(): PortableSnapshot {
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
        val projects = dao.projects().map { PortableProject(ProjectId(it.id), it.name) }
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
        return PortableSnapshot(projects, entries, segments, active)
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
        documents.delete(uri)
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

private fun readBounded(input: java.io.InputStream, maxBytes: Int, job: Job?): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        job?.ensureActive()
        val read = input.read(buffer)
        if (read == -1) break
        total = try {
            Math.addExact(total, read)
        } catch (_: ArithmeticException) {
            throw ReadLimitExceeded()
        }
        if (total > maxBytes) throw ReadLimitExceeded()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
