package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.EntryEntity
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.entryDurationMillis
import dev.opentimemanager.app.ui.formatDateTime
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.app.ui.projectName
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HistoryScreen(
    state: AppUiState,
    onSearch: (String) -> Unit,
    onSave: (String?, Long?, String, Long, Long, String) -> Unit,
    onDelete: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot ?: return
    val context = LocalContext.current
    var edited by remember { mutableStateOf<EntryEntity?>(null) }
    var addEntry by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<EntryEntity?>(null) }
    val groups = state.completedEntries.groupBy {
        Instant.ofEpochMilli(it.startedAtUtcMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                stringResource(R.string.history_heading),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        item {
            OutlinedTextField(
                value = state.controls.historySearch,
                onValueChange = onSearch,
                label = { Text(stringResource(R.string.search_history)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Button(onClick = { addEntry = true }) { Text(stringResource(R.string.add_manual_entry)) } }
        if (groups.isEmpty()) item { Text(stringResource(R.string.no_history), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        groups.forEach { (date, entries) ->
            item(key = "date-$date") {
                Text(
                    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).format(date),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            items(entries, key = { it.id }) { entry ->
                val project = snapshot.projects.firstOrNull { it.id == entry.projectId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(project?.let(context::projectName).orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text(formatDuration(entryDurationMillis(entry.id, state), false), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        Text(formatDateTime(entry.startedAtUtcMillis), style = MaterialTheme.typography.bodySmall)
                        if (entry.note.isNotBlank()) Text(entry.note)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(onClick = { edited = entry }) { Text(stringResource(R.string.edit_entry)) }
                            TextButton(onClick = { deleteCandidate = entry }) { Text(stringResource(R.string.delete_entry)) }
                        }
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
            onSave = { project, start, end, note ->
                val item = edited
                onSave(item?.id, item?.revision, project, start, end, note)
                addEntry = false
                edited = null
            },
        )
    }
    deleteCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.delete_entry)) },
            text = { Text(stringResource(R.string.delete_entry_question)) },
            confirmButton = {
                TextButton(onClick = { onDelete(entry.id, entry.revision); deleteCandidate = null }) { Text(stringResource(R.string.delete_entry)) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun EntryDialog(
    entry: EntryEntity?,
    state: AppUiState,
    onDismiss: () -> Unit,
    onSave: (String, Long, Long, String) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val initial = entry?.startedAtUtcMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
        ?: LocalDateTime.now().minusHours(1).withSecond(0).withNano(0)
    var projectId by remember(entry?.id) { mutableStateOf(entry?.projectId ?: state.selectedProjectId.orEmpty()) }
    var date by remember(entry?.id) { mutableStateOf(initial.toLocalDate().toString()) }
    var time by remember(entry?.id) { mutableStateOf(initial.toLocalTime().toString().take(5)) }
    var duration by remember(entry?.id) {
        mutableStateOf(entry?.let { (entryDurationMillis(it.id, state) / 60_000).coerceAtLeast(1).toString() } ?: "60")
    }
    var note by remember(entry?.id) { mutableStateOf(entry?.note.orEmpty()) }
    var invalid by remember(entry?.id) { mutableStateOf(false) }
    fun submit() {
        try {
            val start = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
            val minutes = duration.toLong()
            val end = Math.addExact(start, Math.multiplyExact(minutes, 60_000L))
            if (minutes <= 0 || projectId.isBlank()) invalid = true else onSave(projectId, start, end, note)
        } catch (_: DateTimeParseException) { invalid = true }
        catch (_: ArithmeticException) { invalid = true }
        catch (_: NumberFormatException) { invalid = true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (entry == null) R.string.add_manual_entry else R.string.edit_entry)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(stringResource(R.string.selected_project), style = MaterialTheme.typography.labelLarge)
                    state.availableProjects.forEach { project ->
                        FilterChip(
                            selected = project.id == projectId,
                            onClick = { projectId = project.id },
                            label = { Text(LocalContext.current.projectName(project)) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                item { OutlinedTextField(date, { date = it; invalid = false }, label = { Text(stringResource(R.string.entry_date)) }, singleLine = true) }
                item { OutlinedTextField(time, { time = it; invalid = false }, label = { Text(stringResource(R.string.entry_time)) }, singleLine = true) }
                item {
                    OutlinedTextField(
                        duration,
                        { duration = it.filter(Char::isDigit); invalid = false },
                        label = { Text(stringResource(R.string.entry_duration_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
                item { OutlinedTextField(note, { note = it; invalid = false }, label = { Text(stringResource(R.string.entry_note)) }, minLines = 2) }
                if (invalid) item { Text(stringResource(R.string.error_invalid_entry), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = ::submit) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
