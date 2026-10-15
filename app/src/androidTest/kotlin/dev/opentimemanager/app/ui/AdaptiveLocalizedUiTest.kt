package dev.opentimemanager.app.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device/CI matrix contract. This task verifies the instrumentation source by compilation only. */
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

    private fun show(locale: String, theme: ThemeMode, width: Int, height: Int, fontScale: Float, layout: NavigationLayout) {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale))
            screenWidthDp = width
            screenHeightDp = height
            this.fontScale = fontScale
        }
        val localized = base.createConfigurationContext(config)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                OpenTimeManagerTheme(theme, dynamicColor = false) {
                    AdaptiveTimerSurface(state(), layout, timerUsesCompactLayout(height, fontScale))
                }
            }
        }
    }

    @Test fun russianCompactLightShowsPrimaryActionAndGoalSemantics() {
        show("ru", ThemeMode.LIGHT, 412, 915, 1f, NavigationLayout.BOTTOM)
        compose.onNodeWithText("Начать", substring = false).assertIsDisplayed()
        compose.onNode(hasContentDescription("Прогресс дневной цели — 0%")).assertIsDisplayed()
    }

    @Test fun englishCompactDarkAtTwoHundredPercentKeepsStartVisible() {
        show("en", ThemeMode.DARK, 412, 915, 2f, NavigationLayout.BOTTOM)
        compose.onNodeWithText("Start", substring = false).assertIsDisplayed()
    }

    @Test fun russianLowHeightLandscapeKeepsPrimaryActionVisible() {
        show("ru", ThemeMode.LIGHT, 800, 380, 1f, NavigationLayout.RAIL)
        compose.onNodeWithText("Начать", substring = false).assertIsDisplayed()
    }

    @Test fun englishExpandedSurfaceExposesTwoUsefulPanes() {
        show("en", ThemeMode.LIGHT, 1_000, 900, 1f, NavigationLayout.TWO_PANE)
        compose.onNode(hasTestTag("timer_primary_pane")).assertExists()
        compose.onNode(hasTestTag("timer_today_pane")).assertExists()
        compose.onNodeWithText("Start", substring = false).assertIsDisplayed()
    }
}
