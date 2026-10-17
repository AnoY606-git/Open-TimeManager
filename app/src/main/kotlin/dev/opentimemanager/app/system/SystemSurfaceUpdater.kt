package dev.opentimemanager.app.system

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.app.widget.TrackingWidgetProvider
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class NotificationAvailability { AVAILABLE, BLOCKED }

/** AppCompat's API 31/32 auto-storage is asynchronous; Core's non-Activity API reads that store. */
internal suspend fun awaitStoredApplicationLocales(context: Context, expected: LocaleListCompat) {
    withContext(Dispatchers.IO) {
        withTimeout(5_000) {
            while (LocaleManagerCompat.getApplicationLocales(context) != expected) delay(25)
        }
    }
}

/** Обновляет проекции только по фактам из Room; не владеет состоянием таймера. */
class SystemSurfaceUpdater(private val context: Context) {
    fun refresh(snapshot: SystemSnapshot): NotificationAvailability {
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
    fun ensureForegroundControls(snapshot: SystemSnapshot): Boolean {
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
