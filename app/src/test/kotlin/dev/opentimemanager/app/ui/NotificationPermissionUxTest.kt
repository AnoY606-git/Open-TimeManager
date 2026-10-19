package dev.opentimemanager.app.ui

import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.di.AppContainer
import dev.opentimemanager.app.settings.ThemeMode
import dev.opentimemanager.app.tracking.ClockSnapshotSource
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.TrackingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Запрет необязательных уведомлений не делает успешную запись времени ошибкой. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationPermissionUxTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var container: AppContainer
    private lateinit var model: AppViewModel
    private val store = ViewModelStore()
    private var now = ClockSnapshot(1_000, 100, 7)

    @Before fun setup() = runBlocking {
        shadowOf(application.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        container = AppContainer(application, clock = ClockSnapshotSource { now })
        model = AppViewModel(application, container)
        store.put("notification-permission", model)
        model.viewModelScope.launch { model.state.collect() }
        await { model.state.value.snapshot != null }
    }

    @After fun cleanup() = runBlocking {
        val jobs = model.viewModelScope.coroutineContext[Job]!!.children.toList()
        store.clear()
        await { jobs.all { it.isCompleted } }
        container.database.close()
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            yield()
        }
    }

    private suspend fun command(expectedState: TrackingState, action: () -> Unit) {
        val existingJobs = model.viewModelScope.coroutineContext[Job]!!.children.toSet()
        action()
        val commandJobs = model.viewModelScope.coroutineContext[Job]!!.children.filterNot { it in existingJobs }.toList()
        assertTrue("The UI action must launch a real command", commandJobs.isNotEmpty())
        await { commandJobs.all { it.isCompleted } && model.state.value.snapshot?.session?.state == expectedState }
        assertEquals(expectedState, container.trackingCoordinator.observeSnapshot().first().session.state)
    }

    @Test fun startPauseStopWithoutNotificationPermissionPersistTimeWithoutSystemFailureWarning() = runBlocking {
        val messages = mutableListOf<UiMessage?>()
        command(TrackingState.RUNNING, model::performPrimary)
        messages += model.state.value.controls.message
        model.clearMessage()
        await { model.state.value.controls.message == null }

        now = ClockSnapshot(3_000, 2_100, 7)
        command(TrackingState.PAUSED, model::performPrimary)
        messages += model.state.value.controls.message
        model.clearMessage()
        await { model.state.value.controls.message == null }

        command(TrackingState.IDLE, model::stop)
        messages += model.state.value.controls.message
        val snapshot = container.trackingCoordinator.observeSnapshot().first()
        assertTrue(snapshot.entries.single().completed)
        assertEquals(2_000L, snapshot.entryDurations.values.single())
        assertFalse(application.getSystemService(NotificationManager::class.java).areNotificationsEnabled())
        assertEquals("All three successful commands must remain warning-free", listOf<UiMessage?>(null, null, null), messages)
    }

    @Test fun activeSessionReconciliationAndSettingsAcceptDisabledOptionalNotifications() = runBlocking {
        command(TrackingState.RUNNING, model::performPrimary)
        model.clearMessage()
        await { model.state.value.controls.message == null }

        val reconcile = model.reconcileSystemControls()
        await { reconcile.isCompleted }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.controls.message)

        val change = model.setTheme(ThemeMode.DARK)
        await { change.isCompleted && model.state.value.preferences.theme == ThemeMode.DARK }
        assertNull(model.state.value.controls.message)
        assertEquals(TrackingState.RUNNING, withContext(Dispatchers.IO) {
            container.trackingCoordinator.observeSnapshot().first().session.state
        })
    }
}
