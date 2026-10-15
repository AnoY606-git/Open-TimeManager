package dev.opentimemanager.app.dataexport

import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine

/** Bridges coroutine cancellation to Android provider/SQLite cancellation. */
internal suspend fun <T> cancellableIo(
    closeOnCancel: () -> Unit = {},
    discardOnCancellation: (T) -> Unit = {},
    block: (CancellationSignal) -> T,
): T = suspendCancellableCoroutine { continuation ->
    val signal = CancellationSignal()
    continuation.invokeOnCancellation {
        runCatching { signal.cancel() }
        runCatching(closeOnCancel)
    }
    if (!continuation.isActive) return@suspendCancellableCoroutine
    try {
        val value = block(signal)
        continuation.resume(value) { _, rejectedValue, _ ->
            runCatching { discardOnCancellation(rejectedValue) }
        }
    } catch (error: Throwable) {
        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
    }
}
