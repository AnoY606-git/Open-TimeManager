package dev.opentimemanager.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
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
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactNavigationLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun russianNavigationLabelsFitAt320DpAndTwoHundredPercentText() = assertLabels("ru", listOf("Таймер", "История", "Отчёты", "Настройки"))

    @Test fun englishNavigationLabelsFitAt320DpAndTwoHundredPercentText() = assertLabels("en", listOf("Timer", "History", "Reports", "Settings"))

    private fun assertLabels(locale: String, labels: List<String>) {
        val overrides = DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 700.dp)) then
            DeviceConfigurationOverride.FontScale(2f) then
            DeviceConfigurationOverride.Locales(LocaleList(Locale(locale)))
        val state = AppUiState(snapshot = AppSnapshot(
            TrackingSession.idle(),
            listOf(ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, "@default", 0, 0)),
            emptyList(), emptyList(),
        ))
        compose.setContent {
            DeviceConfigurationOverride(overrides) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    OpenTimeManagerApp(state, null, NotificationControlStatus.AVAILABLE, {}, AppLanguage.SYSTEM, {})
                }
            }
        }
        val navigation = compose.onNodeWithTag("compact_navigation").getUnclippedBoundsInRoot()
        labels.forEach { label ->
            val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$label extends beyond navigation: $bounds in $navigation", bounds.top >= navigation.top && bounds.bottom <= navigation.bottom)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Missing measured label $label", layouts.isNotEmpty())
            assertFalse("$label is clipped: " + layouts.joinToString { layout ->
                "size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                    "constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}"
            }, layouts.any { it.hasVisualOverflow })
        }
    }
}
