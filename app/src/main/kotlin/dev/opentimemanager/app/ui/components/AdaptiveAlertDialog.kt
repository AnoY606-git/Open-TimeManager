package dev.opentimemanager.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Заголовок и действия прокручиваются вместе с формой: они не могут вытеснить
 * поля до нулевой высоты. Дерево формы не меняется при появлении IME, сохраняя фокус.
 * Содержимое text не должно иметь собственного вертикального scroll/LazyColumn.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdaptiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    properties: DialogProperties = DialogProperties(),
    scrollState: ScrollState = rememberScrollState(),
) {
    BasicAlertDialog(onDismissRequest, modifier, properties) {
        Surface(shape = shape, color = containerColor, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scrollState).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val header: @Composable () -> Unit = {
                    if (icon != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { icon() }
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        Box(Modifier.fillMaxWidth().semantics { heading() }) { title() }
                    }
                }
                val body: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                }
                val actions: @Composable () -> Unit = {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
                header()
                body()
                actions()
            }
        }
    }
}
