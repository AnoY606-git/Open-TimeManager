package dev.opentimemanager.app.system

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bounds only the Android pending-result lifetime; durable work is never cancelled by it. */
internal class PendingResultLifetime(
    scope: CoroutineScope,
    timeoutMillis: Long,
    private val finish: () -> Unit,
) {
    private val finished = AtomicBoolean(false)
    private val watchdog = scope.launch {
        delay(timeoutMillis)
        finishOnce()
    }

    fun bind(work: Job) {
        work.invokeOnCompletion { finishOnce() }
    }

    fun finishOnce() {
        if (finished.compareAndSet(false, true)) {
            watchdog.cancel()
            finish()
        }
    }
}
