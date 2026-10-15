package dev.opentimemanager.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.system.TrackingNotification
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.entryDurationMillis
import dev.opentimemanager.app.ui.formatDateTime
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.app.ui.projectName
import dev.opentimemanager.app.ui.todayTrackedMillis
import dev.opentimemanager.app.ui.NotificationControlStatus
import dev.opentimemanager.core.model.TrackingState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay

private val ProjectColors = listOf(
    0xFF006A67.toInt(), 0xFF8B5000.toInt(), 0xFF3F51B5.toInt(),
    0xFF7B1FA2.toInt(), 0xFF2E7D32.toInt(), 0xFFB3261E.toInt(),
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun TimerScreen(
    state: AppUiState,
    compactTimer: Boolean,
    onSelectProject: (String) -> Unit,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onCreateProject: (String, Int) -> Unit,
    onArchiveProject: (String) -> Unit,
    onSaveNote: (String) -> Unit,
    onResolveReview: (Long?) -> Unit,
    notificationStatus: NotificationControlStatus,
    onManageNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot ?: return
    val context = LocalContext.current
    val selected = state.availableProjects.firstOrNull { it.id == state.selectedProjectId }
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var showCreate by remember { mutableStateOf(false) }
    var showManualReview by remember { mutableStateOf(false) }
    var archiveCandidate by remember { mutableStateOf<ProjectEntity?>(null) }
    val activeEntry = snapshot.entries.firstOrNull { it.id == snapshot.session.entryId?.value }
    var activeNote by remember(activeEntry?.id, activeEntry?.note) { mutableStateOf(activeEntry?.note.orEmpty()) }
    LaunchedEffect(snapshot.session.state, snapshot.session.revision) {
        while (snapshot.session.state == TrackingState.RUNNING) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(1_000 - (nowElapsed % 1_000))
        }
    }
    val elapsed = TrackingNotification.elapsedMillis(snapshot, nowElapsed)
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val todayWithActive = todayTrackedMillis(snapshot, nowElapsed, today, zone)
    val goalMillis = state.preferences.dailyGoalMinutes * 60_000L
    val progress = (todayWithActive.toFloat() / goalMillis).coerceIn(0f, 1f)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactTimer) 10.dp else 18.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.timer_heading),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.daily_goal), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.goal_progress, formatDuration(todayWithActive, false), formatDuration(goalMillis, false)))
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(10.dp).semantics {
                        contentDescription = context.getString(R.string.content_description_goal_progress, (progress * 100).toInt())
                    },
                )
            }
        }
        if (notificationStatus != NotificationControlStatus.AVAILABLE) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (notificationStatus == NotificationControlStatus.PERMISSION_REQUIRED) R.string.notification_permission_needed else R.string.notification_channel_blocked))
                        TextButton(onClick = onManageNotifications) { Text(stringResource(R.string.manage_notifications)) }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(if (compactTimer) 16.dp else 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compactTimer) 10.dp else 18.dp),
                ) {
                    Text(
                        text = formatDuration(elapsed),
                        fontSize = if (compactTimer) 40.sp else 64.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        text = stringResource(TrackingNotification.statusString(snapshot.session.state)),
                        color = if (snapshot.session.state == TrackingState.RUNNING) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (snapshot.session.state == TrackingState.NEEDS_REVIEW) {
                        Text(stringResource(R.string.review_required_message), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.review_end_explanation), style = MaterialTheme.typography.bodySmall)
                        if (compactTimer) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onResolveReview(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.keep_confirmed_time)) }
                                Button(onClick = { showManualReview = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.use_manual_end)) }
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onResolveReview(null) }) { Text(stringResource(R.string.keep_confirmed_time)) }
                            Button(onClick = { showManualReview = true }) { Text(stringResource(R.string.use_manual_end)) }
                        }
                    }
                    if (snapshot.session.state != TrackingState.NEEDS_REVIEW) {
                        val primaryButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                            Button(
                                onClick = onPrimary,
                                enabled = selected != null,
                                modifier = buttonModifier,
                            ) { Text(stringResource(TrackingNotification.primaryString(snapshot.session.state))) }
                        }
                        val hasStop = snapshot.session.state == TrackingState.RUNNING || snapshot.session.state == TrackingState.PAUSED
                        if (compactTimer) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            primaryButton(Modifier.fillMaxWidth())
                            if (hasStop) OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tracking_stop)) }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            primaryButton(Modifier)
                            if (hasStop) OutlinedButton(onClick = onStop) { Text(stringResource(R.string.tracking_stop)) }
                        }
                    }
                }
            }
        }
        if (snapshot.session.state == TrackingState.RUNNING || snapshot.session.state == TrackingState.PAUSED) {
            item {
                OutlinedTextField(
                    value = activeNote,
                    onValueChange = { if (it.codePointCount(0, it.length) <= 2_000) activeNote = it },
                    label = { Text(stringResource(R.string.entry_note)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onSaveNote(activeNote) }) { Text(stringResource(R.string.save_note)) }
            }
        }
        item {
            Text(stringResource(R.string.selected_project), style = MaterialTheme.typography.titleMedium)
            if (state.availableProjects.isEmpty()) {
                Text(stringResource(R.string.no_available_projects), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    items(state.availableProjects, key = { it.id }) { project ->
                        FilterChip(
                            selected = project.id == selected?.id,
                            onClick = { onSelectProject(project.id) },
                            label = { Text(context.projectName(project)) },
                            leadingIcon = {
                                Box(
                                    Modifier.size(12.dp).background(Color(project.colorArgb), MaterialTheme.shapes.small)
                                        .semantics { contentDescription = context.getString(R.string.content_description_project_color, context.projectName(project)) },
                                )
                            },
                        )
                    }
                }
            }
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { showCreate = true }) { Text(stringResource(R.string.create_project)) }
                if (selected != null && selected.id != ProjectRepository.DEFAULT_PROJECT_ID.value && snapshot.session.projectId?.value != selected.id) {
                    TextButton(onClick = { archiveCandidate = selected }) { Text(stringResource(R.string.archive_project)) }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    if (showCreate) ProjectDialog(
        onDismiss = { showCreate = false },
        onCreate = { name, color -> onCreateProject(name, color); showCreate = false },
    )
    archiveCandidate?.let { project ->
        AlertDialog(
            onDismissRequest = { archiveCandidate = null },
            title = { Text(stringResource(R.string.archive_project)) },
            text = { Text(stringResource(R.string.archive_project_question, context.projectName(project))) },
            confirmButton = {
                TextButton(onClick = { onArchiveProject(project.id); archiveCandidate = null }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = { TextButton(onClick = { archiveCandidate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showManualReview) ReviewEndDialog(
        onDismiss = { showManualReview = false },
        onConfirm = { onResolveReview(it); showManualReview = false },
    )
}

@Composable
private fun ReviewEndDialog(onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val now = LocalDateTime.now().withSecond(0).withNano(0)
    var date by remember { mutableStateOf(now.toLocalDate().toString()) }
    var time by remember { mutableStateOf(now.toLocalTime().toString().take(5)) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resolve_review)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(date, { date = it; invalid = false }, label = { Text(stringResource(R.string.entry_date)) }, singleLine = true)
                OutlinedTextField(time, { time = it; invalid = false }, label = { Text(stringResource(R.string.entry_time)) }, singleLine = true)
                if (invalid) Text(stringResource(R.string.error_invalid_entry), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    onConfirm(LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli())
                } catch (_: DateTimeParseException) { invalid = true }
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ProjectDialog(onDismiss: () -> Unit, onCreate: (String, Int) -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(ProjectColors.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.project_name)) }, singleLine = true)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(ProjectColors) { index, candidate ->
                        FilterChip(
                            selected = candidate == color,
                            onClick = { color = candidate },
                            label = { Box(Modifier.size(26.dp).background(Color(candidate), MaterialTheme.shapes.small)) },
                            modifier = Modifier.semantics {
                                contentDescription = context.getString(R.string.content_description_color_option, index + 1)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, color) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.create_project)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TodayPane(state: AppUiState, modifier: Modifier = Modifier) {
    val snapshot = state.snapshot ?: return
    val context = LocalContext.current
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val entries = snapshot.entries.filter { entry ->
        entry.completed && Instant.ofEpochMilli(entry.startedAtUtcMillis).atZone(zone).toLocalDate() == today
    }.sortedByDescending { it.startedAtUtcMillis }
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text(stringResource(R.string.today_heading), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) Text(stringResource(R.string.no_history), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { entry ->
                val project = snapshot.projects.firstOrNull { it.id == entry.projectId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(project?.let(context::projectName).orEmpty(), fontWeight = FontWeight.Medium)
                        Text(formatDateTime(entry.startedAtUtcMillis), style = MaterialTheme.typography.bodySmall)
                        Text(formatDuration(entryDurationMillis(entry.id, state), false), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
