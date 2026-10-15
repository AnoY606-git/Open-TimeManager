package dev.opentimemanager.core.backup

import dev.opentimemanager.core.model.*
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.SecureRandom
import java.time.DateTimeException
import java.time.ZoneId
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

object BackupCodec {
    private const val HEADER_SIZE = 42
    private const val TAG_SIZE = 16
    private const val ITERATIONS = 310000
    private const val MAX_RECORDS = 200000
    private val magic = byteArrayOf(0x4f, 0x54, 0x4d, 0x42, 0x31)
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
        explicitNulls = true
    }

    /** Генератор обязан выдавать криптографически независимые salt и nonce. */
    fun encrypt(snapshot: PortableSnapshot, password: CharArray, random: SecureRandom): ByteArray {
        val limits = BackupLimits()
        checkCounts(snapshot, limits)
        validate(snapshot)
        val plain = encode(snapshot, limits.maxBytes - HEADER_SIZE - TAG_SIZE)
        try {
            val salt = ByteArray(16).also(random::nextBytes)
            val nonce = ByteArray(12).also(random::nextBytes)
            val header = ByteBuffer.allocate(HEADER_SIZE).put(magic).put(1).putInt(ITERATIONS)
                .put(salt).put(nonce).putInt(plain.size).array()
            return header + crypt(Cipher.ENCRYPT_MODE, plain, password, salt, nonce, header)
        } finally {
            plain.fill(0)
        }
    }

    /** Проверяет ограничения до KDF/аллокаций; записи выдаются только после GCM auth. */
    fun decrypt(bytes: ByteArray, password: CharArray, limits: BackupLimits): PortableSnapshot {
        if (bytes.size > limits.maxBytes) throw BackupError.LimitError()
        if (bytes.size < magic.size || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) throw BackupError.MagicError()
        if (bytes.size <= magic.size) throw BackupError.IntegrityError()
        if (bytes[5] != 1.toByte()) throw BackupError.VersionError()
        if (bytes.size < HEADER_SIZE + TAG_SIZE) throw BackupError.IntegrityError()
        val input = ByteBuffer.wrap(bytes)
        if (input.getInt(6) != ITERATIONS) throw BackupError.IntegrityError()
        val plainSize = input.getInt(38)
        if (plainSize > limits.maxBytes) throw BackupError.LimitError()
        if (plainSize < 0 || plainSize.toLong() + HEADER_SIZE + TAG_SIZE != bytes.size.toLong()) throw BackupError.IntegrityError()
        val header = bytes.copyOfRange(0, HEADER_SIZE)
        val plain = try {
            crypt(Cipher.DECRYPT_MODE, bytes.copyOfRange(HEADER_SIZE, bytes.size), password,
                header.copyOfRange(10, 26), header.copyOfRange(26, 38), header)
        } catch (_: AEADBadTagException) {
            // Один публичный отказ без provider-specific cause или деталей пароля.
            throw BackupError.IntegrityError()
        }
        return try {
            decode(plain, limits)
        } finally {
            plain.fill(0)
        }
    }

    private fun crypt(mode: Int, data: ByteArray, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            cipher.doFinal(data)
        } finally {
            key.fill(0)
        }
    }

    private fun encode(snapshot: PortableSnapshot, maxBytes: Int): ByteArray {
        val payload = SnapshotJson(
            1,
            snapshot.projects.map { ProjectJson(it.id.value, it.name) },
            snapshot.entries.map { EntryJson(it.id.value, it.projectId.value, it.note, it.zoneId.id, it.source.name) },
            snapshot.segments.map { SegmentJson(it.id.value, it.entryId.value, it.projectId.value, it.startUtcMillis, it.durationMillis) },
            snapshot.activeEntry?.let { ActiveJson(it.entryId.value, it.projectId.value, it.state.name) },
        )
        val text = json.encodeToString(SnapshotJson.serializer(), payload)
        if (utf8Size(text) > maxBytes) throw BackupError.LimitError()
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun decode(plain: ByteArray, limits: BackupLimits): PortableSnapshot = schemaBoundary {
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plain)).toString()
        val payload = json.decodeFromString(SnapshotJson.serializer(), text)
        if (payload.schemaVersion != 1) throw BackupError.SchemaError()
        if (payload.segments.size > limits.maxSegments) throw BackupError.LimitError()
        val projects = payload.projects.map { PortableProject(ProjectId(it.id), it.name) }
        val entries = payload.entries.map { PortableEntry(EntryId(it.id), ProjectId(it.projectId), it.note, ZoneId.of(it.zoneId), EntrySource.valueOf(it.source)) }
        val segments = payload.segments.map {
            Segment(SegmentId(it.id), EntryId(it.entryId), ProjectId(it.projectId), it.startUtcMillis, it.durationMillis)
        }
        val active = payload.activeEntry?.let {
            val state = TrackingState.valueOf(it.state)
            PortableActiveEntry(EntryId(it.entryId), ProjectId(it.projectId), if (state == TrackingState.RUNNING) TrackingState.NEEDS_REVIEW else state)
        }
        PortableSnapshot(projects, entries, segments, active).also(::validate)
    }

    private fun checkCounts(snapshot: PortableSnapshot, limits: BackupLimits) {
        if (snapshot.projects.size > MAX_RECORDS || snapshot.entries.size > MAX_RECORDS || snapshot.segments.size > limits.maxSegments)
            throw BackupError.LimitError()
    }

    private fun validate(snapshot: PortableSnapshot) {
        val projects = snapshot.projects.associateBy { it.id }
        val entries = snapshot.entries.associateBy { it.id }
        if (projects.size != snapshot.projects.size || entries.size != snapshot.entries.size ||
            snapshot.segments.map { it.id }.toSet().size != snapshot.segments.size) throw BackupError.SchemaError()
        if (snapshot.entries.any { it.projectId !in projects } ||
            snapshot.segments.any { entries[it.entryId]?.projectId != it.projectId }) throw BackupError.SchemaError()
        snapshot.activeEntry?.let {
            if (it.state == TrackingState.IDLE || entries[it.entryId]?.projectId != it.projectId) throw BackupError.SchemaError()
        }
    }

    private fun utf8Size(value: String): Long {
        var size = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            size += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() -> {
                    if (index >= value.length || !value[index++].isLowSurrogate()) throw BackupError.SchemaError()
                    4
                }
                char.isLowSurrogate() -> throw BackupError.SchemaError()
                else -> 3
            }
        }
        return size
    }

    private inline fun <T> schemaBoundary(block: () -> T): T = try {
        block()
    } catch (_: CharacterCodingException) {
        throw BackupError.SchemaError()
    } catch (_: DateTimeException) {
        throw BackupError.SchemaError()
    } catch (_: IllegalArgumentException) {
        throw BackupError.SchemaError()
    } catch (_: ArithmeticException) {
        throw BackupError.SchemaError()
    }
}

@Serializable
private data class SnapshotJson(
    @SerialName("schemaVersion") @Serializable(with = MachineIntSerializer::class) val schemaVersion: Int,
    @SerialName("projects") @Serializable(with = ProjectListSerializer::class) val projects: List<ProjectJson>,
    @SerialName("entries") @Serializable(with = EntryListSerializer::class) val entries: List<EntryJson>,
    @SerialName("segments") @Serializable(with = SegmentListSerializer::class) val segments: List<SegmentJson>,
    @SerialName("activeEntry") val activeEntry: ActiveJson?,
)

@Serializable
private data class ProjectJson(@SerialName("id") val id: String, @SerialName("name") val name: String)

@Serializable
private data class EntryJson(
    @SerialName("id") val id: String,
    @SerialName("projectId") val projectId: String,
    @SerialName("note") val note: String,
    @SerialName("zoneId") val zoneId: String,
    @SerialName("source") val source: String,
)

@Serializable
private data class SegmentJson(
    @SerialName("id") val id: String,
    @SerialName("entryId") val entryId: String,
    @SerialName("projectId") val projectId: String,
    @SerialName("startUtcMillis") @Serializable(with = MachineLongSerializer::class) val startUtcMillis: Long,
    @SerialName("durationMillis") @Serializable(with = MachineLongSerializer::class) val durationMillis: Long,
)

@Serializable
private data class ActiveJson(
    @SerialName("entryId") val entryId: String,
    @SerialName("projectId") val projectId: String,
    @SerialName("state") val state: String,
)

/** Останавливает JSON-массив до создания записи 200001, не строя JsonElement tree. */
private class BoundedListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element)
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): List<T> {
        val input = decoder.beginStructure(descriptor)
        val records = ArrayList<T>()
        while (true) {
            val index = input.decodeElementIndex(descriptor)
            if (index == CompositeDecoder.DECODE_DONE) break
            if (records.size >= 200000) throw BackupError.LimitError()
            records.add(input.decodeSerializableElement(descriptor, index, element))
        }
        input.endStructure(descriptor)
        return records
    }
}

private object ProjectListSerializer : KSerializer<List<ProjectJson>> by BoundedListSerializer(ProjectJson.serializer())
private object EntryListSerializer : KSerializer<List<EntryJson>> by BoundedListSerializer(EntryJson.serializer())
private object SegmentListSerializer : KSerializer<List<SegmentJson>> by BoundedListSerializer(SegmentJson.serializer())

private object MachineLongSerializer : KSerializer<Long> {
    private val integerPattern = Regex("-?(0|[1-9][0-9]*)")
    override val descriptor = Long.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        val value = (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive ?: throw BackupError.SchemaError()
        if (value.isString || !integerPattern.matches(value.content)) throw BackupError.SchemaError()
        return value.longOrNull ?: throw BackupError.SchemaError()
    }
}

private object MachineIntSerializer : KSerializer<Int> {
    override val descriptor = Int.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val value = MachineLongSerializer.deserialize(decoder)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw BackupError.SchemaError()
        return value.toInt()
    }
}
