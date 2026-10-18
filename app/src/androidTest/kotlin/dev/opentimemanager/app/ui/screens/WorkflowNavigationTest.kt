package dev.opentimemanager.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opentimemanager.app.backup.BackupInspection
import dev.opentimemanager.app.backup.BackupFormatResult
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.AppControls
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.ManualProjectPickerState
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.backup.BackupFormat
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI-only contracts for secondary navigation and deliberate unprotected exports. */
@RunWith(AndroidJUnit4::class)
class WorkflowNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val project = ProjectEntity("11111111-1111-4111-8111-111111111111", "Focus project", 0xff6251da.toInt(), 1)
    private fun state() = AppUiState(
        snapshot = AppSnapshot(
            TrackingSession.idle(),
            listOf(ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, "@default", 0, 0), project),
            emptyList(), emptyList(),
        ),
        controls = AppControls(manualProjectPicker = ManualProjectPickerState(projects = listOf(project))),
    )

    private fun show(
        page: SettingsPage = SettingsPage.OVERVIEW,
        actions: BackupActions? = null,
        onDocument: (String) -> Unit = {},
        locale: String = "en",
        fontScale: Float = 1.3f,
        onDefault: (String?) -> Unit = {},
        initialState: AppUiState = state(),
    ) {
        val overrides = DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 915.dp)) then
            DeviceConfigurationOverride.FontScale(fontScale) then
            DeviceConfigurationOverride.Locales(LocaleList(Locale(locale)))
        compose.setContent {
            DeviceConfigurationOverride(overrides) {
                OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                    SettingsScreen(
                        state = initialState, onTheme = {}, onDynamicColor = {}, selectedLanguage = AppLanguage.ENGLISH,
                        onLanguage = {}, onPrivacy = {}, onDailyGoal = {}, initialPage = page,
                        backupActions = actions, onLaunchBackupDocument = onDocument,
                        onDefaultProject = onDefault,
                    )
                }
            }
        }
    }

    @Test fun overviewSeparatesAppearanceTrackingDataAndHelpAndReturns() {
        show()
        compose.onNodeWithTag("settings_category_appearance").performClick()
        compose.onNodeWithTag("theme_system").assertIsSelected()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_category_tracking").performClick()
        compose.onNodeWithTag("default_project_choose").assertIsDisplayed()
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_category_data").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("create_backup"))
        compose.onNodeWithTag("create_backup").assertIsDisplayed()
    }

    @Test fun offlineGuideSearchFindsDetailedRussianBackupHelpAtLargeFont() {
        show(page = SettingsPage.HELP, locale = "ru", fontScale = 1.5f)
        compose.onNodeWithTag("help_search").performTextInput(".otmplain")
        compose.onNodeWithTag("help_list").performScrollToNode(hasTestTag("help_body_backup"))
        compose.onNodeWithTag("help_body_backup").assertExists()
        compose.onNodeWithTag("help_list").performScrollToNode(hasTestTag("settings_back"))
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_category_help").assertExists()
    }

    @Test fun plainCopyCannotBeCreatedBeforeWarningAndCancelHasNoSideEffect() {
        var created = 0
        var launched = 0
        show(page = SettingsPage.DATA, actions = actions { created++; "synthetic.otmplain" }, onDocument = { launched++ })
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("create_plain_backup"))
        compose.onNodeWithTag("create_plain_backup").performClick()
        compose.onNodeWithTag("plain_backup_warning").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, created); assertEquals(0, launched) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("plain_backup_warning").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, created); assertEquals(0, launched) }
    }

    @Test fun confirmingPlainCopyPreparesOnceThenOpensDocumentPicker() {
        var created = 0
        val files = mutableListOf<String>()
        show(page = SettingsPage.DATA, actions = actions { created++; "synthetic.otmplain" }, onDocument = files::add)
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("create_plain_backup"))
        compose.onNodeWithTag("create_plain_backup").performClick()
        compose.onNodeWithTag("plain_backup_confirm").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, created); assertEquals(listOf("synthetic.otmplain"), files) }
        compose.onNodeWithTag("plain_backup_warning").assertDoesNotExist()
    }

    @Test fun preferredProjectPickerOffersExplicitAutomaticFallback() {
        val chosen = mutableListOf<String?>()
        show(page = SettingsPage.TRACKING, onDefault = chosen::add)
        compose.onNodeWithTag("default_project_choose").performClick()
        compose.onNodeWithTag("default_choice_${project.id}").performClick()
        compose.runOnIdle { assertEquals(listOf(project.id), chosen) }
        compose.onNodeWithTag("default_project_choose").performClick()
        compose.onNodeWithTag("default_project_automatic").performClick()
        compose.runOnIdle { assertEquals(listOf(project.id, null), chosen) }
    }

    @Test fun unprotectedRestoreWarnsBeforeInspectionAndNeverAsksForPassword() {
        var inspected = 0
        var cancelled = 0
        val actions = BackupActions(
            prepare = { "unused.otmbackup" }, cancelPrepare = {}, clearSelectedDocument = { cancelled++ },
            inspect = { _, _ -> error("Encrypted reader must not be selected") },
            currentPreview = { null }, discardPreview = {}, restore = { _, _ -> error("not used") },
            format = { BackupFormatResult.Detected(BackupFormat.PLAINTEXT) },
            inspectPlain = { inspected++; BackupInspection.Cancelled },
        )
        show(
            page = SettingsPage.DATA, actions = actions,
            initialState = state().copy(controls = AppControls(pendingRestoreUri = "content://synthetic/plain")),
        )
        compose.onNodeWithTag("plain_backup_inspect_warning").assertIsDisplayed()
        compose.onNodeWithTag("backup_password_input").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, inspected) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(0, inspected) }
    }

    @Test fun reportsDoNotShowPreviousTotalsWhileNewPeriodLoads() {
        compose.setContent {
            OpenTimeManagerTheme(ThemeMode.LIGHT, false) {
                ReportsScreen(state().copy(reportLoading = true), onSelectPeriod = {})
            }
        }
        compose.onNodeWithTag("report_loading").assertIsDisplayed()
        compose.onNodeWithText("0:00").assertDoesNotExist()
    }

    private fun actions(plain: suspend () -> String) = BackupActions(
        prepare = { it.fill('\u0000'); "synthetic.otmbackup" }, cancelPrepare = {}, clearSelectedDocument = {},
        inspect = { _, password -> password.fill('\u0000'); BackupInspection.Cancelled },
        currentPreview = { null }, discardPreview = {}, restore = { _, _ -> error("not used") }, preparePlain = plain,
    )
}
