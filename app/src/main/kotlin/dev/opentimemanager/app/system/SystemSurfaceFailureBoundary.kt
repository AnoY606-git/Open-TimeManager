package dev.opentimemanager.app.system

import android.util.Log
import kotlinx.coroutines.CancellationException

/** Локальная граница Android-адаптеров: ошибка проекции не завершает процесс с работающей записью. */
internal suspend fun containSystemSurfaceFailure(operation: suspend () -> Unit) {
    try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Сообщение исключения и stack trace могут содержать названия проектов или заметки.
        Log.w("SystemSurfaces", "System surface operation failed")
    }
}
