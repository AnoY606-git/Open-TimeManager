package dev.opentimemanager.app.ui.screens

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opentimemanager.app.R
import dev.opentimemanager.app.backup.BackupInspection
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
import kotlin.math.roundToInt

/** Ограничивает отдельное окно диалога реальным размером экрана, включая крупный шрифт. */
@RunWith(AndroidJUnit4::class)
class SettingsWindowLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun automaticDefaultProjectRemainsReachableInShortLandscapeWithLargeText() = inShortLandscape {
        val chosen = mutableListOf<String?>()
        showSettings(SettingsPage.TRACKING, onDefaultProject = chosen::add)
        compose.onNodeWithTag("default_project_choose").performScrollTo().performClick()
        compose.onNodeWithTag("default_project_list")
            .performScrollToNode(hasTestTag("default_project_automatic"))
        compose.onNodeWithTag("default_project_automatic").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf<String?>(null), chosen) }
    }

    @Test
    fun backupPasswordHintRemainsScrollableWithoutTextOverflowInShortLandscape() = inShortLandscape {
        val actions = BackupActions(
            prepare = { error("Создание копии не запрашивалось") },
            cancelPrepare = {}, clearSelectedDocument = {},
            inspect = { _, _ -> BackupInspection.Cancelled },
            currentPreview = { null }, discardPreview = {},
            restore = { _, _ -> error("Восстановление не запрашивалось") },
        )
        showSettings(SettingsPage.DATA, backupActions = actions)
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("create_backup"))
        compose.onNodeWithTag("create_backup").performClick()
        val hintText = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.backup_creation_password_hint)
        val hint = compose.onNodeWithText(hintText, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        hint.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Подсказка пароля должна быть измерена", layouts.isNotEmpty())
        assertFalse("Подсказка пароля обрезана", layouts.any { it.hasVisualOverflow })
        hint.performScrollTo().assertIsDisplayed()
    }

    private fun showSettings(
        page: SettingsPage,
        onDefaultProject: (String?) -> Unit = {},
        backupActions: BackupActions? = null,
    ) {
        compose.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                SettingsScreen(
                    state = AppUiState(),
                    onTheme = {}, onDynamicColor = {}, selectedLanguage = AppLanguage.ENGLISH,
                    onLanguage = {}, onPrivacy = {}, onDailyGoal = {},
                    initialPage = page,
                    onDefaultProject = onDefaultProject,
                    backupActions = backupActions,
                )
            }
        }
    }

    private fun inShortLandscape(test: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resources = instrumentation.targetContext.resources
        val originalOrientation = resources.configuration.orientation
        val previousOverride = shell("wm size").lineSequence()
            .firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val previousFontScale = shell("settings get system font_scale").trim()
        val originalFontScale = resources.configuration.fontScale
        val density = resources.displayMetrics.density
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size ${(700 * density).roundToInt()}x${(280 * density).roundToInt()}")
            compose.waitUntil(10_000) {
                resources.configuration.screenWidthDp > resources.configuration.screenHeightDp &&
                    resources.configuration.fontScale == 2f
            }
            test()
        } finally {
            try {
                shell("wm size ${previousOverride ?: "reset"}")
            } finally {
                shell(if (previousFontScale == "null") "settings delete system font_scale"
                    else "settings put system font_scale $previousFontScale")
            }
            compose.waitUntil(10_000) {
                resources.configuration.orientation == originalOrientation &&
                    resources.configuration.fontScale == originalFontScale
            }
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
