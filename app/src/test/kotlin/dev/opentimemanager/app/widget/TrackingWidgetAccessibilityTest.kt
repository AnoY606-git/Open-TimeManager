package dev.opentimemanager.app.widget

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.opentimemanager.app.R
import dev.opentimemanager.app.data.ProjectEntity
import dev.opentimemanager.app.tracking.LEGACY_COMMAND_GENERATION
import dev.opentimemanager.app.tracking.SystemSnapshot
import dev.opentimemanager.core.model.ClockSnapshot
import dev.opentimemanager.core.model.EntryId
import dev.opentimemanager.core.model.OpenSegment
import dev.opentimemanager.core.model.ProjectId
import dev.opentimemanager.core.model.Segment
import dev.opentimemanager.core.model.SegmentId
import dev.opentimemanager.core.model.TrackingSession
import dev.opentimemanager.core.model.TrackingState
import java.time.Duration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class TrackingWidgetAccessibilityTest {
    @Test fun delayedApplyAndReapplyAnnounceOnlyFrozenConfirmedTimeInBothLanguages() {
        for (locale in listOf("en", "ru")) {
            val context = localizedContext(locale)
            for (state in listOf(TrackingState.IDLE, TrackingState.PAUSED, TrackingState.NEEDS_REVIEW)) {
                val views = TrackingWidgetProvider.views(context, snapshot(state), nowElapsedMillis = SystemClock.elapsedRealtime())
                ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
                val widget = views.apply(context, null)
                val displayed = if (state == TrackingState.IDLE) "00:00" else "07:14"
                val hms = if (state == TrackingState.IDLE) "0:00:00" else "0:07:14"
                val expected = if (locale == "en") "Duration: $hms (hours, minutes and seconds)"
                    else "Длительность: $hms (часы, минуты и секунды)"
                assertEquals("$locale $state delayed apply", expected, visibleTimer(widget, displayed).contentDescription?.toString())
                ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
                views.reapply(context, widget)
                assertEquals("$locale $state delayed reapply", expected, visibleTimer(widget, displayed).contentDescription?.toString())
            }
        }
    }

    @Test fun resumingReappliedWidgetRestoresNativeAdvancingChronometerDescription() {
        val context = localizedContext("en")
        val paused = TrackingWidgetProvider.views(context, snapshot(TrackingState.PAUSED), nowElapsedMillis = SystemClock.elapsedRealtime())
        val widget = paused.apply(context, null)
        val start = SystemClock.elapsedRealtime()
        val running = snapshot(TrackingState.RUNNING, start)
        TrackingWidgetProvider.views(context, running, nowElapsedMillis = start).reapply(context, widget)
        val timer = widget.findViewById<Chronometer>(R.id.widget_timer)
        assertEquals(View.VISIBLE, timer.visibility)
        val first = timer.contentDescription.toString()
        assertFalse(first.startsWith("Duration:"))
        assertTrue(timer.isImportantForAccessibility)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        TrackingWidgetProvider.views(context, running, nowElapsedMillis = SystemClock.elapsedRealtime()).reapply(context, widget)
        assertNotEquals(first, timer.contentDescription.toString())
    }

    private fun visibleTimer(root: View, displayed: String): TextView = descendants(root)
        .filterIsInstance<TextView>().single { it.visibility == View.VISIBLE && it.text.toString() == displayed }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun localizedContext(locale: String): Context {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return ContextThemeWrapper(
            application.createConfigurationContext(Configuration(application.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            }),
            application.applicationInfo.theme,
        )
    }

    private fun snapshot(state: TrackingState, startedAt: Long = 0): SystemSnapshot {
        val project = ProjectEntity("project", "Private project", 0, 0)
        val session = if (state == TrackingState.IDLE) TrackingSession.idle() else TrackingSession.restore(
            state, 1, EntryId("entry"), ProjectId("project"),
            if (state == TrackingState.RUNNING) OpenSegment(SegmentId("open"), ClockSnapshot(0, startedAt, 1)) else null,
            listOf(Segment(SegmentId("closed"), EntryId("entry"), ProjectId("project"), 0, 434_000)),
        )
        return SystemSnapshot(session, LEGACY_COMMAND_GENERATION, project, project)
    }
}
