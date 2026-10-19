package dev.opentimemanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.R
import dev.opentimemanager.app.system.LiveUpdatePreferences
import dev.opentimemanager.app.system.SystemSurfaceUpdater
import dev.opentimemanager.app.system.liveUpdatePlatformStatus
import dev.opentimemanager.app.system.openLiveUpdateSettings
import dev.opentimemanager.app.ui.components.AppCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class LiveUpdatePreferenceChange { SAVED, SAVE_FAILED, REFRESH_FAILED }

/** A projection failure must not pretend that a successfully committed choice was lost. */
internal suspend fun applyLiveUpdatePreferenceChange(
    preferences: LiveUpdatePreferences,
    enabled: Boolean,
    refresh: suspend () -> Unit,
): LiveUpdatePreferenceChange {
    val saved = try {
        preferences.setEnabled(enabled)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!saved) return LiveUpdatePreferenceChange.SAVE_FAILED
    return try {
        refresh()
        LiveUpdatePreferenceChange.SAVED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LiveUpdatePreferenceChange.REFRESH_FAILED
    }
}

/** Standalone card. No observers outside its visible lifecycle and no automatic opt-in. */
@Composable
fun LiveUpdateControlsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val application = context.applicationContext
    val app = application as? OpenTimeManagerApplication
    val preferences = remember(application) { LiveUpdatePreferences(application) }
    var choice by remember(preferences) { mutableStateOf(preferences.current()) }
    var platform by remember(application) { mutableStateOf(liveUpdatePlatformStatus(application)) }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, preferences, application) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !saving) {
                choice = preferences.current()
                platform = liveUpdatePlatformStatus(application)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AppCard(modifier.testTag("live_updates_card")) {
        Text(stringResource(R.string.live_updates_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.live_updates_description), style = MaterialTheme.typography.bodyMedium)
        if (!platform.supported && !platform.hyperIslandProtocolAvailable) {
            Text(stringResource(R.string.live_updates_requires_android16), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                    value = choice.enabled,
                    enabled = !saving,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        if (!saving) {
                            saving = true
                            feedback = null
                            // Explicit user work survives navigation away from this card. It does
                            // not start an FGS, poll, or install a persistent preference observer.
                            (app?.applicationScope ?: scope).launch {
                                try {
                                    feedback = when (applyLiveUpdatePreferenceChange(preferences, enabled) {
                                        val owner = requireNotNull(app)
                                        val snapshot = owner.container.trackingCoordinator.observeSystemSnapshot().first()
                                        SystemSurfaceUpdater(owner).refresh(snapshot)
                                    }) {
                                        LiveUpdatePreferenceChange.SAVED -> R.string.live_updates_saved
                                        LiveUpdatePreferenceChange.SAVE_FAILED -> R.string.live_updates_save_failed
                                        LiveUpdatePreferenceChange.REFRESH_FAILED -> R.string.live_updates_refresh_failed
                                    }
                                } finally {
                                    choice = preferences.current()
                                    platform = liveUpdatePlatformStatus(application)
                                    saving = false
                                }
                            }
                        }
                    },
                ).testTag("live_updates_toggle"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.live_updates_enable), modifier = Modifier.weight(1f))
                Switch(checked = choice.enabled, onCheckedChange = null, enabled = !saving)
            }
            if (saving) Text(stringResource(R.string.live_updates_saving))
            if (platform.hyperIslandProtocolAvailable) Text(
                stringResource(R.string.live_updates_hyperisland_hint), style = MaterialTheme.typography.bodySmall,
            )
            if (platform.supported && !platform.requestApiAvailable) Text(
                stringResource(R.string.live_updates_request_api_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (platform.supported) Text(stringResource(when (platform.canPost) {
                true -> R.string.live_updates_android_allows
                false -> R.string.live_updates_android_blocks
                null -> R.string.live_updates_status_unknown
            }))
            platform.hasPromotableCharacteristics?.let { eligible ->
                Text(stringResource(if (eligible) R.string.live_updates_layout_eligible else R.string.live_updates_layout_ineligible))
            }
            OutlinedButton(
                onClick = { if (!openLiveUpdateSettings(context)) feedback = R.string.live_updates_settings_unavailable },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("live_updates_settings"),
            ) { Text(stringResource(if (platform.supported) R.string.live_updates_manage else R.string.platform_app_settings)) }
        }
        Text(stringResource(R.string.live_updates_dismissal_hint), style = MaterialTheme.typography.bodySmall)
        feedback?.let { message ->
            Text(stringResource(message), modifier = Modifier.testTag("live_updates_feedback").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
