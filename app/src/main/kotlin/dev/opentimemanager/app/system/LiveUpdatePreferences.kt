package dev.opentimemanager.app.system

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only slot/entry identities, never project names, notes, or portable settings. */
data class LiveUpdateSessionKey internal constructor(val generation: String, val entryId: String) {
    companion object {
        fun from(snapshot: SystemSnapshot): LiveUpdateSessionKey? =
            fromValues(snapshot.generation, snapshot.session.entryId?.value)

        internal fun fromValues(generation: String?, entryId: String?): LiveUpdateSessionKey? {
            if (generation.isNullOrBlank() || generation.length > 64 || entryId.isNullOrBlank() || entryId.length > 256) return null
            return LiveUpdateSessionKey(generation, entryId)
        }
    }
}

data class LiveUpdatePreferenceState(
    val enabled: Boolean = false,
    val dismissedSession: LiveUpdateSessionKey? = null,
) {
    /** Session eligibility only; the notification builder separately gates the platform API. */
    fun requestsPromotion(snapshot: SystemSnapshot): Boolean =
        enabled && snapshot.session.state == TrackingState.RUNNING &&
            LiveUpdateSessionKey.from(snapshot)?.let { it != dismissedSession } == true
}

internal data class LiveUpdateNotificationPolicy(val session: LiveUpdateSessionKey, val requestPromotion: Boolean)

/** Local opt-in. Only an explicit UI action uses commit, always off the main thread. */
class LiveUpdatePreferences internal constructor(
    private val preferences: SharedPreferences,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE))

    // Never hold the IO commit monitor from a notification/receiver/main-thread read.
    fun current(): LiveUpdatePreferenceState = readState()

    suspend fun setEnabled(enabled: Boolean): Boolean = withContext(dispatcher) {
        synchronized(preferences) {
            val before = readState()
            try {
                val editor = preferences.edit()
                editor.putBoolean(ENABLED, enabled)
                // Only an explicit off -> on gesture can re-enable a dismissed session.
                if (enabled && !before.enabled) {
                    writeSession(editor, DISMISSED, null)
                    writeSession(editor, POSTED, null)
                }
                if (editor.commit()) true else {
                    restoreInMemory(before.enabled)
                    false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                restoreInMemory(before.enabled)
                false
            }
        }
    }

    internal fun notificationPolicy(snapshot: SystemSnapshot): LiveUpdateNotificationPolicy? {
        val state = readState()
        val session = LiveUpdateSessionKey.from(snapshot)
        if (!state.enabled || session == null || snapshot.session.state == TrackingState.IDLE) return null
        return try {
            if (readSession(POSTED) != session) {
                val editor = preferences.edit()
                writeSession(editor, POSTED, session)
                editor.apply()
            }
            LiveUpdateNotificationPolicy(session, state.requestsPromotion(snapshot))
        } catch (_: Exception) {
            null // Optional promotion must not break the normal controls.
        }
    }

    internal fun recordDismissal(session: LiveUpdateSessionKey): Boolean {
        // A delayed deleteIntent from an older entry must not erase the newer entry's dismissal.
        if (readSession(POSTED) != session) return false
        if (readState().dismissedSession == session) return true
        return try {
            val editor = preferences.edit()
            writeSession(editor, DISMISSED, session)
            editor.apply() // Immediate in-memory suppression; Android schedules the small disk write.
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readState(): LiveUpdatePreferenceState = try {
        val values = preferences.all
        LiveUpdatePreferenceState(values[ENABLED] as? Boolean ?: false, sessionFrom(values, DISMISSED))
    } catch (_: Exception) {
        LiveUpdatePreferenceState() // Corrupt/unreadable opt-in fails closed.
    }

    private fun readSession(prefix: String): LiveUpdateSessionKey? = try {
        sessionFrom(preferences.all, prefix)
    } catch (_: Exception) {
        null
    }

    private fun sessionFrom(values: Map<String, *>, prefix: String): LiveUpdateSessionKey? =
        LiveUpdateSessionKey.fromValues(values["${prefix}_generation"] as? String, values["${prefix}_entry"] as? String)

    private fun writeSession(editor: SharedPreferences.Editor, prefix: String, session: LiveUpdateSessionKey?) {
        if (session == null) {
            editor.remove("${prefix}_generation").remove("${prefix}_entry")
        } else {
            editor.putString("${prefix}_generation", session.generation).putString("${prefix}_entry", session.entryId)
        }
    }

    private fun restoreInMemory(enabled: Boolean) {
        // SharedPreferences.commit(false) may already have changed memory. Restore the prior choice
        // rather than reporting failure while silently leaving a new opt-in enabled.
        try {
            // Do not overwrite a dismissal delivered while disk commit was in progress.
            preferences.edit().putBoolean(ENABLED, enabled).apply()
        } catch (_: Exception) { /* best-effort; the UI still reports failure */ }
    }

    private companion object {
        const val ENABLED = "live_updates_enabled"
        const val DISMISSED = "live_updates_dismissed"
        const val POSTED = "live_updates_posted"
    }
}

// Public Android 36.1 contract. compileSdk 36 does not yet expose the setter/constant.
// A public-method lookup plus the documented extra keeps API 31-35 and base API 36 safe;
// base Android 16 may ignore the request and retain a normal notification.
internal const val REQUEST_PROMOTED_ONGOING_EXTRA = "android.requestPromotedOngoing"
private val promotedOngoingSetter by lazy {
    try {
        Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", java.lang.Boolean.TYPE)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

internal fun requestPromotedOngoing(builder: Notification.Builder) {
    if (Build.VERSION.SDK_INT < 36) return
    try {
        promotedOngoingSetter?.let { it.invoke(builder, true); return }
    } catch (_: ReflectiveOperationException) { /* use the public extras contract */ }
    catch (_: SecurityException) { /* keep the normal notification as a fallback */ }
    builder.addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING_EXTRA, true) })
    // FLAG_PROMOTED_ONGOING is owned by Android, never set by the app.
}

data class LiveUpdatePlatformStatus(
    val supported: Boolean,
    val requestApiAvailable: Boolean = false,
    val canPost: Boolean? = null,
    val hasPromotableCharacteristics: Boolean? = null,
)

/** Advisory only: neither API promises a chip, promotion, or OEM support. No Room read. */
fun liveUpdatePlatformStatus(context: Context): LiveUpdatePlatformStatus {
    if (Build.VERSION.SDK_INT < 36) return LiveUpdatePlatformStatus(supported = false)
    val manager = context.getSystemService(NotificationManager::class.java)
    val canPost = try { manager.canPostPromotedNotifications() } catch (_: Exception) { null }
    val characteristics = try {
        manager.activeNotifications.firstOrNull { it.id == TrackingNotification.ID && it.tag == null }
            ?.notification?.hasPromotableCharacteristics()
    } catch (_: Exception) { null }
    return LiveUpdatePlatformStatus(true, promotedOngoingSetter != null, canPost, characteristics)
}

fun liveUpdateSettingsIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= 36) {
    // This is the public API 36 Settings action (the guide also uses the name
    // ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS, absent from the API 36 SDK).
    Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
} else appDetailsIntent(context)

private fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** User-tap only, with an ordinary public app-details fallback. */
fun openLiveUpdateSettings(context: Context): Boolean {
    for (intent in listOf(liveUpdateSettingsIntent(context), appDetailsIntent(context)).distinctBy { it.action }) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: Exception) { /* try the public fallback; never guess OEM activities */ }
    }
    return false
}
