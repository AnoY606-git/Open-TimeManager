package dev.opentimemanager.app.ui.screens

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.system.LiveUpdatePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class LiveUpdateControlsWriteTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun explicitChoiceIsCommittedBeforeOneProjectionRefresh() = runBlocking {
        val preferences = LiveUpdatePreferences(context)
        var refreshes = 0
        assertFalse(preferences.current().enabled)
        val result = applyLiveUpdatePreferenceChange(preferences, true) {
            assertTrue(preferences.current().enabled)
            refreshes++
        }
        assertEquals(LiveUpdatePreferenceChange.SAVED, result)
        assertEquals(1, refreshes)
    }

    @Test fun saveFailureDoesNotRefreshOrPretendToEnable() = runBlocking {
        val store = context.getSharedPreferences("system_surfaces", Context.MODE_PRIVATE)
        val failing = object : SharedPreferences by store {
            override fun edit(): SharedPreferences.Editor {
                val editor = store.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        val preferences = LiveUpdatePreferences(failing)
        var refreshed = false
        assertEquals(LiveUpdatePreferenceChange.SAVE_FAILED, applyLiveUpdatePreferenceChange(preferences, true) {
            refreshed = true
        })
        assertFalse(refreshed)
        assertFalse(preferences.current().enabled)
    }

    @Test fun projectionFailureReportsTheSavedChoiceRatherThanRollingItBack() = runBlocking {
        val preferences = LiveUpdatePreferences(context)
        assertEquals(LiveUpdatePreferenceChange.REFRESH_FAILED, applyLiveUpdatePreferenceChange(preferences, true) {
            throw IllegalStateException("System surface unavailable")
        })
        assertTrue(preferences.current().enabled)
    }

    @Test fun cancellationEscapesTheExplicitWriteBoundary() = runBlocking {
        val cancellation = CancellationException("cancel")
        try {
            applyLiveUpdatePreferenceChange(LiveUpdatePreferences(context), true) { throw cancellation }
            fail("Cancellation must reach its owner")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }
}
