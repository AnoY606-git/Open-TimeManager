package dev.opentimemanager.core.backup

import dev.opentimemanager.core.model.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val projectId = ProjectId("a1234567-89ab-4cde-8fab-0123456789ab")
    private val entryId = EntryId("b1234567-89ab-4cde-8fab-0123456789ab")
    private val segmentId = SegmentId("c1234567-89ab-4cde-8fab-0123456789ab")
    private val password get() = "fixture-password".toCharArray()
    // Независимый frozen vector: Python hashlib.pbkdf2_hmac + cryptography AESGCM,
    // Plaintext — канонический JSON ниже (79 UTF-8 байт), задан вручную.
    private val golden = hex("4f544d4231010004baf0000102030405060708090a0b0c0d0e0f101112131415161718191a1b0000004f29f83ae9457bed11247d7497ad9bede439ab6f9865fafffcf585309164f59ee8ad781ebd7562f09a63680647123a4c626a4befe1ef13a67734f83f85f8de3db75d64272f5bc49ccf6c68cd449820d34ebcb6ea1283262c447982c0bb673d76")
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun random() = object : SecureRandom() {
        private var next = 0
        override fun nextBytes(bytes: ByteArray) { for (index in bytes.indices) bytes[index] = (next++).toByte() }
    }

    private fun snapshot(
        state: TrackingState? = null,
        project: ProjectId = projectId,
        entry: EntryId = entryId,
        segment: SegmentId = segmentId,
    ): PortableSnapshot = PortableSnapshot(
        projects = listOf(PortableProject(project, "Работа ☕")),
        entries = listOf(PortableEntry(entry, project, "Заметка\r\n\"тест\"", ZoneId.of("Europe/Moscow"), EntrySource.MANUAL)),
        segments = listOf(Segment(segment, entry, project, -1234, 1500)),
        activeEntry = state?.let { PortableActiveEntry(entry, project, it) },
    )

    private fun roundtrip(snapshot: PortableSnapshot) = BackupCodec.decrypt(BackupCodec.encrypt(snapshot, password, random()), password, BackupLimits())

    // Другой KDF, nonce, порядок полей или отсутствие AAD нарушит совместимость файла.
    @Test fun `empty snapshot encrypts to independent golden vector`() {
        assertArrayEquals(golden, BackupCodec.encrypt(PortableSnapshot(), password, random()))
    }

    @Test fun `independent golden vector decrypts without any records`() {
        val result = BackupCodec.decrypt(golden, password, BackupLimits())
        assertTrue(result.projects.isEmpty())
        assertTrue(result.entries.isEmpty())
        assertTrue(result.segments.isEmpty())
        assertNull(result.activeEntry)
    }

    @Test fun `roundtrip retains UTF8 metadata ids zone milliseconds and negative epoch`() {
        val source = snapshot()
        val result = roundtrip(source)
        assertEquals(source.projects, result.projects)
        assertEquals(source.entries, result.entries)
        assertEquals(source.segments, result.segments)
        assertNull(result.activeEntry)
    }

    @Test fun `running snapshot imports as needs review without runtime clock baseline`() {
        val result = roundtrip(snapshot(TrackingState.RUNNING))
        assertEquals(PortableActiveEntry(entryId, projectId, TrackingState.NEEDS_REVIEW), result.activeEntry)
        assertEquals(snapshot().segments, result.segments)
    }

    @Test fun `paused and needs review snapshots retain non-running state`() {
        for (state in listOf(TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
            assertEquals(state, roundtrip(snapshot(state)).activeEntry?.state)
        }
    }

    @Test fun `wrong password and changed ciphertext produce same public integrity error`() {
        val wrongPassword = assertThrows(BackupError.IntegrityError::class.java) { BackupCodec.decrypt(golden, "wrong".toCharArray(), BackupLimits()) }
        val tampered = golden.copyOf().also { it[45] = (it[45].toInt() xor 1).toByte() }
        val corrupted = assertThrows(BackupError.IntegrityError::class.java) { BackupCodec.decrypt(tampered, password, BackupLimits()) }
        assertEquals(wrongPassword.message, corrupted.message)
        assertNull(wrongPassword.cause)
        assertNull(corrupted.cause)
    }

    @Test fun `salt nonce and declared length are authenticated header fields`() {
        for (offset in listOf(10, 26, 41)) {
            val tampered = golden.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertThrows(BackupError.IntegrityError::class.java) { BackupCodec.decrypt(tampered, password, BackupLimits()) }
        }
    }

    @Test fun `invalid magic and unsupported version have typed errors`() {
        assertThrows(BackupError.MagicError::class.java) { BackupCodec.decrypt(golden.copyOf().also { it[0] = 0 }, password, BackupLimits()) }
        assertThrows(BackupError.VersionError::class.java) { BackupCodec.decrypt(golden.copyOf().also { it[5] = 2 }, password, BackupLimits()) }
    }

    @Test fun `truncated or extended encrypted body is an integrity failure`() {
        for (bytes in listOf(golden.copyOf(6), golden.copyOf(41), golden.copyOf(golden.size - 1), golden + byteArrayOf(0))) {
            assertThrows(BackupError.IntegrityError::class.java) { BackupCodec.decrypt(bytes, password, BackupLimits()) }
        }
    }

    @Test fun `untrusted KDF iteration count never triggers attacker selected work`() {
        val altered = golden.copyOf().also { ByteBuffer.wrap(it).putInt(6, Int.MAX_VALUE) }
        assertThrows(BackupError.IntegrityError::class.java) { BackupCodec.decrypt(altered, password, BackupLimits()) }
    }

    @Test fun `container byte limit is inclusive and checked before crypto`() {
        assertTrue(BackupCodec.decrypt(golden, password, BackupLimits(maxBytes = golden.size)).segments.isEmpty())
        assertThrows(BackupError.LimitError::class.java) { BackupCodec.decrypt(golden, password, BackupLimits(maxBytes = golden.size - 1)) }
        assertThrows(BackupError.LimitError::class.java) { BackupCodec.decrypt(ByteArray(50 * 1024 * 1024 + 1), password, BackupLimits()) }
    }

    @Test fun `encryption obeys the caller container and segment limits`() {
        assertThrows(BackupError.LimitError::class.java) {
            BackupCodec.encrypt(snapshot(), password, random(), BackupLimits(maxBytes = 128))
        }
        assertThrows(BackupError.LimitError::class.java) {
            BackupCodec.encrypt(snapshot(), password, random(), BackupLimits(maxSegments = 0))
        }
    }

    @Test fun `declared plaintext above 50 MiB is rejected before allocation`() {
        val oversized = golden.copyOf().also { ByteBuffer.wrap(it).putInt(38, 50 * 1024 * 1024 + 1) }
        assertThrows(BackupError.LimitError::class.java) { BackupCodec.decrypt(oversized, password, BackupLimits()) }
    }

    @Test fun `segment hard limit accepts 200000 for validation but rejects next record`() {
        // При 200000 повторяющиеся id дойдут до SchemaError; при 200001 раньше
        // сработает LimitError. Так проверяется inclusive граница без 200000 unique id.
        for ((count, error) in listOf(200000 to BackupError.SchemaError::class.java, 200001 to BackupError.LimitError::class.java)) {
            val payload = buildString {
                append("{\"schemaVersion\":1,\"projects\":[],\"entries\":[],\"segments\":[")
                repeat(count) {
                    if (it > 0) append(',')
                    append("{\"id\":\"${segmentId.value}\",\"entryId\":\"${entryId.value}\",\"projectId\":\"${projectId.value}\",\"startUtcMillis\":0,\"durationMillis\":1}")
                }
                append("],\"activeEntry\":null}")
            }.toByteArray(Charsets.UTF_8)
            assertThrows(error) { BackupCodec.decrypt(authenticated(payload), password, BackupLimits()) }
        }
    }

    @Test fun `configured segment limit accepts equality and rejects next record`() {
        val bytes = BackupCodec.encrypt(snapshot(), password, random())
        assertEquals(1, BackupCodec.decrypt(bytes, password, BackupLimits(maxSegments = 1)).segments.size)
        assertThrows(BackupError.LimitError::class.java) { BackupCodec.decrypt(bytes, password, BackupLimits(maxSegments = 0)) }
    }

    @Test fun `limits cannot weaken hard resource ceilings`() {
        assertThrows(IllegalArgumentException::class.java) { BackupLimits(maxBytes = 50 * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { BackupLimits(maxSegments = 200001) }
        assertThrows(IllegalArgumentException::class.java) { BackupLimits(maxSegments = -1) }
        assertThrows(IllegalArgumentException::class.java) { BackupLimits(maxBytes = 0) }
    }

    @Test fun `authenticated unknown fields schema malformed JSON and trailing tokens are rejected`() {
        val invalid = listOf(
            """{"schemaVersion":2,"projects":[],"entries":[],"segments":[],"activeEntry":null}""",
            """{"schemaVersion":1,"projects":[],"entries":[],"segments":[],"activeEntry":null,"future":0}""",
            """{"schemaVersion":1,"projects":[{"id":"a1234567-89ab-4cde-8fab-0123456789ab","name":"p","unknown":1}],"entries":[],"segments":[],"activeEntry":null}""",
            """{"schemaVersion":1,"projects":[],"entries":[],"segments":[],"activeEntry":null} false""",
            """{"schemaVersion":1,"projects":[],"entries":[],"segments":[]}""",
            """{"schemaVersion":1.5,"projects":[],"entries":[],"segments":[],"activeEntry":null}""",
            """{"schemaVersion":1,"projects":null,"entries":[],"segments":[],"activeEntry":null}""",
            """{"schemaVersion":1,"projects":[""",
        )
        for (payload in invalid) {
            assertThrows(payload, BackupError.SchemaError::class.java) { BackupCodec.decrypt(authenticated(payload.toByteArray(Charsets.UTF_8)), password, BackupLimits()) }
        }
        assertThrows(BackupError.SchemaError::class.java) { BackupCodec.decrypt(authenticated(byteArrayOf(0xff.toByte())), password, BackupLimits()) }
    }

    @Test fun `snapshot duplicate ids and broken entry references fail before encryption`() {
        val source = snapshot()
        val invalid = listOf(
            PortableSnapshot(source.projects + source.projects, source.entries, source.segments),
            PortableSnapshot(source.projects, source.entries + source.entries, source.segments),
            PortableSnapshot(source.projects, source.entries, source.segments + source.segments),
            PortableSnapshot(emptyList(), source.entries, source.segments),
            PortableSnapshot(source.projects, emptyList(), source.segments),
            PortableSnapshot(source.projects, source.entries, source.segments, PortableActiveEntry(EntryId("d1234567-89ab-4cde-8fab-0123456789ab"), projectId, TrackingState.PAUSED)),
            PortableSnapshot(source.projects, source.entries, source.segments, PortableActiveEntry(entryId, projectId, TrackingState.IDLE)),
        )
        for (value in invalid) assertThrows(BackupError.SchemaError::class.java) { BackupCodec.encrypt(value, password, random()) }
    }

    @Test fun `authenticated segment still obeys domain invariants`() {
        // Две metadata records и один segment с duration=0, независимо собранные поля.
        val payload = validJson.replace("\"durationMillis\":1", "\"durationMillis\":0").toByteArray(Charsets.UTF_8)
        assertThrows(BackupError.SchemaError::class.java) { BackupCodec.decrypt(authenticated(payload), password, BackupLimits()) }
    }

    // JSON-строки, float и неизвестные enum не должны незаметно менять machine schema.
    @Test fun `machine numbers and enum values require exact JSON types`() {
        val valid = validJson
        for (invalid in listOf(
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            valid.replace("\"startUtcMillis\":0", "\"startUtcMillis\":\"0\""),
            valid.replace("\"durationMillis\":1", "\"durationMillis\":1.5"),
            valid.replace("\"source\":\"IMPORT\"", "\"source\":\"UNKNOWN\""),
            valid.replace("\"zoneId\":\"UTC\"", "\"zoneId\":\"invalid/zone\""),
        )) {
            assertThrows(invalid, BackupError.SchemaError::class.java) {
                BackupCodec.decrypt(authenticated(invalid.toByteArray(Charsets.UTF_8)), password, BackupLimits())
            }
        }
    }

    // Удаление UUID guard примет короткие id, UUID.fromString без shape guard
    // также молча примет сокращённую форму 1-1-1-1-1.
    @Test fun `encrypt rejects malformed UUID definitions and references`() {
        for (invalidId in listOf("p", "1-1-1-1-1", "a123456789ab4cde8fab0123456789ab", "a1234567-89ab-4cde-8fab-0123456789ag")) {
            val invalid = listOf(
                snapshot(TrackingState.PAUSED, project = ProjectId(invalidId)),
                snapshot(TrackingState.PAUSED, entry = EntryId(invalidId)),
                snapshot(segment = SegmentId(invalidId)),
            )
            for (value in invalid) assertThrows(invalidId, BackupError.SchemaError::class.java) {
                BackupCodec.encrypt(value, password, random())
            }
        }
        val valid = snapshot(TrackingState.PAUSED)
        val invalidReferences = listOf(
            PortableSnapshot(valid.projects, listOf(valid.entries.single().copy(projectId = ProjectId("bad"))), valid.segments, valid.activeEntry),
            PortableSnapshot(valid.projects, valid.entries, listOf(valid.segments.single().copy(entryId = EntryId("bad"))), valid.activeEntry),
            PortableSnapshot(valid.projects, valid.entries, listOf(valid.segments.single().copy(projectId = ProjectId("bad"))), valid.activeEntry),
            PortableSnapshot(valid.projects, valid.entries, valid.segments, valid.activeEntry!!.copy(entryId = EntryId("bad"))),
            PortableSnapshot(valid.projects, valid.entries, valid.segments, valid.activeEntry.copy(projectId = ProjectId("bad"))),
        )
        for (value in invalidReferences) assertThrows(BackupError.SchemaError::class.java) { BackupCodec.encrypt(value, password, random()) }
    }

    @Test fun `authenticated JSON rejects malformed UUIDs even when references agree`() {
        for (id in listOf(projectId.value, entryId.value, segmentId.value)) {
            for (invalid in listOf("bad", "1-1-1-1-1", "a1234567-89ab-4cde-8fab-0123456789ag")) {
                val payload = validJson.replace(id, invalid).toByteArray(Charsets.UTF_8)
                assertThrows("$id -> $invalid", BackupError.SchemaError::class.java) {
                    BackupCodec.decrypt(authenticated(payload), password, BackupLimits())
                }
            }
        }
    }

    @Test fun `canonical UUID text roundtrips case insensitively without normalization`() {
        for (upperCase in listOf(false, true)) {
            fun text(value: String) = if (upperCase) value.uppercase(java.util.Locale.ROOT) else value
            val source = snapshot(TrackingState.PAUSED, ProjectId(text(projectId.value)), EntryId(text(entryId.value)), SegmentId(text(segmentId.value)))
            val result = roundtrip(source)
            assertEquals(source.projects, result.projects)
            assertEquals(source.entries, result.entries)
            assertEquals(source.segments, result.segments)
            assertEquals(source.activeEntry, result.activeEntry)
        }
    }

    private val validJson = """{"schemaVersion":1,"projects":[{"id":"a1234567-89ab-4cde-8fab-0123456789ab","name":"P"}],"entries":[{"id":"b1234567-89ab-4cde-8fab-0123456789ab","projectId":"a1234567-89ab-4cde-8fab-0123456789ab","note":"","zoneId":"UTC","source":"IMPORT"}],"segments":[{"id":"c1234567-89ab-4cde-8fab-0123456789ab","entryId":"b1234567-89ab-4cde-8fab-0123456789ab","projectId":"a1234567-89ab-4cde-8fab-0123456789ab","startUtcMillis":0,"durationMillis":1}],"activeEntry":null}"""

    @Test fun `encryption does not overwrite caller password`() {
        val chars = password
        BackupCodec.encrypt(PortableSnapshot(), chars, random())
        assertArrayEquals("fixture-password".toCharArray(), chars)
        BackupCodec.decrypt(golden, chars, BackupLimits())
        assertArrayEquals("fixture-password".toCharArray(), chars)
    }

    @Test fun `snapshot defensively owns lists used for later encryption`() {
        val source = snapshot()
        val projects = source.projects.toMutableList()
        val entries = source.entries.toMutableList()
        val segments = source.segments.toMutableList()
        val value = PortableSnapshot(projects, entries, segments)
        projects.clear(); entries.clear(); segments.clear()
        assertEquals(source.projects, roundtrip(value).projects)
        assertEquals(source.entries, value.entries)
        assertEquals(source.segments, value.segments)
        assertThrows(UnsupportedOperationException::class.java) { (value.segments as MutableList<*>).clear() }
    }

    // Нужен для проверки парсера на аутентичных, но некорректных данных. Он не
    // использует codec или его сериализатор; схема payload задаётся literals выше.
    private fun authenticated(payload: ByteArray): ByteArray {
        val header = golden.copyOfRange(0, 42).also { ByteBuffer.wrap(it).putInt(38, payload.size) }
        val spec = PBEKeySpec(password, header.copyOfRange(10, 26), 310000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.copyOfRange(26, 38)))
            cipher.updateAAD(header)
            header + cipher.doFinal(payload)
        } finally { key.fill(0) }
    }
}
