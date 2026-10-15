package dev.opentimemanager.app.ui.screens

import dev.opentimemanager.core.backup.BackupError
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTransferStatusTest {
    @Test
    fun createLimitHasSpecificUserVisibleStatusWhileOtherFailuresRemainIoErrors() {
        assertEquals(
            TransferUiStatus.LIMIT_EXCEEDED,
            backupCreateFailureStatus(BackupError.LimitError()),
        )
        assertEquals(
            TransferUiStatus.IO_ERROR,
            backupCreateFailureStatus(IOException("provider failed")),
        )
    }
}
