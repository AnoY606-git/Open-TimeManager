package dev.opentimemanager.app.backup

import android.net.Uri
import dev.opentimemanager.app.dataexport.CsvExportCoordinator
import dev.opentimemanager.app.dataexport.CsvExportResult
import dev.opentimemanager.app.ui.UtcBounds

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

    suspend fun beginBackup(password: CharArray): String {
        val prepared = backups.prepareBackup(password)
        synchronized(lock) {
            pendingBackup?.clear()
            pendingBackup = prepared
        }
        return prepared.fileName
    }

    fun cancelBackup() {
        synchronized(lock) {
            pendingBackup?.clear()
            pendingBackup = null
        }
    }

    suspend fun finishBackup(uri: Uri?): BackupWriteResult {
        val prepared = synchronized(lock) { pendingBackup.also { pendingBackup = null } }
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

    fun consumePreview(expected: BackupPreview) {
        synchronized(lock) {
            check(preview === expected) { "Backup preview is no longer current" }
            preview = null
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
            pendingBackup?.clear()
            pendingBackup = null
            preview?.discard()
            preview = null
        }
    }
}
