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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import dev.opentimemanager.app.R
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.settings.UserSettings
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.backup.BackupFileCoordinator
import dev.opentimemanager.app.backup.BackupConflictKind
import dev.opentimemanager.app.backup.BackupInspection
import dev.opentimemanager.app.backup.BackupPreview
import dev.opentimemanager.app.backup.BackupReadFailure
import dev.opentimemanager.app.ui.launchOwnedSecret
import dev.opentimemanager.app.backup.RestoreMode
import dev.opentimemanager.app.backup.RestoreResult
import dev.opentimemanager.core.backup.BackupError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    val busy = state.controls.dataTransferBusy
    var status by remember { mutableStateOf<TransferUiStatus?>(null) }
    var showBackupPassword by remember { mutableStateOf(false) }
    val restoreUri = state.controls.pendingRestoreUri?.let(Uri::parse)
    var preview by remember { mutableStateOf(backupActions?.currentPreview?.invoke()) }
    var confirmReplace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun restore(mode: RestoreMode) {
        val selected = preview ?: return
        val actions = backupActions ?: return
        preview = null
        confirmReplace = false
        scope.launch {
            try {
                status = when (actions.restore(selected, mode)) {
                    is RestoreResult.Applied -> TransferUiStatus.BACKUP_RESTORED
                    is RestoreResult.Conflicts -> TransferUiStatus.CONFLICT
                    is RestoreResult.ActiveTimerBlocked -> TransferUiStatus.ACTIVE_TIMER
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selected.discard()
                status = TransferUiStatus.IO_ERROR
            }
        }
    }
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
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
                    if (busy) Text(stringResource(R.string.data_operation_in_progress))
                    status?.let { value ->
                        Text(
                            stringResource(value.resource()),
                            color = if (value == TransferUiStatus.BACKUP_RESTORED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
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
    }

    if (showBackupPassword) PasswordDialog(
        title = stringResource(R.string.create_backup),
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
                            is BackupInspection.Ready -> preview = inspection.preview
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
    preview?.let { selected ->
        BackupPreviewDialog(
            preview = selected,
            onDismiss = {
                backupActions?.discardPreview?.invoke()
                preview = null
            },
            onMerge = { restore(RestoreMode.MERGE) },
            onReplace = { confirmReplace = true },
        )
    }
    if (confirmReplace) AlertDialog(
        onDismissRequest = { confirmReplace = false },
        title = { Text(stringResource(R.string.replace_backup_title)) },
        text = { Text(stringResource(R.string.replace_backup_warning)) },
        confirmButton = {
            Button(onClick = { restore(RestoreMode.REPLACE) }) {
                Text(stringResource(R.string.restore_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PasswordDialog(
    title: String,
    onDismiss: () -> Unit,
    onSubmit: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val passwordDescription = stringResource(R.string.backup_password_content_description)
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
                supportingText = { Text(stringResource(R.string.backup_password_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = passwordDescription },
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val secret = password.toCharArray()
                    password = ""
                    onSubmit(secret)
                },
                enabled = password.isNotEmpty(),
            ) { Text(stringResource(R.string.continue_action)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BackupPreviewDialog(
    preview: BackupPreview,
    onDismiss: () -> Unit,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
            }
        },
        confirmButton = {
            Button(onClick = onMerge, enabled = preview.conflicts.isEmpty()) {
                Text(stringResource(R.string.restore_merge))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReplace) { Text(stringResource(R.string.restore_replace)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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

internal fun backupCreateFailureStatus(error: Throwable): TransferUiStatus = when (error) {
    is BackupError.LimitError -> TransferUiStatus.LIMIT_EXCEEDED
    else -> TransferUiStatus.IO_ERROR
}

private fun TransferUiStatus.resource(): Int = when (this) {
    TransferUiStatus.BACKUP_RESTORED -> R.string.backup_restored_success
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
