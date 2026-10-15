package dev.opentimemanager.app.system

import android.app.*
import android.content.Context
import android.os.SystemClock
import dev.opentimemanager.app.R
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.core.model.TrackingState

class TrackingNotification(private val context: Context) {
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
    }

    fun build(snapshot: SystemSnapshot, privacyMode: Boolean = true, nowWallMillis: Long = System.currentTimeMillis(), nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Notification {
        val state = snapshot.session.state
        val title = if (privacyMode) context.getString(statusString(state)) else projectTitle(context, snapshot)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking).setContentTitle(title)
            .setContentText(context.getString(statusString(state)))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STOPWATCH)
            .setPublicVersion(Notification.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_tracking).setContentTitle(context.getString(statusString(state))).build())
        TrackingActions.openApp(context)?.let(builder::setContentIntent)
        val elapsed = elapsedMillis(snapshot, nowElapsedMillis)
        builder.setWhen(nowWallMillis - elapsed).setShowWhen(true).setUsesChronometer(state == TrackingState.RUNNING)
        TrackingActions.primary(snapshot)?.let {
            builder.addAction(Notification.Action.Builder(null, context.getString(primaryString(state)), TrackingActions.pendingIntent(context, it)).build())
        }
        if (state == TrackingState.RUNNING || state == TrackingState.PAUSED) {
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.tracking_stop), TrackingActions.pendingIntent(context, TrackingActions.stop(snapshot))).build())
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "work_time_tracking"
        const val ID = 1
        fun statusString(state: TrackingState): Int = when (state) {
            TrackingState.IDLE -> R.string.tracking_ready
            TrackingState.RUNNING -> R.string.tracking_running
            TrackingState.PAUSED -> R.string.tracking_paused
            TrackingState.NEEDS_REVIEW -> R.string.tracking_review
        }
        fun primaryString(state: TrackingState): Int = when (state) {
            TrackingState.IDLE -> R.string.tracking_start
            TrackingState.RUNNING -> R.string.tracking_pause
            TrackingState.PAUSED -> R.string.tracking_resume
            TrackingState.NEEDS_REVIEW -> R.string.tracking_open
        }
        fun elapsedMillis(snapshot: SystemSnapshot, nowElapsedMillis: Long): Long {
            return elapsedMillis(snapshot.session, nowElapsedMillis)
        }
        fun elapsedMillis(session: dev.opentimemanager.core.model.TrackingSession, nowElapsedMillis: Long): Long {
            val closed = session.confirmedDurationMillis
            val open = if (session.state == TrackingState.RUNNING) session.openSegment?.let { (nowElapsedMillis - it.startedAt.elapsedRealtimeMillis).coerceAtLeast(0) } ?: 0 else 0
            return if (Long.MAX_VALUE - closed < open) Long.MAX_VALUE else closed + open
        }
        fun projectTitle(context: Context, snapshot: SystemSnapshot): String {
            val project = snapshot.activeProject
                ?: return context.getString(statusString(snapshot.session.state))
            return if (project.id == ProjectRepository.DEFAULT_PROJECT_ID.value) context.getString(R.string.default_project_name) else project.name
        }
    }
}
