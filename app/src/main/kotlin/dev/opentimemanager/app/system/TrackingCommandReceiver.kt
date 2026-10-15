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
        val work = app.applicationScope.launch {
            containSystemSurfaceFailure {
                dispatch(intent, app.container.trackingCoordinator, SystemSurfaceUpdater(context.applicationContext))
            }
        }
        PendingResultLifetime(app.applicationScope, PENDING_RESULT_BUDGET_MILLIS, pending::finish).bind(work)
    }

    companion object {
        internal const val PENDING_RESULT_BUDGET_MILLIS = 8_000L
        suspend fun dispatch(intent: Intent, coordinator: TrackingCoordinator, updater: SystemSurfaceUpdater): TrackingResult? {
            val command = TrackingActions.decode(intent) ?: return null
            val result = coordinator.execute(command)
            val snapshot = (result as? TrackingResult.Applied)?.snapshot
                ?: coordinator.observeSystemSnapshot().first()
            updater.refresh(snapshot)
            if (result is TrackingResult.Applied) updater.ensureForegroundControls(snapshot)
            return result
        }
    }
}
