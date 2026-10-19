package dev.opentimemanager.app.system

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.widget.TextView
import androidx.core.app.LocaleManagerCompat
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.data.ProjectRepository
import dev.opentimemanager.app.tracking.LEGACY_COMMAND_GENERATION
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.app.widget.TrackingWidgetProvider
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32], application = Application::class)
class NonActivityLocaleTest {
    @Test fun coldStartAndReusedSystemConsumersResolvePersistedLocalesWithoutAnActivity() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val context = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val record = application.getFileStreamPath(APP_COMPAT_LOCALES_RECORD)
        val original = record.takeIf { it.exists() }?.readBytes()
        val project = ProjectEntity(ProjectRepository.DEFAULT_PROJECT_ID.value, "@default", 0, 0)
        val snapshot = SystemSnapshot(
            TrackingSession.restore(
                TrackingState.RUNNING, 1, EntryId("entry"), ProjectRepository.DEFAULT_PROJECT_ID,
                OpenSegment(SegmentId("open"), ClockSnapshot(1_000, 100, 1)), emptyList(),
            ),
            LEGACY_COMMAND_GENERATION, project, project,
        )
        val notification = TrackingNotification(context)
        val updater = SystemSurfaceUpdater(context)
        try {
            // The real AppCompat storage format is a cold-start fixture here. The instrumentation
            // test also exercises setApplicationLocales + Activity recreation and its async writer.
            for ((tag, title, status, primary) in listOf(
                listOf("ru", "Без проекта", "Идёт учёт", "Пауза"),
                listOf("en", "No project", "Tracking", "Pause"),
            )) {
                application.openFileOutput(APP_COMPAT_LOCALES_RECORD, Context.MODE_PRIVATE).use { output ->
                    output.write("<locales application_locales=\"$tag\"/>".toByteArray(Charsets.UTF_8))
                }
                assertEquals(tag, LocaleManagerCompat.getApplicationLocales(application).toLanguageTags())
                assertEquals("Tracking", context.getString(R.string.tracking_running))

                val rendered = notification.build(snapshot, privacyMode = false)
                assertEquals(status, rendered.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertEquals(title, rendered.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                assertEquals(primary, rendered.actions.first().title.toString())
                assertEquals(status, TrackingTileService.presentation(context, snapshot).label)
                val widget = TrackingWidgetProvider.views(context, snapshot, privacyMode = false).apply(context, null)
                assertEquals(title, widget.findViewById<TextView>(R.id.widget_status).text.toString())
                assertEquals(primary, widget.findViewById<TextView>(R.id.widget_primary).text.toString())

                updater.refresh(snapshot)
                val active = context.getSystemService(NotificationManager::class.java).activeNotifications
                    .single { it.id == TrackingNotification.ID }.notification
                assertEquals(status, active.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertTrue(active.extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank())
            }
            application.deleteFile(APP_COMPAT_LOCALES_RECORD)
            assertEquals("Tracking", notification.build(snapshot).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        } finally {
            if (original == null) application.deleteFile(APP_COMPAT_LOCALES_RECORD)
            else application.openFileOutput(APP_COMPAT_LOCALES_RECORD, Context.MODE_PRIVATE).use { it.write(original) }
            context.getSystemService(NotificationManager::class.java).cancel(TrackingNotification.ID)
        }
    }

    companion object {
        private const val APP_COMPAT_LOCALES_RECORD = "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file"
    }
}
