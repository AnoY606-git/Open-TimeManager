package dev.opentimemanager.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.fail
import org.junit.Test
import org.junit.Assert.assertEquals

class UiWriteBoundaryTest {
    @Test
    fun ownedSecretIsClearedWhenCoroutineIsCancelledBeforeItsBodyStarts() = runBlocking {
        val parent = SupervisorJob().also { it.cancel() }
        val secret = "never-started".toCharArray()
        var entered = false

        val job = CoroutineScope(parent + Dispatchers.Unconfined).launchOwnedSecret(secret) {
            entered = true
        }
        job.join()

        assertEquals(false, entered)
        assertEquals(CharArray(secret.size).toList(), secret.toList())
    }

    @Test
    fun failedNewDocumentWarnsWhenProviderCouldNotRemovePartialFile() {
        assertEquals(UiMessage.DATA_IO_ERROR, transferFailureMessage(partialDocumentDeleted = true))
        assertEquals(
            UiMessage.PARTIAL_FILE_CLEANUP_REQUIRED,
            transferFailureMessage(partialDocumentDeleted = false),
        )
    }

    @Test fun uiWriteBoundaryNeverConvertsCancellationIntoAUserError() = runBlocking {
        try {
            cancellationAwareUiWrite { throw CancellationException("screen left") }
            fail("CancellationException must be rethrown")
        } catch (_: CancellationException) {
            Unit
        }
    }
}
