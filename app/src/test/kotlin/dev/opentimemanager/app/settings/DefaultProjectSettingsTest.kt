package dev.opentimemanager.app.settings

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class DefaultProjectSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val projectId = "50000000-0000-4000-8000-000000000001"
    private fun newSettings(name: String = "default-project-${UUID.randomUUID()}") = UserSettings(
        context,
        name,
        mutationBoundary = SettingsMutationBoundary(),
        privacyMirror = PrivacyMirror { true },
    )

    @Test fun freshInstallKeepsAutomaticSelection() = runBlocking {
        val settings = newSettings()
        assertNull(withTimeout(5_000) { settings.defaultProjectId.first() })
        assertEquals(ThemeMode.SYSTEM, settings.current().theme)
    }

    @Test fun preferredProjectSurvivesRecreationAndEveryUnrelatedSettingsWrite() = runBlocking {
        val name = "default-project-recreation-${UUID.randomUUID()}"
        val settings = newSettings(name)
        settings.setDefaultProjectId(projectId)
        settings.setTheme(ThemeMode.DARK)
        settings.setDynamicColor(false)
        settings.setPrivacy(false)
        settings.setDailyGoalMinutes(510)
        assertEquals(projectId, settings.defaultProjectId.first())

        val recreated = newSettings(name)
        assertEquals(projectId, recreated.defaultProjectId.first())
        assertEquals(UserPreferences(ThemeMode.DARK, false, false, 510), recreated.current())
        assertTrue(context.preferencesDataStoreFile(name).isFile)
    }

    @Test fun portableReplacementAndRestorePortPreserveLocalOverride() = runBlocking {
        val settings = newSettings()
        settings.setDefaultProjectId(projectId)
        val replaced = UserPreferences(ThemeMode.LIGHT, false, true, 600)
        assertEquals(SettingsMutationResult.APPLIED, settings.replace(replaced))
        assertEquals(projectId, settings.defaultProjectId.first())

        val restored = UserPreferences(ThemeMode.DARK, true, false, 720)
        settings.mutationBoundary.serialized {
            assertEquals(SettingsMutationResult.APPLIED, settings.replaceWithinMutationBoundary(restored))
        }
        assertEquals(restored, settings.current())
        assertEquals(projectId, settings.defaultProjectId.first())
        assertEquals(restored, settings.withStrictExportSnapshot { it })
    }

    @Test fun clearingOnlyRemovesTheOverrideAndPersistsAcrossInstances() = runBlocking {
        val name = "default-project-clear-${UUID.randomUUID()}"
        val settings = newSettings(name)
        settings.setTheme(ThemeMode.DARK)
        val before = settings.current()
        settings.setDefaultProjectId(projectId)
        assertEquals(before, settings.current())
        settings.setDefaultProjectId(null)
        val recreated = newSettings(name)
        assertNull(recreated.defaultProjectId.first())
        assertEquals(before, recreated.current())
    }

    @Test fun malformedAndNoncanonicalIdsCannotReplaceAValidPreference() = runBlocking {
        val settings = newSettings()
        settings.setDefaultProjectId(projectId)
        for (invalid in listOf("", " ", "not-a-uuid", "1-1-1-1-1", "$projectId ", "AAAAAAAA-0000-4000-8000-000000000001")) {
            try {
                settings.setDefaultProjectId(invalid)
                fail("Accepted invalid preferred project: $invalid")
            } catch (_: IllegalArgumentException) {
                assertEquals(projectId, settings.defaultProjectId.first())
            }
        }
    }

    @Test fun invalidStoredValueIsIgnoredWithoutLosingOtherPreferences() = runBlocking {
        val name = "default-project-invalid-${UUID.randomUUID()}"
        val job = SupervisorJob()
        val seed = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
            context.preferencesDataStoreFile(name)
        }
        try {
            seed.edit {
                it[stringPreferencesKey("default_project_id")] = "1-1-1-1-1"
                it[stringPreferencesKey("theme")] = ThemeMode.DARK.name
            }
        } finally {
            job.cancelAndJoin()
        }

        val settings = newSettings(name)
        assertNull(settings.defaultProjectId.first())
        assertEquals(ThemeMode.DARK, settings.current().theme)
        settings.setDefaultProjectId(projectId)
        assertEquals(projectId, settings.defaultProjectId.first())
    }

    @Test fun concurrentPortableAndLocalWritesDoNotLoseEitherPreference() = runBlocking {
        val settings = newSettings()
        coroutineScope {
            launch { settings.setDefaultProjectId(projectId) }
            launch { settings.setTheme(ThemeMode.DARK) }
            launch { settings.setDynamicColor(false) }
            launch { settings.setPrivacy(false) }
            launch { settings.setDailyGoalMinutes(900) }
        }
        assertEquals(projectId, settings.defaultProjectId.first())
        assertEquals(UserPreferences(ThemeMode.DARK, false, false, 900), settings.current())
    }

    @Test fun localOverrideDoesNotCreateOrDrainPortableRecoveryIntent() = runBlocking {
        val pending = PendingUserPreferences("restore", UserPreferences(ThemeMode.LIGHT))
        var journalReads = 0
        var journalWrites = 0
        val journal = object : SettingsRecoveryJournal {
            override suspend fun pending(): PendingUserPreferences? {
                journalReads++
                return pending
            }
            override suspend fun save(value: PendingUserPreferences) { journalWrites++ }
            override suspend fun delete(operationId: String): Boolean {
                journalWrites++
                return true
            }
        }
        val settings = UserSettings(
            context,
            "default-project-journal-${UUID.randomUUID()}",
            recoveryJournal = journal,
            privacyMirror = PrivacyMirror { error("Local override must not mirror privacy") },
        )
        settings.setDefaultProjectId(projectId)
        assertEquals(projectId, settings.defaultProjectId.first())
        settings.setDefaultProjectId(null)
        assertNull(settings.defaultProjectId.first())
        assertEquals(0, journalReads)
        assertEquals(0, journalWrites)
    }
}
