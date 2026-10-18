package dev.opentimemanager.app.backup

import android.app.Application
import android.net.Uri
import android.os.CancellationSignal
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.AppDatabase
import dev.opentimemanager.app.data.CompletedEntryRepository
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.dataexport.CsvExportCoordinator
import dev.opentimemanager.app.dataexport.DocumentStore
import dev.opentimemanager.app.settings.PrivacyMirror
import dev.opentimemanager.app.settings.SettingsMutationBoundary
import dev.opentimemanager.app.settings.StrictPreferencesReader
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserPreferences
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.core.backup.BackupCodec
import dev.opentimemanager.core.backup.BackupError
import dev.opentimemanager.core.backup.BackupFormat
import dev.opentimemanager.core.backup.BackupLimits
import dev.opentimemanager.core.backup.PlainBackupCodec
import dev.opentimemanager.core.backup.PortableEntry
import dev.opentimemanager.core.backup.PortablePreferences
import dev.opentimemanager.core.backup.PortableProject
import dev.opentimemanager.core.backup.PortableSnapshot
import dev.opentimemanager.core.backup.PortableTheme
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.EntrySource
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class PlainBackupFileCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var documents: PlainBackupTestDocuments
    private val uri = Uri.parse("content://tests/archive.otmplain")
    private val encryptedName = Uri.parse("content://tests/archive.otmbackup")
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:30:00Z"), ZoneOffset.UTC)

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        documents = PlainBackupTestDocuments()
        database.trackingDao().initialize(1_000)
        CompletedEntryRepository(database, Dispatchers.IO).save(
            id = EntryId("10000000-0000-4000-8000-000000000001"),
            projectId = ProjectRepository.DEFAULT_PROJECT_ID,
            note = "Readable client note ☕",
            startUtcMillis = 2_000,
            durationMillis = 90_500,
            expectedRevision = null,
        )
        Unit
    }

    @After fun tearDown() { database.close() }

    @Test fun plainBackupIsExplicitReadablePasswordlessAndUsesNoEntropy() = runBlocking {
        val backup = BackupFileCoordinator(
            database, documents, RestoreRepository(database, Dispatchers.IO), Dispatchers.IO,
            random = object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) { throw AssertionError("Plain backup must not encrypt") }
            },
            clock = clock,
        )
        val before = database.trackingDao().entries()
        val prepared = backup.preparePlainBackup()
        assertEquals("open-timemanager-2026-10-18.otmplain", prepared.fileName)
        assertEquals(BackupFormat.PLAINTEXT, prepared.format)
        assertEquals(0, documents.openCalls)
        assertEquals(BackupWriteResult.Success, backup.writeBackup(prepared, uri, true))
        assertTrue(prepared.isCleared)
        assertTrue(checkNotNull(documents.lastWrittenArray).all { it == 0.toByte() })
        assertTrue(documents.bytes.getValue(uri).toString(Charsets.UTF_8).contains("Readable client note ☕"))
        assertEquals(BackupFormatResult.Detected(BackupFormat.PLAINTEXT), backup.backupFormat(uri))
        val inspection = backup.inspectBackup(uri) as BackupInspection.Ready
        assertEquals(BackupFormat.PLAINTEXT, inspection.preview.format)
        assertEquals(BackupCounts(1, 1, 1), inspection.preview.counts)
        assertEquals(before, database.trackingDao().entries())
        inspection.preview.discard()
        assertFalse(inspection.preview.hasPayload)
    }

    @Test fun plaintextCarriesCompleteProjectMetadataAndPortableSettingsThroughReplace() = runBlocking {
        val settings = settings("plain-settings")
        settings.replace(UserPreferences(ThemeMode.DARK, false, false, 615))
        val project = ProjectEntity("15000000-0000-4000-8000-000000000001", "Archived ☕", 0xff123456.toInt(), -123, 9_999)
        database.projectDao().insert(project)
        val restore = RestoreRepository(database, Dispatchers.IO, settings = settings)
        val backup = coordinator(settings = settings, restore = restore)

        val prepared = backup.preparePlainBackup()
        assertEquals(BackupWriteResult.Success, backup.writeBackup(prepared, uri, true))
        val decoded = PlainBackupCodec.decode(documents.bytes.getValue(uri), BackupFileCoordinator.APP_LIMITS)
        assertEquals(PortablePreferences(PortableTheme.DARK, false, false, 615), decoded.preferences)
        assertEquals(project.colorArgb, decoded.projects.single { it.id.value == project.id }.colorArgb)
        database.projectDao().deleteEmpty(project.id)
        settings.replace(UserPreferences())

        val preview = (backup.inspectBackup(uri) as BackupInspection.Ready).preview
        assertTrue(restore.restore(preview, RestoreMode.REPLACE) is RestoreResult.Applied)
        assertEquals(project, database.trackingDao().project(project.id))
        val restored = settings.current()
        assertEquals(ThemeMode.DARK, restored.theme)
        assertFalse(restored.dynamicColor)
        assertFalse(restored.privacyMode)
        assertEquals(615, restored.dailyGoalMinutes)
        assertFalse(preview.hasPayload)
    }

    @Test fun automaticDetectionUsesBytesNotTheExtensionAndWrongEncryptedPasswordsNeverDowngrade() = runBlocking {
        val backup = coordinator()
        documents.bytes[encryptedName] = PlainBackupCodec.encode(source())
        assertEquals(BackupFormatResult.Detected(BackupFormat.PLAINTEXT), backup.backupFormat(encryptedName))
        val unusedPassword = "not needed".toCharArray()
        val plainPreview = (backup.inspectBackup(encryptedName, unusedPassword) as BackupInspection.Ready).preview
        assertEquals(BackupFormat.PLAINTEXT, plainPreview.format)
        assertArrayEquals(CharArray(unusedPassword.size), unusedPassword)
        plainPreview.discard()

        // Historical credentials remain readable, even with an intentionally misleading filename.
        val encrypted = BackupCodec.encrypt(source(), "x".toCharArray(), SecureRandom())
        documents.bytes[uri] = encrypted
        assertEquals(BackupFormatResult.Detected(BackupFormat.ENCRYPTED), backup.backupFormat(uri))
        assertEquals(BackupInspection.Failed(BackupReadFailure.PASSWORD_REQUIRED), backup.inspectBackup(uri))
        val wrong = "wrong".toCharArray()
        assertEquals(BackupInspection.Failed(BackupReadFailure.INTEGRITY), backup.inspectBackup(uri, wrong))
        assertArrayEquals(CharArray(wrong.size), wrong)
        val encryptedPreview = (backup.inspectBackup(uri, "x".toCharArray()) as BackupInspection.Ready).preview
        assertEquals(BackupFormat.ENCRYPTED, encryptedPreview.format)
        encryptedPreview.discard()
        documents.bytes[uri] = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(BackupInspection.Failed(BackupReadFailure.INTEGRITY), backup.inspectBackup(uri, "x".toCharArray()))
        documents.bytes[uri] = encrypted + PlainBackupCodec.encode(source())
        assertEquals(BackupInspection.Failed(BackupReadFailure.INTEGRITY), backup.inspectBackup(uri, "x".toCharArray()))
    }

    @Test fun expectedFormatBindsInspectionToTheWarningAndPasswordPromptDespiteProviderChanges() = runBlocking {
        val backup = coordinator()
        documents.bytes[uri] = PlainBackupCodec.encode(source())
        assertEquals(BackupFormatResult.Detected(BackupFormat.PLAINTEXT), backup.backupFormat(uri))
        documents.bytes[uri] = BackupCodec.encrypt(source(), "x".toCharArray(), SecureRandom())
        assertEquals(
            BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT),
            backup.inspectBackup(uri, expectedFormat = BackupFormat.PLAINTEXT),
        )
        documents.bytes[uri] = PlainBackupCodec.encode(source())
        val password = "x".toCharArray()
        assertEquals(
            BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT),
            backup.inspectBackup(uri, password, expectedFormat = BackupFormat.ENCRYPTED),
        )
        assertArrayEquals(CharArray(password.size), password)
    }

    @Test fun formatAndInspectionShareBoundedContainerValidationAndDistinctPlaintextErrors() = runBlocking {
        val backup = coordinator()
        val before = database.trackingDao().entries()
        val valid = PlainBackupCodec.encode(source())
        val cases = listOf(
            valid.copyOf(valid.size - 1) to BackupReadFailure.PLAIN_INTEGRITY,
            valid.copyOf().also { it[it.lastIndex] = 0 } to BackupReadFailure.PLAIN_INTEGRITY,
            valid.copyOf().also { it[6] = '2'.code.toByte() } to BackupReadFailure.UNSUPPORTED_VERSION,
            "{\"schemaVersion\":2}".toByteArray() to BackupReadFailure.INVALID_FORMAT,
            byteArrayOf() to BackupReadFailure.INVALID_FORMAT,
        )
        cases.forEach { (bytes, reason) ->
            documents.bytes[uri] = bytes
            assertEquals(BackupFormatResult.Failed(reason), backup.backupFormat(uri))
            assertEquals(BackupInspection.Failed(reason), backup.inspectBackup(uri))
        }
        assertEquals(before, database.trackingDao().entries())
    }

    @Test fun exactContainerLimitIsInclusiveForPlainCreationDetectionAndInspection() = runBlocking {
        val backup = coordinator()
        assertEquals(BackupWriteResult.Success, backup.writeBackup(backup.preparePlainBackup(), uri, true))
        val size = documents.bytes.getValue(uri).size
        val exact = coordinator(limits = BackupLimits(maxBytes = size))
        assertEquals(BackupWriteResult.Success, exact.writeBackup(exact.preparePlainBackup(), uri, true))
        assertEquals(BackupFormatResult.Detected(BackupFormat.PLAINTEXT), exact.backupFormat(uri))
        (exact.inspectBackup(uri) as BackupInspection.Ready).preview.discard()
        val tooSmall = coordinator(limits = BackupLimits(maxBytes = size - 1))
        assertThrows(BackupError.LimitError::class.java) { runBlocking { tooSmall.preparePlainBackup() } }
        assertEquals(BackupFormatResult.Failed(BackupReadFailure.LIMIT), tooSmall.backupFormat(uri))
        assertEquals(BackupInspection.Failed(BackupReadFailure.LIMIT), tooSmall.inspectBackup(uri))
    }

    @Test fun checksumValidPlaintextStillNeedsStrictSchemaValidationBeforeAnyPreview() = runBlocking {
        val backup = coordinator()
        val beforeProjects = database.trackingDao().projects()
        val beforeEntries = database.trackingDao().entries()
        val bytes = PlainBackupCodec.encode(source())
        val payload = bytes.copyOfRange(82, bytes.size).toString(Charsets.UTF_8)
        val invalid = listOf(
            payload.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
            payload.replace("\"activeEntry\":null", "\"activeEntry\":null,\"unexpected\":true"),
            payload.replace("\"durationMillis\":500", "\"durationMillis\":0"),
            payload + " false",
            "{",
        )
        invalid.forEach { text ->
            documents.bytes[uri] = container(text)
            // A framing probe is deliberately not schema validation or authentication.
            assertEquals(BackupFormatResult.Detected(BackupFormat.PLAINTEXT), backup.backupFormat(uri))
            assertEquals(BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT), backup.inspectBackup(uri))
        }
        assertEquals(beforeProjects, database.trackingDao().projects())
        assertEquals(beforeEntries, database.trackingDao().entries())
    }

    @Test fun plaintextEnforcesEveryOperationalLimitAndRestoreDomainRulesWithoutMutation() = runBlocking<Unit> {
        val before = database.trackingDao().entries()
        documents.bytes[uri] = PlainBackupCodec.encode(source())
        val policies = listOf(
            BackupOperationalLimits(maxProjects = 0),
            BackupOperationalLimits(maxEntries = 0),
            BackupOperationalLimits(maxSegments = 0),
            BackupOperationalLimits(maxUserTextBytes = 8), // Project (7) + é (2).
        )
        policies.forEach { policy ->
            assertEquals(BackupInspection.Failed(BackupReadFailure.LIMIT), coordinator(operationalLimits = policy).inspectBackup(uri))
        }
        val exact = coordinator(operationalLimits = BackupOperationalLimits(1, 1, 1, 9))
        (exact.inspectBackup(uri) as BackupInspection.Ready).preview.discard()
        assertEquals(
            BackupInspection.Failed(BackupReadFailure.LIMIT),
            coordinator(limits = BackupLimits(maxSegments = 0)).inspectBackup(uri),
        )
        val source = source()
        val invalid = listOf(
            PortableSnapshot(projects = listOf(source.projects.single().copy(name = "  "))),
            PortableSnapshot(projects = listOf(source.projects.single().copy(name = "p".repeat(81)))),
            PortableSnapshot(source.projects, listOf(source.entries.single().copy(note = "n".repeat(2_001))), source.segments),
        )
        invalid.forEach { snapshot ->
            documents.bytes[uri] = PlainBackupCodec.encode(snapshot)
            assertEquals(BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT), coordinator().inspectBackup(uri))
        }
        assertEquals(before, database.trackingDao().entries())
        assertThrows(BackupError.LimitError::class.java) {
            runBlocking { coordinator(operationalLimits = BackupOperationalLimits(maxProjects = 0)).preparePlainBackup() }
        }
    }

    @Test fun onlyContentDocumentsCanBeOpenedOrDeletedAndNullDestinationsCancelCleanly() = runBlocking {
        val backup = coordinator()
        assertEquals(BackupFormatResult.Cancelled, backup.backupFormat(null))
        assertEquals(BackupInspection.Cancelled, backup.inspectBackup(null))
        val cancelled = backup.preparePlainBackup()
        assertEquals(BackupWriteResult.Cancelled, backup.writeBackup(cancelled, null, true))
        assertTrue(cancelled.isCleared)
        for (value in listOf("file:///private/archive.otmplain", "https://tests/archive.otmplain", "content:archive.otmplain")) {
            val invalidUri = Uri.parse(value)
            assertEquals(BackupFormatResult.Failed(BackupReadFailure.INVALID_FORMAT), backup.backupFormat(invalidUri))
            val password = "private password".toCharArray()
            assertEquals(BackupInspection.Failed(BackupReadFailure.INVALID_FORMAT), backup.inspectBackup(invalidUri, password))
            assertArrayEquals(CharArray(password.size), password)
            val prepared = backup.preparePlainBackup()
            assertEquals(BackupWriteResult.Failed(false), backup.writeBackup(prepared, invalidUri, true))
            assertTrue(prepared.isCleared)
            assertEquals(BackupWriteResult.Failed(false), backup.discardCreatedDocument(invalidUri))
        }
        assertEquals(0, documents.openCalls)
        assertTrue(documents.deleted.isEmpty())
    }

    @Test fun boundedReadConsumesAtMostOneExcessByteClosesStreamsAndClearsTemporaryReadableBuffers() = runBlocking {
        val maxBytes = BackupFileCoordinator.APP_MAX_BYTES
        var readBytes = 0
        var closed = false
        documents.inputFactory = { _, _ ->
            object : InputStream() {
                override fun read(): Int { readBytes++; return 0 }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    documents.lastReadArray = buffer
                    buffer.fill(1, offset, offset + length)
                    readBytes += length
                    return length
                }
                override fun close() { closed = true }
            }
        }
        val backup = coordinator()
        assertEquals(BackupFormatResult.Failed(BackupReadFailure.LIMIT), backup.backupFormat(uri))
        assertEquals(maxBytes + 1, readBytes)
        assertTrue(closed)
        assertTrue(checkNotNull(documents.lastReadArray).all { it == 0.toByte() })
        readBytes = 0
        closed = false
        assertEquals(BackupInspection.Failed(BackupReadFailure.LIMIT), backup.inspectBackup(uri))
        assertEquals(maxBytes + 1, readBytes)
        assertTrue(closed)
    }

    @Test fun zeroReturningProvidersDoNotSpinAndReadCloseFailuresCannotPublishPreviews() = runBlocking {
        val payload = PlainBackupCodec.encode(source())
        var zero = true
        var failClose = false
        documents.inputFactory = { _, _ ->
            object : ByteArrayInputStream(payload) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    documents.lastReadArray = buffer
                    if (zero) { zero = false; return 0 }
                    return super.read(buffer, offset, length)
                }
                override fun close() { if (failClose) throw IOException("close failed") }
            }
        }
        val backup = coordinator()
        assertEquals(BackupFormatResult.Detected(BackupFormat.PLAINTEXT), backup.backupFormat(uri))
        assertTrue(checkNotNull(documents.lastReadArray).all { it == 0.toByte() })
        failClose = true
        assertEquals(BackupInspection.Failed(BackupReadFailure.IO), backup.inspectBackup(uri))
        assertEquals(BackupFormatResult.Failed(BackupReadFailure.IO), backup.backupFormat(uri))
        documents.inputFactory = { _, _ -> null }
        assertEquals(BackupInspection.Failed(BackupReadFailure.IO), backup.inspectBackup(uri))
        documents.inputFactory = { _, _ -> throw SecurityException("grant revoked") }
        assertEquals(BackupFormatResult.Failed(BackupReadFailure.IO), backup.backupFormat(uri))
    }

    @Test fun formatDetectionAndInspectionCancelProviderOpensAndClearSuppliedPasswords() = runBlocking {
        val backup = coordinator()
        for (inspect in listOf(false, true)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val signalCancelled = AtomicBoolean(false)
            documents.inputFactory = { _, signal ->
                signal.setOnCancelListener { signalCancelled.set(true); release.countDown() }
                started.countDown()
                release.await()
                signal.throwIfCanceled()
                ByteArrayInputStream(PlainBackupCodec.encode(source()))
            }
            val password = "never retained".toCharArray()
            val job = launch(Dispatchers.Default) {
                if (inspect) backup.inspectBackup(uri, password) else backup.backupFormat(uri)
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(5_000) { job.join() }
                assertTrue(signalCancelled.get())
                assertTrue(job.isCancelled)
                if (inspect) assertArrayEquals(CharArray(password.size), password)
            } finally {
                release.countDown()
                job.cancel()
                password.fill('\u0000')
            }
        }
    }

    @Test fun formatDetectionAndInspectionCloseBlockedReadsOnCancellation() = runBlocking {
        val backup = coordinator()
        for (inspect in listOf(false, true)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = AtomicBoolean(false)
            documents.inputFactory = { _, _ ->
                object : InputStream() {
                    override fun read(): Int = throw AssertionError("Bulk read expected")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        documents.lastReadArray = buffer
                        buffer[0] = 42
                        started.countDown()
                        release.await()
                        throw IOException("closed during read")
                    }
                    override fun close() { closed.set(true); release.countDown() }
                }
            }
            val job = launch(Dispatchers.Default) {
                if (inspect) backup.inspectBackup(uri) else backup.backupFormat(uri)
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(5_000) { job.join() }
                assertTrue(closed.get())
                assertTrue(job.isCancelled)
                assertTrue(checkNotNull(documents.lastReadArray).all { it == 0.toByte() })
            } finally {
                release.countDown()
                job.cancel()
            }
        }
    }

    @Test fun plainWriteFailuresAndCancellationWipeOwnedBytesAndDeleteOnlyNewDocuments() = runBlocking {
        val backup = coordinator()
        documents.failWrites = true
        val first = backup.preparePlainBackup()
        assertEquals(BackupWriteResult.Failed(true), backup.writeBackup(first, uri, true))
        assertTrue(first.isCleared)
        assertTrue(checkNotNull(documents.lastWrittenArray).all { it == 0.toByte() })
        assertTrue(uri in documents.deleted)
        documents.deleted.clear()
        val existing = backup.preparePlainBackup()
        assertEquals(BackupWriteResult.Failed(false), backup.writeBackup(existing, uri, false))
        assertTrue(existing.isCleared)
        assertTrue(documents.deleted.isEmpty())
        documents.failWrites = false
        documents.cancelWrites = true
        val cancelled = backup.preparePlainBackup()
        try {
            backup.writeBackup(cancelled, uri, true)
            throw AssertionError("Cancellation expected")
        } catch (_: CancellationException) {
            assertTrue(cancelled.isCleared)
            assertTrue(uri in documents.deleted)
            assertTrue(checkNotNull(documents.lastWrittenArray).all { it == 0.toByte() })
        }
    }

    @Test fun sessionOwnsPlainPayloadAndOrphanedCallbacksNeverCreateEmptyBackups() = runBlocking {
        val backup = coordinator()
        val transfers = session(backup)
        assertEquals("open-timemanager-2026-10-18.otmplain", transfers.beginPlainBackup())
        assertEquals(BackupWriteResult.Success, transfers.finishBackup(uri))
        assertEquals(BackupFormatResult.Detected(BackupFormat.PLAINTEXT), backup.backupFormat(uri))
        assertEquals("open-timemanager-2026-10-18.otmbackup", transfers.beginBackup("correct horse".toCharArray()))
        assertEquals(BackupWriteResult.Success, transfers.finishBackup(uri))
        assertEquals(BackupFormatResult.Detected(BackupFormat.ENCRYPTED), backup.backupFormat(uri))
        transfers.beginPlainBackup()
        transfers.cancelBackup()
        val calls = documents.openCalls
        assertEquals(BackupWriteResult.Failed(true), transfers.finishBackup(uri))
        assertEquals(calls, documents.openCalls)
        transfers.beginPlainBackup()
        transfers.clear()
        assertEquals(BackupWriteResult.Failed(true), transfers.finishBackup(uri))
        assertFalse(uri in documents.bytes)
    }

    @Test fun providerDeletionFailureIsReportedButNeverRetainsThePreparedPlaintext() = runBlocking {
        val backup = coordinator()
        documents.failWrites = true
        documents.deleteResult = false
        val failed = backup.preparePlainBackup()
        assertEquals(BackupWriteResult.Failed(false), backup.writeBackup(failed, uri, true))
        assertTrue(failed.isCleared)
        assertTrue(checkNotNull(documents.lastWrittenArray).all { it == 0.toByte() })
        assertTrue(uri in documents.deleted)
        documents.throwDelete = true
        val alsoFailed = backup.preparePlainBackup()
        assertEquals(BackupWriteResult.Failed(false), backup.writeBackup(alsoFailed, uri, true))
        assertTrue(alsoFailed.isCleared)
    }

    @Test fun clearDuringPlainPreparationCannotInstallALateReadablePayloadInTheSession() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val settings = settings("plain-session-clear", StrictPreferencesReader { store ->
            entered.complete(Unit)
            release.await()
            store.data.first()
        })
        val transfers = session(coordinator(settings = settings))
        val preparing = async { transfers.beginPlainBackup() }
        entered.await()
        transfers.clear()
        release.complete(Unit)
        try {
            preparing.await()
            throw AssertionError("Superseded preparation must not survive clear")
        } catch (_: CancellationException) {
            Unit
        }
        assertEquals(BackupWriteResult.Failed(true), transfers.finishBackup(uri))
        assertEquals(0, documents.openCalls)
    }

    @Test fun failedEncryptedPreparationCannotFallBackToAPreviouslyPendingPlainBackup() = runBlocking {
        val transfers = session(coordinator())
        transfers.beginPlainBackup()
        val password = "short".toCharArray()
        assertThrows(BackupCreationPasswordException::class.java) { runBlocking { transfers.beginBackup(password) } }
        assertArrayEquals(CharArray(password.size), password)
        assertEquals(BackupWriteResult.Failed(true), transfers.finishBackup(uri))
        assertEquals(0, documents.openCalls)
        assertFalse(uri in documents.bytes)
    }

    @Test fun strictSettingsFailureAbortsPlainPreparationWithoutOpeningAnyDocument() = runBlocking {
        val settings = settings("plain-strict-failure", StrictPreferencesReader { throw IOException("strict read failed") })
        assertThrows(IOException::class.java) { runBlocking { coordinator(settings = settings).preparePlainBackup() } }
        assertEquals(0, documents.openCalls)
        assertTrue(documents.bytes.isEmpty())
        assertTrue(documents.deleted.isEmpty())
    }

    private fun source(): PortableSnapshot {
        val project = ProjectId("20000000-0000-4000-8000-000000000001")
        val entry = EntryId("30000000-0000-4000-8000-000000000001")
        return PortableSnapshot(
            projects = listOf(PortableProject(project, "Project")),
            entries = listOf(PortableEntry(entry, project, "é", ZoneId.of("UTC"), EntrySource.MANUAL)),
            segments = listOf(Segment(SegmentId("40000000-0000-4000-8000-000000000001"), entry, project, 1_000, 500)),
        )
    }

    private fun coordinator(
        limits: BackupLimits = BackupFileCoordinator.APP_LIMITS,
        operationalLimits: BackupOperationalLimits = BackupOperationalLimits(),
        settings: UserSettings? = null,
        restore: RestoreRepository = RestoreRepository(database, Dispatchers.IO, settings = settings),
    ) = BackupFileCoordinator(
        database, documents, restore, Dispatchers.IO,
        limits = limits, operationalLimits = operationalLimits, clock = clock, settings = settings,
    )

    private fun settings(name: String, reader: StrictPreferencesReader? = null): UserSettings {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return if (reader == null) UserSettings(context, "$name-${System.nanoTime()}", privacyMirror = PrivacyMirror { true })
        else UserSettings(
            context, "$name-${System.nanoTime()}", mutationBoundary = SettingsMutationBoundary(),
            privacyMirror = PrivacyMirror { true }, strictPreferencesReader = reader,
        )
    }

    private fun session(backup: BackupFileCoordinator) = DataTransferSession(
        CsvExportCoordinator(database, documents, Dispatchers.IO, ZoneId.of("UTC")), backup,
    )

    private fun container(payload: String): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "OTMP1\n1\n${bytes.size.toString(16).padStart(8, '0')}\n$hash\n".toByteArray(Charsets.US_ASCII) + bytes
    }
}

/** In-memory SAF boundary; never opens actual provider, filesystem, or device documents. */
internal class PlainBackupTestDocuments : DocumentStore {
    val bytes = linkedMapOf<Uri, ByteArray>()
    val deleted = linkedSetOf<Uri>()
    var openCalls = 0
    var failWrites = false
    var cancelWrites = false
    var deleteResult = true
    var throwDelete = false
    var lastWrittenArray: ByteArray? = null
    var lastReadArray: ByteArray? = null
    var inputFactory: ((Uri, CancellationSignal) -> InputStream?)? = null

    override fun openInput(uri: Uri, cancellationSignal: CancellationSignal): InputStream? {
        openCalls++
        inputFactory?.let { return it(uri, cancellationSignal) }
        val payload = bytes[uri] ?: return null
        return object : ByteArrayInputStream(payload) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                lastReadArray = buffer
                return super.read(buffer, offset, length)
            }
        }
    }

    override fun openOutput(uri: Uri, cancellationSignal: CancellationSignal): OutputStream {
        openCalls++
        return object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                lastWrittenArray = buffer
                if (cancelWrites) throw CancellationException("provider cancelled write")
                if (failWrites) throw IOException("provider failed write")
                super.write(buffer, offset, length)
            }
            override fun close() {
                bytes[uri] = toByteArray()
                super.close()
            }
        }
    }

    override fun delete(uri: Uri): Boolean {
        deleted += uri
        if (throwDelete) throw SecurityException("provider rejected delete")
        if (deleteResult) bytes.remove(uri)
        return deleteResult
    }
}
