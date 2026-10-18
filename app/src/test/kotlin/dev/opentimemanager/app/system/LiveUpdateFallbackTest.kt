package dev.opentimemanager.app.system

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class LiveUpdateFallbackTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun olderAndroidKeepsTheNormalPrivateChronometerAndActionsEvenWhenOptedIn() = runBlocking {
        val notification = TrackingNotification(context)
        assertFalse(LiveUpdatePreferences(context).current().enabled)
        val off = notification.build(liveUpdateSnapshot(), nowWallMillis = 20_000, nowElapsedMillis = 4_000)
        assertFalse(off.extras.getBoolean(REQUEST_PROMOTED_ONGOING_EXTRA))
        LiveUpdatePreferences(context).setEnabled(true)
        val running = notification.build(liveUpdateSnapshot(), nowWallMillis = 20_000, nowElapsedMillis = 4_000)
        assertFalse(running.extras.getBoolean(REQUEST_PROMOTED_ONGOING_EXTRA))
        assertNull(running.deleteIntent)
        assertTrue(running.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(running.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(13_000L, running.`when`)
        assertEquals(listOf("Pause", "Stop"), running.actions.map { it.title.toString() })
        assertEquals("Tracking", running.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val redacted = notification.build(liveUpdateSnapshot(), privacyMode = false).publicVersion
        assertFalse(redacted.extras.toString().contains("Private project"))
        val paused = notification.build(liveUpdateSnapshot(TrackingState.PAUSED))
        assertFalse(paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("00:05", paused.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(TrackingNotification.CHANNEL_ID).importance)
    }

    @Test fun cachedProjectionIncludesOptInAndDismissalWithoutChangingPrivateActions() = runBlocking {
        val snapshot = liveUpdateSnapshot()
        val preferences = LiveUpdatePreferences(context)
        val updater = SystemSurfaceUpdater(context)
        updater.refresh(snapshot)
        val first = shadowOf(manager).getNotification(TrackingNotification.ID)
        val keyBefore = systemSurfaceKey(context, snapshot, privacyMode = true)
        preferences.setEnabled(true)
        val keyAfter = systemSurfaceKey(context, snapshot, privacyMode = true)
        assertNotEquals(keyBefore, keyAfter)
        updater.refresh(snapshot)
        val enabled = shadowOf(manager).getNotification(TrackingNotification.ID)
        assertNotSame(first, enabled)
        assertEquals(first.actions.first().actionIntent, enabled.actions.first().actionIntent)

        // Exercise the platform-independent session policy; actual API 36 request calls belong
        // in instrumentation, since these SDKs deliberately cannot execute them.
        val session = preferences.notificationPolicy(snapshot)!!.session
        preferences.recordDismissal(session)
        assertNotEquals(keyAfter, systemSurfaceKey(context, snapshot, privacyMode = true))
        updater.refresh(snapshot)
        val dismissed = shadowOf(manager).getNotification(TrackingNotification.ID)
        assertNotSame(enabled, dismissed)
        assertFalse(dismissed.extras.toString().contains("Private project"))
        updater.refresh(liveUpdateSnapshot())
        assertSame(dismissed, shadowOf(manager).getNotification(TrackingNotification.ID))
    }

    @Test fun olderAndroidReportsUnsupportedAndUsesOnlyPublicAppDetailsSettings() {
        val status = liveUpdatePlatformStatus(context)
        assertFalse(status.supported)
        assertFalse(status.requestApiAvailable)
        assertNull(status.canPost)
        assertNull(status.hasPromotableCharacteristics)
        val intent = liveUpdateSettingsIntent(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
        assertNull(intent.component)
        var opened: Intent? = null
        val capture = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { opened = intent }
        }
        assertTrue(openLiveUpdateSettings(capture))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened?.action)
        val unavailable = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw SecurityException("Unavailable") }
        }
        assertFalse(openLiveUpdateSettings(unavailable))
    }
}
