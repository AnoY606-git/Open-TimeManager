package dev.opentimemanager.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

@Composable
fun ScreenHeading(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        val compactHeading = maxWidth.value / LocalDensity.current.fontScale < 300f
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = if (compactHeading) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun SectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** The same calm, readable empty-state hierarchy across lists and reports. */
@Composable
fun AppMessageCard(title: String, message: String, glyph: AppGlyph, modifier: Modifier = Modifier) {
    AppCard(modifier, contentPadding = PaddingValues(20.dp)) {
        AccentIcon(glyph)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FormErrorText(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Fit only a numeric display; labels and actions always keep the system text scale. */
@Composable
fun FittedDurationText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    description: String? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availableWidth = constraints.maxWidth.coerceAtLeast(1)
        // Measuring, rather than dividing by fontScale, also handles Android's nonlinear scaling.
        val fittedStyle = remember(text, style, textMeasurer, availableWidth) {
            fittedDurationStyle(text, style, textMeasurer, availableWidth)
        }
        Text(
            text, style = fittedStyle, color = color, maxLines = 1, softWrap = false,
            modifier = Modifier.fillMaxWidth().semantics { if (description != null) contentDescription = description },
        )
    }
}

internal fun fittedDurationStyle(text: String, style: TextStyle, textMeasurer: TextMeasurer, availableWidthPx: Int): TextStyle {
    var candidate = style
    val availableWidth = availableWidthPx.coerceAtLeast(1)
    repeat(10) {
        val width = textMeasurer.measure(text, candidate, softWrap = false, maxLines = 1).size.width
        if (width <= availableWidth) return candidate
        val scale = (availableWidth.toFloat() / width).coerceIn(0.1f, 0.98f)
        candidate = candidate.copy(
            fontSize = candidate.fontSize * scale,
            lineHeight = candidate.lineHeight * scale,
            letterSpacing = candidate.letterSpacing * scale,
        )
    }
    return candidate
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun AccentIcon(
    glyph: AppGlyph,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Box(
        modifier.size(40.dp).background(containerColor, RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(glyph, modifier = Modifier.size(21.dp), tint = tint)
    }
}
