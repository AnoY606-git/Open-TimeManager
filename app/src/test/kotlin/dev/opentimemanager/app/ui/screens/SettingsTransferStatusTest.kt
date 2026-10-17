package dev.opentimemanager.app.ui.screens

import dev.opentimemanager.app.backup.BackupCreationPasswordError
import dev.opentimemanager.app.backup.BackupCreationPasswordException
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.app.R
import dev.opentimemanager.core.backup.BackupError
import dev.opentimemanager.core.model.TrackingState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTransferStatusTest {
    @Test
    fun restoreRejectionsHaveSpecificDialogMessagesAndSuccessIsNotAnError() {
        assertEquals(
            R.string.backup_preview_active_timer_error,
            RestoreResult.ActiveTimerBlocked(TrackingState.PAUSED).status().previewFailureResource(),
        )
        assertEquals(
            R.string.backup_preview_conflict_error,
            RestoreResult.Conflicts(emptyList()).status().previewFailureResource(),
        )
        assertEquals(
            R.string.backup_preview_limit_error,
            RestoreResult.LimitExceeded(20_000).status().previewFailureResource(),
        )
        assertEquals(R.string.backup_preview_io_error, TransferUiStatus.IO_ERROR.previewFailureResource())
        assertNull(RestoreResult.Applied(1, 0, 0).status().previewFailureResource())
        assertNull(TransferUiStatus.INVALID_PASSWORD_OR_FILE.previewFailureResource())
    }

    @Test
    fun creationPasswordRejectionsHaveSpecificStatusesInsteadOfIoErrors() {
        assertEquals(
            TransferUiStatus.BACKUP_PASSWORD_TOO_SHORT,
            backupCreateFailureStatus(BackupCreationPasswordException(BackupCreationPasswordError.TOO_SHORT)),
        )
        assertEquals(
            TransferUiStatus.BACKUP_PASSWORD_TOO_LONG,
            backupCreateFailureStatus(BackupCreationPasswordException(BackupCreationPasswordError.TOO_LONG)),
        )
    }

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
