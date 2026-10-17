package dev.opentimemanager.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.theme.LocalAppPalette
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualRedesignTest {
    @get:Rule val compose = createComposeRule()

    private fun state(destination: AppDestination) = AppUiState(
        snapshot = AppSnapshot(
            TrackingSession.idle(),
            listOf(ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, "No project", 0xff6251da.toInt(), 0)),
            emptyList(),
            emptyList(),
        ),
        controls = AppControls(destination = destination),
    )

    private fun show(destination: AppDestination, dark: Boolean = false, fontScale: Float = 1f) {
        val overrides = DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 915.dp)) then
            DeviceConfigurationOverride.FontScale(fontScale) then
            DeviceConfigurationOverride.Locales(LocaleList(Locale("en")))
        compose.setContent {
            DeviceConfigurationOverride(overrides) {
                OpenTimeManagerTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                    OpenTimeManagerApp(
                        state = state(destination),
                        viewModel = null,
                        notificationStatus = NotificationControlStatus.AVAILABLE,
                        onManageNotifications = {},
                        selectedLanguage = AppLanguage.ENGLISH,
                        onLanguage = {},
                    )
                }
            }
        }
    }

    @Test fun navigationKeepsDistinctAccessibleDestinations() {
        show(AppDestination.TIMER)
        listOf("Timer", "History", "Reports", "Settings").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Timer").assertIsSelected()
    }

    @Test fun emptyHistoryRemainsHonestAndAllowsManualEntryAtLargeFont() {
        show(AppDestination.HISTORY, fontScale = 2f)
        compose.onNodeWithContentDescription("Add entry").assertIsDisplayed()
        compose.onNodeWithText("No completed work yet").assertExists()
    }

    @Test fun emptyDarkReportsDoNotFabricateTimeAndUnavailableExportIsDisabled() {
        show(AppDestination.REPORTS, dark = true, fontScale = 1.3f)
        compose.onNodeWithText("0:00").assertIsDisplayed()
        compose.onNodeWithText("No confirmed time in this period").assertExists()
        compose.onNodeWithTag("reports_list").performScrollToNode(hasText("Export this period as CSV"))
        compose.onNodeWithText("Export this period as CSV").assertIsNotEnabled()
    }

    @Test fun lightPaletteKeepsReadableTextAndPrimaryControls() = assertPaletteContrast(ThemeMode.LIGHT)

    @Test fun darkPaletteKeepsReadableTextAndPrimaryControls() = assertPaletteContrast(ThemeMode.DARK)

    private fun assertPaletteContrast(mode: ThemeMode) {
        val pairs = mutableListOf<Pair<Color, Color>>()
        compose.setContent {
            OpenTimeManagerTheme(mode, dynamicColor = false) {
                val colors = MaterialTheme.colorScheme
                val hero = LocalAppPalette.current
                pairs.clear()
                pairs += colors.onSurface to colors.surface
                pairs += colors.onSurfaceVariant to colors.background
                pairs += colors.onPrimary to colors.primary
                pairs += hero.onHero to hero.hero
                pairs += hero.heroMuted to hero.hero
            }
        }
        compose.runOnIdle {
            assertTrue(pairs.isNotEmpty())
            pairs.forEach { (foreground, background) ->
                val a = foreground.luminance()
                val b = background.luminance()
                val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                assertTrue("Contrast $contrast for $foreground on $background", contrast >= 4.5f)
            }
        }
    }
}
