package dev.opentimemanager.app.ui

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime execution requires a device; CI still compiles this measured-shell contract. */
@RunWith(AndroidJUnit4::class)
class AdaptiveLocalizedUiTest {
    @get:Rule val compose = createComposeRule()

    private fun state() = AppUiState(
        snapshot = AppSnapshot(
            TrackingSession.idle(),
            listOf(ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, "No project", 0xff006a67.toInt(), 0)),
            emptyList(),
            emptyList(),
        ),
    )

    private fun show(locale: String, theme: ThemeMode, width: Int, height: Int, fontScale: Float) {
        val overrides = DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp)) then
            DeviceConfigurationOverride.FontScale(fontScale) then
            DeviceConfigurationOverride.Locales(LocaleList(Locale(locale)))
        compose.setContent {
            DeviceConfigurationOverride(overrides) {
                OpenTimeManagerTheme(theme, dynamicColor = false) {
                    OpenTimeManagerApp(
                        state = state(),
                        viewModel = null,
                        notificationStatus = NotificationControlStatus.AVAILABLE,
                        onManageNotifications = {},
                        selectedLanguage = if (locale == "ru") AppLanguage.RUSSIAN else AppLanguage.ENGLISH,
                        onLanguage = {},
                    )
                }
            }
        }
    }

    private fun assertPrimaryActionInsideShell() {
        val shell: DpRect = compose.onNodeWithTag("adaptive_shell").getUnclippedBoundsInRoot()
        val action: DpRect = compose.onNodeWithTag("timer_primary_action").getUnclippedBoundsInRoot()
        assertTrue(action.left >= shell.left && action.top >= shell.top)
        assertTrue(action.right <= shell.right && action.bottom <= shell.bottom)
    }

    @Test fun russianCompactLightMeasuresBottomNavigationAndPrimaryAction() {
        show("ru", ThemeMode.LIGHT, 412, 915, 1f)
        compose.onNode(hasTestTag("compact_navigation")).assertExists()
        compose.onNode(hasTestTag("expanded_navigation")).assertDoesNotExist()
        compose.onNode(hasTestTag("timer_today_pane")).assertDoesNotExist()
        compose.onNodeWithText("Начать", substring = false).assertIsDisplayed()
        compose.onNode(hasContentDescription("Прогресс дневной цели — 0%")).assertIsDisplayed()
        assertPrimaryActionInsideShell()
    }

    @Test fun englishCompactDarkAtTwoHundredPercentKeepsActionInsideViewport() {
        show("en", ThemeMode.DARK, 412, 915, 2f)
        compose.onNodeWithText("Start", substring = false).assertIsDisplayed()
        assertPrimaryActionInsideShell()
    }

    @Test fun russianLowHeightLandscapeMeasuresRailWithoutForcingTwoPane() {
        show("ru", ThemeMode.LIGHT, 800, 380, 1f)
        compose.onNode(hasTestTag("expanded_navigation")).assertExists()
        compose.onNode(hasTestTag("compact_navigation")).assertDoesNotExist()
        compose.onNode(hasTestTag("timer_today_pane")).assertDoesNotExist()
        assertPrimaryActionInsideShell()
    }

    @Test fun englishExpandedMeasuredShellExposesTwoUsefulPanes() {
        show("en", ThemeMode.LIGHT, 1_000, 900, 1f)
        compose.onNode(hasTestTag("expanded_navigation")).assertExists()
        compose.onNode(hasTestTag("timer_primary_pane")).assertExists()
        compose.onNode(hasTestTag("timer_today_pane")).assertExists()
        compose.onNodeWithText("Start", substring = false).assertIsDisplayed()
        assertPrimaryActionInsideShell()
    }

    @Test fun englishExpandedDarkKeepsBothPanesAndAccessiblePrimaryAction() {
        show("en", ThemeMode.DARK, 1_000, 900, 1f)
        compose.onNode(hasTestTag("expanded_navigation")).assertExists()
        compose.onNode(hasTestTag("timer_today_pane")).assertExists()
        compose.onNodeWithText("Start", substring = false).assertIsDisplayed()
        assertPrimaryActionInsideShell()
    }

    @Test fun veryShortLandscapeWithLargeTextCanReachLastRailDestination() {
        show("en", ThemeMode.DARK, 800, 280, 2f)
        compose.onNode(hasTestTag("expanded_navigation")).assertExists()
        compose.onNodeWithText("Settings", substring = false).performScrollTo().assertIsDisplayed()
    }
}
