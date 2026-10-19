package dev.opentimemanager.app.system

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

internal fun liveUpdateSnapshot(
    state: TrackingState = TrackingState.RUNNING,
    entryId: String = "entry-a",
    generation: String = "generation-a",
    revision: Long = 1,
    entryStartedAtUtcMillis: Long? = System.currentTimeMillis(),
): SystemSnapshot {
    val project = ProjectEntity("project", "Private project", 0, 0)
    val session = if (state == TrackingState.IDLE) TrackingSession.idle() else TrackingSession.restore(
        state, revision, EntryId(entryId), ProjectId(project.id),
        if (state == TrackingState.RUNNING) OpenSegment(SegmentId("open"), ClockSnapshot(7_000, 2_000, 1)) else null,
        emptyList(), 5_000, 6_000,
    )
    return SystemSnapshot(session, generation, project.takeUnless { state == TrackingState.IDLE }, project, entryStartedAtUtcMillis = entryStartedAtUtcMillis)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class LiveUpdatePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun defaultIsOffAndOnlyRunningSessionsCanRequestPromotion() = runBlocking {
        val preferences = LiveUpdatePreferences(context)
        assertFalse(preferences.current().enabled)
        assertFalse(preferences.current().requestsPromotion(liveUpdateSnapshot()))
        assertNull(preferences.notificationPolicy(liveUpdateSnapshot()))
        assertTrue(preferences.setEnabled(true))
        assertTrue(preferences.current().requestsPromotion(liveUpdateSnapshot()))
        for (state in listOf(TrackingState.IDLE, TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
            assertFalse(preferences.current().requestsPromotion(liveUpdateSnapshot(state)))
        }
    }

    @Test fun dismissalSuppressesTheWholeEntryAcrossPauseResumeAndPreferenceInstances() = runBlocking {
        val preferences = LiveUpdatePreferences(context)
        preferences.setEnabled(true)
        val snapshot = liveUpdateSnapshot()
        val policy = preferences.notificationPolicy(snapshot)!!
        val pending = LiveUpdateDismissReceiver.pendingIntent(context, policy.session)
        LiveUpdateDismissReceiver().onReceive(context, shadowOf(pending).savedIntent)

        val reopened = LiveUpdatePreferences(context)
        assertEquals(policy.session, reopened.current().dismissedSession)
        assertFalse(reopened.current().requestsPromotion(snapshot))
        assertFalse(reopened.notificationPolicy(liveUpdateSnapshot(TrackingState.PAUSED, revision = 2))!!.requestPromotion)
        assertFalse(reopened.notificationPolicy(liveUpdateSnapshot(revision = 3))!!.requestPromotion)
        assertFalse(context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE).all.values.any {
            it.toString().contains("Private project")
        })
    }

    @Test fun aNewEntryIsEligibleAndLateOldDeleteCannotEraseItsDismissal() = runBlocking {
        val preferences = LiveUpdatePreferences(context)
        preferences.setEnabled(true)
        val old = preferences.notificationPolicy(liveUpdateSnapshot())!!
        preferences.recordDismissal(old.session)
        val next = preferences.notificationPolicy(liveUpdateSnapshot(entryId = "entry-b"))!!
        assertTrue(next.requestPromotion)
        assertTrue(preferences.recordDismissal(next.session))
        assertFalse(preferences.recordDismissal(old.session))
        assertEquals(next.session, preferences.current().dismissedSession)
        assertFalse(preferences.current().requestsPromotion(liveUpdateSnapshot(entryId = "entry-b")))
        assertTrue(preferences.notificationPolicy(liveUpdateSnapshot(entryId = "entry-b", generation = "after-replace"))!!.requestPromotion)
    }

    @Test fun onlyAnExplicitOffThenOnResetsDismissal() = runBlocking {
        val preferences = LiveUpdatePreferences(context)
        preferences.setEnabled(true)
        val snapshot = liveUpdateSnapshot()
        preferences.recordDismissal(preferences.notificationPolicy(snapshot)!!.session)
        preferences.setEnabled(true)
        assertFalse(preferences.current().requestsPromotion(snapshot))
        preferences.setEnabled(false)
        assertFalse(preferences.current().requestsPromotion(snapshot))
        preferences.setEnabled(true)
        assertNull(preferences.current().dismissedSession)
        assertTrue(preferences.current().requestsPromotion(snapshot))
    }

    @Test fun deleteIntentIsImmutableExplicitAndBoundToGenerationAndEntryNotRevision() {
        val key = LiveUpdateSessionKey.from(liveUpdateSnapshot())!!
        val first = LiveUpdateDismissReceiver.pendingIntent(context, key)
        assertTrue(first.isImmutable)
        assertTrue(first.isBroadcast)
        assertEquals(first, LiveUpdateDismissReceiver.pendingIntent(context, LiveUpdateSessionKey.from(liveUpdateSnapshot(revision = 2))!!))
        assertNotEquals(first, LiveUpdateDismissReceiver.pendingIntent(context, LiveUpdateSessionKey.from(liveUpdateSnapshot(entryId = "entry-b"))!!))
        assertNotEquals(first, LiveUpdateDismissReceiver.pendingIntent(context, LiveUpdateSessionKey.from(liveUpdateSnapshot(generation = "other"))!!))
        val intent = shadowOf(first).savedIntent
        assertEquals(ComponentName(context, LiveUpdateDismissReceiver::class.java), intent.component)
        assertEquals(Intent.FLAG_RECEIVER_FOREGROUND, intent.flags and Intent.FLAG_RECEIVER_FOREGROUND)
        assertEquals(key, LiveUpdateDismissReceiver.decode(intent))
        assertNull(LiveUpdateDismissReceiver.decode(Intent(intent).putExtra("entryId", "tampered")))
        assertNull(LiveUpdateDismissReceiver.decode(Intent(intent).setAction("unexpected")))
        assertFalse(intent.extras.toString().contains("Private project"))
    }

    @Test fun enablingCommitsOnIoAndPreservesTheExistingPrivacyPreference() = runBlocking {
        val store = context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
        store.edit().putBoolean("privacy", false).commit()
        val commitThread = AtomicReference<Thread>()
        val observed = object : SharedPreferences by store {
            override fun edit(): SharedPreferences.Editor {
                val editor = store.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        commitThread.set(Thread.currentThread())
                        return editor.commit()
                    }
                }
            }
        }
        assertTrue(LiveUpdatePreferences(observed).setEnabled(true))
        assertNotNull(commitThread.get())
        assertNotSame(Thread.currentThread(), commitThread.get())
        assertFalse(store.getBoolean("privacy", true))
    }

    @Test fun failedCommitDoesNotSilentlyLeaveANewOptInEnabled() = runBlocking {
        val store = context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
        val failing = object : SharedPreferences by store {
            override fun edit(): SharedPreferences.Editor {
                val editor = store.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean {
                        editor.commit() // Android can update memory even when commit reports failure.
                        return false
                    }
                }
            }
        }
        val preferences = LiveUpdatePreferences(failing)
        assertFalse(preferences.setEnabled(true))
        assertFalse(preferences.current().enabled)
        assertFalse(LiveUpdatePreferences(context).current().requestsPromotion(liveUpdateSnapshot()))
    }
}
