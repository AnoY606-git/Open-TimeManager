package dev.opentimemanager.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.system.TrackingNotification
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.NotificationControlStatus
import dev.opentimemanager.app.ui.TodayEntryProjection
import dev.opentimemanager.app.ui.components.AccentIcon
import dev.opentimemanager.app.ui.components.AppCard
import dev.opentimemanager.app.ui.components.AppGlyph
import dev.opentimemanager.app.ui.components.AppIcon
import dev.opentimemanager.app.ui.components.ScreenHeading
import dev.opentimemanager.app.ui.components.SectionHeading
import dev.opentimemanager.app.ui.formatDateTime
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.app.ui.projectName
import dev.opentimemanager.app.ui.theme.LocalAppPalette
import dev.opentimemanager.app.ui.todayTrackedMillis
import dev.opentimemanager.app.ui.validateReviewEndInput
import dev.opentimemanager.core.model.TrackingState
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.delay

private val ProjectColors = listOf(
    0xFF6251DA.toInt(), 0xFF19816E.toInt(), 0xFF4772C8.toInt(),
    0xFFAC6836.toInt(), 0xFFB04E83.toInt(), 0xFF597D3C.toInt(),
)

@Composable
fun TimerScreen(
    state: AppUiState,
    compactTimer: Boolean,
    onSelectProject: (String) -> Unit,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onCreateProject: (String, Int) -> Unit,
    onArchiveProject: (String) -> Unit,
    onLoadMoreProjects: () -> Unit,
    onSaveNote: (String) -> Unit,
    onResolveReview: (Long?) -> Unit,
    notificationStatus: NotificationControlStatus,
    onManageNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot ?: return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val selected = state.availableProjects.firstOrNull { it.id == state.selectedProjectId }
    val sessionProject = snapshot.projects.firstOrNull { it.id == snapshot.session.projectId?.value } ?: selected
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var showCreate by remember { mutableStateOf(false) }
    var showManualReview by remember { mutableStateOf(false) }
    var archiveCandidate by remember { mutableStateOf<ProjectEntity?>(null) }
    val activeEntry = snapshot.entries.firstOrNull { it.id == snapshot.session.entryId?.value }
    var activeNote by remember(activeEntry?.id, activeEntry?.note) { mutableStateOf(activeEntry?.note.orEmpty()) }
    LaunchedEffect(lifecycle, snapshot.session.state, snapshot.session.revision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (snapshot.session.state == TrackingState.RUNNING) {
                nowElapsed = SystemClock.elapsedRealtime()
                delay((1_000 - (nowElapsed % 1_000)).coerceAtLeast(1))
            }
        }
    }
    val elapsed = TrackingNotification.elapsedMillis(snapshot.session, nowElapsed)
    val todayWithActive = todayTrackedMillis(state.todayProjection, snapshot.session, nowElapsed)
    val goalMillis = state.preferences.dailyGoalMinutes * 60_000L
    val progress = (todayWithActive.toFloat() / goalMillis).coerceIn(0f, 1f)

    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = if (compactTimer) 14.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactTimer) 12.dp else 16.dp),
    ) {
        item {
            ScreenHeading(
                title = stringResource(R.string.timer_heading),
                subtitle = if (compactTimer) null else stringResource(R.string.timer_design_subtitle),
            )
        }
        item {
            TimerHero(
                elapsed = elapsed,
                trackingState = snapshot.session.state,
                projectName = sessionProject?.let(context::projectName)
                    ?: stringResource(R.string.timer_design_choose_project),
                compactTimer = compactTimer,
                primaryEnabled = selected != null,
                onPrimary = onPrimary,
                onStop = onStop,
                onKeepConfirmed = { onResolveReview(null) },
                onManualReview = { showManualReview = true },
            )
        }
        item {
            DailyGoalCard(todayWithActive, goalMillis, progress)
        }
        if (notificationStatus != NotificationControlStatus.AVAILABLE) {
            item {
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        AccentIcon(AppGlyph.Shield, modifier = Modifier.size(36.dp))
                        Text(
                            stringResource(
                                if (notificationStatus == NotificationControlStatus.PERMISSION_REQUESTABLE ||
                                    notificationStatus == NotificationControlStatus.PERMISSION_FIXED
                                ) R.string.notification_permission_needed
                                else R.string.notification_channel_blocked,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TextButton(onClick = onManageNotifications, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.manage_notifications))
                    }
                }
            }
        }
        item {
            ProjectPickerCard(
                state = state,
                selected = selected,
                onSelectProject = onSelectProject,
                onCreateProject = { showCreate = true },
                onArchiveProject = { archiveCandidate = selected },
                onLoadMoreProjects = onLoadMoreProjects,
            )
        }
        if (snapshot.session.state == TrackingState.RUNNING || snapshot.session.state == TrackingState.PAUSED) {
            item {
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(AppGlyph.Note, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        SectionHeading(stringResource(R.string.entry_note))
                    }
                    OutlinedTextField(
                        value = activeNote,
                        onValueChange = { if (it.codePointCount(0, it.length) <= 2_000) activeNote = it },
                        label = { Text(stringResource(R.string.entry_note)) },
                        placeholder = { Text(stringResource(R.string.timer_design_note_placeholder)) },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { onSaveNote(activeNote) },
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    ) {
                        AppIcon(AppGlyph.Check, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.save_note))
                    }
                }
            }
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
@OptIn(ExperimentalLayoutApi::class)
private fun TimerHero(
    elapsed: Long,
    trackingState: TrackingState,
    projectName: String,
    compactTimer: Boolean,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onKeepConfirmed: () -> Unit,
    onManualReview: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = palette.hero,
        contentColor = palette.onHero,
    ) {
        BoxWithConstraints(Modifier.padding(if (compactTimer) 16.dp else 24.dp)) {
            val headerWidth = maxWidth
            Column(verticalArrangement = Arrangement.spacedBy(if (compactTimer) 10.dp else 18.dp)) {
                if (!compactTimer) {
                    Text(
                        stringResource(R.string.timer_design_session),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.heroMuted,
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.widthIn(max = headerWidth),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppIcon(AppGlyph.Folder, modifier = Modifier.size(18.dp), tint = palette.heroAccent)
                        Text(
                            projectName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    TimerStatusChip(trackingState)
                }
                TimerDigits(formatDuration(elapsed), compactTimer)
                if (trackingState == TrackingState.NEEDS_REVIEW) {
                    Text(stringResource(R.string.review_required_message), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.review_end_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.heroMuted,
                    )
                    OutlinedButton(
                        onClick = onKeepConfirmed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.heroMuted.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.onHero),
                    ) { Text(stringResource(R.string.keep_confirmed_time), textAlign = TextAlign.Center) }
                    Button(
                        onClick = onManualReview,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.heroAccent, contentColor = palette.hero),
                    ) { Text(stringResource(R.string.use_manual_end), textAlign = TextAlign.Center) }
                } else {
                    TimerControls(trackingState, primaryEnabled, onPrimary, onStop)
                }
            }
        }
    }
}

@Composable
private fun TimerDigits(value: String, compactTimer: Boolean) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Only the numerical display scales down to fit. Labels and controls retain the user's font scale.
        val size = (maxWidth.value / (value.length * 0.62f) / fontScale)
            .coerceAtMost(if (compactTimer) 40f else 68f).sp
        Text(
            value,
            style = MaterialTheme.typography.displayLarge.copy(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
                fontSize = size,
                lineHeight = size * 1.12f,
                letterSpacing = (-1.5).sp,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun TimerStatusChip(trackingState: TrackingState) {
    val palette = LocalAppPalette.current
    val glyph = when (trackingState) {
        TrackingState.RUNNING -> AppGlyph.Play
        TrackingState.PAUSED -> AppGlyph.Pause
        TrackingState.NEEDS_REVIEW -> AppGlyph.Shield
        TrackingState.IDLE -> AppGlyph.Timer
    }
    Surface(shape = CircleShape, color = palette.heroAccentContainer, contentColor = palette.heroAccent) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(glyph, modifier = Modifier.size(14.dp))
            Text(
                stringResource(TrackingNotification.statusString(trackingState)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TimerControls(trackingState: TrackingState, primaryEnabled: Boolean, onPrimary: () -> Unit, onStop: () -> Unit) {
    val palette = LocalAppPalette.current
    val fontScale = LocalDensity.current.fontScale
    val hasStop = trackingState == TrackingState.RUNNING || trackingState == TrackingState.PAUSED
    val primaryButton: @Composable (Modifier) -> Unit = { modifier ->
        Button(
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = modifier.heightIn(min = 54.dp).testTag("timer_primary_action"),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.heroAccent,
                contentColor = palette.hero,
                disabledContainerColor = palette.heroAccent.copy(alpha = 0.22f),
                disabledContentColor = palette.onHero.copy(alpha = 0.5f),
            ),
        ) {
            AppIcon(if (trackingState == TrackingState.RUNNING) AppGlyph.Pause else AppGlyph.Play, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(TrackingNotification.primaryString(trackingState)), fontWeight = FontWeight.SemiBold)
        }
    }
    val stopButton: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(
            onClick = onStop,
            modifier = modifier.heightIn(min = 54.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            border = BorderStroke(1.dp, palette.heroMuted.copy(alpha = 0.4f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.onHero),
        ) {
            AppIcon(AppGlyph.Stop, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tracking_stop))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (fontScale >= 1.3f || maxWidth < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                primaryButton(Modifier.fillMaxWidth())
                if (hasStop) stopButton(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                primaryButton(Modifier.weight(1f))
                if (hasStop) stopButton(Modifier)
            }
        }
    }
}

@Composable
private fun DailyGoalCard(todayMillis: Long, goalMillis: Long, progress: Float) {
    val context = LocalContext.current
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentIcon(AppGlyph.Target, modifier = Modifier.size(40.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.daily_goal), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.goal_progress, formatDuration(todayMillis, false), formatDuration(goalMillis, false)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).semantics {
                contentDescription = context.getString(R.string.content_description_goal_progress, (progress * 100).toInt())
            },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ProjectPickerCard(
    state: AppUiState,
    selected: ProjectEntity?,
    onSelectProject: (String) -> Unit,
    onCreateProject: () -> Unit,
    onArchiveProject: () -> Unit,
    onLoadMoreProjects: () -> Unit,
) {
    val context = LocalContext.current
    val snapshot = state.snapshot ?: return
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        SectionHeading(stringResource(R.string.selected_project))
        if (state.availableProjects.isEmpty()) {
            Text(
                stringResource(R.string.no_available_projects),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.availableProjects, key = { it.id }) { project ->
                    FilterChip(
                        selected = project.id == selected?.id,
                        onClick = { onSelectProject(project.id) },
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 280.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        label = {
                            Text(context.projectName(project), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        leadingIcon = {
                            Box(
                                Modifier.size(10.dp).background(Color(project.colorArgb), CircleShape)
                                    .semantics {
                                        contentDescription = context.getString(R.string.content_description_project_color, context.projectName(project))
                                    },
                            )
                        },
                        trailingIcon = if (project.id == selected?.id) {
                            { AppIcon(AppGlyph.Check, modifier = Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
            if (snapshot.hasMoreProjects) {
                TextButton(onClick = onLoadMoreProjects, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.load_more_projects))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = onCreateProject,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                AppIcon(AppGlyph.Plus, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.create_project))
            }
            if (selected != null && selected.id != ProjectRepository.DEFAULT_PROJECT_ID.value && snapshot.session.projectId?.value != selected.id) {
                TextButton(onClick = onArchiveProject, modifier = Modifier.heightIn(min = 48.dp)) {
                    AppIcon(AppGlyph.Archive, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.archive_project))
                }
            }
        }
    }
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
                validateReviewEndInput(date, time, zone).value?.let(onConfirm) ?: run { invalid = true }
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
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
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
    if (state.snapshot == null) return
    val projection = state.todayProjection
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeading(stringResource(R.string.today_heading)) }
        if (projection.rows.isEmpty()) {
            item {
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                    AccentIcon(AppGlyph.History, modifier = Modifier.size(48.dp))
                    Text(stringResource(R.string.no_history), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.timer_design_today_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AccentIcon(AppGlyph.Check, modifier = Modifier.size(44.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.timer_design_today_confirmed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatDuration(projection.confirmedMillis, false),
                                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            item {
                SectionHeading(stringResource(R.string.timer_design_today_sessions), modifier = Modifier.padding(top = 6.dp))
            }
            items(projection.rows, key = { "${it.entryId.value}:${it.projectId.value}" }) { row ->
                TodayEntryCard(state, row)
            }
        }
    }
}

@Composable
private fun TodayEntryCard(state: AppUiState, row: TodayEntryProjection) {
    val context = LocalContext.current
    val entry = state.todayEntries.firstOrNull { it.id == row.entryId.value }
    val project = state.todayProjects.firstOrNull { it.id == row.projectId.value }
    val projectColor = project?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            AccentIcon(
                AppGlyph.Folder,
                modifier = Modifier.size(36.dp),
                tint = projectColor,
                containerColor = projectColor.copy(alpha = 0.1f),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val projectName = project?.let(context::projectName) ?: row.projectId.value
                val duration: @Composable () -> Unit = {
                    Text(
                        formatDuration(row.durationMillis, false),
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (largeFont) {
                    Text(projectName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    duration()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            projectName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        duration()
                    }
                }
                Text(
                    formatDateTime(row.firstStartUtcMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!entry?.note.isNullOrBlank()) {
                    Text(entry?.note.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
