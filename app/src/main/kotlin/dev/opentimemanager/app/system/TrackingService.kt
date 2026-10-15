package dev.opentimemanager.app.system

import android.app.Service
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.R
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.*

/** FGS поддерживает доступ к управлению; монотонная опора и сегменты принадлежат Room. */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        val notification = TrackingNotification(this)
        // Срок startForeground не зависит от скорости Room; до чтения нет выдуманного timer state.
        val initial = Notification.Builder(this, TrackingNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking).setContentTitle(getString(R.string.tracking_channel))
            .setOngoing(true).setOnlyAlertOnce(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(TrackingNotification.ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(TrackingNotification.ID, initial)
        val coordinator = (application as OpenTimeManagerApplication).container.trackingCoordinator
        scope.launch {
            containSystemSurfaceFailure(
                onFailure = {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                },
            ) {
                coordinator.observeSystemSnapshot().collect { snapshot ->
                    if (snapshot.session.state == TrackingState.IDLE) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(android.app.NotificationManager::class.java).notify(TrackingNotification.ID, notification.build(snapshot, SystemSurfaceUpdater.privacyMode(this@TrackingService)))
                    }
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
