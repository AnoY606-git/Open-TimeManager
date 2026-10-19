package dev.opentimemanager.app.ui.screens

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opentimemanager.app.MainActivity
import dev.opentimemanager.app.R
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.AppControls
import dev.opentimemanager.app.ui.AppDestination
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.NotificationControlStatus
import dev.opentimemanager.app.ui.OpenTimeManagerApp
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Проверяет поиск внутри оболочки приложения с настоящей IME и системным масштабом текста. */
@RunWith(AndroidJUnit4::class)
class SettingsSearchWindowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun russianSearchRemainsVisibleAboveImeAt320DpAndTwoHundredPercentText() = assertSearchWorks("ru")

    @Test
    fun englishSearchRemainsVisibleAboveImeAt320DpAndTwoHundredPercentText() = assertSearchWorks("en")

    private fun assertSearchWorks(locale: String) = inNarrowWindow(locale) {
        val state = AppUiState(
            snapshot = AppSnapshot(TrackingSession.idle(), emptyList(), emptyList(), emptyList()),
            controls = AppControls(destination = AppDestination.SETTINGS),
        )
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    OpenTimeManagerApp(
                        state = state,
                        viewModel = null,
                        notificationStatus = NotificationControlStatus.AVAILABLE,
                        onManageNotifications = {},
                        selectedLanguage = if (locale == "ru") AppLanguage.RUSSIAN else AppLanguage.ENGLISH,
                        onLanguage = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("compact_navigation").assertIsDisplayed()
        compose.onNodeWithTag("expanded_navigation").assertDoesNotExist()

        val query = localizedText(locale, R.string.theme)
        val search = compose.onNodeWithTag("settings_search")
        search.assertIsDisplayed().performClick()
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.decorView.windowInsetsController?.show(WindowInsets.Type.ime())
        }
        awaitImeVisibility(visible = true)
        search.performTextReplacement(query)
        compose.waitForIdle()

        // Нижняя панель не должна отнимать место у поиска, пока открыта системная клавиатура.
        compose.onNodeWithTag("compact_navigation").assertDoesNotExist()
        // Не прокручиваем поле из теста: ввод сам должен сохранить его целиком в доступной области.
        val viewport = compose.onNodeWithTag("settings_list").getUnclippedBoundsInRoot()
        val field = search.assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Поле поиска обрезано: $field, доступная область: $viewport",
            field.right > field.left && field.bottom > field.top &&
                field.left >= viewport.left && field.right <= viewport.right &&
                field.top >= viewport.top && field.bottom <= viewport.bottom)
        search.assertTextContains(query)
        val result = compose.onNodeWithTag("settings_category_appearance").getUnclippedBoundsInRoot()
        assertTrue("Первый результат обрезан: $result, доступная область: $viewport",
            result.left >= viewport.left && result.right <= viewport.right &&
                result.top >= viewport.top && result.bottom <= viewport.bottom)
        compose.onNodeWithTag("settings_category_appearance").assertIsDisplayed().performClick()
        awaitImeVisibility(visible = false)
        compose.onNodeWithTag("compact_navigation").assertIsDisplayed()
        compose.onNodeWithTag("settings_search").assertDoesNotExist()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("theme_system"))
        compose.onNodeWithTag("theme_system").assertIsDisplayed()

        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_back"))
        compose.onNodeWithTag("settings_back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings_search").assertTextContains(query)
        compose.onNodeWithTag("settings_category_tracking").assertDoesNotExist()
        compose.onNodeWithTag("compact_navigation").assertIsDisplayed()
    }

    private fun awaitImeVisibility(visible: Boolean) {
        var previousBottom = -1
        var stableSince = SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            var matches = false
            compose.activityRule.scenario.onActivity { activity ->
                val insets = activity.window.decorView.rootWindowInsets
                val bottom = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: -1
                val now = SystemClock.uptimeMillis()
                if (bottom != previousBottom) {
                    previousBottom = bottom
                    stableSince = now
                }
                // Ждём и видимость, и завершение изменения высоты системной IME.
                matches = insets?.isVisible(WindowInsets.Type.ime()) == visible &&
                    (if (visible) bottom > 0 else bottom == 0) && now - stableSince >= 250
            }
            matches
        }
        compose.waitForIdle()
    }

    private fun localizedText(locale: String, resource: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(locale))
        }
        return context.createConfigurationContext(configuration).getString(resource)
    }

    private fun inNarrowWindow(locale: String, test: () -> Unit) {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val originalConfiguration = Configuration(resources.configuration)
        val previousOverride = shell("wm size").lineSequence()
            .firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val previousFontScale = shell("settings get system font_scale").trim()
        lateinit var previousLocales: LocaleListCompat
        compose.activityRule.scenario.onActivity { previousLocales = AppCompatDelegate.getApplicationLocales() }
        val density = resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(320 * density).roundToInt()}x${(720 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                resources.configuration.screenWidthDp in 319..321 &&
                    resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    resources.configuration.fontScale == 2f
            }
            setHostApplicationLocales(LocaleListCompat.forLanguageTags(locale))
            test()
        } finally {
            try {
                // Снимаем фокус и удаляем fixture до configuration changes даже при assertion failure.
                compose.activityRule.scenario.onActivity { activity ->
                    activity.currentFocus?.clearFocus()
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
                resources.configuration.screenWidthDp == originalConfiguration.screenWidthDp &&
                    resources.configuration.screenHeightDp == originalConfiguration.screenHeightDp &&
                    resources.configuration.fontScale == originalConfiguration.fontScale
            }
        }
    }

    private fun setHostApplicationLocales(locales: LocaleListCompat) {
        compose.activityRule.scenario.onActivity { AppCompatDelegate.setApplicationLocales(locales) }
        awaitHostApplicationLocales(locales)
        // Пересоздаём Activity, чтобы Context ComposeView также получил выбранный язык.
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
