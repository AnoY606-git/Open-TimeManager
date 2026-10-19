package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.opentimemanager.app.R
import dev.opentimemanager.app.ui.ReportPeriod
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.app.ui.formatMonthlyBalanceDelta
import dev.opentimemanager.app.ui.monthlyBalance
import dev.opentimemanager.app.ui.suggestedMonthlyTargetMinutes
import dev.opentimemanager.app.ui.validateMonthlyTargetInput
import dev.opentimemanager.app.ui.components.AppCard
import dev.opentimemanager.app.ui.components.AppGlyph
import dev.opentimemanager.app.ui.components.AppIcon
import dev.opentimemanager.app.ui.components.FittedDurationText
import dev.opentimemanager.app.ui.components.FormErrorText
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
private fun monthName(month: YearMonth): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(month, locale) {
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MonthReportNavigation(month: YearMonth, today: LocalDate, zone: ZoneId, onSelect: (ReportPeriod) -> Unit) {
    val previous = month.minusMonths(1).takeIf { ReportPeriod.CalendarMonth(it).validatedBounds(today, zone).value != null }
    val next = month.plusMonths(1).takeIf { ReportPeriod.CalendarMonth(it).validatedBounds(today, zone).value != null }
    val current = YearMonth.from(today)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val previousLabel = stringResource(R.string.report_month_previous)
            IconButton(
                onClick = { previous?.let { onSelect(ReportPeriod.CalendarMonth(it)) } },
                enabled = previous != null,
                modifier = Modifier.size(48.dp).testTag("report_month_previous").semantics { contentDescription = previousLabel },
            ) { AppIcon(AppGlyph.ChevronRight, modifier = Modifier.rotate(180f)) }
            Text(monthName(month), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).semantics { heading() })
            val nextLabel = stringResource(R.string.report_month_next)
            IconButton(
                onClick = { next?.let { onSelect(ReportPeriod.CalendarMonth(it)) } }, enabled = next != null,
                modifier = Modifier.size(48.dp).testTag("report_month_next").semantics { contentDescription = nextLabel },
            ) { AppIcon(AppGlyph.ChevronRight) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (month != current) TextButton(onClick = { onSelect(ReportPeriod.MONTH) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.report_month_current))
            }
        }
    }
}

@Composable
internal fun MonthlyBalanceCard(
    actualMillis: Long, targetMinutes: Int?, loading: Boolean, finished: Boolean, canEdit: Boolean, onEdit: () -> Unit,
) {
    AppCard(Modifier.testTag("monthly_balance_card")) {
        Text(stringResource(R.string.monthly_balance_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        if (loading) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.data_operation_in_progress))
        } else {
            val balance = monthlyBalance(actualMillis, targetMinutes)
            Text(stringResource(R.string.monthly_actual), style = MaterialTheme.typography.labelLarge)
            FittedDurationText(formatDuration(actualMillis), MaterialTheme.typography.headlineMedium,
                modifier = Modifier.testTag("monthly_actual"))
            Text(stringResource(R.string.monthly_target), style = MaterialTheme.typography.labelLarge)
            if (targetMinutes == null) {
                Text(stringResource(R.string.monthly_target_unset), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.monthly_target_unset_hint), style = MaterialTheme.typography.bodyMedium)
            } else {
                FittedDurationText(formatDuration(targetMinutes.toLong() * 60_000L, false), MaterialTheme.typography.headlineSmall)
                balance.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                val delta = requireNotNull(balance.deltaMillis)
                val label = when {
                    delta > 0 -> R.string.monthly_balance_above
                    delta == 0L -> R.string.monthly_balance_met
                    finished -> R.string.monthly_balance_below
                    else -> R.string.monthly_balance_remaining
                }
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                FittedDurationText(formatMonthlyBalanceDelta(delta), MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("monthly_delta"))
            }
            Text(stringResource(R.string.monthly_balance_confirmed_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onEdit, enabled = canEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("monthly_target_edit")) {
            Text(stringResource(if (targetMinutes == null) R.string.monthly_target_set else R.string.monthly_target_edit))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun MonthlyTargetDialog(
    month: YearMonth, targetMinutes: Int?, dailyGoalMinutes: Int, onDismiss: () -> Unit, onSave: (Int?) -> Unit,
) {
    var hours by rememberSaveable(month) { mutableStateOf(targetMinutes?.div(60)?.toString().orEmpty()) }
    var minutes by rememberSaveable(month) { mutableStateOf((targetMinutes?.rem(60) ?: 0).toString()) }
    var invalid by rememberSaveable(month) { mutableStateOf(false) }
    val estimate = remember(month, dailyGoalMinutes) { suggestedMonthlyTargetMinutes(month, dailyGoalMinutes) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            // Заголовок и кнопки тоже прокручиваются: крупный текст не лишает поля высоты.
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(monthName(month), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.monthly_target), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = hours, onValueChange = { hours = it; invalid = false }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.monthly_target_hours)) },
                    modifier = Modifier.fillMaxWidth().testTag("monthly_target_hours"),
                )
                OutlinedTextField(
                    value = minutes, onValueChange = { minutes = it; invalid = false }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.monthly_target_minutes)) },
                    modifier = Modifier.fillMaxWidth().testTag("monthly_target_minutes"),
                )
                if (invalid) FormErrorText(stringResource(R.string.monthly_target_error))
                Text(stringResource(R.string.monthly_target_estimate, formatDuration(estimate.toLong() * 60_000L, false)))
                Text(stringResource(R.string.monthly_target_estimate_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { hours = (estimate / 60).toString(); minutes = (estimate % 60).toString(); invalid = false },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("monthly_target_estimate")) {
                    Text(stringResource(R.string.monthly_target_use_estimate))
                }
                Text(stringResource(R.string.monthly_plan_local_only), style = MaterialTheme.typography.bodySmall)
                if (targetMinutes != null) TextButton(onClick = { onSave(null) }, modifier = Modifier.heightIn(min = 48.dp).testTag("monthly_target_remove")) {
                    Text(stringResource(R.string.monthly_target_remove))
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = {
                        val value = validateMonthlyTargetInput(month, hours, minutes)
                        if (value == null) invalid = true else onSave(value)
                    }, modifier = Modifier.heightIn(min = 48.dp).testTag("monthly_target_save")) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}
