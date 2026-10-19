package dev.opentimemanager.app.ui.screens

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opentimemanager.app.R
import dev.opentimemanager.app.MainActivity
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.AppControls
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.ReportPeriod
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Проверяет реальное окно редактора, включая IME и перенос пояснения при крупном шрифте. */
@RunWith(AndroidJUnit4::class)
class MonthlyReportWindowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun russianMonthlyPlanCanBeSavedFromShortLandscapeWindow() = assertEditorWorks("ru")

    @Test fun englishMonthlyPlanCanBeSavedFromShortLandscapeWindow() = assertEditorWorks("en")

    private fun assertEditorWorks(locale: String) = inShortLandscape(locale) {
        val month = YearMonth.of(2026, 9)
        val saved = mutableListOf<Pair<YearMonth, Int?>>()
        val state = AppUiState(
            snapshot = AppSnapshot(TrackingSession.idle(), emptyList(), emptyList(), emptyList()),
            controls = AppControls(reportPeriod = ReportPeriod.CalendarMonth(month)),
        )
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    ReportsScreen(
                        state = state,
                        onSelectPeriod = {},
                        onMonthlyTargetChange = { selectedMonth, minutes -> saved.add(selectedMonth to minutes) },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("reports_list").performScrollToNode(hasTestTag("monthly_target_edit"))
        compose.onNodeWithTag("monthly_target_edit").assertIsDisplayed().performClick()
        compose.onNodeWithTag("monthly_target_hours").performScrollTo().assertIsDisplayed()
            .performClick().performTextReplacement("168")
        compose.onNodeWithTag("monthly_target_minutes").performScrollTo().assertIsDisplayed()
            .performClick().performTextReplacement("30")

        val localOnly = compose.onNodeWithText(localizedText(locale, R.string.monthly_plan_local_only), useUnmergedTree = true)
        localOnly.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        localOnly.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Пояснение локального хранения должно быть измерено", layouts.isNotEmpty())
        assertFalse("Пояснение локального хранения обрезано", layouts.any { it.hasVisualOverflow })

        compose.onNodeWithTag("monthly_target_save").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(month to 10_110), saved) }
        compose.onNodeWithTag("monthly_target_hours").assertDoesNotExist()
    }

    private fun localizedText(locale: String, resource: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(locale))
        }
        return context.createConfigurationContext(configuration).getString(resource)
    }

    private fun inShortLandscape(locale: String, test: () -> Unit) {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val originalOrientation = resources.configuration.orientation
        val originalFontScale = resources.configuration.fontScale
        val previousOverride = shell("wm size").lineSequence()
            .firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val previousFontScale = shell("settings get system font_scale").trim()
        lateinit var previousLocales: LocaleListCompat
        compose.activityRule.scenario.onActivity { previousLocales = AppCompatDelegate.getApplicationLocales() }
        val density = resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(700 * density).roundToInt()}x${(280 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                resources.configuration.screenWidthDp > resources.configuration.screenHeightDp &&
                    resources.configuration.fontScale == 2f
            }
            setHostApplicationLocales(LocaleListCompat.forLanguageTags(locale))
            test()
        } finally {
            try {
                // Убираем fixture и её native Dialog до следующих configuration changes, в том числе при assertion failure.
                compose.activityRule.scenario.onActivity { activity ->
                    WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow }.forEach {
                        it.windowInsetsController?.hide(WindowInsets.Type.ime())
                    }
                    activity.setContent {}
                }
                compose.waitForIdle()
            } finally {
                try {
                    shell("wm size ${previousOverride ?: "reset"}")
                } finally {
                    try {
                        shell(if (previousFontScale == "null") "settings delete system font_scale"
                            else "settings put system font_scale $previousFontScale")
                    } finally {
                        setHostApplicationLocales(previousLocales)
                    }
                }
            }
            compose.waitUntil(10_000) {
                resources.configuration.orientation == originalOrientation && resources.configuration.fontScale == originalFontScale
            }
        }
    }

    private fun setHostApplicationLocales(locales: LocaleListCompat) {
        compose.activityRule.scenario.onActivity { AppCompatDelegate.setApplicationLocales(locales) }
        awaitHostApplicationLocales(locales)
        // Новая Activity гарантирует, что Context её ComposeView и будущих Dialog имеют выбранный язык.
        val previousActivity = compose.activity
        compose.activityRule.scenario.recreate()
        awaitHostApplicationLocales(locales, previousActivity)
        compose.waitForIdle()
    }

    private fun awaitHostApplicationLocales(expected: LocaleListCompat, previousActivity: MainActivity? = null) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val expectedLanguage = checkNotNull(expected[0] ?: LocaleManagerCompat.getSystemLocales(app)[0]).language
        compose.waitUntil(10_000) {
            val storedMatches = LocaleManagerCompat.getApplicationLocales(app) == expected
            var ready = false
            compose.activityRule.scenario.onActivity { activity ->
                val viewLanguage = activity.findViewById<ViewGroup>(android.R.id.content)
                    ?.getChildAt(0)?.context?.resources?.configuration?.locales?.get(0)?.language
                ready = storedMatches && activity !== previousActivity && !activity.isChangingConfigurations &&
                    activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    AppCompatDelegate.getApplicationLocales() == expected &&
                    activity.resources.configuration.locales[0].language == expectedLanguage &&
                    viewLanguage == expectedLanguage
            }
            ready
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
