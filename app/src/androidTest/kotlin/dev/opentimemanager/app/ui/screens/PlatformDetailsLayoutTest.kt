package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformDetailsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishSystemHelpIsCollapsedAndCanBeExpandedAtLargeFont() = checkDetails("en")
    @Test fun russianSystemHelpIsCollapsedAndCanBeExpandedAtLargeFont() = checkDetails("ru")

    private fun checkDetails(language: String) {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 700.dp)) then
                    DeviceConfigurationOverride.FontScale(2f) then
                    DeviceConfigurationOverride.Locales(LocaleList(Locale(language))),
            ) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    LazyColumn(Modifier.testTag("platform_test_list")) { item { PlatformIntegrationCard() } }
                }
            }
        }
        compose.onNodeWithTag("platform_compatibility_details").assertDoesNotExist()
        compose.onNodeWithTag("platform_test_list").performScrollToNode(hasTestTag("add_quick_tile"))
        compose.onNodeWithTag("add_quick_tile").assertIsDisplayed()
        compose.onNodeWithTag("platform_test_list").performScrollToNode(hasTestTag("platform_details_toggle"))
        compose.onNodeWithTag("platform_details_toggle").performClick()
        compose.onNodeWithTag("platform_compatibility_details").assertExists()
        compose.onNodeWithTag("platform_details_toggle").performClick()
        compose.onNodeWithTag("platform_compatibility_details").assertDoesNotExist()
    }
}
