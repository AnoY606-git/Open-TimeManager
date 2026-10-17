package dev.opentimemanager.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
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
import dev.opentimemanager.app.backup.RestoreMode
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.core.backup.BackupError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
@OptIn(ExperimentalLayoutApi::class)
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
        modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("settings_list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                stringResource(R.string.settings_heading),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        item { Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleLarge) }
        item {
            PreferenceCard(title = stringResource(R.string.theme)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = preferences.theme == mode,
                            onClick = { onTheme(mode) },
                            label = { Text(stringResource(mode.label())) },
                        )
                    }
                }
            }
        }
        item {
            TogglePreference(
                title = stringResource(R.string.dynamic_color),
                checked = preferences.dynamicColor,
                onChecked = onDynamicColor,
            )
        }
        item {
            PreferenceCard(title = stringResource(R.string.language)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = selectedLanguage == language,
                            onClick = { onLanguage(language) },
                            label = { Text(stringResource(language.label())) },
                            modifier = Modifier.testTag("language_${language.name.lowercase()}"),
                        )
                    }
                }
            }
        }
        item {
            TogglePreference(
                title = stringResource(R.string.privacy_mode),
                checked = preferences.privacyMode,
                onChecked = onPrivacy,
            )
        }
        item {
            PreferenceCard(title = stringResource(R.string.daily_goal_minutes)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it.filter(Char::isDigit).take(4) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { goal.toIntOrNull()?.takeIf { it in UserSettings.MIN_GOAL..UserSettings.MAX_GOAL }?.let(onDailyGoal) },
                        enabled = goal.toIntOrNull() in UserSettings.MIN_GOAL..UserSettings.MAX_GOAL,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(stringResource(R.string.apply)) }
                }
            }
        }
        item { Text(stringResource(R.string.data_management), style = MaterialTheme.typography.titleLarge) }
        item {
            PreferenceCard(title = stringResource(R.string.portable_backup)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.portable_backup_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { status = null; showBackupPassword = true },
                        enabled = !busy && backupActions != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.create_backup)) }
                    OutlinedButton(
                        onClick = {
                            status = null
                            onLaunchOpenBackupDocument(arrayOf(BackupFileCoordinator.MIME_TYPE))
                        },
                        enabled = !busy && backupActions != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.restore_backup)) }
                    if (preview != null && !showPreview) {
                        Text(stringResource(R.string.backup_preview_hidden_hint))
                        OutlinedButton(
                            onClick = { if (!busy) showPreview = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().testTag("backup_preview_resume"),
                        ) { Text(stringResource(R.string.backup_preview_resume)) }
                    }
                    if (busy) Text(stringResource(R.string.data_operation_in_progress))
                    status?.let { value ->
                        Text(
                            stringResource(value.resource()),
                            color = if (value == TransferUiStatus.BACKUP_RESTORED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.testTag("backup_transfer_status"),
                        )
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_local))
                    Text(stringResource(R.string.version_label, version), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text(stringResource(R.string.legal_information), style = MaterialTheme.typography.titleLarge) }
        item {
            PreferenceCard(title = stringResource(R.string.legal_information)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegalDocument.entries.forEach { document ->
                        OutlinedButton(
                            onClick = { selectedLegalDocument = document },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(document.titleResource))
                        }
                    }
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
private fun PreferenceCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun TogglePreference(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = checked, onCheckedChange = onChecked)
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
