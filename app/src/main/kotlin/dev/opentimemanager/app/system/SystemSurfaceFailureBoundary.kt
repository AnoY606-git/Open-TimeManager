package dev.opentimemanager.app.system

import android.util.Log
import kotlinx.coroutines.CancellationException

/** Локальная граница Android-адаптеров: ошибка проекции не завершает процесс с работающей записью. */
internal suspend fun containSystemSurfaceFailure(
    onFailure: () -> Unit = {},
    operation: suspend () -> Unit,
) {
    try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        try { onFailure() } catch (_: Exception) { /* cleanup is best-effort */ }
        // Сообщение исключения и stack trace могут содержать названия проектов или заметки.
        Log.w("SystemSurfaces", "System surface operation failed")
    }
}
