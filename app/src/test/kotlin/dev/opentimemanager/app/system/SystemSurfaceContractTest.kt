package dev.opentimemanager.app.system

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Chronometer
import androidx.room.Room
import dev.opentimemanager.app.OpenTimeManagerApplication
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.*
import dev.opentimemanager.app.tracking.*
import dev.opentimemanager.app.widget.TrackingWidgetProvider
import dev.opentimemanager.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.robolectric.Shadows.shadowOf
import org.junit.After
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemSurfaceContractTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After fun cleanupApplication() = runBlocking {
        val app = context as? OpenTimeManagerApplication ?: return@runBlocking
        val job = app.applicationScope.coroutineContext[Job]!!
        app.applicationScope.cancel()
        // Complete cancellation before Robolectric resets Main: startup can hold the
        // process settings boundary across Room IO and must release it on Main.
        withTimeout(5_000) {
            while (!job.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                yield()
            }
        }
        app.container.database.close()
    }

    private fun fixture(state: TrackingState = TrackingState.RUNNING, revision: Long = 1): SystemSnapshot {
        val segment = Segment(SegmentId("closed"), EntryId("entry"), ProjectId("project"), 1000, 5000)
        val session = when (state) {
            TrackingState.IDLE -> TrackingSession.idle()
            TrackingState.RUNNING -> TrackingSession.restore(state, revision, EntryId("entry"), ProjectId("project"), OpenSegment(SegmentId("open"), ClockSnapshot(7000, 2000, 1)), listOf(segment))
            TrackingState.PAUSED -> TrackingSession.restore(state, revision, EntryId("entry"), ProjectId("project"), null, listOf(segment))
            TrackingState.NEEDS_REVIEW -> TrackingSession.restore(state, revision, EntryId("entry"), ProjectId("project"), null, listOf(segment))
        }
        val project = ProjectEntity("project", "Secret project", 0, 0)
        return SystemSnapshot(session, LEGACY_COMMAND_GENERATION, project, project)
    }

    @Test fun immutableExplicitActionsKeepDistinctTargetsAndDeduplicateRepeatedRenders() {
        val first = TrackingActions.pendingIntent(context, TrackingCommand.Pause(CommandTarget(EntryId("entry"), 1, CommandId("pause-1"))))
        val same = TrackingActions.pendingIntent(context, TrackingCommand.Pause(CommandTarget(EntryId("entry"), 1, CommandId("pause-1"))))
        val next = TrackingActions.pendingIntent(context, TrackingCommand.Pause(CommandTarget(EntryId("entry"), 2, CommandId("pause-2"))))
        val stop = TrackingActions.pendingIntent(context, TrackingCommand.Stop(CommandTarget(EntryId("entry"), 1, CommandId("stop-1"))))
        assertTrue(first.isImmutable)
        assertEquals(first, same)
        assertNotEquals(first, next)
        assertNotEquals(first, stop)
        val intent = shadowOf(first).savedIntent
        assertEquals(TrackingCommandReceiver::class.java.name, intent.component!!.className)
        assertEquals(TrackingCommand.Pause(CommandTarget(EntryId("entry"), 1, CommandId("pause-1"))), TrackingActions.decode(intent))
        assertNull(TrackingActions.decode(Intent(context, TrackingCommandReceiver::class.java).setAction(TrackingActions.STOP)))
    }

    @Test fun allTimerActionsUseForegroundBroadcastsWithImmutableExplicitIdentity() {
        val target = CommandTarget(EntryId("entry"), 1, CommandId("timer-command"))
        val commands = listOf(
            TrackingCommand.Start(CommandId("timer-start"), ProjectId("project"), 0),
            TrackingCommand.Pause(target),
            TrackingCommand.Resume(target),
            TrackingCommand.Stop(target),
        )
        val pendingIntents = commands.map { command ->
            val pending = TrackingActions.pendingIntent(context, command)
            val intent = shadowOf(pending).savedIntent
            assertTrue(pending.isBroadcast)
            assertTrue(pending.isImmutable)
            assertEquals(ComponentName(context, TrackingCommandReceiver::class.java), intent.component)
            assertEquals(Intent.FLAG_RECEIVER_FOREGROUND, intent.flags and Intent.FLAG_RECEIVER_FOREGROUND)
            assertEquals(command, TrackingActions.decode(intent))
            assertEquals(pending, TrackingActions.pendingIntent(context, command))
            pending
        }
        assertEquals(commands.size, pendingIntents.toSet().size)
    }

    @Test fun notificationUsesSilentChannelChronometerAndPrivatePublicVersion() {
        val builder = TrackingNotification(context)
        val running = builder.build(fixture(), privacyMode = true, nowWallMillis = 20_000, nowElapsedMillis = 4000)
        assertTrue(running.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(13_000L, running.`when`)
        assertEquals(listOf("Pause", "Stop"), running.actions.map { it.title.toString() })
        assertFalse(running.extras.toString().contains("Secret"))
        val visible = builder.build(fixture(), privacyMode = false)
        assertTrue(visible.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Secret project"))
        assertFalse(visible.publicVersion.extras.toString().contains("Secret"))
        val paused = builder.build(fixture(TrackingState.PAUSED), true)
        assertFalse(paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(listOf("Resume", "Stop"), paused.actions.map { it.title.toString() })
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(TrackingNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
    }

    @Test fun defaultProjectNameIsLocalizedOnNotificationAndWidget() {
        val snapshot = fixture().copy(
            activeProject = ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, "@default", 0, 0),
            session = TrackingSession.restore(
                TrackingState.RUNNING,
                1,
                EntryId("entry"),
                ProjectRepository.DEFAULT_PROJECT_ID,
                OpenSegment(SegmentId("open"), ClockSnapshot(7_000, 2_000, 1)),
                emptyList(),
            ),
        )
        val englishTitle = TrackingNotification(context).build(snapshot, privacyMode = false)
            .extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertEquals("No project", englishTitle)
        val ruConfig = android.content.res.Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        val russian = context.createConfigurationContext(ruConfig)
        val russianWidget = TrackingWidgetProvider.views(russian, snapshot, privacyMode = false).apply(russian, null)
        assertEquals("Без проекта", russianWidget.findViewById<TextView>(R.id.widget_status).text.toString())
    }

    @Test fun idleQuickActionStartsOnlyWithExactlyOneEligibleProject() {
        val idle = fixture(TrackingState.IDLE).copy(activeProject = null)
        assertNull(TrackingActions.primary(idle.copy(quickStartProject = null)))
        val only = ProjectEntity("only", "Only", 0, 0)
        assertEquals(
            ProjectId("only"),
            (TrackingActions.primary(idle.copy(quickStartProject = only)) as TrackingCommand.Start).projectId,
        )
        // Null also represents 2+ eligible projects: the app must ask for a selection.
        assertNull(TrackingActions.primary(idle.copy(quickStartProject = null)))
    }

    @Test fun ambiguousWidgetStartHonestlyAsksForAProject() {
        val idle = fixture(TrackingState.IDLE).copy(activeProject = null, quickStartProject = null)
        val views = TrackingWidgetProvider.views(context, idle).apply(context, null)
        assertEquals("Choose project", views.findViewById<TextView>(R.id.widget_primary).text.toString())
    }

    @Test fun widgetAndTileProjectIdleRunningPausedAndReviewWithoutPrivateText() {
        for ((state, label, action) in listOf(
            Triple(TrackingState.IDLE, "Ready", "Start"), Triple(TrackingState.RUNNING, "Tracking", "Pause"),
            Triple(TrackingState.PAUSED, "Paused", "Resume"), Triple(TrackingState.NEEDS_REVIEW, "Review required", "Open app")
        )) {
            val snapshot = fixture(state)
            val views = TrackingWidgetProvider.views(context, snapshot, true, 4000).apply(context, null)
            assertEquals(label, views.findViewById<TextView>(R.id.widget_status).text.toString())
            assertEquals(action, views.findViewById<TextView>(R.id.widget_primary).text.toString())
            assertFalse(views.findViewById<TextView>(R.id.widget_status).text.toString().contains("Secret"))
            assertEquals(if (state == TrackingState.IDLE || state == TrackingState.NEEDS_REVIEW) View.GONE else View.VISIBLE, views.findViewById<View>(R.id.widget_stop).visibility)
            val tile = TrackingTileService.presentation(context, snapshot)
            assertEquals(label, tile.label)
            assertEquals(if (state == TrackingState.RUNNING) android.service.quicksettings.Tile.STATE_ACTIVE else android.service.quicksettings.Tile.STATE_INACTIVE, tile.state)
        }
        val running = TrackingWidgetProvider.views(context, fixture(), true, 4000).apply(context, null)
        assertEquals(-3000L, running.findViewById<Chronometer>(R.id.widget_timer).base)
    }

    @Test fun staleSystemActionIsRejectedByRoomAndDeniedNotificationsLeaveTrackingIntact() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            var now = ClockSnapshot(1000, 100, 1)
            val coordinator = TrackingCoordinator(RoomTrackingRepository(db, ClockSnapshotSource { now }, Dispatchers.IO))
            coordinator.observeSnapshot().first()
            coordinator.execute(TrackingCommand.Start(CommandId("start"), ProjectRepository.DEFAULT_PROJECT_ID, 0))
            val running = coordinator.observeSnapshot().first()
            val staleStop = TrackingCommand.Stop(CommandTarget(running.session.entryId!!, running.session.revision, CommandId("old-stop")))
            val encoded = shadowOf(TrackingActions.pendingIntent(context, staleStop)).savedIntent
            now = ClockSnapshot(2000, 1100, 1)
            coordinator.execute(TrackingCommand.Pause(CommandTarget(running.session.entryId!!, running.session.revision, CommandId("pause"))))
            val updater = SystemSurfaceUpdater(context)
            val result = TrackingCommandReceiver.dispatch(encoded, coordinator, updater)
            assertEquals(TrackingResult.Rejected(TrackingFailure.STALE_REVISION), result)
            val current = coordinator.observeSystemSnapshot().first()
            assertEquals(TrackingState.PAUSED, current.session.state)
            shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            assertEquals(NotificationAvailability.BLOCKED, updater.refresh(current))
            assertEquals(TrackingState.PAUSED, coordinator.observeSnapshot().first().session.state)
        } finally { db.close() }
    }

    @Test fun blockedTrackingChannelReportsUnavailableControls() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(TrackingNotification.CHANNEL_ID, "Tracking", NotificationManager.IMPORTANCE_NONE))
        assertEquals(NotificationAvailability.BLOCKED, SystemSurfaceUpdater(context).refresh(fixture()))
    }

    @Test fun reviewTileRoutesToMainActivityInsteadOfSendingNoCommand() {
        val intent = TrackingTileService.reviewIntent(context, fixture(TrackingState.NEEDS_REVIEW))
        assertEquals("dev.opentimemanager.app.MainActivity", intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, intent.flags)
    }

    @Test fun refreshPublishesCurrentNotificationAndIdleRemovesIt() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val updater = SystemSurfaceUpdater(context)
        updater.refresh(fixture())
        assertEquals(1, manager.activeNotifications.size)
        assertEquals("Tracking", manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        updater.refresh(fixture(TrackingState.IDLE))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun pausedWidgetKeepsConfirmedDurationWhenHostAppliesViewsLater() {
        val remoteViews = TrackingWidgetProvider.views(context, fixture(TrackingState.PAUSED), true, android.os.SystemClock.elapsedRealtime())
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(1))
        val view = remoteViews.apply(context, null)
        assertEquals("00:05", view.findViewById<Chronometer>(R.id.widget_timer).text.toString())
    }

    @Test
    @Config(application = dev.opentimemanager.app.OpenTimeManagerApplication::class)
    fun destroyingForegroundServiceNeverStopsPersistedEntry() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<dev.opentimemanager.app.OpenTimeManagerApplication>()
        android.provider.Settings.Global.putInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 1)
        val coordinator = app.container.trackingCoordinator
        val initial = coordinator.observeSnapshot().first()
        coordinator.execute(TrackingCommand.Start(CommandId("service-start"), ProjectRepository.DEFAULT_PROJECT_ID, initial.session.revision))
        val before = coordinator.observeSnapshot().first()
        val service = org.robolectric.Robolectric.buildService(TrackingService::class.java).create()
        assertEquals("Work time tracking", shadowOf(service.get()).lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        service.destroy()
        val after = coordinator.observeSnapshot().first()
        assertEquals(TrackingState.RUNNING, after.session.state)
        assertEquals(before.session.entryId, after.session.entryId)
        assertEquals(before.session.revision, after.session.revision)
    }

    @Test
    @Config(application = dev.opentimemanager.app.OpenTimeManagerApplication::class)
    fun foregroundServiceRequestsStickyRestartForPersistedSessionReconciliation() {
        val service = org.robolectric.Robolectric.buildService(TrackingService::class.java).create().get()
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 1))
        service.onDestroy()
    }

    @Test
    @Config(application = dev.opentimemanager.app.OpenTimeManagerApplication::class)
    fun recreatedForegroundServiceStopsItselfWhenPersistedStateIsIdle() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<dev.opentimemanager.app.OpenTimeManagerApplication>()
            assertEquals(TrackingState.IDLE, app.container.trackingCoordinator.observeSystemSnapshot().first().session.state)
            val controller = org.robolectric.Robolectric.buildService(TrackingService::class.java).create()
            withTimeout(5_000) {
                while (!shadowOf(controller.get()).isStoppedBySelf) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    yield()
                }
            }
            controller.destroy()
        }
    }

    @Test fun systemComponentsHaveOnlyTheirRequiredExposureAndPermissions() {
        val pm = context.packageManager
        val permissions = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue("Foreground control permission is required", "android.permission.FOREGROUND_SERVICE" in permissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in permissions)
        assertTrue("android.permission.POST_NOTIFICATIONS" in permissions)
        assertFalse("android.permission.INTERNET" in permissions)
        val service = pm.getServiceInfo(ComponentName(context.packageName, "${context.packageName}.system.TrackingService"), 0)
        assertFalse(service.exported)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        val tile = pm.getServiceInfo(ComponentName(context.packageName, "${context.packageName}.system.TrackingTileService"), 0)
        assertTrue(tile.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        val receiver = pm.getReceiverInfo(ComponentName(context.packageName, "${context.packageName}.system.TrackingCommandReceiver"), 0)
        assertFalse(receiver.exported)
        val widget = pm.getReceiverInfo(ComponentName(context.packageName, "${context.packageName}.widget.TrackingWidgetProvider"), 0)
        assertFalse(widget.exported)
    }
}
