package dev.opentimemanager.app.system

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.tracking.DailyTrackingProgress
import dev.opentimemanager.core.model.TrackingState
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Проверяем передаваемый OEM контракт, а не наличие острова в неизвестной прошивке. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class HyperIslandNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before fun supportedUnlockedDevice() {
        // Android автоматически выдаёт собственное signature-разрешение AndroidX;
        // Robolectric API31 не моделирует эту выдачу для ContextCompat.registerReceiver.
        shadowOf(context).grantPermissions("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        Settings.System.putInt(context.contentResolver, "notification_focus_protocol", 3)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }

    @Test fun islandIsOptInAndDoesNotReplaceTheOrdinaryNotification() = runBlocking {
        val renderer = TrackingNotification(context)
        assertNull(renderer.build(liveUpdateSnapshot()).extras.getString("miui.focus.param"))
        LiveUpdatePreferences(context).setEnabled(true)
        val observing = HyperIslandScreenObserver.update(context, liveUpdateSnapshot(), true, enabled = true)
        assertTrue(org.robolectric.shadows.ShadowLog.getLogsForTag("SystemSurfaces").toString(), observing)
        val notification = renderer.build(liveUpdateSnapshot(entryStartedAtUtcMillis = 1_000), nowWallMillis = 20_000, nowElapsedMillis = 4_000)
        val data = JSONObject(notification.extras.getString("miui.focus.param") ?: "{}").getJSONObject("param_v2")
        assertTrue(data.getBoolean("updatable"))
        assertFalse(data.getBoolean("enableFloat"))
        assertFalse(data.getBoolean("islandFirstFloat"))
        assertFalse(data.getBoolean("filterWhenNoPermission"))
        assertEquals("Tracking", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(listOf("Pause", "Stop"), notification.actions.map { it.title.toString() })
        // AOSP 31–35 still does not request Android 16 promotion.
        assertFalse(notification.extras.getBoolean("android.requestPromotedOngoing"))
    }

    @Test fun compactAndExpandedViewsUseTheSameRealCountUpAnchor() = runBlocking {
        LiveUpdatePreferences(context).setEnabled(true)
        val notification = TrackingNotification(context).build(liveUpdateSnapshot(entryStartedAtUtcMillis = 1_000), nowWallMillis = 20_000, nowElapsedMillis = 4_000)
        val data = JSONObject(notification.extras.getString("miui.focus.param") ?: "{}").getJSONObject("param_v2")
        val compact = data.getJSONObject("param_island").getJSONObject("bigIslandArea").getJSONObject("sameWidthDigitInfo").getJSONObject("timerInfo")
        val expanded = data.getJSONObject("highlightInfo").getJSONObject("timerInfo")
        for (timer in listOf(compact, expanded)) {
            assertEquals(1, timer.getInt("timerType"))
            assertEquals(13_000L, timer.getLong("timerWhen"))
            assertEquals(20_000L, timer.getLong("timerSystemCurrent"))
        }
        assertEquals("Tracking", data.getString("aodTitle"))
    }

    @Test fun pauseIsStaticAndControlsReuseThePrivateImmutableCommands() = runBlocking {
        LiveUpdatePreferences(context).setEnabled(true)
        val notification = TrackingNotification(context).build(liveUpdateSnapshot(TrackingState.PAUSED, entryStartedAtUtcMillis = 1_000), nowWallMillis = 90_000, nowElapsedMillis = 80_000)
        val data = JSONObject(notification.extras.getString("miui.focus.param") ?: "{}").getJSONObject("param_v2")
        val compact = data.getJSONObject("param_island").getJSONObject("bigIslandArea").getJSONObject("sameWidthDigitInfo")
        assertFalse(compact.has("timerInfo"))
        assertEquals("00:05", compact.getString("digit"))
        assertFalse(data.getJSONObject("highlightInfo").has("timerInfo"))
        assertEquals("00:05", data.getJSONObject("highlightInfo").getString("title"))
        val actionNames = data.getJSONArray("actions")
        val actions = notification.extras.getBundle("miui.focus.actions")!!
        assertEquals(2, actionNames.length())
        for (index in 0..1) {
            @Suppress("DEPRECATION")
            val action = actions.getParcelable<Notification.Action>(actionNames.getJSONObject(index).getString("action"))!!
            assertEquals(notification.actions[index].actionIntent, action.actionIntent)
            assertTrue(action.actionIntent.isImmutable)
        }
        assertEquals("Resume", notification.actions[0].title.toString())
    }

    @Test fun islandNeverContainsProjectNamesAndPublicVersionNeverContainsOemData() = runBlocking {
        LiveUpdatePreferences(context).setEnabled(true)
        val snapshot = liveUpdateSnapshot().copy(dailyProgress = DailyTrackingProgress(LocalDate.of(2026, 10, 19), 7_200_000))
        for (privacy in listOf(false, true)) {
            val notification = TrackingNotification(context).build(snapshot, privacyMode = privacy)
            val payload = notification.extras.getString("miui.focus.param")!!
            assertFalse(payload.contains("Private project"))
            assertFalse(payload.contains("generation-a"))
            assertFalse(payload.contains("entry-a"))
            assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 3_072)
            assertTrue(JSONObject(payload).getJSONObject("param_v2").getJSONObject("highlightInfo").getString("content").contains("Today 2:00 of 8:00"))
            assertNull(notification.publicVersion.extras.getString("miui.focus.param"))
            assertNull(notification.publicVersion.extras.getBundle("miui.focus.actions"))
        }
    }

    @Test fun unsupportedDevicesLockedDevicesAndReviewKeepOnlyTheNativeFallback() = runBlocking {
        LiveUpdatePreferences(context).setEnabled(true)
        val renderer = TrackingNotification(context)
        for (protocol in listOf(0, 1, 2)) {
            Settings.System.putInt(context.contentResolver, "notification_focus_protocol", protocol)
            assertNull(renderer.build(liveUpdateSnapshot()).extras.getString("miui.focus.param"))
        }
        Settings.System.putInt(context.contentResolver, "notification_focus_protocol", 3)
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Samsung")
        assertNull(renderer.build(liveUpdateSnapshot()).extras.getString("miui.focus.param"))
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        assertNull(renderer.build(liveUpdateSnapshot()).extras.getString("miui.focus.param"))
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        for (state in listOf(TrackingState.IDLE, TrackingState.NEEDS_REVIEW)) {
            assertNull(renderer.build(liveUpdateSnapshot(state)).extras.getString("miui.focus.param"))
        }
    }

    @Test fun dismissalSuppressesOemRequestsAcrossPauseAndResumeButNotNewEntries() = runBlocking {
        val preferences = LiveUpdatePreferences(context)
        preferences.setEnabled(true)
        val renderer = TrackingNotification(context)
        val running = renderer.build(liveUpdateSnapshot())
        assertNotNull(running.deleteIntent)
        LiveUpdateDismissReceiver().onReceive(context, shadowOf(running.deleteIntent).savedIntent)
        for (state in listOf(TrackingState.RUNNING, TrackingState.PAUSED)) {
            assertNull(renderer.build(liveUpdateSnapshot(state, revision = 2)).extras.getString("miui.focus.param"))
        }
        val next = renderer.build(liveUpdateSnapshot(entryId = "entry-b"))
        assertEquals("reopen", JSONObject(next.extras.getString("miui.focus.param")!!)
            .getJSONObject("param_v2").optString("reopen"))
        // Повторное явное включение снимает локальное подавление, а не только
        // выставляет флаг: OEM должен разрешить уже отменённый notification id.
        preferences.setEnabled(false)
        assertNull(renderer.build(liveUpdateSnapshot()).extras.getString("miui.focus.param"))
        preferences.setEnabled(true)
        val reenabled = renderer.build(liveUpdateSnapshot())
        assertEquals("reopen", JSONObject(reenabled.extras.getString("miui.focus.param")!!)
            .getJSONObject("param_v2").optString("reopen"))
    }

    @Test fun missingDailyTotalDoesNotRepeatTheStateOnTheExpandedCard() = runBlocking {
        LiveUpdatePreferences(context).setEnabled(true)
        val notification = TrackingNotification(context).build(liveUpdateSnapshot())
        val highlight = JSONObject(notification.extras.getString("miui.focus.param")!!).getJSONObject("param_v2").getJSONObject("highlightInfo")
        assertEquals("Tracking", highlight.getString("content"))
        assertFalse(highlight.has("subContent"))
    }

    @Test fun swipeKeyguardAlsoHidesAllIslandDataEvenWithoutASecureDeviceLock() = runBlocking {
        LiveUpdatePreferences(context).setEnabled(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertNull(TrackingNotification(context).build(liveUpdateSnapshot()).extras.getString("miui.focus.param"))
    }

    @Test fun processObserverWorksWithoutAForegroundServiceAndDoesNotResurrectAnEndedEntry() = runBlocking {
        LiveUpdatePreferences(context).setEnabled(true)
        val renderer = TrackingNotification(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(TrackingNotification.ID, renderer.build(liveUpdateSnapshot()))
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        val hidden = shadowOf(manager).getNotification(TrackingNotification.ID)
        for (key in listOf("miui.focus.param", "miui.focus.actions", "miui.focus.pics")) assertFalse(hidden.extras.containsKey(key))
        SystemSurfaceUpdater(context).refresh(liveUpdateSnapshot(TrackingState.IDLE))
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.activeNotifications.isEmpty())
        val next = renderer.build(liveUpdateSnapshot(entryId = "next-entry"))
        assertEquals("reopen", JSONObject(next.extras.getString("miui.focus.param")!!)
            .getJSONObject("param_v2").optString("reopen"))
        manager.notify(TrackingNotification.ID, next)
        manager.cancel(TrackingNotification.ID)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Unlock must not repost a removed notification", manager.activeNotifications.isEmpty())
    }

    @Test fun fixedUtcDeadlineShrinksTimeoutAndUnknownOrExpiredStartsUseOnlyNativeControls() = runBlocking {
        LiveUpdatePreferences(context).setEnabled(true)
        val renderer = TrackingNotification(context)
        val snapshot = liveUpdateSnapshot(entryStartedAtUtcMillis = 1_000)
        val notification = renderer.build(snapshot, nowWallMillis = 3_601_000, nowElapsedMillis = 4_000)
        val params = JSONObject(notification.extras.getString("miui.focus.param")!!).getJSONObject("param_v2")
        assertEquals(660, params.getInt("timeout"))
        assertEquals(39_600, params.getJSONObject("param_island").getInt("islandTimeout"))
        for (start in listOf(null, 21_000L, Long.MIN_VALUE)) {
            assertNull(renderer.build(snapshot.copy(entryStartedAtUtcMillis = start), nowWallMillis = 20_000).extras.getString("miui.focus.param"))
        }
        for (age in listOf(43_140_001L, 43_200_000L, 46_800_000L)) {
            val fallback = renderer.build(snapshot, nowWallMillis = 1_000 + age, nowElapsedMillis = 4_000)
            assertNull(fallback.extras.getString("miui.focus.param"))
            assertEquals(2, fallback.actions.size)
        }
    }
}
