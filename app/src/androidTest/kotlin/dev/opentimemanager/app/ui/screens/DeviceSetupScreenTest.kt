package dev.opentimemanager.app.ui.screens

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.ui.NotificationControlStatus
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSetupScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishWizardIsScrollableAt320DpAndLargeFontWithoutAutomaticPermissionRequest() = checkWizard("en")
    @Test fun russianWizardIsScrollableAt320DpAndLargeFontWithoutAutomaticPermissionRequest() = checkWizard("ru")

    private fun checkWizard(language: String) {
        val restoration = StateRestorationTester(compose)
        var notificationRequests = 0
        var completed = 0
        var skipped = 0
        restoration.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 700.dp)) then
                    DeviceConfigurationOverride.FontScale(2f) then
                    DeviceConfigurationOverride.Locales(LocaleList(Locale(language))),
            ) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    DeviceSetupScreen(
                        notificationStatus = NotificationControlStatus.PERMISSION_REQUESTABLE,
                        onManageNotifications = { notificationRequests++ },
                        onSkip = { skipped++ },
                        onComplete = { completed++ },
                    )
                }
            }
        }
        compose.onNodeWithTag("device_setup_device").assertExists()
        compose.onNodeWithTag("device_setup_next").performScrollTo().performClick()
        compose.onNodeWithTag("device_setup_notifications").assertExists()
        compose.runOnIdle { assertEquals(0, notificationRequests) }

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("device_setup_notifications").assertExists()
        compose.onNodeWithTag("device_setup_manage_notifications").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, notificationRequests) }
        compose.onNodeWithTag("device_setup_next").performScrollTo().performClick()
        compose.onNodeWithTag("device_setup_surfaces").assertExists()
        compose.onNodeWithTag("device_setup_complete").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, completed)
            assertEquals(0, skipped)
            assertEquals(1, notificationRequests)
        }
    }

    @Test fun skipIsAvailableBeforeAnyPermissionOrSystemAction() {
        var skipped = 0
        compose.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                DeviceSetupScreen(
                    notificationStatus = NotificationControlStatus.APP_BLOCKED,
                    onManageNotifications = { error("Разрешение без нажатия") },
                    onSkip = { skipped++ },
                    onComplete = { error("Завершение не выбрано") },
                )
            }
        }
        compose.onNodeWithTag("device_setup_skip").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, skipped) }
    }
}
