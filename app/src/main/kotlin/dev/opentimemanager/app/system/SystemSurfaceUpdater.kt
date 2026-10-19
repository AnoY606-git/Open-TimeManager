package dev.opentimemanager.app.system

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.service.quicksettings.TileService
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.app.tracking.TrackingCommand
import dev.opentimemanager.app.widget.TrackingWidgetProvider
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

enum class NotificationAvailability { AVAILABLE, BLOCKED }

/** Value identity of the rendered controls, not of a newly restored TrackingSession instance. */
internal data class SystemSurfaceKey(
    val state: TrackingState,
    val confirmedDurationMillis: Long,
    val runningAnchor: OpenSegment?,
    val primary: TrackingCommand?,
    val stop: TrackingCommand.Stop?,
    val title: String,
    val dailyDate: LocalDate?,
    val dailyGoalMinutes: Int?,
    val dailyTrackedMinutes: Long?,
    val configuration: Configuration,
    val liveUpdates: LiveUpdatePreferenceState,
)

internal fun systemSurfaceKey(context: Context, snapshot: SystemSnapshot, privacyMode: Boolean): SystemSurfaceKey {
    val strings = ContextCompat.getContextForLanguage(context)
    val state = snapshot.session.state
    val daily = snapshot.dailyProgress.takeIf { state == TrackingState.RUNNING || state == TrackingState.PAUSED }
    return SystemSurfaceKey(
        state = state,
        confirmedDurationMillis = snapshot.session.confirmedDurationMillis,
        runningAnchor = snapshot.session.openSegment.takeIf { state == TrackingState.RUNNING },
        primary = TrackingActions.primary(snapshot),
        stop = if (state == TrackingState.RUNNING || state == TrackingState.PAUSED) TrackingActions.stop(snapshot) else null,
        title = if (privacyMode) strings.getString(TrackingNotification.statusString(state)) else TrackingNotification.projectTitle(strings, snapshot),
        dailyDate = daily?.date,
        dailyGoalMinutes = daily?.goalMinutes,
        dailyTrackedMinutes = daily?.trackedMillis?.div(60_000L),
        configuration = Configuration(strings.resources.configuration),
        liveUpdates = LiveUpdatePreferences(context).current(),
    )
}

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
    private var lastProjection: SystemSurfaceKey? = null
    private var lastAvailability: NotificationAvailability? = null
    private var lastTile: TrackingTileService.Presentation? = null

    fun refresh(snapshot: SystemSnapshot): NotificationAvailability {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(TrackingNotification.CHANNEL_ID)
        val available = manager.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE
        val availability = if (available) NotificationAvailability.AVAILABLE else NotificationAvailability.BLOCKED
        val privacy = privacyMode(context)
        val projection = systemSurfaceKey(context, snapshot, privacy)
        // Room also invalidates this flow for unrelated history/note edits. The host chronometers
        // already advance themselves; re-posting identical controls is not a timer tick.
        if (channel != null && projection == lastProjection && availability == lastAvailability) return availability

        val notification = TrackingNotification(context)
        TrackingWidgetProvider.update(context, snapshot)
        val tile = TrackingTileService.presentation(context, snapshot)
        if (tile != lastTile) {
            TileService.requestListeningState(context, ComponentName(context, TrackingTileService::class.java))
            lastTile = tile
        }
        if (snapshot.session.state == TrackingState.IDLE) {
            HyperIslandScreenObserver.update(context, snapshot, privacy, enabled = false)
            context.stopService(Intent(context, TrackingService::class.java))
            manager.cancel(TrackingNotification.ID)
        } else if (available) {
            manager.notify(TrackingNotification.ID, notification.build(snapshot, privacy))
        }
        // A failed publish must remain retryable; this cache never owns durable tracking state.
        lastProjection = projection
        lastAvailability = availability
        return availability
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
