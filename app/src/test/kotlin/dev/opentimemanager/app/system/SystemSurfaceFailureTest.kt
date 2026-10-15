package dev.opentimemanager.app.system

import android.app.NotificationManager
import android.content.*
import android.content.res.Configuration
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.app.widget.TrackingWidgetProvider
import dev.opentimemanager.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OpenTimeManagerApplication::class)
class SystemSurfaceFailureTest {
    private val app = ApplicationProvider.getApplicationContext<OpenTimeManagerApplication>()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val scopeJob get() = app.applicationScope.coroutineContext[Job]!!

    @Before fun setup() = runBlocking {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 1)
        await { app.getSystemService(NotificationManager::class.java).getNotificationChannel(TrackingNotification.CHANNEL_ID) != null }
    }

    @After fun cleanup() = runBlocking {
        try {
            val jobs = scopeJob.children.toList()
            app.applicationScope.cancel()
            await { jobs.all { it.isCompleted } }
            app.container.database.close()
        } finally { Thread.setDefaultUncaughtExceptionHandler(previousHandler) }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            yield()
        }
    }

    private suspend fun sendAndAwaitFinish(receiver: BroadcastReceiver, intent: Intent) {
        val filter = IntentFilter(intent.action).apply { intent.data?.scheme?.let { addDataScheme(it) } }
        app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        try {
            app.sendBroadcast(intent.setComponent(null).setPackage(app.packageName))
            await { shadowOf(receiver).wentAsync() && shadowOf(receiver).originalPendingResult != null }
            val pending = shadowOf(receiver).originalPendingResult
            await { shadowOf(pending).future.isDone }
            assertTrue("goAsync result must finish even when Room throws", shadowOf(pending).future.isDone)
        } finally { app.unregisterReceiver(receiver) }
    }

    private fun assertSurvivedWithoutPrivateLogs() {
        assertTrue("Recoverable failure reached uncaught handler: $uncaught", uncaught.isEmpty())
        assertTrue(scopeJob.isActive)
        assertFalse(ShadowLog.getLogs().any { it.msg.contains("Sensitive project note") })
    }

    private fun assertSanitizedFailureReported() {
        val logs = ShadowLog.getLogsForTag("SystemSurfaces")
        assertTrue("A recoverable failure must produce a sanitized diagnostic", logs.isNotEmpty())
        assertTrue(logs.all { it.throwable == null && !it.msg.contains("Sensitive project note") })
    }

    @Test fun rejectedRoomWriteFinishesCommandBroadcastAndPreservesIdleState() = runBlocking {
        val coordinator = app.container.trackingCoordinator
        val before = coordinator.observeSnapshot().first()
        withContext(Dispatchers.IO) {
            app.container.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_start BEFORE INSERT ON entries BEGIN SELECT RAISE(ABORT, 'Sensitive project note'); END")
        }
        val command = TrackingCommand.Start(CommandId("failing-start"), ProjectRepository.DEFAULT_PROJECT_ID, before.session.revision)
        val intent = shadowOf(TrackingActions.pendingIntent(app, command)).savedIntent
        sendAndAwaitFinish(TrackingCommandReceiver(), Intent(intent))
        assertSurvivedWithoutPrivateLogs()
        assertSanitizedFailureReported()
        val after = coordinator.observeSnapshot().first()
        assertEquals(before.session.state, after.session.state)
        assertEquals(before.session.revision, after.session.revision)
        assertTrue(after.entries.isEmpty())
    }

    @Test fun closedRoomFinishesWidgetBroadcastWithoutCrashingApplication() = runBlocking {
        val jobs = scopeJob.children.toList()
        jobs.forEach { it.cancel() }
        await { jobs.all { it.isCompleted } }
        app.container.database.close()
        sendAndAwaitFinish(TrackingWidgetProvider(), Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(42)))
        assertSurvivedWithoutPrivateLogs()
        assertSanitizedFailureReported()
    }

    @Test fun failedRoomObservationDoesNotCrashApplicationOrExposePrivateError() = runBlocking {
        val observer = scopeJob.children.single()
        withContext(Dispatchers.IO) {
            val dao = app.container.database.trackingDao()
            dao.updateSlot(dao.slot()!!.copy(state = "Sensitive project note"))
        }
        await { observer.isCompleted }
        assertSurvivedWithoutPrivateLogs()
        assertSanitizedFailureReported()
    }

    @Test fun configurationCallbackRefreshesNotificationFromCommittedRoomState() = runBlocking {
        val coordinator = app.container.trackingCoordinator
        val initial = coordinator.observeSnapshot().first()
        coordinator.execute(TrackingCommand.Start(CommandId("configuration-start"), ProjectRepository.DEFAULT_PROJECT_ID, initial.session.revision))
        val manager = app.getSystemService(NotificationManager::class.java)
        await { manager.activeNotifications.isNotEmpty() }
        val observers = scopeJob.children.toList()
        observers.forEach { it.cancel() }
        await { observers.all { it.isCompleted } }
        manager.cancelAll()
        app.onConfigurationChanged(Configuration(app.resources.configuration))
        await { manager.activeNotifications.isNotEmpty() }
        assertEquals("Tracking", manager.activeNotifications.single().notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertSurvivedWithoutPrivateLogs()
    }

    @Test fun configurationRoomFailureIsContainedWithoutCancellingApplicationScope() = runBlocking {
        val observers = scopeJob.children.toList()
        observers.forEach { it.cancel() }
        await { observers.all { it.isCompleted } }
        app.container.database.close()
        app.onConfigurationChanged(Configuration(app.resources.configuration))
        await { ShadowLog.getLogsForTag("SystemSurfaces").isNotEmpty() }
        assertSurvivedWithoutPrivateLogs()
        assertSanitizedFailureReported()
    }

    @Test fun cancellationEscapesFailureBoundary() = runBlocking {
        val cancellation = CancellationException("Sensitive project note")
        try {
            containSystemSurfaceFailure { throw cancellation }
            fail("Cancellation must propagate to the coroutine owner")
        } catch (caught: CancellationException) { assertSame(cancellation, caught) }
        assertTrue(ShadowLog.getLogsForTag("SystemSurfaces").isEmpty())
    }

    @Test fun errorEscapesFailureBoundary() = runBlocking {
        val error = AssertionError("Sensitive project note")
        var escaped: AssertionError? = null
        try { containSystemSurfaceFailure { throw error } }
        catch (caught: AssertionError) { escaped = caught }
        assertSame("Errors must reach the owner", error, escaped)
        assertTrue(ShadowLog.getLogsForTag("SystemSurfaces").isEmpty())
    }

    @Test fun pendingResultLifetimeFinishesOnceAtBudgetWithoutCancellingDurableWork() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val finishCount = AtomicInteger()
        val durable = owner.launch { delay(100); finishCount.addAndGet(10) }
        val lifetime = PendingResultLifetime(owner, 20, finishCount::incrementAndGet)
        lifetime.bind(durable)

        delay(50)
        assertEquals(1, finishCount.get())
        durable.join()
        assertEquals(11, finishCount.get())
        lifetime.finishOnce()
        assertEquals(11, finishCount.get())
        owner.cancel()
    }

    @Test fun serviceBoundaryRunsCleanupAndStillRethrowsCancellation() = runBlocking {
        var cleaned = false
        containSystemSurfaceFailure(onFailure = { cleaned = true }) { error("projection failed") }
        assertTrue(cleaned)
        val cancellation = CancellationException("cancel")
        try {
            containSystemSurfaceFailure(onFailure = { cleaned = false }) { throw cancellation }
            fail("Cancellation must escape")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        assertTrue(cleaned)
    }
}
