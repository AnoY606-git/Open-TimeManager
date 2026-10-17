package dev.opentimemanager.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCreationPasswordPolicyTest {
    @Test
    fun requiresTwelveCodePointsForCreationWithoutTrimming() {
        for (password in listOf("", "x", "a".repeat(11))) {
            assertEquals(BackupCreationPasswordError.TOO_SHORT, BackupCreationPasswordPolicy.validate(password))
        }
        assertNull(BackupCreationPasswordPolicy.validate("a".repeat(12)))
        assertNull(BackupCreationPasswordPolicy.validate(" ".repeat(12)))
    }

    @Test
    fun supplementaryCharactersCountOnceRatherThanAsTwoUtf16Units() {
        val rocket = "\uD83D\uDE80"
        assertEquals(12, rocket.repeat(6).length)
        assertEquals(BackupCreationPasswordError.TOO_SHORT, BackupCreationPasswordPolicy.validate(rocket.repeat(6)))
        assertEquals(BackupCreationPasswordError.TOO_SHORT, BackupCreationPasswordPolicy.validate(rocket.repeat(11)))
        assertNull(BackupCreationPasswordPolicy.validate(rocket.repeat(12)))
    }

    @Test
    fun combiningCodePointsAreNotNormalizedBeforeCounting() {
        assertNull(BackupCreationPasswordPolicy.validate("e\u0301".repeat(6)))
        assertEquals(BackupCreationPasswordError.TOO_SHORT, BackupCreationPasswordPolicy.validate("\u00e9".repeat(6)))
    }

    @Test
    fun acceptsExactly1024Utf8BytesAndRejectsTheNextByteForEveryEncodingWidth() {
        val boundaries = listOf(
            "a".repeat(1024),
            "\u00e9".repeat(512),
            "\u20ac".repeat(341) + "a",
            "\uD83D\uDE80".repeat(256),
        )
        for (password in boundaries) {
            assertEquals(1024, password.toByteArray(Charsets.UTF_8).size)
            assertNull(BackupCreationPasswordPolicy.validate(password))
            assertEquals(1025, (password + "a").toByteArray(Charsets.UTF_8).size)
            assertEquals(BackupCreationPasswordError.TOO_LONG, BackupCreationPasswordPolicy.validate(password + "a"))
        }
    }

    @Test
    fun validationNeverRewritesOrClearsTheCallerPasswordAndErrorsDoNotContainIt() {
        val password = "  e\u0301PassWord\uD83D\uDE80  ".toCharArray()
        val original = password.copyOf()
        BackupCreationPasswordPolicy.requireValid(password)
        assertArrayEquals(original, password)

        val short = "secret".toCharArray()
        val rejected = assertThrows(BackupCreationPasswordException::class.java) {
            BackupCreationPasswordPolicy.requireValid(short)
        }
        assertEquals(BackupCreationPasswordError.TOO_SHORT, rejected.error)
        assertArrayEquals("secret".toCharArray(), short)
        assertFalse(rejected.toString().contains("secret"))

        val tooLong = "\u00e9".repeat(513).toCharArray()
        val tooLongOriginal = tooLong.copyOf()
        val overLimit = assertThrows(BackupCreationPasswordException::class.java) {
            BackupCreationPasswordPolicy.requireValid(tooLong)
        }
        assertEquals(BackupCreationPasswordError.TOO_LONG, overLimit.error)
        assertArrayEquals(tooLongOriginal, tooLong)
    }
}
