package dev.opentimemanager.app.settings

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import java.time.YearMonth
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class MonthlyTargetSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val september = YearMonth.of(2026, 9)
    private val october = YearMonth.of(2026, 10)

    private fun newSettings(name: String = "monthly-target-${UUID.randomUUID()}") = UserSettings(
        context, name,
        mutationBoundary = SettingsMutationBoundary(),
        privacyMirror = PrivacyMirror { true },
    )

    @Test fun freshInstallHasNoImplicitMonthlyNorm() = runBlocking {
        assertEquals(emptyMap<YearMonth, Int>(), newSettings().monthlyTargets.first())
    }

    @Test fun separateMonthTargetsSurviveSettingsRecreation() = runBlocking {
        val name = "monthly-target-recreation-${UUID.randomUUID()}"
        val settings = newSettings(name)
        settings.setMonthlyTargetMinutes(september, 9_600)
        settings.setMonthlyTargetMinutes(october, 10_560)
        assertEquals(mapOf(september to 9_600, october to 10_560), newSettings(name).monthlyTargets.first())
        assertTrue(context.preferencesDataStoreFile(name).isFile)
    }

    @Test fun zeroIsSavedAndNullDeletesOnlyThatMonth() = runBlocking {
        val settings = newSettings()
        settings.setMonthlyTargetMinutes(september, 0)
        settings.setMonthlyTargetMinutes(october, 10_560)
        assertEquals(mapOf(september to 0, october to 10_560), settings.monthlyTargets.first())
        settings.setMonthlyTargetMinutes(september, null)
        assertEquals(mapOf(october to 10_560), settings.monthlyTargets.first())
    }

    @Test fun dailyGoalAndPortableReplacementPreserveMonthSpecificTargets() = runBlocking {
        val settings = newSettings()
        settings.setMonthlyTargetMinutes(september, 9_600)
        settings.setDailyGoalMinutes(240)
        settings.replace(UserPreferences(ThemeMode.DARK, false, true, 600))
        settings.mutationBoundary.serialized {
            settings.replaceWithinMutationBoundary(UserPreferences(dailyGoalMinutes = 720))
        }
        assertEquals(mapOf(september to 9_600), settings.monthlyTargets.first())
        assertEquals(720, settings.withStrictExportSnapshot { it.dailyGoalMinutes })
    }

    @Test fun invalidMonthAndMinuteValuesCannotReplaceAnExistingNorm() = runBlocking {
        val settings = newSettings()
        settings.setMonthlyTargetMinutes(september, 9_600)
        for ((month, minutes) in listOf(
            september to -1, september to 43_201,
            YearMonth.of(2025, 2) to 40_321,
            YearMonth.of(1899, 12) to 60, YearMonth.of(10_000, 1) to 60,
        )) {
            try {
                settings.setMonthlyTargetMinutes(month, minutes)
                fail("Нельзя сохранить $month: $minutes")
            } catch (_: IllegalArgumentException) {
                assertEquals(mapOf(september to 9_600), settings.monthlyTargets.first())
            }
        }
    }

    @Test fun malformedStoredKeysAndValuesAreIgnoredWithoutLosingValidNorms() = runBlocking {
        val name = "monthly-target-invalid-${UUID.randomUUID()}"
        val job = SupervisorJob()
        val seed = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
            context.preferencesDataStoreFile(name)
        }
        try {
            seed.edit {
                it[intPreferencesKey("month_target_2026-09")] = 9_600
                it[intPreferencesKey("month_target_2026-10")] = -1
                it[intPreferencesKey("month_target_2026-11")] = 43_201
                it[stringPreferencesKey("month_target_2026-12")] = "9600"
                it[intPreferencesKey("month_target_2026-13")] = 60
                it[intPreferencesKey("month_target_2026-9")] = 60
                it[intPreferencesKey("month_target_1899-12")] = 60
                it[intPreferencesKey("month_target_+2026-08")] = 60
                it[stringPreferencesKey("theme")] = ThemeMode.DARK.name
            }
        } finally {
            job.cancelAndJoin()
        }
        val settings = newSettings(name)
        assertEquals(mapOf(september to 9_600), settings.monthlyTargets.first())
        assertEquals(ThemeMode.DARK, settings.current().theme)
        settings.setMonthlyTargetMinutes(YearMonth.of(2026, 12), 0)
        assertEquals(mapOf(september to 9_600, YearMonth.of(2026, 12) to 0), settings.monthlyTargets.first())
    }

    @Test fun concurrentMonthAndPortableWritesPreserveEveryValue() = runBlocking {
        val settings = newSettings()
        coroutineScope {
            launch { settings.setMonthlyTargetMinutes(september, 9_600) }
            launch { settings.setMonthlyTargetMinutes(october, 10_560) }
            launch { settings.setDailyGoalMinutes(300) }
        }
        assertEquals(mapOf(september to 9_600, october to 10_560), settings.monthlyTargets.first())
        assertEquals(300, settings.current().dailyGoalMinutes)
    }
}
