package dev.opentimemanager.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.settings.AppLanguage
import dev.opentimemanager.app.ui.screens.HistoryScreen
import dev.opentimemanager.app.ui.screens.ReportsScreen
import dev.opentimemanager.app.ui.screens.SettingsScreen
import dev.opentimemanager.app.ui.screens.TimerScreen
import dev.opentimemanager.app.ui.screens.TodayPane

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
    val message = state.controls.message
    val messageText = message?.let { stringResource(it.resource()) }
    LaunchedEffect(message, messageText) {
        if (message != null && messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel?.clearMessage()
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("adaptive_shell")) {
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
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar(Modifier.testTag("compact_navigation")) {
                            destinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = state.controls.destination == destination,
                                    onClick = { viewModel?.navigate(destination) },
                                    icon = { DestinationMarker(destination) },
                                    label = { Text(stringResource(destination.label())) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    DestinationContent(state, viewModel, layout, compactTimer, notificationStatus, onManageNotifications, selectedLanguage, onLanguage, Modifier.padding(padding))
                }
            } else {
                Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    NavigationRail(Modifier.testTag("expanded_navigation")) {
                        destinations.forEach { destination ->
                            NavigationRailItem(
                                selected = state.controls.destination == destination,
                                onClick = { viewModel?.navigate(destination) },
                                icon = { DestinationMarker(destination) },
                                label = { Text(stringResource(destination.label())) },
                            )
                        }
                    }
                    DestinationContent(state, viewModel, layout, compactTimer, notificationStatus, onManageNotifications, selectedLanguage, onLanguage, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DestinationMarker(destination: AppDestination) {
    val description = stringResource(destination.description())
    Box(
        Modifier.size(18.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
            .semantics { contentDescription = description },
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
            onDelete = { id, revision -> viewModel?.deleteEntry(id, revision) },
            modifier = modifier,
        )
        AppDestination.REPORTS -> ReportsScreen(state, { viewModel?.selectReportPeriod(it) }, modifier)
        AppDestination.SETTINGS -> SettingsScreen(
            state = state,
            onTheme = { viewModel?.setTheme(it) },
            onDynamicColor = { viewModel?.setDynamicColor(it) },
            selectedLanguage = selectedLanguage,
            onLanguage = onLanguage,
            onPrivacy = { viewModel?.setPrivacy(it) },
            onDailyGoal = { viewModel?.setDailyGoalMinutes(it) },
            modifier = modifier,
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
    UiMessage.STALE_ENTRY -> R.string.error_stale_entry
    UiMessage.ACTION_FAILED -> R.string.error_action_failed
    UiMessage.SAVED -> R.string.action_completed
}
