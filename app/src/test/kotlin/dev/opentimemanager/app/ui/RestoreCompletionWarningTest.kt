package dev.opentimemanager.app.ui

import dev.opentimemanager.app.backup.RestoreResult
import org.junit.Assert.*
import org.junit.Test

class RestoreCompletionWarningTest {
    @Test fun pendingPrivacyWarningCannotBeHiddenByLessSpecificSystemWarning() {
        val applied = RestoreResult.Applied(2, 3, 4, preferencesPending = true)
        assertEquals(UiMessage.SETTINGS_SYSTEM_PRIVACY_PENDING, restoreCompletionWarning(applied, UiMessage.SYSTEM_CONTROLS_UNAVAILABLE))
        assertEquals(UiMessage.SETTINGS_SYSTEM_PRIVACY_PENDING, restoreCompletionWarning(applied, null))
    }

    @Test fun convergedRestoreRetainsOnlyARealSystemWarning() {
        val applied = RestoreResult.Applied(2, 3, 4)
        assertNull(restoreCompletionWarning(applied, null))
        assertEquals(UiMessage.SYSTEM_CONTROLS_UNAVAILABLE, restoreCompletionWarning(applied, UiMessage.SYSTEM_CONTROLS_UNAVAILABLE))
    }
}
