package dev.opentimemanager.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.backup.BackupFileCoordinator
import dev.opentimemanager.app.dataexport.CsvExportCoordinator
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.ui.screens.HistoryScreen
import dev.opentimemanager.app.ui.screens.ReportsScreen
import dev.opentimemanager.app.ui.screens.SettingsScreen
import dev.opentimemanager.app.ui.screens.TimerScreen
import dev.opentimemanager.app.ui.screens.TodayPane
import dev.opentimemanager.app.ui.screens.BackupActions
import dev.opentimemanager.app.ui.components.AppGlyph
import dev.opentimemanager.app.ui.components.AppIcon

@Composable
fun OpenTimeManagerApp(
    state: AppUiState,
    viewModel: AppViewModel?,
    notificationStatus: NotificationControlStatus,
    onManageNotifications: () -> Unit,
    selectedLanguage: AppLanguage,
    onLanguage: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val configuration = LocalConfiguration.current
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = state.controls.destination != AppDestination.TIMER) {
        viewModel?.navigate(AppDestination.TIMER)
    }
    // These launchers stay registered for every destination. Android may deliver
    // a pending result after process recreation before Reports/Settings is shown.
    val csvDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CsvExportCoordinator.MIME_TYPE),
    ) { uri -> viewModel?.completeCsvExport(uri) }
    val backupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFileCoordinator.MIME_TYPE),
    ) { uri -> viewModel?.completeBackup(uri) }
    val openBackupDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel?.selectBackupDocument(uri) }
    val message = state.controls.message
    val messageText = message?.let { stringResource(it.resource()) }
    LaunchedEffect(message, messageText) {
        if (message != null && messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel?.clearMessage()
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("adaptive_shell")) {
        val widthDp = maxWidth.value.toInt()
        val heightDp = maxHeight.value.toInt()
        val layout = navigationLayout(widthDp, heightDp)
        val compactTimer = timerUsesCompactLayout(heightDp, configuration.fontScale)
        if (snapshot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val destinations = AppDestination.entries
            if (layout == NavigationLayout.BOTTOM) {
                Scaffold(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                            shadowElevation = 6.dp,
                        ) {
                            NavigationBar(
                                modifier = Modifier.testTag("compact_navigation"),
                                containerColor = Color.Transparent,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            ) {
                                destinations.forEach { destination ->
                                    NavigationBarItem(
                                        selected = state.controls.destination == destination,
                                        onClick = { viewModel?.navigate(destination) },
                                        icon = { DestinationMarker(destination) },
                                        label = { Text(stringResource(destination.label()), style = MaterialTheme.typography.labelMedium) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    DestinationContent(
                        state, viewModel, layout, compactTimer, notificationStatus,
                        onManageNotifications, selectedLanguage, onLanguage,
                        onLaunchCsvDocument = csvDocument::launch,
                        onLaunchBackupDocument = backupDocument::launch,
                        onLaunchOpenBackupDocument = openBackupDocument::launch,
                        modifier = Modifier.padding(padding),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(
                            modifier = Modifier.testTag("expanded_navigation"),
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                destinations.forEach { destination ->
                                    NavigationRailItem(
                                        selected = state.controls.destination == destination,
                                        onClick = { viewModel?.navigate(destination) },
                                        icon = { DestinationMarker(destination) },
                                        label = { Text(stringResource(destination.label()), style = MaterialTheme.typography.labelMedium) },
                                        colors = NavigationRailItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                        DestinationContent(
                            state, viewModel, layout, compactTimer, notificationStatus,
                            onManageNotifications, selectedLanguage, onLanguage,
                            onLaunchCsvDocument = csvDocument::launch,
                            onLaunchBackupDocument = backupDocument::launch,
                            onLaunchOpenBackupDocument = openBackupDocument::launch,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DestinationMarker(destination: AppDestination) {
    AppIcon(
        glyph = when (destination) {
            AppDestination.TIMER -> AppGlyph.Timer
            AppDestination.HISTORY -> AppGlyph.History
            AppDestination.REPORTS -> AppGlyph.Reports
            AppDestination.SETTINGS -> AppGlyph.Settings
        },
        contentDescription = stringResource(destination.description()),
        modifier = Modifier.size(23.dp),
    )
}

@Composable
private fun DestinationContent(
    state: AppUiState,
    viewModel: AppViewModel?,
    layout: NavigationLayout,
    compactTimer: Boolean,
    notificationStatus: NotificationControlStatus,
    onManageNotifications: () -> Unit,
    selectedLanguage: AppLanguage,
    onLanguage: (AppLanguage) -> Unit,
    onLaunchCsvDocument: (String) -> Unit,
    onLaunchBackupDocument: (String) -> Unit,
    onLaunchOpenBackupDocument: (Array<String>) -> Unit,
    modifier: Modifier,
) {
    when (state.controls.destination) {
        AppDestination.TIMER -> if (layout == NavigationLayout.TWO_PANE) {
            AdaptiveTimerSurface(state, layout, compactTimer, modifier = modifier, viewModel = viewModel, notificationStatus = notificationStatus, onManageNotifications = onManageNotifications)
        } else AdaptiveTimerSurface(state, layout, compactTimer, modifier = modifier, viewModel = viewModel, notificationStatus = notificationStatus, onManageNotifications = onManageNotifications)
        AppDestination.HISTORY -> HistoryScreen(
            state = state,
            onSearch = { viewModel?.searchHistory(it) },
            onSave = { id, revision, draft -> viewModel?.saveManualEntry(id, revision, draft) },
            onDelete = { id, revision, generation -> viewModel?.deleteEntry(id, revision, generation) },
            onLoadMore = { viewModel?.loadMoreHistory() },
            onOpenProjectPicker = { viewModel?.openManualProjectPicker(it) },
            onSearchProjects = { viewModel?.searchManualProjects(it) },
            onLoadMoreProjects = { viewModel?.loadMoreManualProjects() },
            onCloseProjectPicker = { viewModel?.closeManualProjectPicker() },
            modifier = modifier,
        )
        AppDestination.REPORTS -> ReportsScreen(
            state = state,
            onSelectPeriod = { viewModel?.selectReportPeriod(it) },
            modifier = modifier,
            onBeginCsvExport = viewModel?.let { model -> model::beginCsvExport },
            onCancelCsvExport = viewModel?.let { model -> model::cancelCsvExport },
            onLaunchCsvDocument = onLaunchCsvDocument,
        )
        AppDestination.SETTINGS -> SettingsScreen(
            state = state,
            onTheme = { viewModel?.setTheme(it) },
            onDynamicColor = { viewModel?.setDynamicColor(it) },
            selectedLanguage = selectedLanguage,
            onLanguage = onLanguage,
            onPrivacy = { viewModel?.setPrivacy(it) },
            onDailyGoal = { viewModel?.setDailyGoalMinutes(it) },
            onDefaultProject = { viewModel?.setDefaultProject(it) },
            onOpenProjectPicker = { viewModel?.openManualProjectPicker(it) },
            onSearchProjects = { viewModel?.searchManualProjects(it) },
            onLoadMoreProjects = { viewModel?.loadMoreManualProjects() },
            onCloseProjectPicker = { viewModel?.closeManualProjectPicker() },
            notificationStatus = notificationStatus,
            onManageNotifications = onManageNotifications,
            modifier = modifier,
            onLaunchBackupDocument = onLaunchBackupDocument,
            onLaunchOpenBackupDocument = onLaunchOpenBackupDocument,
            backupActions = viewModel?.let { model ->
                BackupActions(
                    prepare = model::prepareBackup,
                    cancelPrepare = model::cancelBackup,
                    clearSelectedDocument = model::clearBackupDocument,
                    inspect = model::inspectBackup,
                    currentPreview = model::currentBackupPreview,
                    discardPreview = model::discardBackupPreview,
                    restore = model::restoreBackup,
                    preparePlain = model::preparePlainBackup,
                    format = model::backupFormat,
                    inspectPlain = model::inspectPlainBackup,
                )
            },
        )
    }
}

@Composable
fun AdaptiveTimerSurface(
    state: AppUiState,
    layout: NavigationLayout,
    compactTimer: Boolean,
    modifier: Modifier = Modifier,
    viewModel: AppViewModel? = null,
    notificationStatus: NotificationControlStatus = NotificationControlStatus.AVAILABLE,
    onManageNotifications: () -> Unit = {},
) {
    val timer: @Composable () -> Unit = {
        TimerScreen(
            state = state,
            compactTimer = compactTimer,
            onSelectProject = { viewModel?.selectProject(it) },
            onPrimary = { viewModel?.performPrimary() },
            onStop = { viewModel?.stop() },
            onCreateProject = { name, color -> viewModel?.createProject(name, color) },
            onArchiveProject = { viewModel?.archiveProject(it) },
            onLoadMoreProjects = { viewModel?.loadMoreProjects() },
            onSaveNote = { viewModel?.saveActiveNote(it) },
            onResolveReview = { viewModel?.resolveReview(it) },
            notificationStatus = notificationStatus,
            onManageNotifications = onManageNotifications,
        )
    }
    if (layout == NavigationLayout.TWO_PANE) {
        Row(modifier.fillMaxSize()) {
            Box(Modifier.weight(1.1f).fillMaxSize().testTag("timer_primary_pane")) { timer() }
            VerticalDivider()
            Box(Modifier.weight(0.9f).fillMaxSize().testTag("timer_today_pane")) { TodayPane(state) }
        }
    } else {
        Box(modifier.fillMaxSize().testTag("timer_primary_pane")) { timer() }
    }
}

@Composable
private fun AppDestination.label(): Int = when (this) {
    AppDestination.TIMER -> R.string.nav_timer
    AppDestination.HISTORY -> R.string.nav_history
    AppDestination.REPORTS -> R.string.nav_reports
    AppDestination.SETTINGS -> R.string.nav_settings
}

private fun AppDestination.description(): Int = when (this) {
    AppDestination.TIMER -> R.string.content_description_timer
    AppDestination.HISTORY -> R.string.content_description_history
    AppDestination.REPORTS -> R.string.content_description_reports
    AppDestination.SETTINGS -> R.string.content_description_settings
}

private fun UiMessage.resource(): Int = when (this) {
    UiMessage.INVALID_ENTRY -> R.string.error_invalid_entry
    UiMessage.INVALID_PROJECT -> R.string.error_invalid_project
    UiMessage.PROJECT_LIMIT_REACHED -> R.string.error_project_limit_reached
    UiMessage.STALE_ENTRY -> R.string.error_stale_entry
    UiMessage.ACTION_FAILED -> R.string.error_action_failed
    UiMessage.SAVED -> R.string.action_completed
    UiMessage.CSV_EXPORTED -> R.string.csv_export_success
    UiMessage.BACKUP_CREATED -> R.string.backup_created_success
    UiMessage.PLAIN_BACKUP_CREATED -> R.string.backup_plain_saved
    UiMessage.DATA_IO_ERROR -> R.string.data_io_error
    UiMessage.PARTIAL_FILE_CLEANUP_REQUIRED -> R.string.partial_file_cleanup_required
    UiMessage.SYSTEM_CONTROLS_UNAVAILABLE -> R.string.system_controls_unavailable
    UiMessage.SETTINGS_SYSTEM_PRIVACY_PENDING -> R.string.settings_system_privacy_pending
}
