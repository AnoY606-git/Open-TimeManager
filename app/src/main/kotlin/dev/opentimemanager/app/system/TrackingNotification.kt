package dev.opentimemanager.app.system

import android.app.*
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import dev.opentimemanager.app.R
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.ui.formatDuration
import dev.opentimemanager.core.model.TrackingState

class TrackingNotification(private val context: Context) {
    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ContextCompat.getString(context, R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); enableVibration(false); setShowBadge(false)
            }
        )
    }

    fun build(snapshot: SystemSnapshot, privacyMode: Boolean = true, nowWallMillis: Long = System.currentTimeMillis(), nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Notification {
        // Do not retain a localized wrapper: the same service/builder can outlive a language change.
        val strings = ContextCompat.getContextForLanguage(context)
        val state = snapshot.session.state
        val title = strings.getString(statusString(state))
        val daily = snapshot.dailyProgress.takeIf { state == TrackingState.RUNNING || state == TrackingState.PAUSED }
        val detail = if (state == TrackingState.NEEDS_REVIEW) strings.getString(R.string.notification_review_hint) else daily?.let {
            strings.getString(
                R.string.notification_daily_progress,
                formatDuration(it.trackedMillis, includeSeconds = false),
                formatDuration(it.goalMinutes * 60_000L, includeSeconds = false),
            )
        }
        val project = if (privacyMode) null else projectTitle(strings, snapshot).takeUnless { it == title }
        val body = listOfNotNull(detail, project).distinct().joinToString(" · ").takeIf { it.isNotBlank() }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tracking).setContentTitle(title)
            .setContentText(body)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_STOPWATCH)
            .setPublicVersion(Notification.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_tracking).setContentTitle(strings.getString(statusString(state))).build())
        TrackingActions.openApp(context)?.let(builder::setContentIntent)
        val elapsed = elapsedMillis(snapshot, nowElapsedMillis)
        if (state == TrackingState.RUNNING) {
            builder.setWhen(nowWallMillis - elapsed).setShowWhen(true).setUsesChronometer(true)
        } else {
            // A non-running notification's `when` is a timestamp, not a frozen chronometer.
            // Show only the confirmed duration; it must not age while paused or awaiting review.
            builder.setShowWhen(false).setUsesChronometer(false)
                .setSubText(DateUtils.formatElapsedTime(elapsed / 1000))
        }
        if (daily != null) {
            // Полоса и её кэш используют те же целые минуты, что и видимая подпись.
            val minutes = (daily.trackedMillis / 60_000L).coerceAtMost(daily.goalMinutes.toLong())
            val progress = (minutes * DAILY_PROGRESS_MAX / daily.goalMinutes).toInt()
            if (Build.VERSION.SDK_INT >= 36) {
                applyProgressStyle(builder, progress)
            } else {
                builder.setProgress(DAILY_PROGRESS_MAX, progress, false)
                if (body != null) builder.setStyle(Notification.BigTextStyle().bigText(body))
            }
        } else if (body != null) {
            builder.setStyle(Notification.BigTextStyle().bigText(body))
        }
        val actions = mutableListOf<Notification.Action>()
        TrackingActions.primary(snapshot)?.let {
            actions += Notification.Action.Builder(null, strings.getString(primaryString(state)), TrackingActions.pendingIntent(context, it)).build()
        }
        if (state == TrackingState.RUNNING || state == TrackingState.PAUSED) {
            actions += Notification.Action.Builder(null, strings.getString(R.string.tracking_stop), TrackingActions.pendingIntent(context, TrackingActions.stop(snapshot))).build()
        }
        actions.forEach(builder::addAction)
        val hyperIsland = hyperIslandProtocolAvailable(context)
        if (Build.VERSION.SDK_INT >= 36 || hyperIsland) {
            try {
                val policy = LiveUpdatePreferences(context).notificationPolicy(snapshot)
                val observerReady = HyperIslandScreenObserver.update(context, snapshot, privacyMode, hyperIsland && policy?.requestHyperIsland == true)
                policy?.let {
                    builder.setDeleteIntent(LiveUpdateDismissReceiver.pendingIntent(context, policy.session))
                    if (policy.requestPromotion) requestPromotedOngoing(builder)
                    if (policy.requestHyperIsland && observerReady && hyperIslandContentVisible(context)) {
                        hyperIslandExtras(context, state, title, detail, elapsed, snapshot.entryStartedAtUtcMillis, nowWallMillis, actions)?.let(builder::addExtras)
                    }
                }
            } catch (_: Exception) {
                HyperIslandScreenObserver.close()
                // Необязательное оформление не должно ломать обычные кнопки таймера.
            }
        } else HyperIslandScreenObserver.close()
        return builder.build()
    }

    @RequiresApi(36)
    private fun applyProgressStyle(builder: Notification.Builder, progress: Int) {
        builder.setColor(Color.rgb(0, 153, 92))
        builder.setStyle(
            Notification.ProgressStyle()
                .setProgressSegments(List(4) { Notification.ProgressStyle.Segment(DAILY_PROGRESS_MAX / 4).setColor(Color.rgb(0, 153, 92)) })
                .setStyledByProgress(true)
                .setProgress(progress),
        )
    }

    companion object {
        const val CHANNEL_ID = "work_time_tracking"
        const val ID = 1
        private const val DAILY_PROGRESS_MAX = 1_000
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
            val strings = ContextCompat.getContextForLanguage(context)
            val project = snapshot.activeProject
                ?: return strings.getString(statusString(snapshot.session.state))
            return if (project.id == ProjectRepository.DEFAULT_PROJECT_ID.value) strings.getString(R.string.default_project_name) else project.name
        }
    }
}
