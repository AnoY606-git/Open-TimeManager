package dev.opentimemanager.app.system

import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.os.Build
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
                } else if (snapshot.session.state == TrackingState.NEEDS_REVIEW) {
                    if (Build.VERSION.SDK_INT >= 34) {
                        TrackingActions.openApp(this@TrackingTileService)?.let(::startActivityAndCollapse)
                    } else {
                        openReviewOnLegacyAndroid(reviewIntent(this@TrackingTileService, snapshot))
                    }
                }
            }
        }
    }
    private fun coordinator() = (application as OpenTimeManagerApplication).container.trackingCoordinator
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openReviewOnLegacyAndroid(intent: Intent) = startActivityAndCollapse(intent)
    data class Presentation(val label: String, val state: Int)
    companion object {
        fun presentation(context: Context, snapshot: AppSnapshot) = Presentation(context.getString(TrackingNotification.statusString(snapshot.session.state)), if (snapshot.session.state == TrackingState.RUNNING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
        fun reviewIntent(context: Context, snapshot: AppSnapshot): Intent {
            require(snapshot.session.state == TrackingState.NEEDS_REVIEW)
            return Intent().setClassName(context, "dev.opentimemanager.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
