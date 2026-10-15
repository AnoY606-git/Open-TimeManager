package dev.opentimemanager.app.backup

import dev.opentimemanager.core.backup.PortableSnapshot

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
    snapshot: PortableSnapshot,
) {
    @Volatile
    private var payload: PortableSnapshot? = snapshot

    val hasPayload: Boolean
        get() = payload != null

    internal fun takePayload(): PortableSnapshot = synchronized(this) {
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
