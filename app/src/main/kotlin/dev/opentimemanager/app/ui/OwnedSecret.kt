package dev.opentimemanager.app.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Transfers ownership of mutable secret characters to a coroutine. The
 * completion hook also runs when a cancelled scope never starts the body.
 */
internal fun CoroutineScope.launchOwnedSecret(
    secret: CharArray,
    block: suspend (CharArray) -> Unit,
): Job {
    val job = launch {
        try {
            block(secret)
        } finally {
            secret.fill('\u0000')
        }
    }
    job.invokeOnCompletion { secret.fill('\u0000') }
    return job
}
