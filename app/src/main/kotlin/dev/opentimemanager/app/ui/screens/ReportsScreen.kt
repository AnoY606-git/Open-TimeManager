package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opentimemanager.app.R
import dev.opentimemanager.app.ui.AppUiState
import dev.opentimemanager.app.ui.DateInputError
import dev.opentimemanager.app.ui.ReportPeriod
import dev.opentimemanager.app.ui.defaultCustomReport
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.app.ui.projectName
import dev.opentimemanager.app.ui.validateCustomReportInput
import dev.opentimemanager.app.ui.UtcBounds
import dev.opentimemanager.app.ui.components.AccentIcon
import dev.opentimemanager.app.ui.components.AppCard
import dev.opentimemanager.app.ui.components.AppGlyph
import dev.opentimemanager.app.ui.components.AppIcon
import dev.opentimemanager.app.ui.components.AppMessageCard
import dev.opentimemanager.app.ui.components.FittedDurationText
import dev.opentimemanager.app.ui.components.FormErrorText
import dev.opentimemanager.app.ui.components.ScreenHeading
import dev.opentimemanager.app.ui.components.SectionHeading
import dev.opentimemanager.app.ui.theme.LocalAppPalette
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val today = rememberCurrentDate(zone)
    val bounds = state.controls.reportPeriod.validatedBounds(today, zone).value
        ?: requireNotNull(ReportPeriod.WEEK.validatedBounds(today, zone).value)
    val totals = state.reportTotals
    // Never label a previous query's totals with the newly selected period or time zone.
    val reportLoading = state.reportLoading || state.reportBounds?.let { it != bounds } == true
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    }
    val from = Instant.ofEpochMilli(bounds.fromUtcMillis).atZone(zone).toLocalDate()
    val through = Instant.ofEpochMilli(bounds.toUtcMillis).minusMillis(1).atZone(zone).toLocalDate()
    val dateRange = if (from == through) dateFormatter.format(from) else stringResource(
        R.string.insights_report_date_range,
        dateFormatter.format(from),
        dateFormatter.format(through),
    )
    val fallbackColor = MaterialTheme.colorScheme.primary
    val slices = remember(totals, state.reportProjects, context, locale, fallbackColor) {
        val projects = state.reportProjects.associateBy { it.id }
        val percentFormatter = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }
        totals.projectTotals.entries.sortedByDescending { it.value }.map { (projectId, duration) ->
            val project = projects[projectId.value]
            val fraction = if (totals.totalMillis > 0L) {
                (duration.toDouble() / totals.totalMillis.toDouble()).coerceIn(0.0, 1.0)
            } else 0.0
            ReportProjectSlice(
                projectId = projectId.value,
                name = project?.let(context::projectName) ?: projectId.value,
                color = project?.let { Color(it.colorArgb) } ?: fallbackColor,
                durationMillis = duration,
                fraction = fraction.toFloat(),
                percentage = if (fraction > 0.0 && fraction < 0.001) {
                    context.getString(R.string.insights_report_small_share, percentFormatter.format(0.001))
                } else percentFormatter.format(fraction),
            )
        }
    }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var exportLaunchFailed by remember { mutableStateOf(false) }
    val exportBusy = state.controls.dataTransferBusy
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            Modifier.widthIn(max = 880.dp).fillMaxSize().testTag("reports_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("heading") {
                ScreenHeading(
                    title = stringResource(R.string.reports_heading),
                    subtitle = stringResource(R.string.ux_reports_subtitle),
                )
            }
            item("periods") {
                ReportPeriodTabs(
                    period = state.controls.reportPeriod,
                    onSelectPeriod = onSelectPeriod,
                    onCustom = { showCustom = true },
                )
            }
            item("total") {
                if (reportLoading) {
                    AppCard(modifier = Modifier.testTag("report_loading")) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.data_operation_in_progress))
                    }
                } else ReportTotalHero(totals.totalMillis, dateRange)
            }
            if (!reportLoading && slices.isNotEmpty() && totals.totalMillis > 0L) {
                item("distribution") { ReportDistribution(slices) }
            }
            if (!reportLoading && (slices.isEmpty() || totals.totalMillis <= 0L)) {
                item("empty") {
                    AppMessageCard(
                        title = stringResource(R.string.no_report_data),
                        message = stringResource(R.string.ux_reports_empty_hint),
                        glyph = AppGlyph.Reports,
                    )
                }
            } else if (!reportLoading) {
                item("breakdown-heading") {
                    SectionHeading(stringResource(R.string.project_breakdown), modifier = Modifier.padding(top = 4.dp))
                }
                items(slices, key = { it.projectId }) { slice ->
                    ReportProjectCard(slice)
                }
            }
            item("export") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            exportLaunchFailed = false
                            try {
                                val name = onBeginCsvExport?.invoke(bounds) ?: return@OutlinedButton
                                onLaunchCsvDocument(name)
                            } catch (_: Exception) {
                                onCancelCsvExport?.invoke()
                                exportLaunchFailed = true
                            }
                        },
                        enabled = !exportBusy && !reportLoading && onBeginCsvExport != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (exportBusy) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            AppIcon(AppGlyph.Download, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (exportBusy) stringResource(R.string.export_in_progress) else stringResource(R.string.export_csv),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (exportLaunchFailed) {
                        FormErrorText(stringResource(R.string.csv_export_error))
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

private data class ReportProjectSlice(
    val projectId: String,
    val name: String,
    val color: Color,
    val durationMillis: Long,
    val fraction: Float,
    val percentage: String,
)

@Composable
private fun ReportPeriodTabs(period: ReportPeriod, onSelectPeriod: (ReportPeriod) -> Unit, onCustom: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(6.dp)) {
            if (maxWidth.value / LocalDensity.current.fontScale < 240f) {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReportPeriodOption(stringResource(R.string.report_week), period == ReportPeriod.WEEK, { onSelectPeriod(ReportPeriod.WEEK) }, Modifier.fillMaxWidth())
                    ReportPeriodOption(stringResource(R.string.report_month), period == ReportPeriod.MONTH, { onSelectPeriod(ReportPeriod.MONTH) }, Modifier.fillMaxWidth())
                    ReportPeriodOption(stringResource(R.string.report_custom), period is ReportPeriod.Custom, onCustom, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ReportPeriodOption(stringResource(R.string.report_week), period == ReportPeriod.WEEK, { onSelectPeriod(ReportPeriod.WEEK) }, Modifier.weight(1f))
                    ReportPeriodOption(stringResource(R.string.report_month), period == ReportPeriod.MONTH, { onSelectPeriod(ReportPeriod.MONTH) }, Modifier.weight(1f))
                    ReportPeriodOption(stringResource(R.string.report_custom), period is ReportPeriod.Custom, onCustom, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReportPeriodOption(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ReportTotalHero(totalMillis: Long, dateRange: String) {
    val palette = LocalAppPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = palette.hero,
        contentColor = palette.onHero,
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AccentIcon(AppGlyph.Reports, tint = palette.heroAccent, containerColor = palette.heroAccentContainer)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.report_total), style = MaterialTheme.typography.labelLarge, color = palette.heroMuted)
                    Text(dateRange, style = MaterialTheme.typography.bodyMedium, color = palette.onHero)
                }
            }
            ReportDurationTotal(totalMillis)
            Text(
                stringResource(if (totalMillis in 1L..59_999L) R.string.ux_duration_seconds_units else R.string.insights_duration_units),
                style = MaterialTheme.typography.labelMedium,
                color = palette.heroMuted,
            )
        }
    }
}

@Composable
private fun ReportDurationTotal(totalMillis: Long) {
    val showSeconds = totalMillis in 1L..59_999L
    val text = formatDuration(totalMillis, includeSeconds = showSeconds)
    val description = stringResource(if (showSeconds) R.string.ux_duration_seconds_description else R.string.insights_duration_description, text)
    val style = MaterialTheme.typography.displayLarge.copy(
        fontSize = 52.sp,
        lineHeight = 58.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-1.8).sp,
        fontFeatureSettings = "tnum",
    )
    FittedDurationText(
        text,
        style = style,
        color = LocalAppPalette.current.onHero,
        description = description,
    )
}

@Composable
private fun ReportDistribution(slices: List<ReportProjectSlice>) {
    val description = stringResource(R.string.insights_distribution_description)
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        SectionHeading(stringResource(R.string.insights_distribution_heading))
        Canvas(
            Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription = description },
        ) {
            var offset = 0f
            slices.forEach { slice ->
                val width = size.width * slice.fraction
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - offset - width else offset
                drawRect(slice.color, topLeft = Offset(left, 0f), size = Size(width, size.height))
                offset += width
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReportProjectCard(slice: ReportProjectSlice) {
    val showSeconds = slice.durationMillis in 1L..59_999L
    val duration = formatDuration(slice.durationMillis, includeSeconds = showSeconds)
    val durationDescription = stringResource(if (showSeconds) R.string.ux_duration_seconds_description else R.string.insights_duration_description, duration)
    val shareDescription = stringResource(R.string.insights_report_share_value, slice.percentage)
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val nameWidth = if (LocalDensity.current.fontScale >= 1.5f || maxWidth < 280.dp) maxWidth else maxWidth * 0.7f
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.widthIn(max = nameWidth),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(11.dp).background(slice.color, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape),
                    )
                    Text(slice.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                }
                Text(
                    duration,
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.semantics { contentDescription = durationDescription },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                    .background(slice.color.copy(alpha = 0.12f)).clearAndSetSemantics {},
            ) {
                Box(Modifier.fillMaxWidth(slice.fraction).height(6.dp).background(slice.color))
            }
            Text(
                slice.percentage,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = shareDescription },
            )
        }
    }
}

@Composable
private fun CustomPeriodDialog(
    initial: ReportPeriod.Custom,
    onDismiss: () -> Unit,
    onApply: (ReportPeriod.Custom) -> Unit,
) {
    var from by rememberSaveable { mutableStateOf(initial.from.toString()) }
    var through by rememberSaveable { mutableStateOf(initial.through.toString()) }
    var error by rememberSaveable { mutableStateOf<DateInputError?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { AccentIcon(AppGlyph.Reports) },
        title = { Text(stringResource(R.string.report_custom)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    from,
                    { from = it; error = null },
                    label = { Text(stringResource(R.string.report_from)) },
                    singleLine = true,
                    isError = error != null,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    through,
                    { through = it; error = null },
                    label = { Text(stringResource(R.string.report_through)) },
                    singleLine = true,
                    isError = error != null,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { FormErrorText(stringResource(customPeriodErrorResource(it))) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val validation = validateCustomReportInput(from, through, ZoneId.systemDefault())
                    validation.value?.let(onApply) ?: run { error = validation.error }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
        },
    )
}

internal fun customPeriodErrorResource(error: DateInputError): Int = when (error) {
    DateInputError.REVERSED_RANGE -> R.string.ux_report_reversed_dates
    DateInputError.RANGE_TOO_LARGE -> R.string.ux_report_range_too_large
    DateInputError.OUT_OF_SUPPORTED_RANGE, DateInputError.OVERFLOW -> R.string.ux_date_out_of_range
    DateInputError.MALFORMED -> R.string.ux_report_invalid_dates
}
