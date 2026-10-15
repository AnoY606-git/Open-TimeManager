package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.ReportPeriod
import dev.opentimemanager.app.ui.defaultCustomReport
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.app.ui.projectName
import dev.opentimemanager.app.ui.validateCustomReportInput
import dev.opentimemanager.app.ui.UtcBounds
import java.time.ZoneId

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ReportsScreen(
    state: AppUiState,
    onSelectPeriod: (ReportPeriod) -> Unit,
    modifier: Modifier = Modifier,
    onBeginCsvExport: ((UtcBounds) -> String)? = null,
    onCancelCsvExport: (() -> Unit)? = null,
    onLaunchCsvDocument: (String) -> Unit = {},
) {
    if (state.snapshot == null) return
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val today = rememberCurrentDate(zone)
    val bounds = state.controls.reportPeriod.validatedBounds(today, zone).value
        ?: requireNotNull(ReportPeriod.WEEK.validatedBounds(today, zone).value)
    val totals = state.reportTotals
    var showCustom by remember { mutableStateOf(false) }
    var exportLaunchFailed by remember { mutableStateOf(false) }
    val exportBusy = state.controls.dataTransferBusy
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                stringResource(R.string.reports_heading),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.controls.reportPeriod == ReportPeriod.WEEK,
                    onClick = { onSelectPeriod(ReportPeriod.WEEK) },
                    label = { Text(stringResource(R.string.report_week)) },
                )
                FilterChip(
                    selected = state.controls.reportPeriod == ReportPeriod.MONTH,
                    onClick = { onSelectPeriod(ReportPeriod.MONTH) },
                    label = { Text(stringResource(R.string.report_month)) },
                )
                FilterChip(
                    selected = state.controls.reportPeriod is ReportPeriod.Custom,
                    onClick = { showCustom = true },
                    label = { Text(stringResource(R.string.report_custom)) },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.report_total), style = MaterialTheme.typography.labelLarge)
                    Text(formatDuration(totals.totalMillis, false), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        exportLaunchFailed = false
                        try {
                            val name = onBeginCsvExport?.invoke(bounds) ?: return@Button
                            onLaunchCsvDocument(name)
                        } catch (_: Exception) {
                            onCancelCsvExport?.invoke()
                            exportLaunchFailed = true
                        }
                    },
                    enabled = !exportBusy && onBeginCsvExport != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (exportBusy) stringResource(R.string.export_in_progress) else stringResource(R.string.export_csv))
                }
                if (exportLaunchFailed) {
                    Text(
                        stringResource(R.string.csv_export_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (totals.projectTotals.isEmpty()) {
            item { Text(stringResource(R.string.no_report_data), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item { Text(stringResource(R.string.project_breakdown), style = MaterialTheme.typography.titleLarge) }
            totals.projectTotals.entries.sortedByDescending { it.value }.forEach { (projectId, duration) ->
                val project = state.reportProjects.firstOrNull { it.id == projectId.value }
                val fraction = if (totals.totalMillis == 0L) 0f else duration.toFloat() / totals.totalMillis
                item(key = projectId.value) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(project?.let(context::projectName) ?: projectId.value, fontWeight = FontWeight.Medium)
                            Text(formatDuration(duration, false))
                        }
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    }
                }
            }
        }
    }
    if (showCustom) CustomPeriodDialog(
        initial = state.controls.reportPeriod as? ReportPeriod.Custom ?: defaultCustomReport(today),
        onDismiss = { showCustom = false },
        onApply = { onSelectPeriod(it); showCustom = false },
    )
}

@Composable
private fun CustomPeriodDialog(
    initial: ReportPeriod.Custom,
    onDismiss: () -> Unit,
    onApply: (ReportPeriod.Custom) -> Unit,
) {
    var from by remember { mutableStateOf(initial.from.toString()) }
    var through by remember { mutableStateOf(initial.through.toString()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(from, { from = it; invalid = false }, label = { Text(stringResource(R.string.report_from)) }, singleLine = true)
                OutlinedTextField(through, { through = it; invalid = false }, label = { Text(stringResource(R.string.report_through)) }, singleLine = true)
                if (invalid) Text(stringResource(R.string.error_invalid_entry), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                validateCustomReportInput(from, through, ZoneId.systemDefault()).value?.let(onApply)
                    ?: run { invalid = true }
            }) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
