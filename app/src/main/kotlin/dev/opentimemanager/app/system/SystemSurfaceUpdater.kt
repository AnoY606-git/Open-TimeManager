package dev.opentimemanager.app.system

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import dev.opentimemanager.app.tracking.AppSnapshot
import dev.opentimemanager.app.widget.TrackingWidgetProvider
import dev.opentimemanager.core.model.TrackingState

enum class NotificationAvailability { AVAILABLE, BLOCKED }

/** Обновляет проекции только по фактам из Room; не владеет состоянием таймера. */
class SystemSurfaceUpdater(private val context: Context) {
    fun refresh(snapshot: AppSnapshot): NotificationAvailability {
        val notification = TrackingNotification(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val available = manager.areNotificationsEnabled() && manager.getNotificationChannel(TrackingNotification.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        TrackingWidgetProvider.update(context, snapshot)
        TileService.requestListeningState(context, ComponentName(context, TrackingTileService::class.java))
        if (snapshot.session.state == TrackingState.IDLE) {
            context.stopService(Intent(context, TrackingService::class.java))
            manager.cancel(TrackingNotification.ID)
        } else if (available) {
            manager.notify(TrackingNotification.ID, notification.build(snapshot, privacyMode(context)))
        }
        return if (available) NotificationAvailability.AVAILABLE else NotificationAvailability.BLOCKED
    }

    /** Вызывается только после команды пользователя; запрет FGS не откатывает сохранённую запись. */
    fun ensureForegroundControls(snapshot: AppSnapshot): Boolean {
        if (snapshot.session.state == TrackingState.IDLE) return true
        return try {
            context.startForegroundService(Intent(context, TrackingService::class.java))
            true
        } catch (_: android.app.ForegroundServiceStartNotAllowedException) { false }
        catch (_: SecurityException) { false }
    }

    companion object {
        fun privacyMode(context: Context): Boolean = context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE).getBoolean("privacy", true)
    }
}
