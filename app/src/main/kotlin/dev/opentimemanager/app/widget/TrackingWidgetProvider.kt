package dev.opentimemanager.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.R
import dev.opentimemanager.app.system.*
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class TrackingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = readAndUpdate(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) = readAndUpdate(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) readAndUpdate(context)
    }
    private fun readAndUpdate(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext as OpenTimeManagerApplication
        val work = app.applicationScope.launch {
            containSystemSurfaceFailure {
                update(context, app.container.trackingCoordinator.observeSystemSnapshot().first())
            }
        }
        PendingResultLifetime(app.applicationScope, 8_000L, pending::finish).bind(work)
    }
    companion object {
        fun update(context: Context, snapshot: SystemSnapshot) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TrackingWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, snapshot, SystemSurfaceUpdater.privacyMode(context)))
        }
        fun views(context: Context, snapshot: SystemSnapshot, privacyMode: Boolean = true, nowElapsedMillis: Long = SystemClock.elapsedRealtime()): RemoteViews {
            val strings = ContextCompat.getContextForLanguage(context)
            val state = snapshot.session.state
            val title = if (privacyMode) strings.getString(TrackingNotification.statusString(state)) else TrackingNotification.projectTitle(strings, snapshot)
            return RemoteViews(context.packageName, R.layout.tracking_widget).apply {
                setTextViewText(R.id.widget_status, title)
                setTextViewText(R.id.widget_primary, strings.getString(TrackingNotification.primaryString(state)))
                setTextViewText(R.id.widget_stop, strings.getString(R.string.tracking_stop))
                setChronometer(R.id.widget_timer, nowElapsedMillis - TrackingNotification.elapsedMillis(snapshot, nowElapsedMillis), null, state == TrackingState.RUNNING)
                // Host может применить RemoteViews позднее: paused показывает только подтверждённое время.
                if (state != TrackingState.RUNNING) setTextViewText(R.id.widget_timer, android.text.format.DateUtils.formatElapsedTime(TrackingNotification.elapsedMillis(snapshot, nowElapsedMillis) / 1000))
                val primary = TrackingActions.primary(snapshot)?.let { TrackingActions.pendingIntent(context, it) } ?: TrackingActions.openApp(context)
                setOnClickPendingIntent(R.id.widget_primary, primary)
                setBoolean(R.id.widget_primary, "setEnabled", primary != null)
                val canStop = state == TrackingState.RUNNING || state == TrackingState.PAUSED
                setViewVisibility(R.id.widget_stop, if (canStop) View.VISIBLE else View.GONE)
                setOnClickPendingIntent(R.id.widget_stop, if (canStop) TrackingActions.pendingIntent(context, TrackingActions.stop(snapshot)) else null)
            }
        }
    }
}
