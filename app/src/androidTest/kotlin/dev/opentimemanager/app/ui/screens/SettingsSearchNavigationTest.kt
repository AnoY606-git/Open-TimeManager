package dev.opentimemanager.app.ui.screens

import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opentimemanager.app.R
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Навигация и ввод поиска не применяют настройки и не запускают системные действия. */
@RunWith(AndroidJUnit4::class)
class SettingsSearchNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val writes = mutableListOf<String>()

    private fun show(locale: String = "en", largeText: Boolean = false): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        val overrides = DeviceConfigurationOverride.ForcedSize(
            if (largeText) DpSize(320.dp, 720.dp) else DpSize(360.dp, 915.dp),
        ) then DeviceConfigurationOverride.FontScale(if (largeText) 2f else 1.3f) then
            DeviceConfigurationOverride.Locales(LocaleList(Locale(locale)))
        restoration.setContent {
            DeviceConfigurationOverride(overrides) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    SettingsScreen(
                        state = AppUiState(),
                        onTheme = { writes += "theme" }, onDynamicColor = { writes += "colors" },
                        selectedLanguage = AppLanguage.ENGLISH, onLanguage = { writes += "language" },
                        onPrivacy = { writes += "privacy" }, onDailyGoal = { writes += "goal" },
                        onDefaultProject = { writes += "project" },
                        onManageNotifications = { writes += "notifications" },
                    )
                }
            }
        }
        return restoration
    }

    @Test
    fun exactRussianDailyGoalLabelFindsTrackingSection() {
        searchForSettingLabel("ru", R.string.daily_goal, "settings_category_tracking")
    }

    @Test
    fun exactEnglishDeviceColorsLabelFindsAppearanceSection() {
        searchForSettingLabel("en", R.string.dynamic_color, "settings_category_appearance")
    }

    @Test
    fun exactEnglishPrivacyLabelFindsTrackingSection() {
        searchForSettingLabel("en", R.string.privacy_mode, "settings_category_tracking")
    }

    @Test
    fun exactEnglishPlainCopyActionFindsDataSection() {
        searchForSettingLabel("en", R.string.backup_plain_create, "settings_category_data")
    }

    @Test
    fun exactRussianWidgetActionFindsSystemSection() {
        searchForSettingLabel("ru", R.string.platform_pin_widget, "settings_category_system")
    }

    private fun searchForSettingLabel(languageTag: String, resourceId: Int, expectedSectionTag: String) {
        show(locale = languageTag)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(languageTag))
        }
        val label = context.createConfigurationContext(configuration).getString(resourceId)
        compose.onNodeWithTag("settings_search").performTextInput(label)
        compose.onNodeWithTag(expectedSectionTag).assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), writes) }
    }

    @Test
    fun searchFindsOwningSectionAndBackRestoresQueryUntilExplicitlyCleared() {
        show()
        compose.onNodeWithTag("settings_search").performTextInput("language")
        compose.onNodeWithTag("settings_category_appearance").assertIsDisplayed().performClick()
        compose.onNodeWithTag("theme_system").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_search").assertTextContains("language")
        compose.onNodeWithTag("settings_category_tracking").assertDoesNotExist()
        compose.onNodeWithTag("settings_search_clear").performClick()
        compose.onNodeWithTag("settings_category_tracking").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), writes) }
    }

    @Test
    fun unknownSearchHasRecoverableEmptyState() {
        show()
        compose.onNodeWithTag("settings_search").performTextInput("unfindable")
        compose.onNodeWithTag("settings_search_empty").assertIsDisplayed()
        compose.onNodeWithTag("settings_category_appearance").assertDoesNotExist()
        compose.onNodeWithTag("settings_search_clear").performClick()
        compose.onNodeWithTag("settings_search_empty").assertDoesNotExist()
        compose.onNodeWithTag("settings_category_appearance").assertIsDisplayed()
    }

    @Test
    fun russianWidgetKeywordOpensSystemSectionWithItsExistingControls() {
        show(locale = "ru")
        compose.onNodeWithTag("settings_search").performTextInput("виджет")
        compose.onNodeWithTag("settings_category_system").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("pin_tracking_widget"))
        compose.onNodeWithTag("pin_tracking_widget").assertIsDisplayed()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_back"))
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_search").assertTextContains("виджет")
        compose.runOnIdle { assertEquals(emptyList<String>(), writes) }
    }

    @Test
    fun queryAndOpenedSectionSurviveSavedStateWithoutApplyingPreferences() {
        val restoration = show()
        compose.onNodeWithTag("settings_search").performTextInput("theme")
        compose.onNodeWithTag("settings_category_appearance").performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("theme_system").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_search").assertTextContains("theme")
        compose.runOnIdle { assertEquals(emptyList<String>(), writes) }
    }

    @Test
    fun overviewScrollSurvivesSavedStateRestoration() {
        val restoration = show(locale = "ru", largeText = true)
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_category_system"))
        val previousScroll = compose.onNodeWithTag("settings_list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Обзор должен быть прокручен до восстановления", previousScroll > 0f)

        restoration.emulateSavedInstanceStateRestore()

        val restoredScroll = compose.onNodeWithTag("settings_list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("Позиция обзора должна восстановиться", previousScroll, restoredScroll, 0.001f)
    }

    @Test
    fun systemCategoryIsReachableWithUnclippedTextAtNarrowWidthAndDoubleFontScale() {
        show(locale = "ru", largeText = true)
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_category_system"))
        compose.onNodeWithTag("settings_category_system").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Android и устройство", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Заголовок системного раздела должен быть измерен", layouts.isNotEmpty())
        assertFalse("Заголовок системного раздела обрезан", layouts.any { it.hasVisualOverflow })
        compose.onNodeWithTag("settings_category_system").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("pin_tracking_widget"))
        compose.onNodeWithTag("pin_tracking_widget").assertIsDisplayed()
    }
}
