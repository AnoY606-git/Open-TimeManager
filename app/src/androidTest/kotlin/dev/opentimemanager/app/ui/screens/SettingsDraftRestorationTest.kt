package dev.opentimemanager.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.backup.BackupInspection
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Проверяет восстановление несекретного ввода без записи настроек или файлов. */
@RunWith(AndroidJUnit4::class)
class SettingsDraftRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unappliedDailyGoalDraftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val appliedGoals = mutableListOf<Int>()
        restoration.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                SettingsScreen(
                    state = AppUiState(),
                    onTheme = {}, onDynamicColor = {}, selectedLanguage = AppLanguage.ENGLISH,
                    onLanguage = {}, onPrivacy = {}, onDailyGoal = appliedGoals::add,
                    initialPage = SettingsPage.TRACKING,
                )
            }
        }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("120")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("settings_list").performScrollToNode(hasSetTextAction())
        compose.onNode(hasSetTextAction()).assertTextContains("120")
        compose.runOnIdle { assertEquals(emptyList<Int>(), appliedGoals) }
    }

    @Test
    fun defaultProjectPickerRemainsOpenWithItsSearchAfterSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        // Это состояние принадлежит ViewModel и переживает пересоздание UI независимо от Bundle.
        var state by mutableStateOf(AppUiState())
        restoration.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                SettingsScreen(
                    state = state,
                    onTheme = {}, onDynamicColor = {}, selectedLanguage = AppLanguage.ENGLISH,
                    onLanguage = {}, onPrivacy = {}, onDailyGoal = {},
                    initialPage = SettingsPage.TRACKING,
                    onSearchProjects = { query ->
                        state = state.copy(controls = state.controls.copy(
                            manualProjectPicker = state.controls.manualProjectPicker.copy(query = query),
                        ))
                    },
                )
            }
        }
        compose.onNodeWithTag("default_project_choose").performClick()
        compose.onNodeWithTag("default_project_search").performTextInput("Focus")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("default_project_dialog").assertIsDisplayed()
        compose.onNodeWithTag("default_project_search").assertTextContains("Focus")
    }

    @Test
    fun plainBackupWarningRemainsOpenWithoutCreatingBackupAfterSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var prepared = 0
        var launched = 0
        val actions = BackupActions(
            prepare = { error("Зашифрованная копия не запрашивалась") },
            cancelPrepare = {}, clearSelectedDocument = {},
            inspect = { _, _ -> BackupInspection.Cancelled },
            currentPreview = { null }, discardPreview = {},
            restore = { _, _ -> error("Восстановление не запрашивалось") },
            preparePlain = { prepared++; "synthetic.otmplain" },
        )
        restoration.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                SettingsScreen(
                    state = AppUiState(),
                    onTheme = {}, onDynamicColor = {}, selectedLanguage = AppLanguage.ENGLISH,
                    onLanguage = {}, onPrivacy = {}, onDailyGoal = {},
                    initialPage = SettingsPage.DATA,
                    backupActions = actions,
                    onLaunchBackupDocument = { launched++ },
                )
            }
        }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("create_plain_backup"))
        compose.onNodeWithTag("create_plain_backup").performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("plain_backup_warning").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, prepared); assertEquals(0, launched) }
    }
}
