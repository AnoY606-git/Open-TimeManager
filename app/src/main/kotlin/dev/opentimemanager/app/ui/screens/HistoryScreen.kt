package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import dev.opentimemanager.app.ui.components.AdaptiveAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.EntryFormState
import dev.opentimemanager.app.ui.ManualEntryDraft
import dev.opentimemanager.app.ui.MAX_HISTORY_LIMIT
import dev.opentimemanager.app.ui.entryFormState
import dev.opentimemanager.app.ui.entryDurationMillis
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.app.ui.projectName
import dev.opentimemanager.app.ui.validateEntryForm
import dev.opentimemanager.app.ui.components.AccentIcon
import dev.opentimemanager.app.ui.components.AppCard
import dev.opentimemanager.app.ui.components.AppGlyph
import dev.opentimemanager.app.ui.components.AppIcon
import dev.opentimemanager.app.ui.components.AppMessageCard
import dev.opentimemanager.app.ui.components.FormErrorText
import dev.opentimemanager.app.ui.components.ScreenHeading
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HistoryScreen(
    state: AppUiState,
    onSearch: (String) -> Unit,
    onSave: (String?, Long?, ManualEntryDraft) -> Unit,
    onDelete: (String, Long, String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenProjectPicker: (String?) -> Unit,
    onSearchProjects: (String) -> Unit,
    onLoadMoreProjects: () -> Unit,
    onCloseProjectPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot ?: return
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    }
    val timeFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    }
    var editor by rememberSaveable(stateSaver = HistoryEditSession.Saver) { mutableStateOf<HistoryEditSession?>(null) }
    var deleteCandidate by remember { mutableStateOf<HistoryDeleteRequest?>(null) }
    val groups = remember(snapshot.entries, snapshot.projects, state.controls.historySearch, context, locale, zone) {
        state.completedEntries(context::projectName).groupBy {
            Instant.ofEpochMilli(it.startedAtUtcMillis).atZone(zone).toLocalDate()
        }
    }
    val projects = remember(snapshot.projects) { snapshot.projects.associateBy(ProjectEntity::id) }
    val searchScope = if (state.controls.historySearch.isNotBlank()) {
        historySearchScopeResource(snapshot.hasMoreHistory, state.controls.historyLimit)?.let { resource ->
            if (resource == R.string.ux_history_search_limit_hint) stringResource(resource, MAX_HISTORY_LIMIT) else stringResource(resource)
        }
    } else null
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier.widthIn(max = 880.dp).fillMaxSize().testTag("history_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("heading") {
                ScreenHeading(
                    title = stringResource(R.string.history_heading),
                    subtitle = stringResource(R.string.ux_history_subtitle),
                    trailing = {
                        FilledIconButton(onClick = { editor = HistoryEditSession.open(state) }, modifier = Modifier.size(48.dp)) {
                            AppIcon(AppGlyph.Plus, contentDescription = stringResource(R.string.add_manual_entry))
                        }
                    },
                )
            }
            item("search") {
                OutlinedTextField(
                    value = state.controls.historySearch,
                    onValueChange = onSearch,
                    label = { Text(stringResource(R.string.search_history)) },
                    supportingText = if (searchScope != null) {
                        { Text(searchScope) }
                    } else null,
                    leadingIcon = { AppIcon(AppGlyph.Search) },
                    trailingIcon = if (state.controls.historySearch.isNotEmpty()) {
                        {
                            IconButton(onClick = { onSearch("") }, modifier = Modifier.size(48.dp)) {
                                AppIcon(AppGlyph.Close, contentDescription = stringResource(R.string.insights_clear_search))
                            }
                        }
                    } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (groups.isEmpty()) {
                item("empty") {
                    val searching = state.controls.historySearch.isNotBlank()
                    AppMessageCard(
                        title = stringResource(
                            if (searching && searchScope != null) R.string.ux_history_loaded_no_matches
                            else if (searching) R.string.insights_history_no_matches else R.string.no_history,
                        ),
                        message = if (searching && searchScope != null) searchScope else stringResource(if (searching) R.string.insights_history_search_hint else R.string.insights_history_empty_hint),
                        glyph = if (searching) AppGlyph.Search else AppGlyph.History,
                    )
                }
            }
            groups.forEach { (date, entries) ->
                item(key = "date-$date") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Text(
                            dateFormatter.format(date),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                }
                items(entries, key = { it.id }) { entry ->
                    HistoryEntryCard(
                        entry = entry,
                        project = projects[entry.projectId],
                        durationMillis = entryDurationMillis(entry.id, state),
                        startLabel = stringResource(
                            R.string.insights_history_started,
                            timeFormatter.format(Instant.ofEpochMilli(entry.startedAtUtcMillis).atZone(zone)),
                        ),
                        onEdit = { editor = HistoryEditSession.open(state, entry) },
                        onDelete = { deleteCandidate = HistoryDeleteRequest.open(state, entry) },
                    )
                }
            }
            if (snapshot.hasMoreHistory && state.controls.historyLimit < MAX_HISTORY_LIMIT) {
                item("load-more") {
                    OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.load_more_history))
                    }
                }
            }
        }
    }
    editor?.let { editing ->
        EntryDialog(
            session = editing,
            state = state,
            onDismiss = { editor = null },
            onOpenProjectPicker = onOpenProjectPicker,
            onSearchProjects = onSearchProjects,
            onLoadMoreProjects = onLoadMoreProjects,
            onCloseProjectPicker = onCloseProjectPicker,
            onSave = { draft ->
                editing.save(draft, onSave)
                editor = null
            },
        )
    }
    deleteCandidate?.let { deletion ->
        AdaptiveAlertDialog(
            onDismissRequest = { deleteCandidate = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                AccentIcon(
                    AppGlyph.Delete,
                    tint = MaterialTheme.colorScheme.error,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                )
            },
            title = { Text(stringResource(R.string.delete_entry)) },
            text = { Text(stringResource(R.string.delete_entry_question)) },
            confirmButton = {
                TextButton(
                    onClick = { deletion.confirm(onDelete); deleteCandidate = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete_entry)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Frozen at the opening click, not rebound when a restore publishes another snapshot. */
internal class HistoryEditSession private constructor(
    val entryId: String?,
    val expectedRevision: Long?,
    val expectedGeneration: String,
    val initialForm: EntryFormState,
) {
    fun save(draft: ManualEntryDraft, onSave: (String?, Long?, ManualEntryDraft) -> Unit) {
        onSave(entryId, expectedRevision, draft.copy(expectedGeneration = expectedGeneration))
    }

    companion object {
        // Восстанавливаем исходный контракт, даже если snapshot уже заменён восстановлением БД.
        val Saver = listSaver<HistoryEditSession?, Any?>(
            save = { session ->
                if (session == null) emptyList() else listOf(
                    session.entryId, session.expectedRevision, session.expectedGeneration,
                ) + session.initialForm.saveableFields()
            },
            restore = { fields ->
                HistoryEditSession(
                    fields[0] as String?, fields[1] as Long?, fields[2] as String,
                    restoreEntryForm(fields.drop(3)),
                )
            },
        )

        fun open(state: AppUiState, entry: EntryEntity? = null, zoneId: ZoneId = ZoneId.systemDefault()): HistoryEditSession {
            val snapshot = requireNotNull(state.snapshot)
            val form = entry?.let { entryFormState(it, entryDurationMillis(it.id, state), zoneId) } ?: run {
                val initial = LocalDateTime.now(zoneId).minusHours(1).withSecond(0).withNano(0)
                EntryFormState(
                    projectId = state.selectedProjectId.orEmpty(),
                    dateInput = initial.toLocalDate().toString(),
                    timeInput = initial.toLocalTime().toString().take(5),
                    durationMinutesInput = "60",
                    noteInput = "",
                    zoneId = zoneId.id,
                )
            }
            return HistoryEditSession(entry?.id, entry?.revision, snapshot.generation, form)
        }
    }
}

// Порядок полей един для исходной формы сеанса и изменяемого черновика.
private fun EntryFormState.saveableFields(): List<Any?> = listOf(
    projectId, dateInput, timeInput, durationMinutesInput, noteInput,
    originalStartUtcMillis, originalDurationMillis, dateEdited, timeEdited, durationEdited, zoneId,
)

private fun restoreEntryForm(fields: List<Any?>) = EntryFormState(
    projectId = fields[0] as String,
    dateInput = fields[1] as String,
    timeInput = fields[2] as String,
    durationMinutesInput = fields[3] as String,
    noteInput = fields[4] as String,
    originalStartUtcMillis = fields[5] as Long?,
    originalDurationMillis = fields[6] as Long?,
    dateEdited = fields[7] as Boolean,
    timeEdited = fields[8] as Boolean,
    durationEdited = fields[9] as Boolean,
    zoneId = fields[10] as String?,
)

private val EntryFormSaver = listSaver<EntryFormState, Any?>(
    save = { it.saveableFields() },
    restore = ::restoreEntryForm,
)

internal class HistoryDeleteRequest private constructor(val entry: EntryEntity, val expectedGeneration: String) {
    fun confirm(onDelete: (String, Long, String) -> Unit) {
        onDelete(entry.id, entry.revision, expectedGeneration)
    }

    companion object {
        fun open(state: AppUiState, entry: EntryEntity): HistoryDeleteRequest =
            HistoryDeleteRequest(entry, requireNotNull(state.snapshot).generation)
    }
}

internal fun historySearchScopeResource(hasMoreHistory: Boolean, historyLimit: Int): Int? = when {
    !hasMoreHistory -> null
    historyLimit < MAX_HISTORY_LIMIT -> R.string.ux_history_search_loaded_hint
    else -> R.string.ux_history_search_limit_hint
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun HistoryEntryCard(
    entry: EntryEntity,
    project: ProjectEntity?,
    durationMillis: Long,
    startLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val name = project?.let(context::projectName) ?: stringResource(R.string.default_project_name)
    val color = project?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val durationWidth = maxWidth / 2
            if (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.5f) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HistoryEntryIdentity(name, color)
                    HistoryEntryDuration(durationMillis)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HistoryEntryIdentity(name, color, Modifier.weight(1f))
                    HistoryEntryDuration(durationMillis, Modifier.widthIn(max = durationWidth))
                }
            }
        }
        if (entry.note.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon(AppGlyph.Note, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    entry.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                Modifier.heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(AppGlyph.Timer, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(startLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    AppIcon(AppGlyph.Edit, contentDescription = stringResource(R.string.edit_entry), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    AppIcon(AppGlyph.Delete, contentDescription = stringResource(R.string.delete_entry), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryIdentity(name: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(AppGlyph.Folder, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(8.dp)
                    .background(color, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HistoryEntryDuration(durationMillis: Long, modifier: Modifier = Modifier) {
    val showSeconds = durationMillis in 1L..59_999L
    val duration = formatDuration(durationMillis, includeSeconds = showSeconds)
    val description = stringResource(if (showSeconds) R.string.ux_duration_seconds_description else R.string.insights_duration_description, duration)
    Text(
        duration,
        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.semantics { contentDescription = description },
    )
}

@Composable
private fun EntryDialog(
    session: HistoryEditSession,
    state: AppUiState,
    onDismiss: () -> Unit,
    onOpenProjectPicker: (String?) -> Unit,
    onSearchProjects: (String) -> Unit,
    onLoadMoreProjects: () -> Unit,
    onCloseProjectPicker: () -> Unit,
    onSave: (ManualEntryDraft) -> Unit,
) {
    val editing = session.entryId != null
    val zone = remember(session) { ZoneId.of(requireNotNull(session.initialForm.zoneId)) }
    val context = LocalContext.current
    val initialForm = session.initialForm
    var form by rememberSaveable(session, stateSaver = EntryFormSaver) { mutableStateOf(initialForm) }
    var invalid by rememberSaveable(session) { mutableStateOf(false) }
    var showProjects by rememberSaveable(session) { mutableStateOf(false) }
    var projectQuery by rememberSaveable(session) { mutableStateOf("") }
    var chosenProject by remember(session) { mutableStateOf<ProjectEntity?>(null) }
    val picker = state.controls.manualProjectPicker
    val selectedProject = chosenProject?.takeIf { it.id == form.projectId }
        ?: picker.pinnedProject?.takeIf { it.id == form.projectId }
        ?: picker.projects.firstOrNull { it.id == form.projectId }
        ?: state.snapshot?.projects?.firstOrNull { it.id == form.projectId }
    LaunchedEffect(session) {
        onOpenProjectPicker(form.projectId.ifBlank { null })
        if (projectQuery.isNotEmpty()) onSearchProjects(projectQuery)
    }
    DisposableEffect(Unit) {
        onDispose(onCloseProjectPicker)
    }
    fun submit() {
        val validation = validateEntryForm(form, zone)
        validation.value?.let(onSave) ?: run { invalid = true }
    }
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { AccentIcon(if (editing) AppGlyph.Edit else AppGlyph.Plus) },
        title = { Text(stringResource(if (editing) R.string.edit_entry else R.string.add_manual_entry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.selected_project), style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(
                            onClick = { showProjects = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("entry_project_picker"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                selectedProject?.let(context::projectName) ?: stringResource(R.string.ux_choose_project),
                                modifier = Modifier.weight(1f),
                            )
                            AppIcon(AppGlyph.ChevronRight, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Column {
                    Text(stringResource(R.string.entry_time_zone, zone.id), style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    OutlinedTextField(
                        form.dateInput,
                        { value ->
                            form = form.copy(dateInput = value, dateEdited = value != initialForm.dateInput)
                            invalid = false
                        },
                        label = { Text(stringResource(R.string.entry_date)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    OutlinedTextField(
                        form.timeInput,
                        { value ->
                            form = form.copy(timeInput = value, timeEdited = value != initialForm.timeInput)
                            invalid = false
                        },
                        label = { Text(stringResource(R.string.entry_time)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    OutlinedTextField(
                        form.durationMinutesInput,
                        { raw ->
                            val value = raw.filter(Char::isDigit)
                            form = form.copy(
                                durationMinutesInput = value,
                                durationEdited = value != initialForm.durationMinutesInput,
                            )
                            invalid = false
                        },
                        label = { Text(stringResource(R.string.entry_duration_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    OutlinedTextField(
                        form.noteInput,
                        { form = form.copy(noteInput = it); invalid = false },
                        label = { Text(stringResource(R.string.entry_note)) },
                        minLines = 2,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (invalid) FormErrorText(stringResource(R.string.error_invalid_entry))
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        },
    )
    if (showProjects) {
        ProjectChooserDialog(
            projects = picker.projects,
            pinnedProject = selectedProject,
            selectedProjectId = form.projectId,
            query = projectQuery,
            onSearch = { query -> projectQuery = query; onSearchProjects(query) },
            hasMore = picker.hasMore,
            loading = picker.loading,
            onLoadMore = onLoadMoreProjects,
            onSelect = { project ->
                chosenProject = project
                form = form.copy(projectId = project.id)
                invalid = false
                showProjects = false
            },
            onDismiss = { showProjects = false },
        )
    }
}
