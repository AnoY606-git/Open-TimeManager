package dev.opentimemanager.app.ui.screens

import android.content.res.Configuration
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opentimemanager.app.R
import dev.opentimemanager.app.backup.BackupConflict
import dev.opentimemanager.app.backup.BackupConflictKind
import dev.opentimemanager.app.backup.BackupCounts
import dev.opentimemanager.app.backup.BackupInspection
import dev.opentimemanager.app.backup.BackupPreview
import dev.opentimemanager.app.backup.RestoreMode
import dev.opentimemanager.app.backup.RestorePayload
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.ui.AppControls
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.NotificationControlStatus
import dev.opentimemanager.app.ui.theme.OpenTimeManagerTheme
import dev.opentimemanager.core.backup.PortablePreferences
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Settings/Timer composables in a test navigation host; all actions and records are synthetic. */
@RunWith(AndroidJUnit4::class)
class SettingsBackupPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun blockedReasonIsVisibleAndHideTimerStopReturnRetriesWithoutInspectingAgain() {
        val fixture = PreviewFixture()
        show(fixture, locale = "ru", fontScale = 1.3f)
        inspectOnce()

        compose.onNodeWithTag("backup_preview_merge").performClick()
        assertDialogFailure("ru", R.string.backup_preview_active_timer_error)
        assertActionsFitDialog()
        compose.onNodeWithTag("backup_preview_hide").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertSame(fixture.candidate, fixture.retained)
            assertTrue(fixture.candidate.hasPayload)
            assertEquals(0, fixture.discardCalls)
        }

        compose.onNodeWithTag("test_timer_destination").performClick()
        compose.onNodeWithText(text("ru", R.string.tracking_stop)).performScrollTo().performClick()
        compose.onNodeWithTag("test_settings_destination").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertIsDisplayed()
        compose.onNodeWithTag("backup_preview_merge").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
        scrollToPageStatus()
        compose.onNodeWithTag("backup_transfer_status").assertTextEquals(text("ru", R.string.backup_restored_success))
        compose.runOnIdle {
            assertEquals(1, fixture.inspectCalls)
            assertEquals(1, fixture.stopCalls)
            assertEquals(2, fixture.restoreCalls)
            assertTrue(fixture.previewReads >= 2)
            assertNull(fixture.retained)
            assertFalse(fixture.candidate.hasPayload)
            assertEquals(0, fixture.discardCalls)
        }
    }

    @Test
    fun hideAndResumeOnSettingsRetainsPreviewButExplicitCancelDestroysIt() {
        val fixture = PreviewFixture()
        show(fixture)
        inspectOnce()

        compose.onNodeWithTag("backup_preview_hide").performClick()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("backup_preview_resume"))
        compose.onNodeWithTag("backup_preview_resume").assertIsDisplayed().performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(fixture.candidate, fixture.retained)
            assertTrue(fixture.candidate.hasPayload)
            assertEquals(1, fixture.inspectCalls)
            assertEquals(0, fixture.discardCalls)
        }

        compose.onNodeWithTag("backup_preview_discard").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
        compose.onNodeWithTag("backup_preview_resume").assertDoesNotExist()
        compose.onNodeWithTag("test_timer_destination").performClick()
        compose.onNodeWithTag("test_settings_destination").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.discardCalls)
            assertNull(fixture.retained)
            assertFalse(fixture.candidate.hasPayload)
        }
    }

    @Test
    fun backHidesInsteadOfDiscardingTheVerifiedPreview() {
        val fixture = PreviewFixture()
        show(fixture)
        inspectOnce()

        pressBack()

        compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("backup_preview_resume"))
        compose.onNodeWithTag("backup_preview_resume").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, fixture.inspectCalls)
            assertEquals(0, fixture.discardCalls)
            assertTrue(fixture.candidate.hasPayload)
        }
    }

    @Test
    fun initialInspectionConflictsCanMergeAfterLocalResolutionWithoutAnotherInspection() {
        val initialConflicts = listOf(
            BackupConflict(BackupConflictKind.PROJECT, "81000000-0000-4000-8000-000000000001"),
        )
        val fixture = PreviewFixture(initialConflicts = initialConflicts)
        var localConflictsResolved = false
        fixture.timerActive = false
        fixture.operation = {
            if (localConflictsResolved) RestoreResult.Applied(0, 0, 0) else RestoreResult.Conflicts(initialConflicts)
        }
        show(fixture)
        inspectOnce()
        compose.runOnIdle {
            assertEquals(initialConflicts, fixture.candidate.conflicts)
            assertTrue(fixture.candidate.hasPayload)
        }
        compose.onNodeWithText(text("en", R.string.backup_preview_modes)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("backup_preview_merge").assertIsEnabled().performClick()
        assertDialogFailure("en", R.string.backup_preview_conflict_error)
        compose.runOnIdle {
            assertSame(fixture.candidate, fixture.retained)
            assertTrue(fixture.candidate.hasPayload)
        }

        compose.onNodeWithTag("backup_preview_hide").performClick()
        compose.onNodeWithTag("test_timer_destination").performClick()
        compose.runOnIdle {
            localConflictsResolved = true
            assertSame(fixture.candidate, fixture.retained)
            assertEquals(initialConflicts, fixture.candidate.conflicts)
        }
        compose.onNodeWithTag("test_settings_destination").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertIsDisplayed()
        compose.onNodeWithTag("backup_password_input").assertDoesNotExist()
        compose.onNodeWithTag("backup_preview_merge").assertIsEnabled().performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
        scrollToPageStatus()
        compose.onNodeWithTag("backup_transfer_status").assertTextEquals(text("en", R.string.backup_restored_success))
        compose.runOnIdle {
            assertEquals(1, fixture.inspectCalls)
            assertEquals(2, fixture.restoreCalls)
            assertEquals(listOf(RestoreMode.MERGE, RestoreMode.MERGE), fixture.modes)
            assertTrue(fixture.previewReads >= 2)
            assertEquals(0, fixture.discardCalls)
            assertEquals(initialConflicts, fixture.candidate.conflicts)
            assertNull(fixture.retained)
            assertFalse(fixture.candidate.hasPayload)
        }
    }

    @Test
    fun conflictLimitAndIoReasonsAreVisibleInTheOpenDialogAndKeepItsPayload() {
        val fixture = PreviewFixture()
        show(fixture)
        inspectOnce()
        compose.runOnIdle {
            fixture.operation = {
                RestoreResult.Conflicts(listOf(BackupConflict(BackupConflictKind.PROJECT, fixture.project.id)))
            }
        }
        compose.onNodeWithTag("backup_preview_merge").performClick()
        assertDialogFailure("en", R.string.backup_preview_conflict_error)
        compose.onNodeWithTag("backup_preview_retention_hint").performScrollTo()
        compose.onNodeWithTag("backup_preview_merge").performClick()
        assertDialogFailure("en", R.string.backup_preview_conflict_error)

        compose.runOnIdle { fixture.operation = { RestoreResult.LimitExceeded(20_000) } }
        compose.onNodeWithTag("backup_preview_merge").performClick()
        assertDialogFailure("en", R.string.backup_preview_limit_error)

        compose.runOnIdle { fixture.operation = { throw IOException("Synthetic local write failure") } }
        compose.onNodeWithTag("backup_preview_merge").performClick()
        assertDialogFailure("en", R.string.backup_preview_io_error)
        compose.runOnIdle {
            assertEquals(4, fixture.restoreCalls)
            assertEquals(1, fixture.inspectCalls)
            assertEquals(0, fixture.discardCalls)
            assertSame(fixture.candidate, fixture.retained)
            assertTrue(fixture.candidate.hasPayload)
        }
    }

    @Test
    fun inFlightRestoreBlocksDuplicateRestoreReplaceHideDiscardAndBack() {
        val fixture = PreviewFixture()
        val completion = CompletableDeferred<RestoreResult>()
        fixture.operation = { completion.await() }
        show(fixture)
        inspectOnce()
        val click = requireNotNull(
            compose.onNodeWithTag("backup_preview_merge").fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )
        // Invoke the same enabled callback twice before recomposition: the local gate must also protect this race.
        compose.runOnIdle { click(); click() }

        for (tag in actionTags) compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.onNodeWithTag("backup_preview_busy").assertIsDisplayed()
        for (tag in actionTags) {
            compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        pressBack()
        compose.onNodeWithTag("backup_preview_dialog").assertIsDisplayed()
        compose.onNodeWithTag("backup_replace_confirmation").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, fixture.restoreCalls)
            assertEquals(0, fixture.discardCalls)
            assertTrue(fixture.candidate.hasPayload)
            completion.complete(RestoreResult.ActiveTimerBlocked(TrackingState.PAUSED))
        }
        compose.onNodeWithTag("backup_preview_merge").assertIsEnabled()
        assertDialogFailure("en", R.string.backup_preview_active_timer_error)
    }

    @Test
    fun replaceRequiresConfirmationAndSuccessConsumesThePreview() {
        val fixture = PreviewFixture()
        val completion = CompletableDeferred<RestoreResult>()
        fixture.operation = { completion.await() }
        show(fixture)
        inspectOnce()
        compose.onNodeWithTag("backup_preview_replace").performClick()
        compose.onNodeWithTag("backup_replace_confirmation").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, fixture.restoreCalls) }
        val confirm = requireNotNull(
            compose.onNodeWithTag("backup_replace_confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )
        compose.runOnIdle { confirm(); confirm() }

        compose.onNodeWithTag("backup_replace_confirmation").assertDoesNotExist()
        for (tag in actionTags) compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(RestoreMode.REPLACE), fixture.modes)
            assertEquals(1, fixture.restoreCalls)
            completion.complete(RestoreResult.Applied(1, 0, 0))
        }
        compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
        scrollToPageStatus()
        compose.onNodeWithTag("backup_transfer_status").assertTextEquals(text("en", R.string.backup_restored_success))
        compose.onNodeWithTag("backup_preview_resume").assertDoesNotExist()
        compose.onNodeWithTag("test_timer_destination").performClick()
        compose.onNodeWithTag("test_settings_destination").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertNull(fixture.retained)
            assertFalse(fixture.candidate.hasPayload)
            assertEquals(1, fixture.inspectCalls)
            assertEquals(0, fixture.discardCalls)
        }
    }

    @Test
    fun processBusyStateAlsoDisablesPreviewActionsWithoutStartingAnotherRestore() {
        val fixture = PreviewFixture()
        show(fixture)
        inspectOnce()
        compose.runOnIdle { fixture.transferBusy = true }

        for (tag in actionTags) compose.onNodeWithTag(tag).assertIsNotEnabled()
        pressBack()
        compose.onNodeWithTag("backup_preview_dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, fixture.restoreCalls)
            assertEquals(0, fixture.discardCalls)
            fixture.transferBusy = false
        }
        compose.onNodeWithTag("backup_preview_hide").assertIsEnabled()
    }

    @Test
    fun cancellationIsNotPresentedAsIoFailureAndTheSamePreviewCanRetry() {
        val fixture = PreviewFixture()
        fixture.operation = { throw CancellationException("Synthetic cancelled restore") }
        show(fixture)
        inspectOnce()
        compose.onNodeWithTag("backup_preview_merge").performClick()

        compose.onNodeWithTag("backup_preview_failure").assertDoesNotExist()
        compose.onNodeWithTag("backup_preview_merge").assertIsEnabled()
        compose.runOnIdle {
            assertTrue(fixture.candidate.hasPayload)
            assertSame(fixture.candidate, fixture.retained)
            assertEquals(0, fixture.discardCalls)
            fixture.operation = { RestoreResult.Applied(1, 0, 0) }
        }
        compose.onNodeWithTag("backup_preview_merge").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, fixture.restoreCalls)
            assertEquals(1, fixture.inspectCalls)
            assertNull(fixture.retained)
            assertFalse(fixture.candidate.hasPayload)
        }
    }

    private fun show(fixture: PreviewFixture, locale: String = "en", fontScale: Float = 1f) {
        val overrides = DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 640.dp)) then
            DeviceConfigurationOverride.FontScale(fontScale) then
            DeviceConfigurationOverride.Locales(LocaleList(Locale(locale)))
        compose.setContent {
            DeviceConfigurationOverride(overrides) {
                val context = LocalContext.current
                val configuration = LocalConfiguration.current
                val localizedContext = remember(context, locale, configuration) {
                    ContextThemeWrapper(context, context.theme).apply {
                        applyOverrideConfiguration(Configuration(configuration).apply {
                            setLocale(java.util.Locale.forLanguageTag(locale))
                        })
                    }
                }
                // Dialogs own a separate Window; configuration-only overrides do not
                // change that Window's resource Context. Localize its Context as well.
                CompositionLocalProvider(LocalContext provides localizedContext) {
                    OpenTimeManagerTheme(ThemeMode.LIGHT, dynamicColor = false) {
                        TestDestinations(fixture, if (locale == "ru") AppLanguage.RUSSIAN else AppLanguage.ENGLISH)
                    }
                }
            }
        }
    }

    private fun inspectOnce() {
        compose.onNodeWithTag("backup_password_input").performTextInput("x")
        compose.onNodeWithTag("backup_password_continue").performClick()
        compose.onNodeWithTag("backup_preview_dialog").assertIsDisplayed()
    }

    private fun assertDialogFailure(locale: String, resource: Int) {
        compose.onNodeWithTag("backup_preview_dialog").assertIsDisplayed()
        compose.onNode(
            hasTestTag("backup_preview_failure") and hasAnyAncestor(hasTestTag("backup_preview_dialog")),
        ).assertIsDisplayed().assertTextEquals(text(locale, resource))
    }

    private fun scrollToPageStatus() {
        compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("backup_transfer_status"))
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun assertActionsFitDialog() {
        val dialog = compose.onNodeWithTag("backup_preview_dialog").getUnclippedBoundsInRoot()
        val bounds = actionTags.map { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot().also { button ->
                assertTrue(button.left >= dialog.left && button.right <= dialog.right)
                assertTrue(button.top >= dialog.top && button.bottom <= dialog.bottom)
            }
        }
        assertTrue(bounds.take(2).maxOf { it.bottom } <= bounds.drop(2).minOf { it.top })
    }

    private fun text(locale: String, resource: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag(locale))
        }
        return context.createConfigurationContext(configuration).getString(resource)
    }

    private companion object {
        val actionTags = listOf("backup_preview_merge", "backup_preview_replace", "backup_preview_hide", "backup_preview_discard")
    }
}

@Composable
private fun TestDestinations(fixture: PreviewFixture, language: AppLanguage) {
    Column(Modifier.fillMaxSize()) {
        Row {
            TextButton(onClick = { fixture.showSettings = false }, modifier = Modifier.testTag("test_timer_destination")) {
                Text(stringResource(R.string.nav_timer))
            }
            TextButton(onClick = { fixture.showSettings = true }, modifier = Modifier.testTag("test_settings_destination")) {
                Text(stringResource(R.string.nav_settings))
            }
        }
        Box(Modifier.weight(1f)) {
            if (fixture.showSettings) {
                SettingsScreen(
                    state = fixture.state(),
                    onTheme = {},
                    onDynamicColor = {},
                    selectedLanguage = language,
                    onLanguage = {},
                    onPrivacy = {},
                    onDailyGoal = {},
                    backupActions = fixture.actions,
                )
            } else {
                TimerScreen(
                    state = fixture.state(),
                    compactTimer = true,
                    onSelectProject = {},
                    onPrimary = {},
                    onStop = { fixture.timerActive = false; fixture.stopCalls += 1 },
                    onCreateProject = { _, _ -> },
                    onArchiveProject = {},
                    onLoadMoreProjects = {},
                    onSaveNote = {},
                    onResolveReview = {},
                    notificationStatus = NotificationControlStatus.AVAILABLE,
                    onManageNotifications = {},
                )
            }
        }
    }
}

private class PreviewFixture(initialConflicts: List<BackupConflict> = emptyList()) {
    val project = ProjectEntity("81000000-0000-4000-8000-000000000001", "Synthetic project", 0, 0)
    val candidate = BackupPreview(
        counts = BackupCounts(1, 0, 0),
        conflicts = initialConflicts,
        skippedEntriesWithoutConfirmedTime = 0,
        activeStateWillBeStopped = false,
        payload = RestorePayload(listOf(project), emptyList(), emptyList(), PortablePreferences(), "synthetic-preview", 2),
    )
    var showSettings by mutableStateOf(true)
    var timerActive by mutableStateOf(true)
    var transferBusy by mutableStateOf(false)
    private var pendingUri by mutableStateOf<String?>("content://synthetic/preview.otmbackup")
    private var lastResult by mutableStateOf<RestoreResult?>(null)
    var retained: BackupPreview? = null
    var inspectCalls = 0
    var discardCalls = 0
    var restoreCalls = 0
    var stopCalls = 0
    var previewReads = 0
    val modes = mutableListOf<RestoreMode>()
    var operation: suspend (RestoreMode) -> RestoreResult = {
        if (timerActive) RestoreResult.ActiveTimerBlocked(TrackingState.PAUSED) else RestoreResult.Applied(1, 0, 0)
    }
    val actions = BackupActions(
        prepare = { error("Creation is not part of this synthetic restore scenario") },
        cancelPrepare = {},
        clearSelectedDocument = { pendingUri = null },
        inspect = { uri, _ ->
            assertEquals(Uri.parse("content://synthetic/preview.otmbackup"), uri)
            inspectCalls += 1
            retained = candidate
            BackupInspection.Ready(candidate)
        },
        currentPreview = { previewReads += 1; retained },
        discardPreview = { discardCalls += 1; retained?.discard(); retained = null },
        restore = { selected, mode ->
            assertSame(retained, selected)
            restoreCalls += 1
            modes += mode
            lastResult = null
            val result = selected.usePayload { operation(mode) }
            lastResult = result
            if (result is RestoreResult.Applied) retained = null
            result
        },
    )

    fun state(): AppUiState {
        val entryId = EntryId("82000000-0000-4000-8000-000000000001")
        val projectId = ProjectId(project.id)
        val session = if (timerActive) TrackingSession.restore(
            TrackingState.PAUSED,
            1,
            entryId,
            projectId,
            null,
            listOf(Segment(SegmentId("83000000-0000-4000-8000-000000000001"), entryId, projectId, 1_000, 1_000)),
        ) else TrackingSession.idle()
        return AppUiState(
            snapshot = AppSnapshot(session, listOf(project), emptyList(), emptyList()),
            controls = AppControls(
                pendingRestoreUri = pendingUri,
                lastRestoreResult = lastResult,
                dataTransferBusy = transferBusy,
            ),
        )
    }
}
