package dev.opentimemanager.core.backup

import java.security.MessageDigest

/**
 * Explicit, opt-in, UNENCRYPTED backup. All projects, notes and portable settings
 * are readable. The SHA-256 checksum detects accidental corruption only: anyone
 * can edit the JSON and recompute it. Neither the container nor its schema-checked
 * contents are authenticated, signed, or security-verified.
 *
 * Printable UTF-8 framing (no BOM): `OTMP1\n1\n`, eight lowercase hexadecimal
 * payload-length digits + LF, 64 lowercase SHA-256 digits + LF, then exactly that
 * many bytes of the same JSON schema used by [BackupCodec]. No password or KDF.
 */
object PlainBackupCodec {
    private val magic = "OTMP1\n".toByteArray(Charsets.US_ASCII)
    private const val VERSION_OFFSET = 6
    private const val LENGTH_OFFSET = 8
    private const val CHECKSUM_OFFSET = 17
    private const val HEADER_SIZE = 82
    private const val HEX = "0123456789abcdef"

    fun encode(snapshot: PortableSnapshot, limits: BackupLimits = BackupLimits()): ByteArray {
        val payload = BackupCodec.encodePayload(snapshot, limits, HEADER_SIZE)
        return try {
            val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
            val container = ByteArray(HEADER_SIZE + payload.size)
            magic.copyInto(container)
            container[VERSION_OFFSET] = '1'.code.toByte()
            container[VERSION_OFFSET + 1] = '\n'.code.toByte()
            for (index in 0 until 8) {
                val digit = (payload.size ushr ((7 - index) * 4)) and 0xf
                container[LENGTH_OFFSET + index] = HEX[digit].code.toByte()
            }
            container[CHECKSUM_OFFSET - 1] = '\n'.code.toByte()
            checksum.forEachIndexed { index, byte ->
                container[CHECKSUM_OFFSET + index * 2] = HEX[(byte.toInt() ushr 4) and 0xf].code.toByte()
                container[CHECKSUM_OFFSET + index * 2 + 1] = HEX[byte.toInt() and 0xf].code.toByte()
            }
            container[HEADER_SIZE - 1] = '\n'.code.toByte()
            payload.copyInto(container, HEADER_SIZE)
            container
        } finally {
            payload.fill(0)
        }
    }

    /** Checks framing/checksum, then the identical strict schema and domain rules. */
    fun decode(bytes: ByteArray, limits: BackupLimits = BackupLimits()): PortableSnapshot {
        requireContainer(bytes, limits)
        return BackupCodec.decodePayload(bytes, limits, HEADER_SIZE, bytes.size - HEADER_SIZE)
    }

    internal fun hasMagic(bytes: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    internal fun requireContainer(bytes: ByteArray, limits: BackupLimits) {
        if (bytes.size > limits.maxBytes) throw BackupError.LimitError()
        if (!hasMagic(bytes)) throw BackupError.MagicError()
        if (bytes.size <= VERSION_OFFSET) throw BackupError.PlainIntegrityError()
        if (bytes[VERSION_OFFSET] != '1'.code.toByte()) throw BackupError.VersionError()
        if (bytes.size < HEADER_SIZE ||
            bytes[VERSION_OFFSET + 1] != '\n'.code.toByte() ||
            bytes[CHECKSUM_OFFSET - 1] != '\n'.code.toByte() ||
            bytes[HEADER_SIZE - 1] != '\n'.code.toByte()) {
            throw BackupError.PlainIntegrityError()
        }
        var payloadSize = 0L
        repeat(8) { index ->
            payloadSize = payloadSize * 16 + hexDigit(bytes[LENGTH_OFFSET + index])
        }
        if (payloadSize > limits.maxBytes) throw BackupError.LimitError()
        if (payloadSize + HEADER_SIZE != bytes.size.toLong()) throw BackupError.PlainIntegrityError()

        val expected = ByteArray(32) { index ->
            ((hexDigit(bytes[CHECKSUM_OFFSET + index * 2]) shl 4) or
                hexDigit(bytes[CHECKSUM_OFFSET + index * 2 + 1])).toByte()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        if (!MessageDigest.isEqual(expected, digest.digest())) throw BackupError.PlainIntegrityError()
    }

    private fun hexDigit(byte: Byte): Int = when (val char = byte.toInt()) {
        in '0'.code..'9'.code -> char - '0'.code
        in 'a'.code..'f'.code -> char - 'a'.code + 10
        else -> throw BackupError.PlainIntegrityError()
    }
}
