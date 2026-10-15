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
 * Предварительно проверенный snapshot. Payload остаётся для повтора после отказа,
 * конфликта или отмены; только Applied или discard удаляет сильную ссылку. Discard во время
 * restore безопасно отладывается до выхода из операции.
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
    @Volatile
    private var discardRequested: Boolean = false

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
                synchronized(this) {
                    inUse = false
                    if (discardRequested) {
                        payload = null
                        completedResult = null
                    }
                }
            }
    }

    fun discard() {
        synchronized(this) {
            if (inUse) {
                discardRequested = true
            } else {
                payload = null
                completedResult = null
            }
        }
    }
}

enum class RestoreMode { MERGE, REPLACE }

sealed interface RestoreResult {
    data class Applied(val projects: Int, val entries: Int, val segments: Int) : RestoreResult
    data class Conflicts(val conflicts: List<BackupConflict>) : RestoreResult
    data class ActiveTimerBlocked(val state: dev.opentimemanager.core.model.TrackingState) : RestoreResult
    data class LimitExceeded(val maximumProjects: Int) : RestoreResult
}
