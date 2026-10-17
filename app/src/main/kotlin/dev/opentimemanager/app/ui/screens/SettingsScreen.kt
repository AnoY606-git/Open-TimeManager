package dev.opentimemanager.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import dev.opentimemanager.app.R
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.backup.BackupFileCoordinator
import dev.opentimemanager.app.backup.BackupConflictKind
import dev.opentimemanager.app.backup.BackupCreationPasswordError
import dev.opentimemanager.app.backup.BackupCreationPasswordException
import dev.opentimemanager.app.backup.BackupCreationPasswordPolicy
import dev.opentimemanager.app.backup.BackupInspection
import dev.opentimemanager.app.backup.BackupPreview
import dev.opentimemanager.app.backup.BackupReadFailure
import dev.opentimemanager.app.ui.launchOwnedSecret
import dev.opentimemanager.app.ui.LegalDocument
import dev.opentimemanager.app.ui.LegalDocumentRepository
import dev.opentimemanager.app.ui.components.AccentIcon
import dev.opentimemanager.app.ui.components.AppCard
import dev.opentimemanager.app.ui.components.AppGlyph
import dev.opentimemanager.app.ui.components.AppIcon
import dev.opentimemanager.app.ui.components.ScreenHeading
import dev.opentimemanager.app.ui.components.SectionHeading
import dev.opentimemanager.app.backup.RestoreMode
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.core.backup.BackupError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class BackupActions(
    val prepare: suspend (CharArray) -> String,
    val cancelPrepare: () -> Unit,
    val clearSelectedDocument: () -> Unit,
    val inspect: suspend (Uri?, CharArray) -> BackupInspection,
    val currentPreview: () -> BackupPreview?,
    val discardPreview: () -> Unit,
    val restore: suspend (BackupPreview, RestoreMode) -> RestoreResult,
)

internal enum class TransferUiStatus {
    BACKUP_RESTORED,
    BACKUP_PASSWORD_TOO_SHORT,
    BACKUP_PASSWORD_TOO_LONG,
    INVALID_PASSWORD_OR_FILE,
    LIMIT_EXCEEDED,
    UNSUPPORTED_VERSION,
    IO_ERROR,
    CONFLICT,
    ACTIVE_TIMER,
}

@Composable
fun SettingsScreen(
    state: AppUiState,
    onTheme: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    selectedLanguage: AppLanguage,
    onLanguage: (AppLanguage) -> Unit,
    onPrivacy: (Boolean) -> Unit,
    onDailyGoal: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backupActions: BackupActions? = null,
    onLaunchBackupDocument: (String) -> Unit = {},
    onLaunchOpenBackupDocument: (Array<String>) -> Unit = {},
) {
    val preferences = state.preferences
    val context = LocalContext.current
    var goal by remember(preferences.dailyGoalMinutes) { mutableStateOf(preferences.dailyGoalMinutes.toString()) }
    var restoreInFlight by remember { mutableStateOf(false) }
    var restoreAttempt by remember { mutableIntStateOf(0) }
    val busy = state.controls.dataTransferBusy || restoreInFlight
    var status by remember(state.controls.lastRestoreResult) {
        mutableStateOf(state.controls.lastRestoreResult?.status())
    }
    var showBackupPassword by remember { mutableStateOf(false) }
    val restoreUri = state.controls.pendingRestoreUri?.let(Uri::parse)
    var preview by remember {
        mutableStateOf(backupActions?.currentPreview?.invoke()?.takeIf { it.hasPayload })
    }
    var showPreview by remember { mutableStateOf(preview != null) }
    var previewStatus by remember {
        mutableStateOf(state.controls.lastRestoreResult?.status()?.takeUnless { it == TransferUiStatus.BACKUP_RESTORED })
    }
    var confirmReplace by remember { mutableStateOf(false) }
    var selectedLegalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.controls.lastRestoreResult) {
        val result = state.controls.lastRestoreResult ?: return@LaunchedEffect
        val selected = preview ?: return@LaunchedEffect
        // A process-owned restore may finish after the previous Settings composition was disposed.
        if (result is RestoreResult.Applied) {
            if (!selected.hasPayload) {
                preview = null
                showPreview = false
                previewStatus = null
                confirmReplace = false
            }
        } else {
            previewStatus = result.status()
        }
    }
    fun restore(mode: RestoreMode) {
        if (busy || restoreInFlight) return
        val selected = preview ?: return
        val actions = backupActions ?: return
        confirmReplace = false
        restoreInFlight = true
        restoreAttempt++
        status = null
        previewStatus = null
        scope.launch {
            try {
                val result = actions.restore(selected, mode)
                status = result.status()
                if (result is RestoreResult.Applied) {
                    preview = null
                    showPreview = false
                } else {
                    previewStatus = result.status()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = TransferUiStatus.IO_ERROR
                previewStatus = TransferUiStatus.IO_ERROR
            } finally {
                restoreInFlight = false
            }
        }
    }
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    LazyColumn(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("settings_list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "heading") {
            ScreenHeading(
                title = stringResource(R.string.settings_heading),
                subtitle = stringResource(R.string.design_settings_subtitle),
            )
        }
        item(key = "appearance_heading") {
            SectionHeading(stringResource(R.string.appearance))
        }
        item(key = "appearance") {
            SettingsCard(contentPadding = PaddingValues(18.dp)) {
                SettingsCardHeading(AppGlyph.Palette, stringResource(R.string.theme))
                Spacer(Modifier.height(12.dp))
                ThemeChoices(selected = preferences.theme, onSelect = onTheme)
                SettingsDivider()
                SettingsToggleRow(
                    title = stringResource(R.string.dynamic_color),
                    description = stringResource(R.string.design_settings_dynamic_hint),
                    checked = preferences.dynamicColor,
                    onChecked = onDynamicColor,
                )
            }
        }
        item(key = "language") {
            SettingsCard(contentPadding = PaddingValues(18.dp)) {
                SettingsCardHeading(AppGlyph.Globe, stringResource(R.string.language))
                Spacer(Modifier.height(12.dp))
                LanguageChoices(selected = selectedLanguage, onSelect = onLanguage)
            }
        }
        item(key = "focus_heading") {
            SectionHeading(
                stringResource(R.string.design_settings_focus_heading),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item(key = "focus") {
            SettingsCard(contentPadding = PaddingValues(18.dp)) {
                SettingsCardHeading(
                    glyph = AppGlyph.Target,
                    title = stringResource(R.string.daily_goal),
                    subtitle = stringResource(R.string.design_settings_goal_hint),
                )
                Spacer(Modifier.height(12.dp))
                DailyGoalEditor(
                    value = goal,
                    onValueChange = { goal = it.filter(Char::isDigit).take(4) },
                    onApply = { goal.toIntOrNull()?.takeIf { it in UserSettings.MIN_GOAL..UserSettings.MAX_GOAL }?.let(onDailyGoal) },
                    canApply = goal.toIntOrNull() in UserSettings.MIN_GOAL..UserSettings.MAX_GOAL,
                )
                SettingsDivider()
                SettingsToggleRow(
                    title = stringResource(R.string.privacy_mode),
                    glyph = AppGlyph.Shield,
                    checked = preferences.privacyMode,
                    onChecked = onPrivacy,
                )
            }
        }
        item(key = "data_heading") {
            SectionHeading(
                stringResource(R.string.data_management),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item(key = "backup") {
            SettingsCard {
                SettingsCardHeading(AppGlyph.Lock, stringResource(R.string.portable_backup))
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.portable_backup_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { status = null; showBackupPassword = true },
                    enabled = !busy && backupActions != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("create_backup"),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    AppIcon(AppGlyph.Download, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.create_backup), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        status = null
                        onLaunchOpenBackupDocument(arrayOf(BackupFileCoordinator.MIME_TYPE))
                    },
                    enabled = !busy && backupActions != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    AppIcon(AppGlyph.Folder, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.restore_backup), textAlign = TextAlign.Center)
                }
                if (busy) {
                    Text(
                        stringResource(R.string.data_operation_in_progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        if (preview != null && !showPreview) {
            item(key = "backup_resume") {
                SettingsCard(contentPadding = PaddingValues(18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIcon(AppGlyph.Archive, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.backup_preview_hidden_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { if (!busy) showPreview = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("backup_preview_resume"),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(stringResource(R.string.backup_preview_resume), textAlign = TextAlign.Center) }
                }
            }
        }
        status?.let { value ->
            // A separate lazy item keeps long localized transfer messages easy to scroll into view.
            item(key = "transfer_status") {
                val restored = value == TransferUiStatus.BACKUP_RESTORED
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (restored) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val color = if (restored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        AppIcon(if (restored) AppGlyph.Check else AppGlyph.Note, modifier = Modifier.size(20.dp), tint = color)
                        Text(
                            stringResource(value.resource()),
                            color = color,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).testTag("backup_transfer_status"),
                        )
                    }
                }
            }
        }
        item(key = "legal_heading") {
            SectionHeading(
                stringResource(R.string.legal_information),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item(key = "legal") {
            SettingsCard(contentPadding = PaddingValues(0.dp)) {
                LegalDocument.entries.forEachIndexed { index, document ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(role = Role.Button) { selectedLegalDocument = document }
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(AppGlyph.Note, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(document.titleResource),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        AppIcon(AppGlyph.ChevronRight, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (index < LegalDocument.entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
        item(key = "local_footer") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIcon(AppGlyph.Shield, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.design_settings_local_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.about_local),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.version_label, version),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    if (showBackupPassword) PasswordDialog(
        title = stringResource(R.string.create_backup),
        creatingBackup = true,
        onDismiss = { showBackupPassword = false },
        onSubmit = { password ->
            showBackupPassword = false
            val actions = backupActions
            if (actions == null) {
                password.fill('\u0000')
            } else {
                scope.launchOwnedSecret(password) { ownedPassword ->
                    try {
                        val fileName = actions.prepare(ownedPassword)
                        onLaunchBackupDocument(fileName)
                    } catch (cancelled: CancellationException) {
                        actions.cancelPrepare()
                        throw cancelled
                    } catch (error: Exception) {
                        actions.cancelPrepare()
                        status = backupCreateFailureStatus(error)
                    }
                }
            }
        },
    )
    if (restoreUri != null) PasswordDialog(
        title = stringResource(R.string.restore_backup),
        creatingBackup = false,
        onDismiss = { backupActions?.clearSelectedDocument?.invoke() },
        onSubmit = { password ->
            val uri = restoreUri
            val actions = backupActions
            actions?.clearSelectedDocument?.invoke()
            if (actions == null) {
                password.fill('\u0000')
            } else {
                scope.launchOwnedSecret(password) { ownedPassword ->
                    try {
                        when (val inspection = actions.inspect(uri, ownedPassword)) {
                            BackupInspection.Cancelled -> Unit
                            is BackupInspection.Ready -> {
                                preview = inspection.preview
                                previewStatus = null
                                showPreview = true
                            }
                            is BackupInspection.Failed -> status = inspection.reason.status()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        status = TransferUiStatus.IO_ERROR
                    }
                }
            }
        },
    )
    preview?.takeIf { showPreview }?.let { selected ->
        BackupPreviewDialog(
            preview = selected,
            status = previewStatus,
            attempt = restoreAttempt,
            busy = busy,
            onHide = {
                if (!busy && !restoreInFlight) {
                    showPreview = false
                    confirmReplace = false
                }
            },
            onDiscard = {
                if (!busy && !restoreInFlight) {
                    backupActions?.discardPreview?.invoke() ?: selected.discard()
                    preview = null
                    showPreview = false
                    previewStatus = null
                    confirmReplace = false
                    status = null
                }
            },
            onMerge = { restore(RestoreMode.MERGE) },
            onReplace = { if (!busy && !restoreInFlight) confirmReplace = true },
        )
    }
    if (confirmReplace) AlertDialog(
        onDismissRequest = { if (!busy) confirmReplace = false },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        modifier = Modifier.testTag("backup_replace_confirmation"),
        title = { Text(stringResource(R.string.replace_backup_title)) },
        text = { Text(stringResource(R.string.replace_backup_warning)) },
        confirmButton = {
            Button(
                onClick = { restore(RestoreMode.REPLACE) },
                enabled = !busy,
                modifier = Modifier.testTag("backup_replace_confirm"),
            ) {
                Text(stringResource(R.string.restore_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) confirmReplace = false }, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
    selectedLegalDocument?.let { document ->
        LegalDocumentDialog(
            document = document,
            onDismiss = { selectedLegalDocument = null },
        )
    }
}

@Composable
private fun LegalDocumentDialog(
    document: LegalDocument,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember(document) { mutableStateOf("") }
    LaunchedEffect(document) {
        text = withContext(Dispatchers.IO) {
            LegalDocumentRepository(context).read(document)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(document.titleResource)) },
        text = {
            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun PasswordDialog(
    title: String,
    creatingBackup: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val input = backupPasswordInputState(password, creatingBackup)
    val passwordDescription = stringResource(R.string.backup_password_content_description)
    val supportingResource = input.error?.status()?.resource() ?: if (creatingBackup) {
        R.string.backup_creation_password_hint
    } else {
        R.string.backup_password_hint
    }
    val dismiss = {
        password = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.backup_password)) },
                supportingText = { Text(stringResource(supportingResource)) },
                isError = input.error != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("backup_password_input")
                    .semantics { contentDescription = passwordDescription },
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (input.canSubmit) {
                        val secret = password.toCharArray()
                        password = ""
                        onSubmit(secret)
                    }
                },
                enabled = input.canSubmit,
                modifier = Modifier.testTag("backup_password_continue"),
            ) { Text(stringResource(R.string.continue_action)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal data class BackupPasswordInputState(
    val canSubmit: Boolean,
    val error: BackupCreationPasswordError?,
)

internal fun backupPasswordInputState(
    password: CharSequence,
    creatingBackup: Boolean,
): BackupPasswordInputState {
    val error = if (creatingBackup) BackupCreationPasswordPolicy.validate(password) else null
    return BackupPasswordInputState(
        canSubmit = password.isNotEmpty() && error == null,
        error = error?.takeIf { password.isNotEmpty() },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BackupPreviewDialog(
    preview: BackupPreview,
    status: TransferUiStatus?,
    attempt: Int,
    busy: Boolean,
    onHide: () -> Unit,
    onDiscard: () -> Unit,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
) {
    val scroll = rememberScrollState()
    val failureResource = status?.previewFailureResource()
    // Identical fast failures must scroll into view even if their intervening busy state was never drawn.
    LaunchedEffect(failureResource, attempt) {
        if (failureResource != null) scroll.scrollTo(0)
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onHide() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        modifier = Modifier.testTag("backup_preview_dialog"),
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                failureResource?.let { resource ->
                    Text(
                        stringResource(resource),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("backup_preview_failure"),
                    )
                }
                if (busy) {
                    Text(stringResource(R.string.data_operation_in_progress), modifier = Modifier.testTag("backup_preview_busy"))
                }
                Text(stringResource(
                    R.string.backup_preview_counts,
                    preview.counts.projects,
                    preview.counts.entries,
                    preview.counts.segments,
                ))
                if (preview.conflicts.isNotEmpty()) {
                    Text(
                        stringResource(R.string.backup_preview_conflicts, preview.conflicts.size),
                        color = MaterialTheme.colorScheme.error,
                    )
                    preview.conflicts.take(MAX_VISIBLE_CONFLICTS).forEach { conflict ->
                        val kind = when (conflict.kind) {
                            BackupConflictKind.PROJECT -> stringResource(R.string.backup_conflict_project)
                            BackupConflictKind.ENTRY -> stringResource(R.string.backup_conflict_entry)
                            BackupConflictKind.SEGMENT -> stringResource(R.string.backup_conflict_segment)
                        }
                        Text(
                            stringResource(R.string.backup_preview_conflict_item, kind, conflict.id),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    val hidden = preview.conflicts.size - MAX_VISIBLE_CONFLICTS
                    if (hidden > 0) {
                        Text(
                            stringResource(R.string.backup_preview_more_conflicts, hidden),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (preview.skippedEntriesWithoutConfirmedTime > 0) {
                    Text(stringResource(
                        R.string.backup_preview_skipped,
                        preview.skippedEntriesWithoutConfirmedTime,
                    ))
                }
                if (preview.activeStateWillBeStopped) Text(stringResource(R.string.backup_preview_active_stopped))
                Text(
                    stringResource(R.string.backup_preview_modes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.backup_preview_retention_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("backup_preview_retention_hint"),
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = onMerge,
                        enabled = !busy,
                        modifier = Modifier.testTag("backup_preview_merge"),
                    ) { Text(stringResource(R.string.restore_merge)) }
                    OutlinedButton(
                        onClick = onReplace,
                        enabled = !busy,
                        modifier = Modifier.testTag("backup_preview_replace"),
                    ) { Text(stringResource(R.string.restore_replace)) }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = onHide,
                        enabled = !busy,
                        modifier = Modifier.testTag("backup_preview_hide"),
                    ) { Text(stringResource(R.string.backup_preview_hide)) }
                    TextButton(
                        onClick = onDiscard,
                        enabled = !busy,
                        modifier = Modifier.testTag("backup_preview_discard"),
                    ) { Text(stringResource(R.string.backup_preview_discard)) }
                }
            }
        },
    )
}

private const val MAX_VISIBLE_CONFLICTS = 5

private fun BackupReadFailure.status(): TransferUiStatus = when (this) {
    BackupReadFailure.INTEGRITY, BackupReadFailure.INVALID_FORMAT -> TransferUiStatus.INVALID_PASSWORD_OR_FILE
    BackupReadFailure.LIMIT -> TransferUiStatus.LIMIT_EXCEEDED
    BackupReadFailure.UNSUPPORTED_VERSION -> TransferUiStatus.UNSUPPORTED_VERSION
    BackupReadFailure.IO -> TransferUiStatus.IO_ERROR
}

internal fun RestoreResult.status(): TransferUiStatus = when (this) {
    is RestoreResult.Applied -> TransferUiStatus.BACKUP_RESTORED
    is RestoreResult.Conflicts -> TransferUiStatus.CONFLICT
    is RestoreResult.ActiveTimerBlocked -> TransferUiStatus.ACTIVE_TIMER
    is RestoreResult.LimitExceeded -> TransferUiStatus.LIMIT_EXCEEDED
}

internal fun backupCreateFailureStatus(error: Throwable): TransferUiStatus = when (error) {
    is BackupCreationPasswordException -> error.error.status()
    is BackupError.LimitError -> TransferUiStatus.LIMIT_EXCEEDED
    else -> TransferUiStatus.IO_ERROR
}

internal fun TransferUiStatus.previewFailureResource(): Int? = when (this) {
    TransferUiStatus.ACTIVE_TIMER -> R.string.backup_preview_active_timer_error
    TransferUiStatus.CONFLICT -> R.string.backup_preview_conflict_error
    TransferUiStatus.LIMIT_EXCEEDED -> R.string.backup_preview_limit_error
    TransferUiStatus.IO_ERROR -> R.string.backup_preview_io_error
    else -> null
}

private fun BackupCreationPasswordError.status(): TransferUiStatus = when (this) {
    BackupCreationPasswordError.TOO_SHORT -> TransferUiStatus.BACKUP_PASSWORD_TOO_SHORT
    BackupCreationPasswordError.TOO_LONG -> TransferUiStatus.BACKUP_PASSWORD_TOO_LONG
}

private fun TransferUiStatus.resource(): Int = when (this) {
    TransferUiStatus.BACKUP_RESTORED -> R.string.backup_restored_success
    TransferUiStatus.BACKUP_PASSWORD_TOO_SHORT -> R.string.backup_creation_password_too_short
    TransferUiStatus.BACKUP_PASSWORD_TOO_LONG -> R.string.backup_creation_password_too_long
    TransferUiStatus.INVALID_PASSWORD_OR_FILE -> R.string.backup_invalid_or_password
    TransferUiStatus.LIMIT_EXCEEDED -> R.string.backup_limit_exceeded
    TransferUiStatus.UNSUPPORTED_VERSION -> R.string.backup_unsupported_version
    TransferUiStatus.IO_ERROR -> R.string.data_io_error
    TransferUiStatus.CONFLICT -> R.string.backup_conflict_error
    TransferUiStatus.ACTIVE_TIMER -> R.string.backup_active_timer_error
}

@Composable
private fun SettingsCard(
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(contentPadding = contentPadding) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsCardHeading(glyph: AppGlyph, title: String, subtitle: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccentIcon(glyph, modifier = Modifier.size(36.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    description: String? = null,
    glyph: AppGlyph? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph?.let { AccentIcon(it, modifier = Modifier.size(36.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.heightIn(min = 48.dp))
    }
}

@Composable
private fun ThemeChoices(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Turn tiles into rows when large type would leave too little room for localized labels.
        if (maxWidth < 212.dp * LocalDensity.current.fontScale) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    ThemeChoice(mode, selected == mode, { onSelect(mode) }, Modifier.fillMaxWidth(), compact = true)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    ThemeChoice(mode, selected == mode, { onSelect(mode) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun ThemeChoice(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("theme_${mode.name.lowercase()}"),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = tint,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        if (compact) {
            Row(
                modifier = Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeChoiceIcon(mode, tint)
                Text(
                    stringResource(mode.label()),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (selected) AppIcon(AppGlyph.Check, modifier = Modifier.size(18.dp), tint = tint)
            }
        } else {
            Column(
                modifier = Modifier.heightIn(min = 84.dp).padding(horizontal = 6.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ThemeChoiceIcon(mode, tint)
                Text(
                    stringResource(mode.label()),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ThemeChoiceIcon(mode: ThemeMode, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        scale(scale = size.minDimension / 24f, pivot = Offset.Zero) {
            val line = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (mode) {
                ThemeMode.SYSTEM -> {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(3f, 4f),
                        size = Size(18f, 13f),
                        cornerRadius = CornerRadius(2f),
                        style = line,
                    )
                    drawLine(tint, Offset(12f, 17f), Offset(12f, 21f), strokeWidth = 1.7f, cap = StrokeCap.Round)
                    drawLine(tint, Offset(8f, 21f), Offset(16f, 21f), strokeWidth = 1.7f, cap = StrokeCap.Round)
                }
                ThemeMode.LIGHT -> {
                    drawCircle(tint, radius = 4f, center = Offset(12f, 12f), style = line)
                    repeat(8) { ray ->
                        val angle = ray * PI / 4
                        val x = cos(angle).toFloat()
                        val y = sin(angle).toFloat()
                        drawLine(
                            tint,
                            Offset(12f + x * 7f, 12f + y * 7f),
                            Offset(12f + x * 9.5f, 12f + y * 9.5f),
                            strokeWidth = 1.7f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                ThemeMode.DARK -> {
                    val moon = Path().apply {
                        moveTo(20f, 13.3f)
                        cubicTo(16.7f, 14.4f, 12.8f, 12.6f, 11.7f, 9.1f)
                        cubicTo(11f, 7.1f, 11.3f, 5f, 12.2f, 3.5f)
                        cubicTo(7.8f, 3.3f, 4f, 6.9f, 4f, 11.5f)
                        cubicTo(4f, 16.2f, 7.8f, 20f, 12.5f, 20f)
                        cubicTo(16.1f, 20f, 19f, 17.1f, 20f, 13.3f)
                        close()
                    }
                    drawPath(moon, tint, style = line)
                }
            }
        }
    }
}

@Composable
private fun LanguageChoices(selected: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 270.dp * LocalDensity.current.fontScale) {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLanguage.entries.forEach { language ->
                    LanguageChoice(language, selected == language, { onSelect(language) }, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppLanguage.entries.forEach { language ->
                    LanguageChoice(language, selected == language, { onSelect(language) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun LanguageChoice(
    language: AppLanguage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier.clip(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("language_${language.name.lowercase()}"),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(language.label()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DailyGoalEditor(
    value: String,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    canApply: Boolean,
) {
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.design_settings_minutes)) },
            supportingText = {
                Text(stringResource(R.string.design_settings_goal_range, UserSettings.MIN_GOAL, UserSettings.MAX_GOAL))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = fieldModifier,
        )
    }
    val apply: @Composable (Modifier) -> Unit = { buttonModifier ->
        Button(
            onClick = onApply,
            enabled = canApply,
            shape = RoundedCornerShape(14.dp),
            modifier = buttonModifier.heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.apply)) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp * LocalDensity.current.fontScale) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field(Modifier.fillMaxWidth())
                apply(Modifier.align(Alignment.End))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                field(Modifier.weight(1f))
                apply(Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun AppLanguage.label(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.ENGLISH -> R.string.language_english
    AppLanguage.RUSSIAN -> R.string.language_russian
}
