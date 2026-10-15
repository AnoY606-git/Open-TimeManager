package dev.opentimemanager.app.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Test

class UiWriteBoundaryTest {
    @Test fun uiWriteBoundaryNeverConvertsCancellationIntoAUserError() = runBlocking {
        try {
            cancellationAwareUiWrite { throw CancellationException("screen left") }
            fail("CancellationException must be rethrown")
        } catch (_: CancellationException) {
            Unit
        }
    }
}
