package dev.opentimemanager.app.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val useMutex = Mutex()
    @Volatile
    private var payload: RestorePayload? = payload
    @Volatile
    private var completedResult: RestoreResult.Applied? = null
    @Volatile
    private var inUse: Boolean = false

    val hasPayload: Boolean
        get() = payload != null

    internal suspend fun usePayload(operation: suspend (RestorePayload) -> RestoreResult): RestoreResult =
        useMutex.withLock {
            val value = synchronized(this) {
                check(!inUse) { "Backup preview is already in use" }
                completedResult?.let { return@withLock it }
                (payload ?: throw IllegalStateException("Backup preview was already consumed")).also { inUse = true }
            }
            try {
                operation(value).also { result ->
                    if (result is RestoreResult.Applied) synchronized(this) {
                        payload = null
                        completedResult = result
                    }
                }
            } finally {
                synchronized(this) { inUse = false }
            }
    }

    fun discard() {
        synchronized(this) {
            check(!inUse) { "Backup preview is being restored" }
            payload = null
            completedResult = null
        }
    }
}

enum class RestoreMode { MERGE, REPLACE }

sealed interface RestoreResult {
    data class Applied(val projects: Int, val entries: Int, val segments: Int) : RestoreResult
    data class Conflicts(val conflicts: List<BackupConflict>) : RestoreResult
    data class ActiveTimerBlocked(val state: dev.opentimemanager.core.model.TrackingState) : RestoreResult
}
