package dev.opentimemanager.app.ui.screens

import dev.opentimemanager.app.backup.BackupCreationPasswordError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupPasswordInputTest {
    @Test
    fun creationStartsDisabledAndShowsAnErrorOnlyAfterInput() {
        val empty = backupPasswordInputState("", creatingBackup = true)
        assertFalse(empty.canSubmit)
        assertNull(empty.error)

        val short = backupPasswordInputState("short", creatingBackup = true)
        assertFalse(short.canSubmit)
        assertEquals(BackupCreationPasswordError.TOO_SHORT, short.error)

        val sufficient = backupPasswordInputState("a".repeat(12), creatingBackup = true)
        assertTrue(sufficient.canSubmit)
        assertNull(sufficient.error)
    }

    @Test
    fun creationButtonUsesUnicodeCodePointsAndUtf8Bytes() {
        val rocket = "\uD83D\uDE80"
        assertFalse(backupPasswordInputState(rocket.repeat(6), creatingBackup = true).canSubmit)
        assertTrue(backupPasswordInputState(rocket.repeat(12), creatingBackup = true).canSubmit)
        assertTrue(backupPasswordInputState("e\u0301".repeat(6), creatingBackup = true).canSubmit)
        assertTrue(backupPasswordInputState("\u00e9".repeat(512), creatingBackup = true).canSubmit)

        val tooLong = backupPasswordInputState("\u00e9".repeat(512) + "a", creatingBackup = true)
        assertFalse(tooLong.canSubmit)
        assertEquals(BackupCreationPasswordError.TOO_LONG, tooLong.error)
    }

    @Test
    fun restoreKeepsTheExistingNonEmptyRuleWithoutTheCreationMinimumOrByteCeiling() {
        for (password in listOf("x", " e\u0301\uD83D\uDE80 ", "\u00e9".repeat(513))) {
            val legacy = backupPasswordInputState(password, creatingBackup = false)
            assertTrue(legacy.canSubmit)
            assertNull(legacy.error)
        }
        assertFalse(backupPasswordInputState("", creatingBackup = false).canSubmit)
    }
}
