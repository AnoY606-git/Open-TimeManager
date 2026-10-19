package dev.opentimemanager.app.ui.screens

import android.appwidget.AppWidgetManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.opentimemanager.app.R
import dev.opentimemanager.app.ui.DeviceFamily
import dev.opentimemanager.app.ui.NotificationControlStatus
import dev.opentimemanager.app.ui.components.AppCard
import dev.opentimemanager.app.ui.components.ScreenHeading
import dev.opentimemanager.app.ui.deviceFamily
import dev.opentimemanager.app.ui.openAppSystemSettings
import dev.opentimemanager.app.ui.readDeviceBackgroundStatus

/** Только подсказки и явные действия пользователя; сама композиция ничего не запрашивает. */
@Composable
fun DeviceSetupScreen(
    notificationStatus: NotificationControlStatus,
    onManageNotifications: () -> Unit,
    onSkip: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var step by rememberSaveable { mutableIntStateOf(0) }
    var background by remember(context) { mutableStateOf(readDeviceBackgroundStatus(context)) }
    fun widgetPinSupport(): Boolean? = runCatching {
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
    }.getOrNull()
    var widgetPinSupported by remember(context) { mutableStateOf(widgetPinSupport()) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                background = readDeviceBackgroundStatus(context)
                widgetPinSupported = widgetPinSupport()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Box(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing).testTag("device_setup"),
        contentAlignment = Alignment.TopCenter,
    ) {
        key(step) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp).testTag("device_setup_scroll"),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ScreenHeading(stringResource(R.string.device_setup_title))
                Text(
                    stringResource(R.string.device_setup_step, step + 1, 3),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_setup_skip"),
                ) { Text(stringResource(R.string.device_setup_skip)) }
                when (step) {
                    0 -> {
                        Text(stringResource(R.string.device_setup_intro))
                        DeviceSetupIdentity()
                    }
                    1 -> {
                        AppCard(Modifier.testTag("device_setup_notifications")) {
                            Text(stringResource(R.string.device_setup_notifications_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(when (notificationStatus) {
                                NotificationControlStatus.AVAILABLE -> R.string.device_setup_notifications_ready
                                NotificationControlStatus.PERMISSION_REQUESTABLE -> R.string.device_setup_notifications_permission
                                NotificationControlStatus.PERMISSION_FIXED -> R.string.device_setup_notifications_fixed
                                NotificationControlStatus.APP_BLOCKED -> R.string.device_setup_notifications_app_blocked
                                NotificationControlStatus.CHANNEL_BLOCKED -> R.string.device_setup_notifications_channel_blocked
                            }))
                            OutlinedButton(
                                onClick = onManageNotifications,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_setup_manage_notifications"),
                            ) {
                                Text(stringResource(if (notificationStatus == NotificationControlStatus.PERMISSION_REQUESTABLE) {
                                    R.string.device_setup_allow_notifications
                                } else R.string.manage_notifications))
                            }
                        }
                        AppCard {
                            Text(stringResource(R.string.platform_compatibility), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(when (background.powerSave) {
                                true -> R.string.platform_battery_saver
                                false -> R.string.device_setup_battery_saver_off
                                null -> R.string.device_setup_battery_unknown
                            }))
                            Text(stringResource(when (background.backgroundRestricted) {
                                true -> R.string.platform_background_restricted
                                false -> R.string.device_setup_background_clear
                                null -> R.string.device_setup_background_unknown
                            }))
                            Text(stringResource(R.string.device_setup_background_hint), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(
                                onClick = { settingsUnavailable = !openAppSystemSettings(context) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.platform_app_settings)) }
                            if (settingsUnavailable) Text(stringResource(R.string.platform_settings_unavailable))
                        }
                    }
                    2 -> {
                        AppCard(Modifier.testTag("device_setup_surfaces")) {
                            Text(stringResource(R.string.device_setup_surfaces_title), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.device_setup_surfaces_intro))
                            Text(stringResource(if (Build.VERSION.SDK_INT >= 33) R.string.device_setup_tile_request else R.string.device_setup_tile_manual))
                            Text(stringResource(when (widgetPinSupported) {
                                true -> R.string.device_setup_widget_available
                                false -> R.string.device_setup_widget_unavailable
                                null -> R.string.device_setup_widget_unknown
                            }))
                        }
                        PlatformIntegrationCard()
                        Text(stringResource(R.string.device_setup_return_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (step > 0) OutlinedButton(
                    onClick = { step-- },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("device_setup_previous"),
                ) { Text(stringResource(R.string.device_setup_previous)) }
                Button(
                    onClick = { if (step < 2) step++ else onComplete() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag(if (step < 2) "device_setup_next" else "device_setup_complete"),
                ) { Text(stringResource(if (step < 2) R.string.device_setup_next else R.string.device_setup_complete)) }
            }
        }
    }
}

@Composable
private fun DeviceSetupIdentity() {
    val family = remember { deviceFamily(Build.MANUFACTURER, Build.BRAND) }
    AppCard(Modifier.testTag("device_setup_device")) {
        Text(stringResource(R.string.device_setup_device_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.device_setup_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        val model = Build.MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: stringResource(R.string.device_setup_unknown)
        Text(stringResource(R.string.device_setup_model, model))
        Text(stringResource(when (family) {
            DeviceFamily.XIAOMI -> R.string.device_setup_family_xiaomi
            DeviceFamily.SAMSUNG -> R.string.device_setup_family_samsung
            DeviceFamily.OPPO -> R.string.device_setup_family_oppo
            DeviceFamily.OTHER -> R.string.device_setup_family_other
        }))
        Text(stringResource(R.string.device_setup_rom_unknown), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(family.hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
