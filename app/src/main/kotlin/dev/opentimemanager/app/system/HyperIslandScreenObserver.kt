package dev.opentimemanager.app.system

import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dev.opentimemanager.app.tracking.SystemSnapshot

/** Одна подписка процесса, а не FGS: отказ/перезапуск службы не оставляет OEM-данные
 * без снятия при выключении экрана. Никаких опросов, Room-чтений или команд таймера. */
internal object HyperIslandScreenObserver {
    // Только процессный Application: Activity и локализованные Context не удерживаем.
    private var owner: Application? = null
    private var receiver: BroadcastReceiver? = null
    private var latestSnapshot: SystemSnapshot? = null
    private var privacyMode = true

    @Synchronized
    fun update(context: Context, snapshot: SystemSnapshot, privacyMode: Boolean, enabled: Boolean): Boolean {
        val application = context.applicationContext as? Application ?: return false
        if (owner !== application) close()
        if (!enabled) {
            latestSnapshot = null
            return false
        }
        latestSnapshot = snapshot
        this.privacyMode = privacyMode
        if (receiver != null) return true
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF && intent.action != Intent.ACTION_USER_PRESENT) return
                updateHyperIslandScreenState(intent.action)
                synchronized(this@HyperIslandScreenObserver) {
                    val latest = latestSnapshot ?: return
                    val manager = context.getSystemService(NotificationManager::class.java)
                    try {
                        // Снятое уведомление не создаём заново, даже если процесс ещё жив.
                        if (manager.activeNotifications.none { it.id == TrackingNotification.ID && it.tag == null }) {
                            latestSnapshot = null
                            return
                        }
                        manager.notify(TrackingNotification.ID, TrackingNotification(context).build(latest, this@HyperIslandScreenObserver.privacyMode))
                    } catch (_: Exception) {
                        updateHyperIslandScreenState(Intent.ACTION_SCREEN_OFF)
                        Log.w("SystemSurfaces", "Screen privacy refresh failed")
                    }
                }
            }
        }
        return try {
            ContextCompat.registerReceiver(application, listener, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            owner = application
            receiver = listener
            // Подписка остаётся до конца процесса, даже после завершения записи: это
            // позволяет получить USER_PRESENT, пропущенный уничтоженной FGS. Без опросов
            // и без сохранённой записи обработчик лишь обновляет признак видимости.
            true
        } catch (failure: Exception) {
            updateHyperIslandScreenState(Intent.ACTION_SCREEN_OFF)
            latestSnapshot = null
            Log.w("SystemSurfaces", "Screen privacy observer unavailable: ${failure.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    fun close() {
        receiver?.let { listener ->
            try { owner?.unregisterReceiver(listener) } catch (_: IllegalArgumentException) { /* Уже снят владельцем Context. */ }
        }
        receiver = null
        owner = null
        latestSnapshot = null
    }
}
