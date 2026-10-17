package dev.opentimemanager.app.backup

import java.nio.CharBuffer

internal enum class BackupCreationPasswordError { TOO_SHORT, TOO_LONG }

internal class BackupCreationPasswordException(
    val error: BackupCreationPasswordError,
) : IllegalArgumentException("Backup creation password does not meet policy")

/** Creation-only policy: observes the exact password without trimming, normalizing or copying it. */
internal object BackupCreationPasswordPolicy {
    const val MIN_CODE_POINTS = 12
    const val MAX_UTF8_BYTES = 1024

    fun validate(password: CharSequence): BackupCreationPasswordError? {
        var codePoints = 0
        var utf8Bytes = 0
        var index = 0
        while (index < password.length) {
            val character = password[index++]
            codePoints++
            utf8Bytes += when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                Character.isHighSurrogate(character) &&
                    index < password.length && Character.isLowSurrogate(password[index]) -> {
                    index++
                    4
                }
                Character.isSurrogate(character) -> 1 // Matches the JDK UTF-8 encoder's replacement size.
                else -> 3
            }
            if (utf8Bytes > MAX_UTF8_BYTES) return BackupCreationPasswordError.TOO_LONG
        }
        return if (codePoints < MIN_CODE_POINTS) BackupCreationPasswordError.TOO_SHORT else null
    }

    fun requireValid(password: CharArray) {
        validate(CharBuffer.wrap(password))?.let { throw BackupCreationPasswordException(it) }
    }
}
