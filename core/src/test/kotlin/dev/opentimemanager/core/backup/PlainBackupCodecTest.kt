package dev.opentimemanager.core.backup

import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.EntrySource
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingState
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PlainBackupCodecTest {
    private val project = ProjectId("a1234567-89ab-4cde-8fab-0123456789ab")
    private val entry = EntryId("b1234567-89ab-4cde-8fab-0123456789ab")
    private val segment = SegmentId("c1234567-89ab-4cde-8fab-0123456789ab")
    private val limits = BackupLimits(maxBytes = 4 * 1024 * 1024, maxSegments = 20_000)

    private fun snapshot(state: TrackingState? = null) = PortableSnapshot(
        projects = listOf(PortableProject(project, "Работа ☕", 0xff123456.toInt(), -1_234, 9_876, false)),
        entries = listOf(PortableEntry(entry, project, "Заметка\r\n\"тест\" 🚀", ZoneId.of("Europe/Moscow"), EntrySource.MANUAL)),
        segments = listOf(Segment(segment, entry, project, -1_234, 1_500)),
        activeEntry = state?.let { PortableActiveEntry(entry, project, it) },
        preferences = PortablePreferences(PortableTheme.DARK, false, false, 615),
    )

    @Test fun `plaintext roundtrip retains the complete existing v2 schema without encryption`() {
        val source = snapshot(TrackingState.RUNNING)
        val bytes = PlainBackupCodec.encode(source, limits)
        val text = bytes.toString(Charsets.UTF_8)

        assertTrue(text.startsWith("OTMP1\n1\n"))
        assertTrue(text.contains("Работа ☕"))
        assertTrue(text.contains("Заметка"))
        assertTrue(text.contains("\"privacyMode\":false"))
        assertTrue(text.contains("\"dailyGoalMinutes\":615"))
        assertEquals(BackupFormat.PLAINTEXT, BackupCodec.detectFormat(bytes, limits))
        assertFalse(BackupFormat.PLAINTEXT.requiresPassword)
        assertTrue(BackupFormat.ENCRYPTED.requiresPassword)
        val restored = PlainBackupCodec.decode(bytes, limits)
        assertEquals(source.projects, restored.projects)
        assertEquals(source.entries, restored.entries)
        assertEquals(source.segments, restored.segments)
        assertEquals(source.preferences, restored.preferences)
        assertEquals(2, restored.sourceSchemaVersion)
        assertEquals(PortableActiveEntry(entry, project, TrackingState.NEEDS_REVIEW), restored.activeEntry)
    }

    @Test fun `plaintext writer has an independently assembled deterministic readable container`() {
        val payload = """{"schemaVersion":2,"projects":[],"entries":[],"segments":[],"activeEntry":null,"preferences":{"theme":"SYSTEM","dynamicColor":true,"privacyMode":true,"dailyGoalMinutes":480}}"""
        assertArrayEquals(container(payload), PlainBackupCodec.encode(PortableSnapshot(), limits))
    }

    @Test fun `plaintext paused and review states retain metadata without a running anchor`() {
        for (state in listOf(TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
            val source = snapshot(state)
            val restored = PlainBackupCodec.decode(PlainBackupCodec.encode(source, limits), limits)
            assertEquals(source.activeEntry, restored.activeEntry)
            assertEquals(source.segments, restored.segments)
        }
    }

    @Test fun `plaintext accepts legacy schema through the same compatibility reader`() {
        val payload = validJson.replace(project.value, DEFAULT_PROJECT_UUID).replace("\"name\":\"P\"", "\"name\":\"Без проекта\"")
        val restored = PlainBackupCodec.decode(container(payload), limits)

        assertEquals(1, restored.sourceSchemaVersion)
        assertEquals(PortablePreferences(), restored.preferences)
        assertEquals(DEFAULT_PROJECT_CANONICAL_NAME, restored.projects.single().name)
        assertTrue(restored.projects.single().isDefault)
        assertEquals(DEFAULT_PROJECT_COLOR_ARGB, restored.projects.single().colorArgb)
    }

    @Test fun `a recomputed checksum allows edits and must never be described as authentication`() {
        val original = PlainBackupCodec.decode(container(validJson), limits)
        val edited = PlainBackupCodec.decode(container(validJson.replace("\"name\":\"P\"", "\"name\":\"Edited\"")), limits)
        assertEquals("P", original.projects.single().name)
        assertEquals("Edited", edited.projects.single().name)
    }

    @Test fun `plaintext and encryption require their own explicit magic and never fall through`() {
        val password = "short legacy password".toCharArray()
        val encrypted = BackupCodec.encrypt(snapshot(), password, SecureRandom(), limits)
        val plain = PlainBackupCodec.encode(snapshot(), limits)

        assertEquals(BackupFormat.ENCRYPTED, BackupCodec.detectFormat(encrypted, limits))
        assertThrows(BackupError.IntegrityError::class.java) {
            BackupCodec.decrypt(encrypted, "wrong password".toCharArray(), limits)
        }
        assertThrows(BackupError.MagicError::class.java) { PlainBackupCodec.decode(encrypted, limits) }
        assertThrows(BackupError.MagicError::class.java) { BackupCodec.decrypt(plain, password, limits) }
        assertThrows(BackupError.MagicError::class.java) { BackupCodec.detectFormat(validJson.toByteArray(), limits) }
        assertThrows(BackupError.MagicError::class.java) { PlainBackupCodec.decode(validJson.toByteArray(), limits) }
        assertArrayEquals("short legacy password".toCharArray(), password)
    }

    @Test fun `plaintext byte ceiling includes its header and is inclusive on both write and read`() {
        val source = snapshot()
        val bytes = PlainBackupCodec.encode(source, limits)
        val exact = BackupLimits(maxBytes = bytes.size)

        assertArrayEquals(bytes, PlainBackupCodec.encode(source, exact))
        assertEquals(source.entries, PlainBackupCodec.decode(bytes, exact).entries)
        assertEquals(BackupFormat.PLAINTEXT, BackupCodec.detectFormat(bytes, exact))
        for (tooSmall in listOf(1, 81, bytes.size - 1)) {
            val small = BackupLimits(maxBytes = tooSmall)
            assertThrows(BackupError.LimitError::class.java) { PlainBackupCodec.encode(source, small) }
            assertThrows(BackupError.LimitError::class.java) { PlainBackupCodec.decode(bytes, small) }
            assertThrows(BackupError.LimitError::class.java) { BackupCodec.detectFormat(bytes, small) }
        }
        assertThrows(BackupError.LimitError::class.java) {
            PlainBackupCodec.decode(ByteArray(limits.maxBytes + 1), limits)
        }
    }

    @Test fun `plaintext segment limits apply to both export and import before a snapshot is returned`() {
        val bytes = PlainBackupCodec.encode(snapshot(), limits)
        val none = BackupLimits(maxSegments = 0)
        assertEquals(1, PlainBackupCodec.decode(bytes, BackupLimits(maxSegments = 1)).segments.size)
        assertThrows(BackupError.LimitError::class.java) { PlainBackupCodec.decode(bytes, none) }
        assertThrows(BackupError.LimitError::class.java) { PlainBackupCodec.encode(snapshot(), none) }

        val source = snapshot()
        assertThrows(BackupError.LimitError::class.java) {
            PlainBackupCodec.encode(PortableSnapshot(projects = List(200_001) { source.projects.single() }))
        }
        assertThrows(BackupError.LimitError::class.java) {
            PlainBackupCodec.encode(PortableSnapshot(entries = List(200_001) { source.entries.single() }))
        }
    }

    @Test fun `plaintext malformed framing length checksum truncation and extension have no password error`() {
        val valid = container(validJson)
        val malformed = listOf(
            valid.copyOf(6),
            valid.copyOf(81),
            valid.copyOf(valid.size - 1),
            valid + byteArrayOf(0),
            valid.copyOf().also { it[7] = '\r'.code.toByte() },
            valid.copyOf().also { it[16] = ' '.code.toByte() },
            valid.copyOf().also { it[81] = ' '.code.toByte() },
            valid.copyOf().also { it[8] = '-'.code.toByte() },
            valid.copyOf().also { it[15] = 'x'.code.toByte() },
            valid.copyOf().also { it[17] = 'A'.code.toByte() },
            valid.copyOf().also { it[17] = if (it[17] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte() },
            valid.copyOf().also { it[it.lastIndex] = ' '.code.toByte() },
        )
        malformed.forEach { bytes ->
            val error = assertThrows(BackupError.PlainIntegrityError::class.java) { PlainBackupCodec.decode(bytes, limits) }
            assertFalse(error.message.orEmpty().contains("пароль"))
            assertNull(error.cause)
            assertThrows(BackupError.PlainIntegrityError::class.java) { BackupCodec.detectFormat(bytes, limits) }
        }
    }

    @Test fun `plaintext magic version and oversized declared length fail before parsing`() {
        val valid = container(validJson)
        assertThrows(BackupError.MagicError::class.java) {
            PlainBackupCodec.decode(valid.copyOf().also { it[0] = 0 }, limits)
        }
        val version = valid.copyOf().also { it[6] = '2'.code.toByte() }
        assertThrows(BackupError.VersionError::class.java) { PlainBackupCodec.decode(version, limits) }
        assertThrows(BackupError.VersionError::class.java) { BackupCodec.detectFormat(version, limits) }
        val oversized = valid.copyOf().also { "ffffffff".toByteArray().copyInto(it, 8) }
        assertThrows(BackupError.LimitError::class.java) { PlainBackupCodec.decode(oversized, limits) }
        assertThrows(BackupError.LimitError::class.java) { BackupCodec.detectFormat(oversized, limits) }
    }

    @Test fun `valid plaintext checksum does not bypass strict JSON schema or UTF8 validation`() {
        val encoded = PlainBackupCodec.encode(snapshot(), limits)
        val v2 = encoded.copyOfRange(82, encoded.size).toString(Charsets.UTF_8)
        val invalid = listOf(
            "", "null", "[]", "{", validJson + " false",
            validJson.replace("\"schemaVersion\":1", "\"schemaVersion\":3"),
            validJson.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            validJson.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            validJson.replace("\"activeEntry\":null", "\"activeEntry\":null,\"future\":0"),
            validJson.replace(",\"activeEntry\":null", ""),
            validJson.replace("\"name\":\"P\"", "\"name\":\"P\",\"unknown\":true"),
            validJson.replace("\"durationMillis\":1", "\"durationMillis\":0"),
            validJson.replace("\"durationMillis\":1", "\"durationMillis\":-1"),
            validJson.replace("\"durationMillis\":1", "\"durationMillis\":1.5"),
            validJson.replace("\"startUtcMillis\":0", "\"startUtcMillis\":\"0\""),
            validJson.replace("\"startUtcMillis\":0", "\"startUtcMillis\":9223372036854775807"),
            validJson.replace("\"source\":\"IMPORT\"", "\"source\":\"UNKNOWN\""),
            validJson.replace("\"zoneId\":\"UTC\"", "\"zoneId\":\"unknown/zone\""),
            validJson.replace(project.value, "1-1-1-1-1"),
            validJson.replace(entry.value, "not-a-uuid"),
            validJson.replace(segment.value, "not-a-uuid"),
            validJson.replace("\"projectId\":\"${project.value}\"", "\"projectId\":\"d1234567-89ab-4cde-8fab-0123456789ab\""),
            v2.replace("\"dailyGoalMinutes\":615", "\"dailyGoalMinutes\":14"),
            v2.replace("\"theme\":\"DARK\"", "\"theme\":\"UNKNOWN\""),
            v2.replace("\"archivedAtUtcMillis\":9876,", ""),
            v2.replace("\"colorArgb\":-15584170", "\"colorArgb\":null"),
            v2.replace("\"isDefault\":false", "\"isDefault\":true"),
            v2.replace("\"schemaVersion\":2", "\"schemaVersion\":1"),
        )
        invalid.forEach { text ->
            assertThrows(text, BackupError.SchemaError::class.java) { PlainBackupCodec.decode(container(text), limits) }
        }
        assertThrows(BackupError.SchemaError::class.java) { PlainBackupCodec.decode(container(byteArrayOf(0xff.toByte())), limits) }
    }

    @Test fun `plaintext rejects duplicate ids broken references active state and duration overflow on both paths`() {
        val source = snapshot()
        val badSnapshots = listOf(
            PortableSnapshot(source.projects + source.projects, source.entries, source.segments),
            PortableSnapshot(source.projects, source.entries + source.entries, source.segments),
            PortableSnapshot(source.projects, source.entries, source.segments + source.segments),
            PortableSnapshot(emptyList(), source.entries, source.segments),
            PortableSnapshot(source.projects, emptyList(), source.segments),
            PortableSnapshot(source.projects, source.entries, source.segments, PortableActiveEntry(entry, project, TrackingState.IDLE)),
        )
        badSnapshots.forEach { value ->
            assertThrows(BackupError.SchemaError::class.java) { PlainBackupCodec.encode(value, limits) }
        }
        val record = """{"id":"${segment.value}","entryId":"${entry.value}","projectId":"${project.value}","startUtcMillis":0,"durationMillis":1}"""
        val duplicate = validJson.replace(record, "$record,$record")
        assertThrows(BackupError.SchemaError::class.java) { PlainBackupCodec.decode(container(duplicate), limits) }
        val overflow = validJson.replace(record, record.replace("\"durationMillis\":1", "\"durationMillis\":9223372036854775807") + "," + record.replace(segment.value, "d1234567-89ab-4cde-8fab-0123456789ab"))
        assertThrows(BackupError.SchemaError::class.java) { PlainBackupCodec.decode(container(overflow), limits) }
        val activeIdle = validJson.replace("\"activeEntry\":null", "\"activeEntry\":{\"entryId\":\"${entry.value}\",\"projectId\":\"${project.value}\",\"state\":\"IDLE\"}")
        assertThrows(BackupError.SchemaError::class.java) { PlainBackupCodec.decode(container(activeIdle), limits) }
    }

    @Test fun `plaintext decoder does not mutate caller bytes and snapshot owns its lists`() {
        val bytes = PlainBackupCodec.encode(snapshot(), limits)
        val original = bytes.copyOf()
        val restored = PlainBackupCodec.decode(bytes, limits)
        assertArrayEquals(original, bytes)
        bytes.fill(0)
        assertEquals(snapshot().entries, restored.entries)
        assertThrows(UnsupportedOperationException::class.java) { (restored.projects as MutableList<*>).clear() }
    }

    private val validJson = """{"schemaVersion":1,"projects":[{"id":"${project.value}","name":"P"}],"entries":[{"id":"${entry.value}","projectId":"${project.value}","note":"","zoneId":"UTC","source":"IMPORT"}],"segments":[{"id":"${segment.value}","entryId":"${entry.value}","projectId":"${project.value}","startUtcMillis":0,"durationMillis":1}],"activeEntry":null}"""

    /** Independent assembler for parser tests, including invalid JSON with a valid checksum. */
    private fun container(text: String): ByteArray = container(text.toByteArray(Charsets.UTF_8))

    private fun container(payload: ByteArray): ByteArray {
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "OTMP1\n1\n${payload.size.toString(16).padStart(8, '0')}\n$checksum\n".toByteArray(Charsets.US_ASCII) + payload
    }
}
