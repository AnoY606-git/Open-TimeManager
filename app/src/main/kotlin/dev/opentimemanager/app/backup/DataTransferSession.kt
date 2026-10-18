package dev.opentimemanager.app.backup

import android.net.Uri
import dev.opentimemanager.app.dataexport.CsvExportCoordinator
import dev.opentimemanager.app.dataexport.CsvExportResult
import dev.opentimemanager.app.ui.UtcBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * ViewModel-scoped owner of state that must outlive an Activity Result launcher.
 * A callback delivered after process recreation has no pending payload, so its
 * newly-created document is removed instead of being left empty.
 */
class DataTransferSession(
    private val csv: CsvExportCoordinator,
    private val backups: BackupFileCoordinator,
) {
    private val lock = Any()
    private var pendingCsv: UtcBounds? = null
    private var pendingBackup: PreparedBackup? = null
    private var backupRequestVersion = 0L
    private var preview: BackupPreview? = null

    fun beginCsv(range: UtcBounds): String = synchronized(lock) {
        pendingCsv = range
        csv.fileName(range)
    }

    fun cancelCsv() {
        synchronized(lock) { pendingCsv = null }
    }

    suspend fun finishCsv(uri: Uri?): CsvExportResult {
        val range = synchronized(lock) { pendingCsv.also { pendingCsv = null } }
        return if (range == null) csv.discardCreatedDocument(uri)
        else csv.exportCsv(range, uri, newlyCreated = true)
    }

    suspend fun beginBackup(password: CharArray): String = beginBackupPreparation {
        backups.prepareBackup(password)
    }

    /** Explicit opt-in; never selected by beginBackup or after a password error. */
    suspend fun beginPlainBackup(): String = beginBackupPreparation {
        backups.preparePlainBackup()
    }

    private suspend fun beginBackupPreparation(prepare: suspend () -> PreparedBackup): String {
        val request = synchronized(lock) {
            // A failed newer request must not leave an older (possibly plaintext)
            // payload available to a delayed document callback.
            pendingBackup?.clear()
            pendingBackup = null
            ++backupRequestVersion
        }
        var prepared: PreparedBackup? = null
        try {
            val value = prepare().also { prepared = it }
            val context = currentCoroutineContext()
            synchronized(lock) {
                context.ensureActive()
                if (request != backupRequestVersion) {
                    throw CancellationException("Backup preparation is no longer current")
                }
                pendingBackup?.clear()
                pendingBackup = value
            }
            return value.fileName
        } catch (error: Throwable) {
            synchronized(lock) {
                if (prepared != null && pendingBackup === prepared) pendingBackup = null
                prepared?.clear()
            }
            throw error
        }
    }

    fun cancelBackup() {
        synchronized(lock) {
            ++backupRequestVersion
            pendingBackup?.clear()
            pendingBackup = null
        }
    }

    suspend fun finishBackup(uri: Uri?): BackupWriteResult {
        val prepared = synchronized(lock) {
            ++backupRequestVersion
            pendingBackup.also { pendingBackup = null }
        }
        return if (prepared == null) backups.discardCreatedDocument(uri)
        else backups.writeBackup(prepared, uri, newlyCreated = true)
    }

    fun retainPreview(value: BackupPreview) {
        synchronized(lock) {
            if (preview !== value) preview?.discard()
            preview = value
        }
    }

    fun currentPreview(): BackupPreview? = synchronized(lock) { preview }

    fun onRestoreResult(expected: BackupPreview, result: RestoreResult) {
        synchronized(lock) {
            if (result is RestoreResult.Applied && preview == null) return
            check(preview === expected) { "Backup preview is no longer current" }
            if (result is RestoreResult.Applied) preview = null
        }
    }

    fun discardPreview() {
        synchronized(lock) {
            preview?.discard()
            preview = null
        }
    }

    fun clear() {
        synchronized(lock) {
            pendingCsv = null
            ++backupRequestVersion
            pendingBackup?.clear()
            pendingBackup = null
            preview?.discard()
            preview = null
        }
    }
}
