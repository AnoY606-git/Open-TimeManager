package dev.opentimemanager.app.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Private manifest receiver. Persists dismissal only; never reads Room or changes a timer. */
class LiveUpdateDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        decode(intent)?.let { LiveUpdatePreferences(context).recordDismissal(it) }
    }

    companion object {
        private const val ACTION = "dev.opentimemanager.action.DISMISS_LIVE_UPDATE"

        internal fun pendingIntent(context: Context, session: LiveUpdateSessionKey): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, LiveUpdateDismissReceiver::class.java).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .setAction(ACTION).setData(data(session))
                    .putExtra("generation", session.generation).putExtra("entryId", session.entryId),
                PendingIntent.FLAG_IMMUTABLE,
            )

        internal fun decode(intent: Intent): LiveUpdateSessionKey? {
            return try {
                if (intent.action != ACTION) return null
                val session = LiveUpdateSessionKey.fromValues(intent.getStringExtra("generation"), intent.getStringExtra("entryId"))
                    ?: return null
                session.takeIf { intent.data == data(it) }
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun data(session: LiveUpdateSessionKey): Uri = Uri.Builder()
            .scheme("otm").authority("live-update-dismiss").appendPath(session.generation).appendPath(session.entryId).build()
    }
}
