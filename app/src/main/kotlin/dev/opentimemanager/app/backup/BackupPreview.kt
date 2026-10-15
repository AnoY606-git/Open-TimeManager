package dev.opentimemanager.app.backup

data class BackupCounts(
    val projects: Int,
    val entries: Int,
    val segments: Int,
)

enum class BackupConflictKind { PROJECT, ENTRY, SEGMENT }

data class BackupConflict(
    val kind: BackupConflictKind,
    val id: String,
)

/**
 * Предварительно проверенный snapshot. Payload одноразовый: restore или discard
 * удаляет единственную сильную ссылку и не удерживает расшифрованные данные.
 */
class BackupPreview internal constructor(
    val counts: BackupCounts,
    val conflicts: List<BackupConflict>,
    val skippedEntriesWithoutConfirmedTime: Int,
    val activeStateWillBeStopped: Boolean,
    payload: RestorePayload,
) {
    @Volatile
    private var payload: RestorePayload? = payload

    val hasPayload: Boolean
        get() = payload != null

    internal fun takePayload(): RestorePayload = synchronized(this) {
        payload?.also { payload = null } ?: throw IllegalStateException("Backup preview was already consumed")
    }

    fun discard() {
        synchronized(this) { payload = null }
    }
}

enum class RestoreMode { MERGE, REPLACE }

sealed interface RestoreResult {
    data class Applied(val projects: Int, val entries: Int, val segments: Int) : RestoreResult
    data class Conflicts(val conflicts: List<BackupConflict>) : RestoreResult
    data class ActiveTimerBlocked(val state: dev.opentimemanager.core.model.TrackingState) : RestoreResult
}
