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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import dev.opentimemanager.app.ui.entryFormState
import dev.opentimemanager.app.ui.entryDurationMillis
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.app.ui.projectName
import dev.opentimemanager.app.ui.validateEntryForm
import dev.opentimemanager.app.ui.components.AccentIcon
import dev.opentimemanager.app.ui.components.AppCard
import dev.opentimemanager.app.ui.components.AppGlyph
import dev.opentimemanager.app.ui.components.AppIcon
import dev.opentimemanager.app.ui.components.ScreenHeading
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HistoryScreen(
    state: AppUiState,
    onSearch: (String) -> Unit,
    onSave: (String?, Long?, ManualEntryDraft) -> Unit,
    onDelete: (String, Long) -> Unit,
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
    var edited by remember { mutableStateOf<EntryEntity?>(null) }
    var addEntry by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<EntryEntity?>(null) }
    val groups = state.completedEntries(context::projectName).groupBy {
        Instant.ofEpochMilli(it.startedAtUtcMillis).atZone(zone).toLocalDate()
    }
    val projects = remember(snapshot.projects) { snapshot.projects.associateBy(ProjectEntity::id) }
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier.widthIn(max = 880.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("heading") {
                ScreenHeading(
                    title = stringResource(R.string.history_heading),
                    subtitle = stringResource(R.string.insights_history_subtitle),
                    trailing = {
                        FilledIconButton(onClick = { addEntry = true }, modifier = Modifier.size(48.dp)) {
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
                    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                        AccentIcon(if (searching) AppGlyph.Search else AppGlyph.History)
                        Text(
                            stringResource(if (searching) R.string.insights_history_no_matches else R.string.no_history),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(if (searching) R.string.insights_history_search_hint else R.string.insights_history_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        onEdit = { edited = entry },
                        onDelete = { deleteCandidate = entry },
                    )
                }
            }
            if (snapshot.hasMoreHistory && state.controls.historyLimit < dev.opentimemanager.app.ui.MAX_HISTORY_LIMIT) {
                item("load-more") {
                    OutlinedButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.load_more_history))
                    }
                }
            }
        }
    }
    if (addEntry || edited != null) {
        EntryDialog(
            entry = edited,
            state = state,
            onDismiss = { addEntry = false; edited = null },
            onOpenProjectPicker = onOpenProjectPicker,
            onSearchProjects = onSearchProjects,
            onLoadMoreProjects = onLoadMoreProjects,
            onCloseProjectPicker = onCloseProjectPicker,
            onSave = { draft ->
                val item = edited
                onSave(item?.id, item?.revision, draft)
                addEntry = false
                edited = null
            },
        )
    }
    deleteCandidate?.let { entry ->
        AlertDialog(
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
                    onClick = { onDelete(entry.id, entry.revision); deleteCandidate = null },
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
    val duration = formatDuration(durationMillis, false)
    val description = stringResource(R.string.insights_duration_description, duration)
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
    entry: EntryEntity?,
    state: AppUiState,
    onDismiss: () -> Unit,
    onOpenProjectPicker: (String?) -> Unit,
    onSearchProjects: (String) -> Unit,
    onLoadMoreProjects: () -> Unit,
    onCloseProjectPicker: () -> Unit,
    onSave: (ManualEntryDraft) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initialForm = remember(entry?.id, entry?.revision) {
        entry?.let { entryFormState(it, entryDurationMillis(it.id, state), zone) } ?: run {
            val initial = java.time.LocalDateTime.now().minusHours(1).withSecond(0).withNano(0)
            EntryFormState(
                projectId = state.selectedProjectId.orEmpty(),
                dateInput = initial.toLocalDate().toString(),
                timeInput = initial.toLocalTime().toString().take(5),
                durationMinutesInput = "60",
                noteInput = "",
            )
        }
    }
    var form by remember(entry?.id, entry?.revision) { mutableStateOf(initialForm) }
    var invalid by remember(entry?.id, entry?.revision) { mutableStateOf(false) }
    LaunchedEffect(entry?.id, entry?.revision) {
        onOpenProjectPicker(entry?.projectId ?: form.projectId.ifBlank { null })
    }
    DisposableEffect(Unit) {
        onDispose(onCloseProjectPicker)
    }
    fun submit() {
        val validation = validateEntryForm(form, zone)
        validation.value?.let(onSave) ?: run { invalid = true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { AccentIcon(if (entry == null) AppGlyph.Plus else AppGlyph.Edit) },
        title = { Text(stringResource(if (entry == null) R.string.add_manual_entry else R.string.edit_entry)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(stringResource(R.string.selected_project), style = MaterialTheme.typography.labelLarge)
                }
                item("project-search") {
                    OutlinedTextField(
                        value = state.controls.manualProjectPicker.query,
                        onValueChange = onSearchProjects,
                        label = { Text(stringResource(R.string.search_projects)) },
                        leadingIcon = { AppIcon(AppGlyph.Search) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.controls.manualProjectPicker.pinnedProject?.let { project ->
                    item(key = "pinned-${project.id}") {
                        ProjectChoice(project, form.projectId) {
                            form = form.copy(projectId = project.id)
                            invalid = false
                        }
                    }
                }
                items(
                    items = state.controls.manualProjectPicker.projects.filterNot {
                        it.id == state.controls.manualProjectPicker.pinnedProject?.id
                    },
                    key = ProjectEntity::id,
                ) { project ->
                    ProjectChoice(project, form.projectId) {
                        form = form.copy(projectId = project.id)
                        invalid = false
                    }
                }
                if (state.controls.manualProjectPicker.hasMore) {
                    item("load-more-projects") {
                        Button(
                            onClick = onLoadMoreProjects,
                            enabled = !state.controls.manualProjectPicker.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.load_more_projects)) }
                    }
                }
                item {
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
                item {
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
                item {
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
                item {
                    OutlinedTextField(
                        form.noteInput,
                        { form = form.copy(noteInput = it); invalid = false },
                        label = { Text(stringResource(R.string.entry_note)) },
                        minLines = 2,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (invalid) item { Text(stringResource(R.string.error_invalid_entry), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ProjectChoice(project: ProjectEntity, selectedProjectId: String, onClick: () -> Unit) {
    FilterChip(
        selected = project.id == selectedProjectId,
        onClick = onClick,
        label = { Text(LocalContext.current.projectName(project)) },
        leadingIcon = {
            Box(Modifier.size(10.dp).background(Color(project.colorArgb), CircleShape))
        },
        trailingIcon = if (project.id == selectedProjectId) {
            { AppIcon(AppGlyph.Check, modifier = Modifier.size(18.dp)) }
        } else null,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    )
}
