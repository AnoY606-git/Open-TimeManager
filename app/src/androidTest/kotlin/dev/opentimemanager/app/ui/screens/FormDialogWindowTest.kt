package dev.opentimemanager.app.ui.screens

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.os.LocaleListCompat
import dev.opentimemanager.app.MainActivity
import dev.opentimemanager.app.R
import dev.opentimemanager.app.backup.BackupCounts
import dev.opentimemanager.app.backup.BackupInspection
import dev.opentimemanager.app.backup.BackupPreview
import dev.opentimemanager.app.backup.RestorePayload
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.AppControls
import dev.opentimemanager.app.ui.NotificationControlStatus
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.backup.PortablePreferences
import dev.opentimemanager.core.backup.BackupFormat
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.roundToInt

/** Размер меняется у настоящего окна: ForcedSize не ограничивает отдельный Dialog. */
@RunWith(Parameterized::class)
class FormDialogWindowTest(private val locale: String) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val ru get() = locale == "ru"
    private val project = ProjectEntity("focus", "Focus", 0xff6251da.toInt(), 0)
    private val state get() = AppUiState(snapshot = AppSnapshot(
        TrackingSession.idle(), listOf(project), emptyList(), emptyList(),
    ))

    @Test fun createProjectFieldAndSubmitRemainReachable() = inShortWindow {
        var created: String? = null
        show {
            TimerScreen(state, true, {}, {}, {}, { name, _ -> created = name }, {}, {}, {}, {},
                NotificationControlStatus.AVAILABLE, {})
        }
        compose.onNodeWithTag("timer_project_picker").performScrollTo().performClick()
        val create = if (ru) "Создать проект" else "Create project"
        compose.onNodeWithTag("project_picker_list").performScrollToNode(androidx.compose.ui.test.hasText(create))
        compose.onNodeWithText(create).performScrollTo().performClick()
        compose.onNodeWithTag("project_create_name").performScrollTo().assertIsDisplayed().performTextInput("Design")
        hideKeyboard()
        compose.onNodeWithTag("project_create_confirm").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Design", created) }
    }

    @Test fun projectNameKeepsFocusAndInputWhenKeyboardResizesPortraitWindow() = inShortWindow(360, 800) {
        var created: String? = null
        show {
            TimerScreen(state, true, {}, {}, {}, { name, _ -> created = name }, {}, {}, {}, {},
                NotificationControlStatus.AVAILABLE, {})
        }
        compose.onNodeWithTag("timer_project_picker").performScrollTo().performClick()
        val create = if (ru) "Создать проект" else "Create project"
        compose.onNodeWithTag("project_picker_list").performScrollToNode(androidx.compose.ui.test.hasText(create))
        compose.onNodeWithText(create).performClick()
        val field = compose.onNodeWithTag("project_create_name")
        field.performScrollTo().performClick().performTextInput("Design")
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                android.view.inspector.WindowInspector.getGlobalWindowViews().any {
                    it.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }
            }
        }
        field.assertIsFocused().performTextInput(" Lab")
        hideKeyboard()
        compose.onNodeWithTag("project_create_confirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Design Lab", created) }
    }

    @Test fun manualEntryProjectAndDateRemainReachable() = inShortWindow {
        show {
            HistoryScreen(state, {}, { _, _, _ -> }, { _, _, _ -> }, {}, {}, {}, {}, {})
        }
        compose.onNodeWithContentDescription(if (ru) "Добавить запись" else "Add entry").performScrollTo().performClick()
        compose.onNodeWithTag("entry_project_picker").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(if (ru) "Дата (ГГГГ-ММ-ДД)" else "Date (YYYY-MM-DD)")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun durationLabelDoesNotCrowdPreviousFieldAtLargeFont() = inShortWindow(320, 800) {
        show {
            HistoryScreen(state, {}, { _, _, _ -> }, { _, _, _ -> }, {}, {}, {}, {}, {})
        }
        compose.onNodeWithContentDescription(if (ru) "Добавить запись" else "Add entry").performScrollTo().performClick()
        val label = compose.onNodeWithText(compose.activity.getString(R.string.entry_duration_minutes), useUnmergedTree = true)
        label.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Единицы длительности должны помещаться в одну строку над рамкой поля", 1, layouts.single().lineCount)
    }

    @Test fun backupPasswordInputAndConfirmationRemainReachable() = inShortWindow {
        showSettings()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("create_backup"))
        compose.onNodeWithTag("create_backup").performClick()
        compose.onNodeWithTag("backup_password_input").performScrollTo().assertIsDisplayed()
            .performTextInput("synthetic-password")
        hideKeyboard()
        compose.onNodeWithTag("backup_password_continue").performScrollTo().assertIsDisplayed()
    }

    @Test fun previewWarningsAndEveryActionRemainReachable() = inShortWindow {
        val preview = BackupPreview(BackupCounts(1, 0, 0), emptyList(), 0, false,
            RestorePayload(listOf(project), emptyList(), emptyList(), PortablePreferences(), "layout-test", 2))
        showSettings(preview)
        compose.onNodeWithTag("backup_preview_retention_hint").performScrollTo().assertIsDisplayed()
        listOf("merge", "replace", "hide", "discard").forEach {
            compose.onNodeWithTag("backup_preview_$it").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun plainPreviewFailureAutomaticallyBecomesVisible() = inShortWindow {
        val preview = BackupPreview(BackupCounts(1, 0, 0), emptyList(), 0, false,
            RestorePayload(listOf(project), emptyList(), emptyList(), PortablePreferences(), "layout-test", 2),
            BackupFormat.PLAINTEXT)
        showSettings(preview, RestoreResult.ActiveTimerBlocked(TrackingState.PAUSED))
        compose.waitUntil(10_000) { compose.onNodeWithTag("backup_preview_failure").isDisplayed() }
        compose.onNodeWithTag("backup_preview_failure").assertIsDisplayed()
    }

    private fun showSettings(preview: BackupPreview? = null, result: RestoreResult? = null) = show {
        SettingsScreen(state.copy(controls = AppControls(lastRestoreResult = result)), {}, {}, if (ru) AppLanguage.RUSSIAN else AppLanguage.ENGLISH,
            {}, {}, {}, initialPage = SettingsPage.DATA,
            backupActions = BackupActions(prepare = { "unused" }, cancelPrepare = {}, clearSelectedDocument = {},
                inspect = { _, _ -> BackupInspection.Cancelled }, currentPreview = { preview },
                discardPreview = {}, restore = { _, _ -> error("not requested") }))
    }

    private fun show(content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
                OpenTimeManagerTheme(if (ru) ThemeMode.LIGHT else ThemeMode.DARK, false, content)
        } }
    }

    private fun hideKeyboard() {
        // Закрывает только IME, не диалог, перед прокруткой к подтверждению.
        compose.runOnUiThread {
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach {
                it.windowInsetsController?.hide(android.view.WindowInsets.Type.ime())
            }
        }
        compose.waitUntil(10_000) {
            compose.runOnUiThread {
                android.view.inspector.WindowInspector.getGlobalWindowViews().none {
                    it.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }
            }
        }
    }

    private fun inShortWindow(widthDp: Int = 800, heightDp: Int = 280, test: () -> Unit) {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val previousSize = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }
            ?.substringAfter(":")?.trim()
        val previousFont = shell("settings get system font_scale").trim()
        val originalOrientation = resources.configuration.orientation
        val originalFont = resources.configuration.fontScale
        val density = resources.displayMetrics.density
        val previousLocales = AppCompatDelegate.getApplicationLocales()
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(widthDp * density).roundToInt()}x${(heightDp * density).roundToInt()}")
            compose.waitUntil(10_000) {
                (resources.configuration.screenWidthDp > resources.configuration.screenHeightDp) == (widthDp > heightDp) &&
                    resources.configuration.fontScale == 2f
            }
            setLocales(LocaleListCompat.forLanguageTags(locale))
            test()
        } finally {
            shell("wm size ${previousSize ?: "reset"}")
            shell(if (previousFont == "null") "settings delete system font_scale" else "settings put system font_scale $previousFont")
            setLocales(previousLocales)
            compose.waitUntil(10_000) { resources.configuration.orientation == originalOrientation && resources.configuration.fontScale == originalFont }
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun setLocales(locales: LocaleListCompat) {
        compose.activityRule.scenario.onActivity { AppCompatDelegate.setApplicationLocales(locales) }
        awaitLocales(locales)
        val previousActivity = compose.activity
        compose.activityRule.scenario.recreate()
        awaitLocales(locales, previousActivity)
        compose.waitForIdle()
    }

    private fun awaitLocales(locales: LocaleListCompat, previousActivity: MainActivity? = null) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val expectedLanguage = checkNotNull(locales[0] ?: androidx.core.app.LocaleManagerCompat.getSystemLocales(app)[0]).language
        compose.waitUntil(10_000) {
            val stored = androidx.core.app.LocaleManagerCompat.getApplicationLocales(app) == locales
            var ready = false
            compose.activityRule.scenario.onActivity { activity ->
                val viewLanguage = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    ?.getChildAt(0)?.context?.resources?.configuration?.locales?.get(0)?.language
                ready = stored && activity !== previousActivity && !activity.isChangingConfigurations &&
                    activity.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED &&
                    AppCompatDelegate.getApplicationLocales() == locales &&
                    activity.resources.configuration.locales[0].language == expectedLanguage && viewLanguage == expectedLanguage
            }
            ready
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-font200-short-window")
        fun parameters() = listOf(arrayOf("ru"), arrayOf("en"))
    }
}
