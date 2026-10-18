package dev.opentimemanager.app.system

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Emulator-only release check. Builds notifications and sends their real deleteIntent; no Room
 * writes, FGS start, notification posting, UI automation, or assertion of actual OS promotion. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class LiveUpdateNotificationApi36Test {
    @Test fun requestsOnlyOptedInRunningEntriesAndHonorsRealPrivateReceiverDismissal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
        val previous = store.all.filterKeys { it.startsWith("live_updates_") }
        suspend fun reset(values: Map<String, *>) = withContext(Dispatchers.IO) {
            val editor = store.edit()
            store.all.keys.filter { it.startsWith("live_updates_") }.forEach { editor.remove(it) }
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    else -> error("Unexpected Live Update preference type")
                }
            }
            assertTrue(editor.commit())
        }
        try {
            reset(emptyMap<String, Any>())
            val preferences = LiveUpdatePreferences(context)
            val builder = TrackingNotification(context)
            val snapshot = fixture()
            val off = builder.build(snapshot)
            assertFalse(preferences.current().enabled)
            assertFalse(off.extras.getBoolean("android.requestPromotedOngoing"))
            assertNull(off.deleteIntent)

            assertTrue(preferences.setEnabled(true))
            val running = builder.build(snapshot, privacyMode = false)
            assertTrue(running.extras.getBoolean("android.requestPromotedOngoing"))
            assertTrue(running.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertEquals(2, running.actions.size)
            assertTrue(running.deleteIntent.isImmutable)
            assertFalse(running.publicVersion.extras.toString().contains("Private API36 project"))
            // These real API calls are advisory. No assertion of FLAG_PROMOTED_ONGOING or a chip:
            // base API 36 does not expose 36.1's request API and can legitimately return false.
            if (Build.VERSION.SDK_INT >= 36) running.hasPromotableCharacteristics()
            val platform = liveUpdatePlatformStatus(context)
            assertTrue(platform.supported)
            assertNotNull(platform.canPost)
            assertFalse(context.packageManager.getReceiverInfo(
                ComponentName(context, LiveUpdateDismissReceiver::class.java), 0,
            ).exported)

            for (state in listOf(TrackingState.PAUSED, TrackingState.NEEDS_REVIEW, TrackingState.IDLE)) {
                assertFalse(builder.build(fixture(state)).extras.getBoolean("android.requestPromotedOngoing"))
            }
            // Rebuild the running entry to bind the real broadcast to the latest shown session.
            val dismissible = builder.build(snapshot)
            val dismissed = CountDownLatch(1)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                if (preferences.current().dismissedSession == LiveUpdateSessionKey.from(snapshot)) dismissed.countDown()
            }
            store.registerOnSharedPreferenceChangeListener(listener)
            try {
                dismissible.deleteIntent.send()
                assertTrue("Private delete receiver must persist this entry's dismissal", dismissed.await(5, TimeUnit.SECONDS))
            } finally {
                store.unregisterOnSharedPreferenceChangeListener(listener)
            }
            assertFalse(builder.build(snapshot).extras.getBoolean("android.requestPromotedOngoing"))
            assertFalse(builder.build(fixture(revision = 3)).extras.getBoolean("android.requestPromotedOngoing"))
            assertTrue(builder.build(fixture(entry = "entry-b")).extras.getBoolean("android.requestPromotedOngoing"))
        } finally {
            reset(previous)
        }
    }

    private fun fixture(state: TrackingState = TrackingState.RUNNING, entry: String = "entry-a", revision: Long = 1): SystemSnapshot {
        val project = ProjectEntity("project", "Private API36 project", 0, 0)
        val session = if (state == TrackingState.IDLE) TrackingSession.idle() else TrackingSession.restore(
            state, revision, EntryId(entry), ProjectId(project.id),
            if (state == TrackingState.RUNNING) OpenSegment(SegmentId("open"), ClockSnapshot(7_000, 2_000, 1)) else null,
            emptyList(), 5_000, 6_000,
        )
        return SystemSnapshot(session, "api36-test-generation", project, project)
    }
}
