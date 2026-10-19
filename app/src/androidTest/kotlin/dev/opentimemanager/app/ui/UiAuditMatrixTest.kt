package dev.opentimemanager.app.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import dev.opentimemanager.app.MainActivity
import dev.opentimemanager.app.R
import dev.opentimemanager.app.backup.BackupInspection
import dev.opentimemanager.app.backup.BackupCounts
import dev.opentimemanager.app.backup.BackupPreview
import dev.opentimemanager.app.backup.RestorePayload
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserPreferences
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.screens.BackupActions
import dev.opentimemanager.app.ui.screens.DeviceSetupScreen
import dev.opentimemanager.app.ui.screens.ReportsScreen
import dev.opentimemanager.app.ui.screens.SettingsPage
import dev.opentimemanager.app.ui.screens.SettingsScreen
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.backup.PortablePreferences
import dev.opentimemanager.core.report.ReportAggregator
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Матрица ловит потерю доступных действий и переполнение текста при смене языка/масштаба.
 * Маршруты оболочки задаются состоянием: null ViewModel не проверяет её навигацию и запись в БД.
 * Внутренние переходы и открытие/закрытие диалогов выполняют реальные обработчики экранов.
 * Снимки — диагностические артефакты, а не автоматически обновляемые эталоны.
 */
@RunWith(Parameterized::class)
class UiAuditMatrixTest(
    private val languageTag: String,
    private val theme: ThemeMode,
    private val fontScale: Float,
) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var originalApplicationLocales: LocaleListCompat
    private var originalSystemFontScale: String? = null
    private var originalFontScale = 1f

    private enum class Content { APP, SETTINGS, BACKUP_PREVIEW, MONTHLY_REPORT, DEVICE_SETUP }
    private data class Screen(
        val content: Content = Content.APP,
        val destination: AppDestination = AppDestination.TIMER,
        val settingsPage: SettingsPage = SettingsPage.OVERVIEW,
        val widthDp: Int = 360,
        val serial: Int = 0,
    )

    private var screen by mutableStateOf(Screen())
    private val failures = mutableListOf<String>()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val projectName = if (languageTag == "ru") "Разработка приложения" else "Application development"
    private val note = if (languageTag == "ru") "Проверка интерфейса" else "Interface audit"
    private val project = ProjectEntity(PROJECT_ID, projectName, 0xff6251da.toInt(), 1)
    private var selectedDefault: String? = null
    private var selectedMonthlyTarget: Pair<YearMonth, Int?>? = null
    private var externalActions = 0
    private var setupCompletions = 0
    private var setupSkips = 0
    private var retainedPreview: BackupPreview? = null

    private val backupActions = BackupActions(
        prepare = { error("Матрица не должна создавать файл") },
        cancelPrepare = {},
        clearSelectedDocument = {},
        inspect = { _, _ -> BackupInspection.Cancelled },
        currentPreview = { retainedPreview },
        discardPreview = { retainedPreview?.discard(); retainedPreview = null },
        restore = { _, _ -> error("Матрица не должна восстанавливать данные") },
        preparePlain = { error("Матрица не должна создавать файл без защиты") },
    )

    @Before
    fun applyRealHostLanguageAndFontScale() {
        compose.activityRule.scenario.onActivity { activity ->
            originalApplicationLocales = AppCompatDelegate.getApplicationLocales()
            originalFontScale = activity.resources.configuration.fontScale
        }
        originalSystemFontScale = shell("settings get system font_scale").trim()
        shell("settings put system font_scale $fontScale")
        awaitFontScale(fontScale)
        setHostApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }

    @After
    fun restoreHostLanguageAndFontScale() {
        retainedPreview?.discard()
        retainedPreview = null
        originalSystemFontScale?.let { previous ->
            shell(if (previous == "null") "settings delete system font_scale" else "settings put system font_scale $previous")
            awaitFontScale(originalFontScale)
        }
        if (::originalApplicationLocales.isInitialized) setHostApplicationLocales(originalApplicationLocales)
    }

    @Test
    fun localizedActionsAndTextRemainUsableAcrossPrimaryScreensSettingsAndDialogs() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            val current = screen
            val overrides = DeviceConfigurationOverride.ForcedSize(DpSize(current.widthDp.dp, 800.dp))
            DeviceConfigurationOverride(overrides) {
                OpenTimeManagerTheme(theme, dynamicColor = false) {
                    key(current.serial) {
                        val state = fixture(current.destination)
                        when (current.content) {
                            Content.APP -> OpenTimeManagerApp(
                                state, null, NotificationControlStatus.AVAILABLE, {}, language(), {},
                            )
                            Content.SETTINGS, Content.BACKUP_PREVIEW -> SettingsScreen(
                                state = state,
                                onTheme = {}, onDynamicColor = {}, selectedLanguage = language(), onLanguage = {},
                                onPrivacy = {}, onDailyGoal = {}, initialPage = current.settingsPage,
                                onDefaultProject = { selectedDefault = it }, backupActions = backupActions,
                                onLaunchBackupDocument = { externalActions++ },
                                onLaunchOpenBackupDocument = { externalActions++ },
                                onOpenDeviceSetup = { screen = screen.copy(content = Content.DEVICE_SETUP, serial = screen.serial + 1) },
                            )
                            Content.MONTHLY_REPORT -> ReportsScreen(
                                state.copy(controls = state.controls.copy(reportPeriod = ReportPeriod.MONTH)),
                                onSelectPeriod = {},
                                onMonthlyTargetChange = { month, minutes -> selectedMonthlyTarget = month to minutes },
                            )
                            Content.DEVICE_SETUP -> DeviceSetupScreen(
                                notificationStatus = NotificationControlStatus.PERMISSION_REQUESTABLE,
                                onManageNotifications = { externalActions++ },
                                onSkip = { setupSkips++; screen = Screen(Content.SETTINGS, settingsPage = SettingsPage.SYSTEM, serial = screen.serial + 1) },
                                onComplete = { setupCompletions++; screen = Screen(Content.SETTINGS, settingsPage = SettingsPage.SYSTEM, serial = screen.serial + 1) },
                            )
                        }
                    }
                }
            }
        } }

        audit("timer") {
            show(AppDestination.TIMER)
            checkpoint("timer_top")
            assertNavigation(AppDestination.TIMER)
            compose.onNodeWithTag("timer_primary_action").performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertInsideShell("timer_primary_action")
            checkpoint("timer_primary_action")
            compose.onNodeWithTag("timer_project_picker").performScrollTo().performClick()
            compose.onNodeWithTag("project_picker_list").performScrollToNode(hasTestTag("project_choice_$PROJECT_ID"))
            compose.onNodeWithTag("project_choice_$PROJECT_ID").assertIsDisplayed().assertHasClickAction()
            checkpoint("timer_project_picker")
            compose.onNodeWithTag("project_picker_list").performScrollToNode(hasText(text(R.string.create_project)))
            compose.onNodeWithText(text(R.string.create_project)).performClick()
            reachable(compose.onNodeWithTag("project_create_name"))
            reachable(compose.onNodeWithTag("project_create_confirm")).assertIsNotEnabled()
            checkpoint("timer_create_project")
            cancelDialog()
        }

        audit("history") {
            show(AppDestination.HISTORY)
            checkpoint("history_top")
            assertNavigation(AppDestination.HISTORY)
            compose.onNodeWithTag("history_list").performScrollToNode(hasText(note))
            compose.onNodeWithText(note).assertIsDisplayed()
            checkpoint("history_entry")
            compose.onNodeWithContentDescription(text(R.string.edit_entry)).performScrollTo().performClick()
            reachable(compose.onNodeWithTag("entry_project_picker")).assertHasClickAction()
            reachable(compose.onNodeWithText(text(R.string.save))).assertHasClickAction()
            checkpoint("history_editor")
            reachable(compose.onNodeWithTag("entry_project_picker")).performClick()
            compose.onNodeWithTag("project_picker_list").performScrollToNode(hasTestTag("project_choice_$PROJECT_ID"))
            compose.onNodeWithTag("project_choice_$PROJECT_ID").assertIsDisplayed()
            checkpoint("history_editor_projects")
            compose.onNodeWithTag("project_picker_list").performScrollToNode(hasText(text(R.string.close)))
            compose.onNodeWithText(text(R.string.close)).performClick()
            cancelDialog()
            compose.onNodeWithTag("history_list").performScrollToNode(hasContentDescription(text(R.string.add_manual_entry)))
            compose.onNodeWithContentDescription(text(R.string.add_manual_entry)).performClick()
            checkpoint("history_manual_entry")
            cancelDialog()
        }

        audit("reports") {
            show(AppDestination.REPORTS)
            checkpoint("reports_top")
            assertNavigation(AppDestination.REPORTS)
            compose.onNodeWithTag("reports_list").performScrollToNode(hasText(text(R.string.report_custom)))
            compose.onNodeWithText(text(R.string.report_custom)).performClick()
            reachable(compose.onNodeWithText(text(R.string.apply))).assertHasClickAction()
            checkpoint("reports_custom_period")
            cancelDialog()
            compose.onNodeWithTag("reports_list").performScrollToNode(hasText(projectName))
            checkpoint("reports_project_breakdown")
            compose.onNodeWithTag("reports_list").performScrollToNode(hasText(text(R.string.export_csv)))
            compose.onNodeWithText(text(R.string.export_csv)).assertIsDisplayed().assertIsNotEnabled()
            checkpoint("reports_export")
        }

        audit("history_delete_confirmation") {
            show(AppDestination.HISTORY)
            compose.onNodeWithTag("history_list").performScrollToNode(hasContentDescription(text(R.string.delete_entry)))
            compose.onNodeWithContentDescription(text(R.string.delete_entry)).performClick()
            checkpoint("history_delete_confirmation")
            reachable(compose.onNodeWithText(text(R.string.delete_entry_question)))
            reachable(compose.onNode(hasText(text(R.string.delete_entry)) and hasClickAction())).assertIsEnabled()
            cancelDialog()
            compose.onNodeWithText(text(R.string.delete_entry_question)).assertDoesNotExist()
            compose.onNodeWithTag("history_list").performScrollToNode(hasText(note))
            compose.onNodeWithText(note).assertIsDisplayed()
        }

        audit("monthly_report") {
            show(content = Content.MONTHLY_REPORT)
            compose.onNodeWithTag("reports_list").performScrollToNode(hasTestTag("monthly_target_edit"))
            compose.onNodeWithTag("monthly_target_edit").assertIsDisplayed().assertIsEnabled()
            checkpoint("reports_monthly_balance")
            compose.onNodeWithTag("monthly_target_edit").performClick()
            checkpoint("reports_monthly_plan")
            compose.onNodeWithTag("monthly_target_hours").performScrollTo().performTextReplacement("160")
            compose.onNodeWithTag("monthly_target_minutes").performScrollTo().performTextReplacement("30")
            compose.onNodeWithTag("monthly_target_save").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(YearMonth.from(today) to 9_630, selectedMonthlyTarget) }
        }

        audit("settings_shell") {
            show(AppDestination.SETTINGS)
            checkpoint("settings_shell")
            assertNavigation(AppDestination.SETTINGS)
        }
        SettingsPage.entries.filter { it != SettingsPage.OVERVIEW }.forEach { page ->
            audit("settings_${page.name.lowercase()}") { auditSettingsPage(page) }
        }
        LegalDocument.entries.forEach { document ->
            audit("legal_${document.name.lowercase()}") {
                show(content = Content.SETTINGS, page = SettingsPage.ABOUT)
                val title = text(document.titleResource)
                val close = text(R.string.close)
                compose.onNodeWithTag("settings_list").performScrollToNode(hasText(title))
                compose.onNodeWithText(title).performClick()
                val loadedBody = SemanticsMatcher("загруженный текст юридического документа") { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any {
                        it.text.isNotBlank() && it.text != title && it.text != close
                    } == true
                } and hasAnyAncestor(isDialog())
                compose.waitUntil(10_000) {
                    compose.onAllNodes(loadedBody, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                checkpoint("legal_${document.name.lowercase()}")
                reachable(compose.onNodeWithText(close)).performClick()
                compose.onAllNodes(loadedBody, useUnmergedTree = true).assertCountEquals(0)
            }
        }
        audit("device_setup") {
            show(content = Content.SETTINGS, page = SettingsPage.SYSTEM)
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_device_setup"))
            compose.onNodeWithTag("open_device_setup").performClick()
            checkpoint("device_setup_identity")
            reachable(compose.onNodeWithTag("device_setup_device"))
            compose.onNodeWithTag("device_setup_next").performScrollTo().performClick()
            checkpoint("device_setup_notifications")
            reachable(compose.onNodeWithTag("device_setup_manage_notifications")).assertIsEnabled()
            compose.onNodeWithTag("device_setup_next").performScrollTo().performClick()
            checkpoint("device_setup_surfaces")
            reachable(compose.onNodeWithTag("device_setup_surfaces"))
            compose.onNodeWithTag("device_setup_complete").performScrollTo().performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("open_device_setup"))
            compose.onNodeWithTag("open_device_setup").performClick()
            checkpoint("device_setup_reopened")
            reachable(compose.onNodeWithTag("device_setup_device"))
            compose.onNodeWithTag("device_setup_skip").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(1, setupCompletions); assertEquals(1, setupSkips) }
        }
        audit("settings_default_project") {
            show(content = Content.SETTINGS, page = SettingsPage.TRACKING)
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("default_project_choose"))
            compose.onNodeWithTag("default_project_choose").performClick()
            compose.onNodeWithTag("default_project_list").performScrollToNode(hasTestTag("default_choice_$PROJECT_ID"))
            compose.onNodeWithTag("default_choice_$PROJECT_ID").assertIsDisplayed()
            checkpoint("settings_default_project")
            compose.onNodeWithTag("default_choice_$PROJECT_ID").performClick()
            compose.onNodeWithTag("default_project_dialog").assertDoesNotExist()
            compose.runOnIdle { assertEquals(PROJECT_ID, selectedDefault) }
        }
        audit("settings_backup_password") {
            show(content = Content.SETTINGS, page = SettingsPage.DATA)
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("create_backup"))
            compose.onNodeWithTag("create_backup").assertIsEnabled().performClick()
            reachable(compose.onNodeWithTag("backup_password_input"))
            reachable(compose.onNodeWithTag("backup_password_continue")).assertIsNotEnabled()
            checkpoint("settings_backup_password")
            compose.onNodeWithText(text(R.string.backup_creation_password_hint), useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            checkpoint("settings_backup_password_hint")
            cancelDialog()
            compose.onNodeWithTag("backup_password_input").assertDoesNotExist()
        }
        audit("settings_plain_backup_warning") {
            show(content = Content.SETTINGS, page = SettingsPage.DATA)
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("create_plain_backup"))
            compose.onNodeWithTag("create_plain_backup").assertIsEnabled().performClick()
            reachable(compose.onNodeWithTag("plain_backup_confirm")).assertIsEnabled()
            checkpoint("settings_plain_backup_warning")
            cancelDialog()
            compose.onNodeWithTag("plain_backup_warning").assertDoesNotExist()
        }
        audit("settings_backup_preview") {
            retainedPreview = BackupPreview(
                counts = BackupCounts(1, 0, 0), conflicts = emptyList(),
                skippedEntriesWithoutConfirmedTime = 0, activeStateWillBeStopped = false,
                payload = RestorePayload(listOf(project), emptyList(), emptyList(), PortablePreferences(), "ui-audit-preview", 2),
            )
            show(content = Content.BACKUP_PREVIEW, page = SettingsPage.DATA)
            compose.onNodeWithTag("backup_preview_dialog").assertIsDisplayed()
            reachable(compose.onNodeWithTag("backup_preview_merge")).assertIsEnabled()
            reachable(compose.onNodeWithTag("backup_preview_replace")).assertIsEnabled()
            checkpoint("settings_backup_preview")
            compose.onNodeWithTag("backup_preview_retention_hint").performScrollTo().assertIsDisplayed()
            checkpoint("settings_backup_preview_hint")
            reachable(compose.onNodeWithTag("backup_preview_hide")).performClick()
            compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("backup_preview_resume"))
            compose.onNodeWithTag("backup_preview_resume").assertIsDisplayed().performClick()
            reachable(compose.onNodeWithTag("backup_preview_discard")).performClick()
            compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
            compose.runOnIdle { assertEquals(null, retainedPreview) }
        }

        // Дополнительная узкая граница: нижние вкладки и самые длинные названия разделов.
        audit("narrow_navigation") {
            show(AppDestination.TIMER, widthDp = 320)
            assertNavigation(AppDestination.TIMER)
            checkpoint("narrow_timer_navigation")
            compose.onNodeWithTag("timer_primary_action").performScrollTo().assertIsDisplayed()
            assertInsideShell("timer_primary_action")
            checkpoint("narrow_timer_action")
        }
        audit("narrow_settings") {
            show(content = Content.SETTINGS, widthDp = 320)
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_category_system"))
            compose.onNodeWithTag("settings_category_system").assertIsDisplayed().assertHasClickAction()
            checkpoint("narrow_settings_system")
            compose.onNodeWithTag("settings_category_system").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("pin_tracking_widget"))
            compose.onNodeWithTag("pin_tracking_widget").assertIsDisplayed().assertHasClickAction()
            checkpoint("narrow_settings_widget")
        }
        assertEquals("Обход диалогов не должен запускать внешние операции", 0, externalActions)
        File(artifactDirectory(), "failures.txt").writeText(failures.joinToString("\n\n"))
        val stages = failures.groupingBy { it.substringBefore(':') }.eachCount()
        assertTrue("$matrixName: ${failures.size} ошибок по этапам $stages; подробности в ui-audit/$matrixName/failures.txt",
            failures.isEmpty())
    }

    private fun auditSettingsPage(page: SettingsPage) {
        show(content = Content.SETTINGS)
        val category = "settings_category_${page.name.lowercase()}"
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag(category))
        compose.onNodeWithTag(category).assertIsDisplayed().performClick()
        checkpoint("settings_${page.name.lowercase()}_top")
        when (page) {
            SettingsPage.APPEARANCE -> {
                compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("language_english"))
                compose.onNodeWithTag("language_english").assertIsDisplayed().assertHasClickAction()
            }
            SettingsPage.TRACKING -> {
                compose.onNodeWithTag("settings_list").performScrollToNode(hasText(text(R.string.apply)))
                compose.onNodeWithText(text(R.string.apply)).assertIsDisplayed().assertIsEnabled()
            }
            SettingsPage.DATA -> {
                compose.onNodeWithTag("settings_list").performScrollToNode(hasText(text(R.string.restore_backup)))
                compose.onNodeWithText(text(R.string.restore_backup)).assertIsDisplayed().assertIsEnabled()
            }
            SettingsPage.SYSTEM -> {
                compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("platform_details_toggle"))
                compose.onNodeWithTag("platform_details_toggle").performClick()
                compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("platform_compatibility_details"))
                compose.onNodeWithTag("platform_compatibility_details").assertIsDisplayed()
            }
            SettingsPage.HELP -> {
                compose.onNodeWithTag("help_list").performScrollToNode(hasTestTag("help_topic_backup"))
                compose.onNodeWithTag("help_topic_backup").performClick()
                compose.onNodeWithTag("help_list").performScrollToNode(hasTestTag("help_body_backup"))
                compose.onNodeWithTag("help_body_backup").assertIsDisplayed()
            }
            SettingsPage.ABOUT -> {
                compose.onNodeWithTag("settings_list").performScrollToNode(hasText(text(R.string.source_license)))
                compose.onNodeWithText(text(R.string.source_license)).assertIsDisplayed().assertHasClickAction()
            }
            SettingsPage.OVERVIEW -> error("Обзор не является вложенной страницей")
        }
        checkpoint("settings_${page.name.lowercase()}_content")
        val list = if (page == SettingsPage.HELP) "help_list" else "settings_list"
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag("settings_back"))
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("settings_search"))
        compose.onNodeWithTag("settings_search").assertIsDisplayed()
    }

    private fun show(
        destination: AppDestination = AppDestination.TIMER,
        content: Content = Content.APP,
        page: SettingsPage = SettingsPage.OVERVIEW,
        widthDp: Int = 360,
    ) {
        compose.runOnIdle {
            if (content != Content.BACKUP_PREVIEW) {
                retainedPreview?.discard()
                retainedPreview = null
            }
            screen = Screen(content, destination, page, widthDp, screen.serial + 1)
        }
        compose.waitForIdle()
    }

    private fun assertNavigation(selected: AppDestination) {
        val labels = listOf(R.string.nav_timer, R.string.nav_history, R.string.nav_reports, R.string.nav_settings)
        val navigation = compose.onNodeWithTag("compact_navigation").getUnclippedBoundsInRoot()
        labels.forEachIndexed { index, resource ->
            val inNavigation = hasText(text(resource)) and hasAnyAncestor(hasTestTag("compact_navigation"))
            compose.onNode(inNavigation).assertIsDisplayed().assertHasClickAction().also {
                if (AppDestination.entries[index] == selected) it.assertIsSelected()
            }
            val label = compose.onNode(inNavigation, useUnmergedTree = true)
            val bounds = label.getUnclippedBoundsInRoot()
            assertTrue("Вкладка ${text(resource)} за границей навигации: $bounds / $navigation",
                bounds.left >= navigation.left && bounds.right <= navigation.right &&
                    bounds.top >= navigation.top && bounds.bottom <= navigation.bottom)
            assertTextFits(label, "вкладка ${text(resource)}")
        }
    }

    private fun assertInsideShell(tag: String) {
        val shell = compose.onNodeWithTag("adaptive_shell").getUnclippedBoundsInRoot()
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue("$tag выходит за доступное окно: $bounds / $shell",
            bounds.left >= shell.left && bounds.top >= shell.top &&
                bounds.right <= shell.right && bounds.bottom <= shell.bottom)
    }

    private fun cancelDialog() {
        reachable(compose.onNodeWithText(text(R.string.cancel))).performClick()
    }

    private fun reachable(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        try {
            node.assertIsDisplayed()
        } catch (_: AssertionError) {
            node.performScrollTo().assertIsDisplayed()
        }
        return node
    }

    private fun checkpoint(stage: String) {
        compose.waitForIdle()
        capture(stage)
        val measuredText = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        val nodes = compose.onAllNodes(measuredText, useUnmergedTree = true).fetchSemanticsNodes()
        val visibleText = nodes.filter { node ->
            node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f &&
                !node.config.contains(SemanticsProperties.EditableText)
        }
        assertTrue("$stage не содержит измеренного текста", visibleText.isNotEmpty())
        val textFailures = mutableListOf<String>()
        val diagnostics = mutableListOf<String>()
        compose.runOnIdle { visibleText.forEach { node ->
            // Поле ввода прокручивает значение по горизонтали; пользовательский текст с многоточием
            // намеренно свёрнут. Навигация и подписи действий проверяются без этого исключения.
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val actionableAncestor = generateSequence(node) { it.parent }.any {
                it.config.contains(SemanticsActions.OnClick) || it.config.contains(SemanticsProperties.Selected)
            }
            layouts.filter { actionableAncestor || it.layoutInput.overflow != androidx.compose.ui.text.style.TextOverflow.Ellipsis }
                .forEach { layout ->
                    val detail = describe(layout)
                    diagnostics += detail
                    if (textIsClipped(layout)) textFailures += "$stage: обрезан текст: $detail"
                }
        } }
        File(artifactDirectory(), "$stage-layout.txt").writeText(diagnostics.joinToString("\n\n"))
        // Продолжаем независимые экраны, чтобы один дефект не лишал матрицу остальных снимков.
        failures += textFailures
    }

    private fun assertTextFits(node: SemanticsNodeInteraction, description: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Нет раскладки: $description", layouts.isNotEmpty())
        assertFalse("Обрезан $description: ${layouts.joinToString { describe(it) }}", layouts.any { textIsClipped(it) })
    }

    private fun textIsClipped(layout: TextLayoutResult): Boolean {
        return textLayoutIsClipped(layout)
    }

    private fun describe(layout: TextLayoutResult): String = buildString {
        append("text='").append(layout.layoutInput.text.text.take(200)).append("', size=").append(layout.size)
        append(", paragraph=").append(layout.multiParagraph.width).append('x').append(layout.multiParagraph.height)
        append(", constraints=").append(layout.layoutInput.constraints)
        append(", widthOverflow=").append(layout.didOverflowWidth).append(", heightOverflow=").append(layout.didOverflowHeight)
        append(", textLength=").append(layout.layoutInput.text.length).append(", lines=").append(layout.lineCount)
        (0 until layout.lineCount).forEach { line ->
            append("\n line").append(line).append(": left=").append(layout.getLineLeft(line))
            append(", right=").append(layout.getLineRight(line)).append(", top=").append(layout.getLineTop(line))
            append(", bottom=").append(layout.getLineBottom(line)).append(", start=").append(layout.getLineStart(line))
            append(", end=").append(layout.getLineEnd(line)).append(", visibleEnd=").append(layout.getLineEnd(line, visibleEnd = true))
            append(", ellipsized=").append(layout.isLineEllipsized(line))
        }
        val rendered = textLayoutAtRenderedWidth(layout)
        append("\n renderedParagraph=").append(rendered.multiParagraph.width).append('x').append(rendered.multiParagraph.height)
        append(", lines=").append(rendered.lineCount).append(", heightOverflow=").append(rendered.didOverflowHeight)
        (0 until rendered.lineCount).forEach { line ->
            append("\n renderedLine").append(line).append(": left=").append(rendered.getLineLeft(line))
            append(", right=").append(rendered.getLineRight(line)).append(", visibleEnd=")
                .append(rendered.getLineEnd(line, visibleEnd = true))
            append(", ellipsized=").append(rendered.isLineEllipsized(line))
        }
    }

    private fun audit(stage: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: AssertionError) {
            failures += "$stage: ${failure.message}"
            capture("${stage}_FAILED")
        }
    }

    private fun capture(stage: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = artifactDirectory()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Не получен снимок $stage" }
        try {
            File(directory, "$stage.png").outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Не сохранён снимок $stage" }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun artifactDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(requireNotNull(context.getExternalFilesDir(null)), "ui-audit/$matrixName").also { directory ->
            check(directory.isDirectory || directory.mkdirs()) { "Не создан каталог снимков: $directory" }
        }
    }

    private fun fixture(destination: AppDestination): AppUiState {
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val start = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val duration = 5_400_000L // Полтора часа подтверждённой работы.
        val end = start + duration
        val entry = EntryEntity(ENTRY_ID, PROJECT_ID, note, start, end, "MANUAL", 1, true,
            zoneId = zone.id, confirmedDurationMillis = duration, lastConfirmedEndUtcMillis = end)
        val segment = Segment(SegmentId(SEGMENT_ID), EntryId(ENTRY_ID), ProjectId(PROJECT_ID), start, duration)
        val projects = listOf(ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, "@default", 0xff006a67.toInt(), 0), project)
        return AppUiState(
            snapshot = AppSnapshot(TrackingSession.idle(), projects, listOf(entry), listOf(segment),
                entryDurations = mapOf(ENTRY_ID to duration), defaultProjectId = PROJECT_ID),
            preferences = UserPreferences(theme = theme, dynamicColor = false, privacyMode = false),
            controls = AppControls(destination = destination, selectedProjectId = PROJECT_ID,
                manualProjectPicker = ManualProjectPickerState(projects = projects)),
            reportTotals = ReportAggregator.aggregateTotals(listOf(segment), from,
                today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()),
            reportProjects = projects,
            todayProjection = ConfirmedDayProjection(from,
                today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), duration,
                listOf(TodayEntryProjection(EntryId(ENTRY_ID), ProjectId(PROJECT_ID), start, duration))),
            todayEntries = listOf(entry), todayProjects = projects,
            monthlyTargets = mapOf(YearMonth.from(today) to 9_600),
        )
    }

    private fun language() = if (languageTag == "ru") AppLanguage.RUSSIAN else AppLanguage.ENGLISH
    private val matrixName get() = "$languageTag-${theme.name.lowercase()}-font${(fontScale * 100).toInt()}"

    private fun awaitFontScale(expected: Float) {
        compose.waitUntil(10_000) {
            var ready = false
            compose.activityRule.scenario.onActivity { activity ->
                ready = activity.resources.configuration.fontScale == expected &&
                    !activity.isChangingConfigurations && activity.lifecycle.currentState == Lifecycle.State.RESUMED
            }
            ready
        }
    }

    private fun setHostApplicationLocales(locales: LocaleListCompat) {
        compose.activityRule.scenario.onActivity { AppCompatDelegate.setApplicationLocales(locales) }
        awaitHostApplicationLocales(locales)
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
                    activity.resources.configuration.locales[0].language == expectedLanguage && viewLanguage == expectedLanguage
            }
            ready
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun text(resource: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(languageTag))
        }
        return context.createConfigurationContext(configuration).getString(resource)
    }

    companion object {
        private const val PROJECT_ID = "11111111-1111-4111-8111-111111111111"
        private const val ENTRY_ID = "22222222-2222-4222-8222-222222222222"
        private const val SEGMENT_ID = "33333333-3333-4333-8333-333333333333"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}-font{2}")
        fun configurations(): Collection<Array<Any>> = listOf("ru", "en").flatMap { language ->
            listOf(ThemeMode.LIGHT, ThemeMode.DARK).flatMap { mode ->
                listOf(1f, 2f).map { scale -> arrayOf<Any>(language, mode, scale) }
            }
        }
    }
}

/**
 * GetTextLayoutResult у String Text пересоздаёт MultiParagraph в maxWidth родителя,
 * сохраняя меньший размер нарисованного текста. Center/End сдвигают строки в этом
 * более широком холсте. Повторная раскладка с теми же шрифтами и реальной шириной
 * восстанавливает координаты и переносы, которые видит пользователь.
 */
private fun textLayoutAtRenderedWidth(layout: TextLayoutResult): TextLayoutResult = TextLayoutResult(
    layoutInput = layout.layoutInput,
    multiParagraph = MultiParagraph(
        intrinsics = layout.multiParagraph.intrinsics,
        constraints = Constraints(maxWidth = layout.size.width),
        maxLines = layout.layoutInput.maxLines,
        overflow = layout.layoutInput.overflow,
    ),
    size = layout.size,
)

private fun textLayoutIsClipped(layout: TextLayoutResult): Boolean {
    val contentEnd = layout.layoutInput.text.text.trimEnd().length
    if (contentEnd == 0) return false
    val rendered = textLayoutAtRenderedWidth(layout)
    if (rendered.lineCount == 0 || rendered.didOverflowHeight || rendered.multiParagraph.didExceedMaxLines) return true
    val complete = rendered.getLineEnd(rendered.lineCount - 1, visibleEnd = true) >= contentEnd
    val linesFit = (0 until rendered.lineCount).all { line ->
        !rendered.isLineEllipsized(line) && rendered.getLineLeft(line) >= -1f &&
            rendered.getLineRight(line) <= rendered.size.width + 1f
    }
    return !complete || !linesFit
}

/** Сам детектор проверяется на реальном Text, включая настоящее обрезание. */
@RunWith(AndroidJUnit4::class)
class UiTextLayoutAuditTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun naturalCenteredLabelDoesNotReportClippingFromWiderSemanticsCanvas() {
        val layout = sample("Centered label")
        assertFalse("Полная центрированная подпись должна помещаться", textLayoutIsClipped(layout))
    }

    @Test
    fun centeredLabelThatReallyExceedsItsWidthStillReportsClipping() {
        val layout = sample("Centered label that cannot fit", Modifier.width(48.dp), maxLines = 1, softWrap = false)
        assertTrue("Узкая строка должна обнаружить потерянный текст", textLayoutIsClipped(layout))
    }

    @Test
    fun textThatReallyExceedsItsHeightStillReportsClipping() {
        val layout = sample("First line\nSecond line", Modifier.height(20.dp))
        assertTrue("Ограничение высоты должно обнаружить потерянную строку", textLayoutIsClipped(layout))
    }

    @Test
    fun ellipsisDoesNotMakeIncompleteActionLabelPass() {
        val layout = sample("Centered label that cannot fit", Modifier.width(48.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        assertTrue("Многоточие не заменяет полную подпись действия", textLayoutIsClipped(layout))
    }

    private fun sample(
        text: String,
        modifier: Modifier = Modifier,
        maxLines: Int = Int.MAX_VALUE,
        softWrap: Boolean = true,
        overflow: TextOverflow = TextOverflow.Clip,
    ): TextLayoutResult {
        compose.setContent {
            Box(Modifier.width(240.dp)) {
                Text(
                    text,
                    modifier = modifier.testTag("layout_audit_sample"),
                    style = TextStyle(fontSize = 24.sp, textAlign = TextAlign.Center),
                    maxLines = maxLines, softWrap = softWrap, overflow = overflow,
                )
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("layout_audit_sample").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Пример должен иметь ровно одну раскладку", 1, layouts.size)
        return layouts.single()
    }
}
