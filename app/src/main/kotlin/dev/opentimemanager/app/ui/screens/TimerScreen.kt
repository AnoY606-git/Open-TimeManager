package dev.opentimemanager.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.opentimemanager.app.ui.components.AppMessageCard
import dev.opentimemanager.app.ui.components.FittedDurationText
import dev.opentimemanager.app.ui.components.FormErrorText
import dev.opentimemanager.app.ui.components.ScreenHeading
import dev.opentimemanager.app.ui.components.SectionHeading
import dev.opentimemanager.app.ui.formatDateTime
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.app.ui.projectName
import dev.opentimemanager.app.ui.theme.LocalAppPalette
import dev.opentimemanager.app.ui.todayTrackedMillis
import dev.opentimemanager.app.ui.validateReviewEndInput
import dev.opentimemanager.core.model.TrackingState
import dev.opentimemanager.core.model.TrackingSession
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId

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
    val locale = LocalConfiguration.current.locales[0]
    val listState = rememberLazyListState()
    val summaryVisible = remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "live-summary" } }
    }
    val availableProjects = remember(snapshot.projects) { state.availableProjects }
    val selectedProjectId = state.selectedProjectId
    val selected = availableProjects.firstOrNull { it.id == selectedProjectId }
    val sessionProject = snapshot.projects.firstOrNull { it.id == snapshot.session.projectId?.value } ?: selected
    var showCreate by remember { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var projectQuery by rememberSaveable { mutableStateOf("") }
    var switchCandidate by remember { mutableStateOf<ProjectEntity?>(null) }
    var showManualReview by remember { mutableStateOf(false) }
    var archiveCandidate by remember { mutableStateOf<ProjectEntity?>(null) }
    val activeEntry = snapshot.entries.firstOrNull { it.id == snapshot.session.entryId?.value }
    var activeNote by remember(activeEntry?.id, activeEntry?.note) { mutableStateOf(activeEntry?.note.orEmpty()) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("timer_list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = if (compactTimer) 14.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactTimer) 12.dp else 16.dp),
    ) {
        item("heading") {
            ScreenHeading(
                title = stringResource(R.string.timer_heading),
                subtitle = if (compactTimer) null else stringResource(R.string.ux_timer_subtitle),
            )
        }
        item("live-summary") {
            LiveTimerSummary(
                state = state,
                clockVisible = summaryVisible.value,
                projectName = sessionProject?.let(context::projectName)
                    ?: stringResource(R.string.timer_design_choose_project),
                compactTimer = compactTimer,
                primaryEnabled = timerPrimaryActionEnabled(snapshot.session, selected),
                onChooseProject = { projectQuery = ""; showProjects = true },
                onPrimary = onPrimary,
                onStop = onStop,
                onKeepConfirmed = { onResolveReview(null) },
                onManualReview = { showManualReview = true },
            )
        }
        if (snapshot.session.state == TrackingState.RUNNING || snapshot.session.state == TrackingState.PAUSED) {
            item("note") {
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
                        enabled = activeNote != activeEntry?.note.orEmpty(),
                        modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    ) {
                        AppIcon(AppGlyph.Check, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.save_note))
                    }
                }
            }
        }
        if (notificationStatus != NotificationControlStatus.AVAILABLE) {
            item("optional-notifications") {
                OptionalNotificationCard(notificationStatus, onManageNotifications)
            }
        }
    }

    if (showProjects) {
        val matching = remember(availableProjects, projectQuery, context, locale) {
            filterTimerProjects(availableProjects, projectQuery, context::projectName)
        }
        ProjectChooserDialog(
            projects = matching,
            selectedProjectId = selected?.id,
            query = projectQuery,
            onSearch = { projectQuery = it },
            hasMore = snapshot.hasMoreProjects,
            loading = false,
            searchHint = if (snapshot.hasMoreProjects) stringResource(R.string.ux_project_search_loaded_hint) else null,
            onLoadMore = onLoadMoreProjects,
            onSelect = { project ->
                showProjects = false
                if (project.id != selected?.id && (snapshot.session.state == TrackingState.RUNNING || snapshot.session.state == TrackingState.PAUSED)) {
                    switchCandidate = project
                } else onSelectProject(project.id)
            },
            onDismiss = { showProjects = false },
            onCreate = { showProjects = false; showCreate = true },
            onArchive = if (selected != null && selected.id != ProjectRepository.DEFAULT_PROJECT_ID.value && snapshot.session.projectId?.value != selected.id) {
                { showProjects = false; archiveCandidate = selected }
            } else null,
        )
    }
    switchCandidate?.let { project ->
        AlertDialog(
            onDismissRequest = { switchCandidate = null },
            title = { Text(stringResource(R.string.ux_switch_project_title, context.projectName(project))) },
            text = { Text(stringResource(R.string.ux_switch_project_message)) },
            confirmButton = {
                TextButton(onClick = { onSelectProject(project.id); switchCandidate = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ux_start_new_timer))
                }
            },
            dismissButton = {
                TextButton(onClick = { switchCandidate = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (showCreate) ProjectDialog(
        onDismiss = { showCreate = false },
        onCreate = { name, color ->
            onCreateProject(name, color)
            projectQuery = name.trim()
            showCreate = false
            showProjects = true
        },
    )
    archiveCandidate?.let { project ->
        AlertDialog(
            onDismissRequest = { archiveCandidate = null },
            title = { Text(stringResource(R.string.archive_project)) },
            text = { Text(stringResource(R.string.archive_project_question, context.projectName(project))) },
            confirmButton = {
                TextButton(onClick = { onArchiveProject(project.id); archiveCandidate = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.archive_project)) }
            },
            dismissButton = { TextButton(onClick = { archiveCandidate = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showManualReview) ReviewEndDialog(
        unconfirmedStartUtcMillis = snapshot.session.openSegment?.startedAt?.wallUtcMillis,
        onDismiss = { showManualReview = false },
        onConfirm = { onResolveReview(it); showManualReview = false },
    )
}

/** Only this list item reads the ticking clock; project, note, and history lists do not. */
@Composable
private fun LiveTimerSummary(
    state: AppUiState,
    clockVisible: Boolean,
    projectName: String,
    compactTimer: Boolean,
    primaryEnabled: Boolean,
    onChooseProject: () -> Unit,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onKeepConfirmed: () -> Unit,
    onManualReview: () -> Unit,
) {
    val session = state.snapshot?.session ?: return
    val nowElapsed = rememberTimerElapsedRealtime(session, enabled = clockVisible)
    val todayWithActive = todayTrackedMillis(state.todayProjection, session, nowElapsed)
    val goalMillis = state.preferences.dailyGoalMinutes * 60_000L
    val progress = if (goalMillis > 0L) (todayWithActive.toFloat() / goalMillis).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(if (compactTimer) 12.dp else 16.dp)) {
        TimerHero(
            elapsed = TrackingNotification.elapsedMillis(session, nowElapsed),
            session = session,
            projectName = projectName,
            compactTimer = compactTimer,
            primaryEnabled = primaryEnabled,
            onChooseProject = onChooseProject,
            onPrimary = onPrimary,
            onStop = onStop,
            onKeepConfirmed = onKeepConfirmed,
            onManualReview = onManualReview,
        )
        DailyGoalCard(todayWithActive, goalMillis, progress)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TimerHero(
    elapsed: Long,
    session: TrackingSession,
    projectName: String,
    compactTimer: Boolean,
    primaryEnabled: Boolean,
    onChooseProject: () -> Unit,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
    onKeepConfirmed: () -> Unit,
    onManualReview: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val trackingState = session.state
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = palette.hero,
        contentColor = palette.onHero,
    ) {
        Box(Modifier.padding(if (compactTimer) 16.dp else 24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(if (compactTimer) 10.dp else 18.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!compactTimer) {
                        Text(
                            stringResource(R.string.ux_current_entry),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.heroMuted,
                        )
                    }
                    TimerStatusChip(trackingState)
                }
                TextButton(
                    onClick = onChooseProject,
                    enabled = trackingState != TrackingState.NEEDS_REVIEW,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("timer_project_picker"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.onHero, disabledContentColor = palette.onHero),
                ) {
                    AppIcon(AppGlyph.Folder, modifier = Modifier.size(20.dp), tint = palette.heroAccent)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        projectName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f),
                    )
                    if (trackingState != TrackingState.NEEDS_REVIEW) {
                        Spacer(Modifier.width(8.dp))
                        AppIcon(AppGlyph.ChevronRight, modifier = Modifier.size(18.dp))
                    }
                }
                TimerDigits(formatDuration(elapsed), compactTimer)
                if (trackingState == TrackingState.NEEDS_REVIEW) {
                    Text(stringResource(R.string.ux_review_message), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(if (session.confirmedDurationMillis > 0L) R.string.ux_review_explanation else R.string.ux_review_no_confirmed),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.heroMuted,
                    )
                    session.lastConfirmedEndUtcMillis?.let { confirmedEnd ->
                        Text(
                            stringResource(R.string.ux_review_checkpoint, formatDateTime(confirmedEnd)),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.heroMuted,
                        )
                    }
                    Button(
                        onClick = onKeepConfirmed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("timer_keep_confirmed"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.heroAccent, contentColor = palette.hero),
                    ) {
                        Text(stringResource(if (session.confirmedDurationMillis > 0L) R.string.keep_confirmed_time else R.string.ux_discard_unconfirmed), textAlign = TextAlign.Center)
                    }
                    OutlinedButton(
                        onClick = onManualReview,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("timer_manual_end"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.heroMuted.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.onHero),
                    ) { Text(stringResource(R.string.use_manual_end), textAlign = TextAlign.Center) }
                } else {
                    TimerControls(trackingState, primaryEnabled, onPrimary, onStop)
                    if (!primaryEnabled) {
                        Text(stringResource(R.string.no_available_projects), style = MaterialTheme.typography.bodyMedium, color = palette.heroMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerDigits(value: String, compactTimer: Boolean) {
    FittedDurationText(
        text = value,
        style = MaterialTheme.typography.displayLarge.copy(
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
            fontSize = if (compactTimer) 40.sp else 68.sp,
            lineHeight = if (compactTimer) 48.sp else 76.sp,
            letterSpacing = (-1).sp,
            textAlign = TextAlign.Center,
        ),
        color = LocalAppPalette.current.onHero,
        modifier = Modifier.testTag("timer_elapsed"),
        description = stringResource(R.string.ux_duration_seconds_description, value),
    )
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
            Text(
                stringResource(TrackingNotification.primaryString(trackingState)),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
    val stopButton: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedButton(
            onClick = onStop,
            modifier = modifier.heightIn(min = 54.dp).testTag("timer_stop_action"),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            border = BorderStroke(1.dp, palette.heroMuted.copy(alpha = 0.4f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.onHero),
        ) {
            AppIcon(AppGlyph.Stop, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tracking_stop), textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (timerControlsShouldStack(maxWidth.value, fontScale)) {
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

internal fun timerPrimaryActionEnabled(session: TrackingSession, selectedProject: ProjectEntity?): Boolean = when (session.state) {
    TrackingState.IDLE -> selectedProject != null && selectedProject.archivedAtUtcMillis == null
    TrackingState.RUNNING, TrackingState.PAUSED -> session.entryId != null && session.projectId != null
    TrackingState.NEEDS_REVIEW -> false
}

internal fun timerControlsShouldStack(availableWidthDp: Float, fontScale: Float): Boolean =
    fontScale >= 1.3f || availableWidthDp / fontScale < 320f

@Composable
private fun DailyGoalCard(todayMillis: Long, goalMillis: Long, progress: Float) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val todayLabel = remember(todayMillis / 60_000L, locale) { formatDuration(todayMillis, false) }
    val goalLabel = remember(goalMillis, locale) { formatDuration(goalMillis, false) }
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        SectionHeading(
            stringResource(R.string.daily_goal),
            trailing = {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        Text(
            stringResource(R.string.goal_progress, todayLabel, goalLabel),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun OptionalNotificationCard(status: NotificationControlStatus, onManage: () -> Unit) {
    var hidden by rememberSaveable(status) { mutableStateOf(false) }
    if (hidden) return
    AppCard(Modifier.fillMaxWidth().testTag("timer_optional_notifications"), contentPadding = PaddingValues(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            AccentIcon(AppGlyph.Notifications, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.ux_optional_notifications), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (status == NotificationControlStatus.PERMISSION_REQUESTABLE) R.string.ux_notifications_permission_hint else R.string.ux_notifications_settings_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onManage, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (status == NotificationControlStatus.PERMISSION_REQUESTABLE) R.string.ux_enable_notifications else R.string.manage_notifications))
            }
            TextButton(onClick = { hidden = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ux_hide_for_now))
            }
        }
    }
}

internal fun filterTimerProjects(
    projects: List<ProjectEntity>,
    query: String,
    displayName: (ProjectEntity) -> String,
): List<ProjectEntity> {
    val trimmed = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)
    return projects.filter {
        it.archivedAtUtcMillis == null && (trimmed.isEmpty() || Normalizer.normalize(displayName(it), Normalizer.Form.NFC).contains(trimmed, ignoreCase = true))
    }
}

/** A bounded list shared with manual entry, rather than a wall of project chips. */
@Composable
internal fun ProjectChooserDialog(
    projects: List<ProjectEntity>,
    selectedProjectId: String?,
    query: String,
    onSearch: (String) -> Unit,
    hasMore: Boolean,
    loading: Boolean,
    onLoadMore: () -> Unit,
    onSelect: (ProjectEntity) -> Unit,
    onDismiss: () -> Unit,
    pinnedProject: ProjectEntity? = null,
    searchHint: String? = null,
    onCreate: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ux_choose_project)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onSearch,
                    label = { Text(stringResource(R.string.search_projects)) },
                    leadingIcon = { AppIcon(AppGlyph.Search) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { onSearch("") }, modifier = Modifier.size(48.dp)) {
                                AppIcon(AppGlyph.Close, contentDescription = stringResource(R.string.insights_clear_search))
                            }
                        }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().testTag("project_picker_search"),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup().testTag("project_picker_list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (searchHint != null) {
                        item("search-scope") {
                            Text(searchHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    pinnedProject?.let { project ->
                        item("pinned-${project.id}") { ProjectChoiceRow(project, selectedProjectId) { onSelect(project) } }
                    }
                    if (onArchive != null) {
                        item("archive-selected") {
                            TextButton(onClick = onArchive, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.ux_archive_selected_project), textAlign = TextAlign.Center)
                            }
                        }
                    }
                    items(projects.filterNot { it.id == pinnedProject?.id }, key = { it.id }) { project ->
                        ProjectChoiceRow(project, selectedProjectId) { onSelect(project) }
                    }
                    if (loading) {
                        item("loading") {
                            Row(
                                Modifier.padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.ux_loading_projects), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else if (projects.isEmpty()) {
                        item("empty") {
                            Text(
                                stringResource(if (query.isBlank()) R.string.ux_no_projects else R.string.ux_no_matching_projects),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    if (hasMore) {
                        item("more") {
                            TextButton(onClick = onLoadMore, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.load_more_projects), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onCreate != null) {
                TextButton(onClick = onCreate, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.create_project), textAlign = TextAlign.Center)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun ProjectChoiceRow(project: ProjectEntity, selectedProjectId: String?, onClick: () -> Unit) {
    val selected = project.id == selectedProjectId
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .testTag("project_choice_${project.id}").padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
            Box(Modifier.size(10.dp).background(Color(project.colorArgb), CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(LocalContext.current.projectName(project), style = MaterialTheme.typography.bodyLarge)
                if (project.archivedAtUtcMillis != null) {
                    Text(stringResource(R.string.ux_archived_project), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ReviewEndDialog(unconfirmedStartUtcMillis: Long?, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(zone) { LocalDateTime.now(zone).withSecond(0).withNano(0) }
    var date by remember { mutableStateOf(now.toLocalDate().toString()) }
    // Do not suggest "now" as if the unknown interval had been tracked until this moment.
    var time by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ux_review_end_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ux_review_end_hint), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.entry_time_zone, zone.id), style = MaterialTheme.typography.bodySmall)
                unconfirmedStartUtcMillis?.let {
                    Text(stringResource(R.string.ux_review_interval_start, formatDateTime(it)), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    date, { date = it; invalid = false },
                    label = { Text(stringResource(R.string.ux_end_date)) },
                    singleLine = true, isError = invalid,
                    modifier = Modifier.fillMaxWidth().testTag("review_end_date"),
                )
                OutlinedTextField(
                    time, { time = it; invalid = false },
                    label = { Text(stringResource(R.string.ux_end_time)) },
                    singleLine = true, isError = invalid,
                    modifier = Modifier.fillMaxWidth().testTag("review_end_time"),
                )
                if (invalid) FormErrorText(stringResource(R.string.ux_review_end_error))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                reviewEndMillis(date, time, zone, unconfirmedStartUtcMillis)?.let(onConfirm) ?: run { invalid = true }
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ux_finish_entry)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun reviewEndMillis(date: String, time: String, zone: ZoneId, unconfirmedStartUtcMillis: Long?): Long? =
    validateReviewEndInput(date, time, zone).value?.takeIf { unconfirmedStartUtcMillis == null || it > unconfirmedStartUtcMillis }

internal fun validProjectName(name: String): Boolean {
    val normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
    return normalized.isNotBlank() && normalized.codePointCount(0, normalized.length) <= 80
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.project_name)) },
                    singleLine = true,
                    isError = name.isNotBlank() && !validProjectName(name),
                    supportingText = { Text(stringResource(R.string.ux_project_name_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("project_create_name"),
                )
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
        confirmButton = {
            TextButton(onClick = { onCreate(name, color) }, enabled = validProjectName(name), modifier = Modifier.heightIn(min = 48.dp).testTag("project_create_confirm")) {
                Text(stringResource(R.string.create_project))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TodayPane(state: AppUiState, modifier: Modifier = Modifier) {
    if (state.snapshot == null) return
    val projection = state.todayProjection
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("today_list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("heading") { ScreenHeading(stringResource(R.string.today_heading), stringResource(R.string.ux_today_subtitle)) }
        if (projection.rows.isEmpty()) {
            item("empty") {
                AppMessageCard(
                    title = stringResource(R.string.ux_today_empty),
                    message = stringResource(R.string.ux_today_empty_hint),
                    glyph = AppGlyph.History,
                )
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
                            FittedDurationText(
                                formatDuration(projection.confirmedMillis, includeSeconds = projection.confirmedMillis in 1L..59_999L),
                                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                                description = stringResource(
                                    if (projection.confirmedMillis in 1L..59_999L) R.string.ux_duration_seconds_description else R.string.insights_duration_description,
                                    formatDuration(projection.confirmedMillis, includeSeconds = projection.confirmedMillis in 1L..59_999L),
                                ),
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
                        formatDuration(row.durationMillis, includeSeconds = row.durationMillis in 1L..59_999L),
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
                    Text(entry?.note.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
