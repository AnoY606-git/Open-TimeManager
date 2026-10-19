package dev.opentimemanager.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.system.LiveUpdatePreferences
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSetupIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val state = AppUiState(
        snapshot = AppSnapshot(
            TrackingSession.idle(),
            listOf(ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, "@default", 0, 0)),
            emptyList(), emptyList(),
        ),
        controls = AppControls(destination = AppDestination.SETTINGS),
    )

    @Test fun skipClosesAutomaticWizardAndSavedStateDoesNotOpenItAgain() {
        val outcomes = mutableListOf<DeviceSetupState>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                OpenTimeManagerApp(
                    state, null, NotificationControlStatus.PERMISSION_REQUESTABLE,
                    onManageNotifications = { error("Разрешение без нажатия") },
                    selectedLanguage = AppLanguage.ENGLISH, onLanguage = {},
                    showDeviceSetupInitially = true,
                    onFinishDeviceSetup = { outcomes += it; true },
                )
            }
        }
        compose.onNodeWithTag("device_setup_skip").performScrollTo().performClick()
        compose.onNodeWithTag("device_setup").assertDoesNotExist()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("device_setup").assertDoesNotExist()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(DeviceSetupState.SKIPPED), outcomes) }
    }

    @Test fun englishManualWizardReturnsToSystemSettingsAndCanBeOpenedAgain() = checkManualWizard("en", "setup wizard")
    @Test fun russianManualWizardReturnsToSystemSettingsAndCanBeOpenedAgain() = checkManualWizard("ru", "мастер настройки")

    @Test @SdkSuppress(minSdkVersion = 36)
    fun completingWizardSynchronizesLiveUpdateChoiceWithThePreservedSettingsCard() = checkLiveUpdateChoice("device_setup_complete")

    @Test @SdkSuppress(minSdkVersion = 36)
    fun skippingWizardSynchronizesLiveUpdateChoiceWithThePreservedSettingsCard() = checkLiveUpdateChoice("device_setup_skip")

    private fun checkLiveUpdateChoice(exitTag: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
        val before = storage.all.toMap()
        val preferences = LiveUpdatePreferences(context)
        try {
            runBlocking { assertTrue(preferences.setEnabled(false)) }
            compose.setContent {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    OpenTimeManagerApp(
                        state, null, NotificationControlStatus.AVAILABLE,
                        onManageNotifications = {}, selectedLanguage = AppLanguage.ENGLISH, onLanguage = {},
                    )
                }
            }
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_category_system"))
            compose.onNodeWithTag("settings_category_system").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("live_updates_toggle"))
            compose.onNodeWithTag("live_updates_toggle").assertIsOff()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_device_setup"))
            compose.onNodeWithTag("open_device_setup").performClick()
            repeat(2) { compose.onNodeWithTag("device_setup_next").performScrollTo().performClick() }

            val dialogToggle = hasTestTag("live_updates_toggle") and hasAnyAncestor(isDialog())
            compose.onNode(dialogToggle).performScrollTo().performClick()
            compose.waitUntil(10_000) {
                preferences.current().enabled &&
                    compose.onNode(dialogToggle).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
            }
            compose.onNodeWithTag(exitTag).performScrollTo().performClick()

            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("live_updates_toggle"))
            // Смена одного лишь remember в диалоге должна провалить эту проверку host UI.
            compose.onNodeWithTag("live_updates_toggle").assertIsOn().performClick()
            compose.waitUntil(10_000) {
                !preferences.current().enabled &&
                    compose.onNodeWithTag("live_updates_toggle").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
            }
            compose.onNodeWithTag("live_updates_toggle").assertIsOff()
            assertFalse(preferences.current().enabled)
        } finally {
            // Сохраняем также suppression/posted keys, которые opt-in может сбросить.
            runBlocking {
                withContext(Dispatchers.IO) {
                    val editor = storage.edit().clear()
                    before.forEach { (key, value) ->
                        when (value) {
                            is Boolean -> editor.putBoolean(key, value)
                            is String -> editor.putString(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                        }
                    }
                    assertTrue("Не удалось восстановить исходные локальные настройки", editor.commit())
                }
            }
        }
    }

    private fun checkManualWizard(language: String, query: String) {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(Locale(language)))) {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                OpenTimeManagerApp(
                    state, null, NotificationControlStatus.AVAILABLE,
                    onManageNotifications = {}, selectedLanguage = AppLanguage.ENGLISH, onLanguage = {},
                )
            }
            }
        }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_search"))
        // Используем настоящий индекс labels/keywords, а не искусственный список теста.
        compose.onNodeWithTag("settings_search").performTextInput(query)
        compose.onNodeWithTag("settings_category_system").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_device_setup"))
        compose.onNodeWithTag("open_device_setup").performClick()
        compose.onNodeWithTag("device_setup_skip").performScrollTo().performClick()

        compose.onNodeWithTag("open_device_setup").assertIsDisplayed().performClick()
        compose.onNodeWithTag("device_setup_device").assertExists()
    }
}
