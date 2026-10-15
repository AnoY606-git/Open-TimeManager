package dev.opentimemanager.app.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.tracking.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class TrackingCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (TrackingActions.decode(intent) == null) return
        val pending = goAsync()
        val app = context.applicationContext as OpenTimeManagerApplication
        app.applicationScope.launch {
            try {
                containSystemSurfaceFailure {
                    dispatch(intent, app.container.trackingCoordinator, SystemSurfaceUpdater(context.applicationContext))
                }
            }
            finally { pending.finish() }
        }
    }

    companion object {
        suspend fun dispatch(intent: Intent, coordinator: TrackingCoordinator, updater: SystemSurfaceUpdater): TrackingResult? {
            val command = TrackingActions.decode(intent) ?: return null
            val result = coordinator.execute(command)
            // Даже при stale/duplicate интерфейс получает свежий факт, а не оптимистический переход.
            val snapshot = coordinator.observeSnapshot().first()
            updater.refresh(snapshot)
            if (result is TrackingResult.Applied) updater.ensureForegroundControls(snapshot)
            return result
        }
    }
}
