package dev.opentimemanager.app.system

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class TrackingTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            coordinator().observeSnapshot().collect { snapshot ->
                val projection = presentation(this@TrackingTileService, snapshot)
                qsTile?.apply { label = projection.label; state = projection.state; updateTile() }
            }
        }
    }
    override fun onStopListening() { listening?.cancel(); super.onStopListening() }
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            scope.launch {
                val snapshot = coordinator().observeSnapshot().first()
                val command = TrackingActions.primary(snapshot)
                if (command != null) {
                    TrackingActions.pendingIntent(this@TrackingTileService, command).send()
                }
            }
        }
    }
    private fun coordinator() = (application as OpenTimeManagerApplication).container.trackingCoordinator
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    data class Presentation(val label: String, val state: Int)
    companion object {
        fun presentation(context: Context, snapshot: AppSnapshot) = Presentation(context.getString(TrackingNotification.statusString(snapshot.session.state)), if (snapshot.session.state == TrackingState.RUNNING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
    }
}
