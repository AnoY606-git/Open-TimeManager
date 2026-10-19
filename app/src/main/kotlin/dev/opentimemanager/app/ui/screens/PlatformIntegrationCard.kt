package dev.opentimemanager.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.opentimemanager.app.R
import dev.opentimemanager.app.ui.deviceFamily
import dev.opentimemanager.app.ui.openAppSystemSettings
import dev.opentimemanager.app.ui.readDeviceBackgroundStatus
import dev.opentimemanager.app.ui.requestTrackingTile
import dev.opentimemanager.app.ui.requestTrackingWidget
import dev.opentimemanager.app.ui.components.AppCard

@Composable
fun PlatformIntegrationCard() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf(readDeviceBackgroundStatus(context)) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    var showCompatibility by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = readDeviceBackgroundStatus(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    AppCard {
        Text(stringResource(R.string.platform_controls_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.platform_controls_summary), style = MaterialTheme.typography.bodyMedium)
        if (status.powerSave) Text(stringResource(R.string.platform_battery_saver), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.backgroundRestricted) Text(stringResource(R.string.platform_background_restricted), color = MaterialTheme.colorScheme.error)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { requestTrackingTile(context) { feedback = it } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_quick_tile"),
            ) { Text(stringResource(R.string.platform_add_tile)) }
            OutlinedButton(
                onClick = { feedback = requestTrackingWidget(context) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pin_tracking_widget"),
            ) { Text(stringResource(R.string.platform_pin_widget)) }
        }
        feedback?.let { Text(stringResource(it), modifier = Modifier.testTag("platform_feedback")) }
        OutlinedButton(
            onClick = { if (!openAppSystemSettings(context)) feedback = R.string.platform_settings_unavailable },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.platform_app_settings)) }
        TextButton(
            onClick = { showCompatibility = !showCompatibility },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("platform_details_toggle"),
        ) { Text(stringResource(if (showCompatibility) R.string.platform_hide_details else R.string.platform_compatibility)) }
        if (showCompatibility) Column(
            Modifier.testTag("platform_compatibility_details"), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.platform_features_hint), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(deviceFamily(Build.MANUFACTURER, Build.BRAND).hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    LiveUpdateControlsCard()
    }
}
